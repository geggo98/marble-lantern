# Training

**TL;DR:** Training has two implementations. Part A is a scalar CPU reference trainer in module `nn`: loss, exact gradients of every operation of [network](network.md), SGD with momentum and AdamW (the default), checkpoints, and the tests that make it the oracle. Part B is the GPU trainer in module `nn-tornado`: each Part A operation becomes a TornadoVM kernel, and each kernel is tested against its Part A twin. The input gradient of the hex convolution is itself a hex convolution with permuted weights, and the weight gradient is a split-K reduction, so no kernel needs an atomic add. One training sample costs about 3 times its forward pass (42 MFLOP for SMALL, 251 MFLOP for LARGE). Self-play needs about 17 times more GPU work than training, so the largest savings come from classical code that takes work away from the network and from the search: legal-move masks, canonical orientation, the mirror symmetry, a teacher bootstrap from `Strategies.BEST_SPEC`, and classical play of race endgames.

This document builds on [network](network.md) (layers, tensor layout, tolerance), [architecture](architecture.md#self-play-and-training-loop) (the self-play loop and the [long-running-job conventions](architecture.md#long-running-jobs)) and [search and evaluation](search-and-evaluation.md). The Java code of Part A goes into module `nn`, package `sternhalma.nn`; the code of Part B goes into module `nn-tornado`, package `sternhalma.tornado`; the training loop and the sample files go into module `selfplay` (see [architecture](architecture.md#module-map)).

## How to read this document

The labels follow [network](network.md#how-to-read-this-document):

- **Project decision (owner):** fixed by the project owner. This document only cites such decisions from other documents.
- **Proposal:** a detail that this document fixes so that parallel implementations agree. Of this document, only work plan steps A1 to A3 and E05 are implemented (see the "As implemented" paragraphs). The owner can change any proposal.
- **Project reasoning:** a conclusion that the project derives itself.
- **To measure:** a number that only an experiment can settle. The text gives a starting value.

KB and MB mean 1024 and 1024² bytes. Numbers of FLOPs and bytes come from the formulas of [network](network.md#how-the-numbers-were-computed); [How the numbers were computed](#how-the-numbers-were-computed) gives the training formulas. Speed estimates are estimates and say so. The usual methods (Adam, AdamW, SGD with momentum, AlphaZero-style targets) have no entry in [references](references.md) yet; this document describes them as common practice and does not cite them (open question 13).

## Goals and non-goals

Goals:

- One loss and one set of gradient formulas, implemented twice: a scalar CPU reference (the oracle) and TornadoVM kernels.
- Gradient formulas exact enough that a programmer can implement them without guessing, and tests strong enough that a plausible bug fails them.
- Training jobs that follow the [long-running-job conventions](architecture.md#long-running-jobs): resumable from their artefacts, abortable, with progress, ETA and a lock.
- Spend network capacity and GPU time only on what classical code cannot do cheaply (see [What classical code covers](#what-classical-code-covers)).

Non-goals:

- New layer types. Training uses exactly the operations of [network](network.md#layers).
- Bit-exact agreement between the CPU and the GPU trainer. They agree within stated tolerances.
- Mixed precision as the default. bfloat16 and float16 are an option for later (see [Precision](#precision)).

## What classical code covers

**Project reasoning:** a network learns from examples what it is not told. Every fact that cheap classical code computes exactly is one fact the network does not need to learn, and every position that classical code plays well is one position that needs no MCTS search with network calls. Self-play costs about 17 times more GPU work than training (see [Where the time goes](#where-the-time-goes)), so savings in self-play count most.

| Fact or task | Classical source | Effect on training | Status |
|---|---|---|---|
| Which moves are legal | Move generator (`Position.legalMoves`) | The softmax runs over legal moves only; the network never learns legality. Each sample stores its legal moves, so training never runs the move generator. | in [network](network.md#policy-head-and-move-logits) |
| Whose turn it is, which corner is home | Canonical orientation (`Orientation`) | All positions look like "mover at the top"; the 2 or 3 rotations need not be learned. | in [network](network.md#canonical-orientation) |
| Left-right symmetry (two players) | `Geometry.mirror` | Every two-player sample counts twice; see [Symmetric augmentation](#symmetric-augmentation). | proposal |
| A sensible first policy and value | `Strategies.BEST_SPEC` (alpha-beta with the learned linear evaluation) | Generation 0 trains on teacher games instead of random play; see [Bootstrap](#bootstrap-generation-0-from-a-classical-teacher). | proposal |
| Race endgames (no contact) | Contact test plus exact solitaire distance or the endgame database ([endgame and opening](endgame-and-opening.md#when-the-database-replaces-the-evaluation)) | Self-play hands the game to a classical player at the first race position. These positions cost no network calls and give no policy samples; their final result still labels the earlier positions. | proposal; needs the contact test |
| Short forced wins | Alpha-beta with `mate=1` | Optional: a shallow mate search at the root plays a found win without MCTS. | to measure |
| Distance to the goal per cell | Single-agent distance table (stage 2) | An input plane would give it to the network for free. This changes the input planes, which are an owner decision; see [network open question 1](network.md#open-questions). | open question 7 |
| Encoding and augmentation | Table lookups | Samples are stored in canonical form once; a training step only expands bits into floats and applies one permutation. | proposal |

The race-endgame rule needs care. A contact test that wrongly reports "no contact" lets the classical player use a wrong exact value. The [endgame document](endgame-and-opening.md#when-the-database-replaces-the-evaluation) requires a conservative test for exactly this reason. Until the project has the contact test and exact race distances, self-play plays every position with MCTS.

**To measure:** the share of plies that race endgames take in self-play games. It decides how much this rule saves.

## Terms

- **Sample:** one training example: a position in canonical form, its legal moves with a policy target, a value target and two weights.
- **Step:** one optimiser update.
- **Batch:** the samples of one step. **Microbatch:** a part of a batch whose gradient is computed in one pass; the gradients of all microbatches of a batch are summed before the step.
- **Generation:** one round of self-play, training and gating ([architecture](architecture.md#self-play-and-training-loop)).
- **Best network:** the network that won the last gating match. Self-play uses it.
- **Candidate:** the network that training produces from the best network.
- **Teacher distribution:** a policy target that does not come from MCTS visit counts, for example from a classical player or from a larger network.

## Part A: the CPU reference trainer

Part A is the oracle for Part B, in the same way as `Network` is the oracle for `VectorNetwork`. It favours obvious correctness over speed. It runs on float arrays with the layouts of [network](network.md#tensor-layout), and it accumulates every sum in double.

### Loss

For a batch of N samples s = 1 … N, with policy weight wp_s ≥ 0 and value weight wv_s ≥ 0:

```text
L = (1 / N) * sum over s of ( wp_s * Lp_s + c_v * wv_s * Lv_s )  +  (lambda / 2) * sum over all weight elements of theta^2
```

N is the number of samples in the batch, not the sum of the weights, so that a weight of 0 removes a sample and does not change the scale of the others (**proposal**). The defaults are c_v = 1 and lambda = 0 with AdamW (it decays the weights directly, see [Optimiser](#optimiser)) or lambda = 1e-4 with SGD (**proposal**, to measure). "Weight elements" are the tensors whose names end in `.weight`; biases get no L2 term.

#### Policy loss

A sample has M_s ≥ 1 legal moves m, each with canonical cells from'_m and to'_m (the canonical map of the sample's mover applied to the engine move, see [network](network.md#policy-head-and-move-logits)) and a target pi_m ≥ 0. The batch builder normalises the targets in double so that their sum is 1, and it rejects a sample whose targets sum to 0 while wp_s > 0. Let f[c'] = policy[2 c' + 0] and t[c'] = policy[2 c' + 1] be the float outputs of the policy head. In double:

```text
l_m    = (double) f[from'_m] + (double) t[to'_m]
mu     = max over m of l_m
S      = sum over m of exp(l_m - mu)
logZ   = mu + log(S)
Lp_s   = sum over m with pi_m > 0 of pi_m * (logZ - l_m)
p_m    = exp(l_m - logZ)
```

This is the cross-entropy of the masked softmax of [network](network.md#policy-head-and-move-logits); `Encoder.moveLogit` computes l_m. Terms with pi_m = 0 are skipped, never computed as 0 · (something). The batch builder also stores the target entropy H_s = −sum over pi_m > 0 of pi_m log pi_m, so that the trainer can report the KL divergence Lp_s − H_s ≥ 0. The KL divergence is the number to watch: the cross-entropy cannot go below H_s.

A sample without legal moves has no policy loss: the builder sets wp_s = 0. A sample with one legal move has Lp_s = 0 and a zero gradient; the E05 self-play writer does not special-case it: wp_s = 1 except for random opening plies (wp_s = 0), which is harmless because the gradient is zero.

#### Value loss

With P players, float outputs v_i = tanh(y_i) of the value head, and targets z_i in [−1, 1] for relative players i (mover first, the order of [network](network.md#value-head)):

```text
Lv_s = (1 / P) * sum over i of (v_i - z_i)^2
```

The target vector comes from `Outcome.values`, reordered: z_i = values[(mover + i) mod P]. The mean over players keeps Lv in [0, 4] for two and three players (**proposal**).

### Gradients

Notation: for a tensor X, dX is the gradient ∂L/∂X. All formulas are per sample; the gradients of the parameters are sums over the samples of the batch. Indices follow [network](network.md#tensor-layout). N_c = 121 is the number of cells, row 121 is the padding row, g(c, k) is the gather table.

#### Loss to network outputs

```text
g_m = wp_s * (p_m - pi_m) / N                         for each legal move m of sample s
df[c'] = sum over m with from'_m = c' of g_m           (0 for cells that no move uses)
dt[c'] = sum over m with to'_m   = c' of g_m
dy_i   = c_v * wv_s * (2 / P) * (v_i - z_i) * (1 - v_i^2) / N
```

Several moves share a "from" cell, and several moves share a "to" cell: the sums accumulate, they do not overwrite. dP (the gradient of the policy output) has 121 rows and no padding row. 1 − v_i² uses the float output v_i, not y_i.

#### Operations

Each forward operation of [network](network.md#layers) has a backward rule. "Given dOut" means that the gradient of the operation's output is known.

| Forward | Backward, given dOut |
|---|---|
| ReLU, out = max(z, 0) | dz = dOut · [out > 0]. At z = 0 the derivative is 0. Use the stored output; z need not be stored. |
| Residual, u = ReLU(z2 + x) | dpre = du · [u > 0]; then dz2 = dpre, and dpre also adds to dx (the identity path). |
| tanh, v = tanh(y) | dy = dv · (1 − v²) |
| Dense, out[o] = b[o] + Σ_i W[o][i] in[i] | dW[o][i] += dOut[o] · in[i]; db[o] += dOut[o]; din[i] = Σ_o W[o][i] dOut[o] |
| 1×1 convolution (policy head) on rows c < 121 | dW[k][ch] += Σ_c dOut[c][k] · in[c][ch]; db[k] += Σ_c dOut[c][k]; din[c][ch] += Σ_k W[k][ch] · dOut[c][k] |
| Mean, out[ch] = (1/121) Σ_{c<121} in[c][ch] | din[c][ch] += dOut[ch] / 121 for c < 121; row 121 gets nothing |
| Hex convolution with bias (below) | see [Hex convolution](#hex-convolution) |

**The padding row:** every gradient activation has 121 + 1 rows, and row 121 is 0 in every channel (**proposal**, the same rule as in the forward pass). Rows 121 of the forward activations are constants, not functions of the parameters, so no gradient flows through them. A test fills row 121 of the scratch buffers with NaN before a backward pass and checks that it is 0 afterwards.

**NaN:** `ReferenceOps.relu` keeps NaN, but NaN > 0 is false, so the ReLU backward rule turns a NaN activation into a zero gradient. The trainer therefore checks that the loss and every gradient element are finite and stops with `IllegalStateException` if not (**proposal**).

#### Hex convolution

Forward, for c < 121 ([network](network.md#gather-table)):

```text
z[c][o] = b[o] + sum over k in 0..6, ch in 0..C_in-1 of W[o][k*C_in + ch] * in[g(c,k)][ch]
```

Backward, given dz with dz[121][·] = 0:

```text
db[o]               += sum over c < 121 of dz[c][o]
dW[o][k*C_in + ch]  += sum over c < 121 of dz[c][o] * in[g(c,k)][ch]
din[r][ch]           = sum over (c < 121, k) with g(c,k) = r of sum over o of dz[c][o] * W[o][k*C_in + ch]     for r < 121
din[121][ch]         = 0
```

The reference implements din in this scatter form: loop over c and k, and add into row g(c, k). Contributions to row 121 are dropped. The scatter form is the obvious transcription of the forward sum, so it belongs in the oracle.

**The gather form (project reasoning):** g(c, k) = r for k ≥ 1 means r = `Geometry.neighbor(c, k − 1)`. Directions d and d ^ 1 are opposite, so this holds exactly when c = `Geometry.neighbor(r, (k − 1) ^ 1)`, that is c = g(r, π(k)) with

```text
pi(0) = 0,  pi(1) = 2, pi(2) = 1,  pi(3) = 4, pi(4) = 3,  pi(5) = 6, pi(6) = 5
```

If that neighbour is off the board, g(r, π(k)) = 121 and dz[121] = 0 contributes nothing. Substituting k' = π(k) (π is its own inverse) gives

```text
din[r][ch] = sum over k' in 0..6, o of dz[g(r,k')][o] * W'[ch][k'*C_out + o],    W'[ch][k'*C_out + o] = W[o][pi(k')*C_in + ch]
```

So the input gradient is a forward hex convolution of dz with the same gather table, the permuted and transposed weight W' of shape [C_in][7 · C_out], and zero bias. It writes 0 to row 121, as required. Part B uses this form, because it is a gather and needs no atomic add. The reference keeps the scatter form, and a test compares the two.

`conv_in` reads the input planes, which do not depend on parameters, so its din is not computed.

#### Order of the backward pass

Per sample, with the activations stored by the forward pass (see [What the forward pass stores](#what-the-forward-pass-stores)):

1. Loss to outputs: dP (rows 0 to 120) and dy, from the formulas above.
2. `head_value.dense2`: dW, db with input `hidden`; dhidden = W2ᵀ dy.
3. ReLU of `dense1`: dz1 = dhidden · [hidden > 0].
4. `head_value.dense1`: dW, db with input `pooled`; dpooled = W1ᵀ dz1.
5. Mean: dx_B[c][ch] = dpooled[ch] / 121 for c < 121, where x_B is the output of the last block.
6. `head_policy`: dW, db with input x_B; dx_B[c][ch] += Σ_k W[k][ch] · dP[c][k].
7. For blocks i = B − 1 down to 0, with block input x_i, stored t_i = ReLU(conv1(x_i)) and u_i = x_{i+1} = ReLU(conv2(t_i) + x_i):
   1. dpre = dx_{i+1} · [u_i > 0].
   2. conv2: dW, db with input t_i and dz = dpre; dt = din of conv2.
   3. dz_1 = dt · [t_i > 0].
   4. conv1: dW, db with input x_i and dz = dz_1; dx_i = din of conv1 + dpre.
8. `conv_in`: x_0 = ReLU(conv_in(planes)); dz = dx_0 · [x_0 > 0]; dW, db with the input planes.

The trainer accumulates the parameter gradients in one double array per tensor, over all samples and microbatches of a batch, in ascending order of (sample, cell). It rounds them to float only when the optimiser reads them (**proposal**).

#### What the forward pass stores

The trainer runs its own forward pass, built from `ReferenceOps` in the same order as `Network`, and keeps per sample:

| Array | Floats | TINY | SMALL | LARGE |
|---|---|---|---|---|
| input planes | 122 · 7 = 854 | 854 | 854 | 854 |
| x_0 and t_i, u_i for each block | 122 · C · (1 + 2B) | 2,928 | 35,136 | 101,504 |
| policy logits | 242 | 242 | 242 | 242 |
| pooled, hidden, values | C + 32 + P | 42 | 66 | 98 |
| **total per sample** | | 4,066 (16 KB) | 36,298 (142 KB) | 102,698 (401 KB) |

The backward pass needs two more activation buffers per sample at a time (dx and dt): 1,952, 7,808 and 15,616 floats (8 KB, 31 KB, 61 KB). A test checks that the trainer's forward outputs equal `Network.forward` bit for bit: both use the same `ReferenceOps` calls in the same order.

### Batch layout

**Proposal:** `TrainingBatch`, an immutable record in `nn`, holds one batch in flat arrays:

| Field | Type and shape | Content |
|---|---|---|
| `size` | int N | samples |
| `input` | float [N][122][7] | encoded planes, as `Encoder.encode` writes them |
| `moveOffsets` | int [N + 1] | the moves of sample s are moveOffsets[s] … moveOffsets[s + 1] − 1 |
| `moveFrom`, `moveTo` | int [M] | canonical cells, 0 … 120 |
| `moveTarget` | float [M] | pi_m, normalised per sample |
| `valueTarget` | float [N][P] | z, relative players |
| `policyWeight`, `valueWeight` | float [N] | wp_s, wv_s |
| `targetEntropy` | double [N] | H_s, for reporting |

The constructor checks the lengths, the cell range, the target sums and that no sample lists the same (from', to') pair twice. The batch builder produces it from replay-buffer samples (see [Replay buffer](#replay-buffer)); tests build it directly or from positions with `Encoder`.

**Gradient accumulation:** a batch of N samples can be computed as microbatches of any sizes. The trainer divides by the N of the whole batch, not by the microbatch size, so the result is the same as one pass over the whole batch up to the double rounding (**proposal**). On the CPU the microbatches run in parallel, each into its own double gradient buffers; the buffers are summed in microbatch order, so the result does not depend on the thread timing.

### Optimiser

**Proposal:** AdamW is the default; SGD with momentum is the second optimiser and the first one to implement.

AdamW, per float parameter θ with float gradient g and step count t = 1, 2, …, all arithmetic in double in the reference:

```text
m      = beta1 * m + (1 - beta1) * g
v      = beta2 * v + (1 - beta2) * g^2
mhat   = m / (1 - beta1^t)
vhat   = v / (1 - beta2^t)
theta  = theta - lr * ( mhat / (sqrt(vhat) + eps) + wd * theta * isWeight )
```

Defaults: beta1 = 0.9, beta2 = 0.999, eps = 1e-8, lr = 1e-3, wd = 0.01; isWeight is 1 for `.weight` tensors and 0 for biases (**proposal**, all to measure). m and v are float arrays with the shapes of the parameters, initially 0.

SGD with momentum, in the convention "velocity, then step": u = μ · u + (g + λ θ · isWeight), θ = θ − lr · u, with μ = 0.9 (**proposal**).

**Project reasoning** for AdamW as the default:

- The network has no normalisation layers (an owner decision, [network](network.md#goals-and-non-goals)). Without them the gradient scales of `conv_in`, the twelve block convolutions and the two heads can differ by orders of magnitude; [network](network.md#numeric-conventions) measured activations that grow by a factor of 2 to 5 per layer with large weights. Adam normalises each parameter's step by its own gradient size. Plain SGD needs one learning rate that suits all layers.
- The project trains on one machine and can afford few tuning runs. Adam usually works over a wider range of learning rates.
- The cost is two more parameter-sized arrays: 2 · 350,308 floats, about 2.7 MB for LARGE.
- Against Adam: AlphaZero-style projects often use SGD with momentum, and Adam with L2 in the loss regularises badly. Decoupled weight decay (the "W" in AdamW) addresses the second point. Keeping SGD as an option addresses the first.

SGD comes first in the work plan because its one-step behaviour is easy to predict, which gives the strongest early test (see [Tests](#tests-of-part-a)).

#### Learning-rate schedule

The learning rate is a pure function of the run configuration, the generation and the step, so that a resumed run uses the same rates (**proposal**):

- **Generation 0** (supervised, fixed data): linear warm-up from 0 to lr over 200 steps, then cosine decay to 0.1 · lr at the last planned step.
- **Self-play generations:** warm-up over 100 steps, then constant lr. A list of (generation, lr) milestones in the run configuration lowers it, for example by a factor of 0.3. The owner sets the milestones after the first generations; nothing changes them automatically.

#### Weight initialisation

Generation 0 starts from `Parameters.init(config, seed)` ([network](network.md#initialisation)), with the seed in the run configuration. Later generations start from the best network.

**Risk (project reasoning):** `Parameters.init` draws the weights with the uniform bound √(6 / fanIn), which keeps the variance of a plain ReLU layer constant. A residual block adds its branch to its input, so without normalisation the variance can grow by up to a factor of 2 per block, up to 64 for LARGE. **To measure** before the first long run: the standard deviation of each block output at initialisation, on encoded self-play positions. If it grows by more than a factor of about 4 over the trunk, the run configuration gets an option `residualInitScale` that multiplies every `block{i}.conv2.weight` by a constant (0 makes each block start as the identity) after `Parameters.init` (open question 1).

### Checkpoints

**Proposal:** a training checkpoint is one safetensors file, written with `Safetensors.write` (atomic by temporary file and rename):

| Tensor names | Content |
|---|---|
| the names of `Parameters.shapes(config)` | the parameters |
| `optim.m.<name>`, `optim.v.<name>` | AdamW state, one pair per parameter tensor |
| `optim.u.<name>` | SGD velocity, one per parameter tensor |

Metadata: the network keys of [network](network.md#metadata-keys) with `format` = `sternhalma-train` and `format_version` = `1`, plus `optimizer` (`adamw` or `sgd`), `step` (completed steps), `generation`, the optimiser hyperparameters as decimal strings, and `run_config_sha256`.

`Parameters.load` rejects extra tensors, so a checkpoint is not a network file. A new class `Checkpoint` reads it with `Safetensors.read`, splits the tensors by prefix and builds the parameters with `Parameters.of`. A promoted network is written as a plain network file with `Parameters.save`, so that every tool can load it.

The batch sampler needs no stored random state: the batch of step t draws from `new SplittableRandom(mix(runSeed, generation, t))`, so a resumed run draws the same batches. Test: 10 steps in one run give the same parameters, bit for bit on the CPU, as 5 steps, a checkpoint, a fresh trainer that loads the checkpoint, and 5 more steps.

### Self-play sample files

**Proposal:** the sample format is safetensors with the standard integer dtypes `U8`, `I16` and `I32` besides `F32`. Python's safetensors library and other tools read these dtypes, so the format stays language-neutral. One file holds complete games of one generation and their samples.

The project's `Safetensors` reader accepts `F32` only (a project decision of [network](network.md#safetensors-file), not an owner decision). The proposal extends the reader with raw typed tensors for sample files; `Parameters.load` keeps rejecting every dtype other than `F32` (open question 9).

File name: `samples-<worker>-<sequence>.safetensors` in the generation's `samples/` directory. Tensors (G games, N samples, M moves in total):

| Tensor | Dtype, shape | Content |
|---|---|---|
| `cells` | U8 [N, 121] | the position in canonical cells: 0 empty, 1 + relative player of the piece otherwise |
| `ply`, `move_limit` | I16 [N] | `Position.ply()`, `rules().moveLimit()`; together they give plane 6 |
| `move_offsets` | I32 [N + 1] | moves of sample s: move_offsets[s] … move_offsets[s + 1] − 1 |
| `move_from`, `move_to` | U8 [M] | canonical cells of the legal moves |
| `move_target` | F32 [M] | policy target: root visit share, or the teacher distribution |
| `played` | I32 [N] | index of the played move in [0, M_s) |
| `value_target` | F32 [N, P] | z, relative players |
| `policy_weight`, `value_weight` | F32 [N] | wp_s, wv_s |
| `flags` | U8 [N] | bit 0: policy target from a full search; bit 1: value from classical adjudication; bit 2: random opening ply |
| `game` | I32 [N] | game index in the file, 0 … G − 1 |
| `game_moves`, `game_move_offsets` | I32 [·], I32 [G + 1] | engine move codes of each game from its start position, for replays and the opening book |
| `game_values` | F32 [G, P] | `Outcome.values`, absolute players |

Metadata: `format` = `sternhalma-samples`, `format_version` = `1`, `players`, the rule set as text, `generation`, `network_sha256` (of the network file that played), `search` (the search configuration as text), `teacher` (for example the value of `Strategies.BEST_SPEC`), `seed`.

**Why canonical cells (project reasoning):** the encoder's work is done once, when the sample is written. Planes 0 to 2 follow from `cells` by comparison, planes 3 to 5 are constants in canonical orientation ([network](network.md#input-planes)), and plane 6 is min(ply, move_limit) / move_limit. Training therefore needs no `Position`, no `Orientation` and no move generator. A test checks that expanding `cells` gives exactly `Encoder.encode` of the original position, for every mover of two and three players.

A sample takes 121 + 4 bytes plus 6 bytes per legal move plus about 40 bytes; with about 80 legal moves (to measure) that is about 650 bytes. **Measured (E05):** 52.5 legal moves per sample and 479 bytes per sample on disk, BEST_SPEC games at 1000 nodes.

**As implemented (round 2, E05):** module `selfplay` writes this format with `format_version` = `1` (`GameShard`, `SampleFile`). `SampleFile` is a small safetensors writer and reader for `U8`, `I16`, `I32` and `F32` in the module itself; `nn.Safetensors` and `Parameters.load` stay `F32`-only (open question 9 answered this way for now). A Python reader with the standard library only (`struct`, `json`) reads the files (`SampleFileTest`). The policy target is the bootstrap target below (1 − ε on the played move, ε = 0.05 spread over the legal moves; flag bit 0 is never set), the value target is the game's label (see the next section), random opening plies get wp = 0 and flag bit 2, and adjudicated games set flag bit 1 on all their samples. `network_sha256` is empty, because the tool plays classical engines only. One file is one shard of 25 to 50 games; the name is `samples-s<seed>-<shard>.safetensors`. Beyond the table above a file holds these game tensors, all `[G]` unless noted:

| Tensor | Dtype | Content |
|---|---|---|
| `game_id` | I32 | game index; the opening is a function of seed and id |
| `game_opening_plies` | I16 | random plies at the start of `game_moves` |
| `game_scores` | I32 [·] | per move the mover's search score, `INT_MIN` for an opening ply or a race-play move; offsets as `game_move_offsets` |
| `game_label`, `game_label_ply` | U8, I16 | layer of the label (0 played to the end, 1 S1, 2 score margin, 3 static evaluation) and the ply of the labelled position; samples exist for plies below it |
| `game_final_ply`, `game_final_values` | I16, F32 [G, 2] | end of a game played to its end (control games, unadjudicated games), else −1 and NaN |
| `game_s1` | I16 [G, 3] | first S1 firing: ply, winner, predicted end ply (−1 if none) |
| `game_margin`, `game_eval` | I16 [G, 2] | first firing of layers 2 and 3: ply, winner |
| `game_race_scores_ignored`, `game_cpu_us` [G, 3], `game_nodes` | I32 | race-band scores seen by layer 2; thread CPU µs (total, search, layers 1 and 3); search nodes |

`game_values` holds the label, not the played result: an S1 verdict is the game value and stays the label even when the players fail to convert it (owner decision, E03 amendment). `game_moves` of a control game continue past the label ply.

### Self-play data tool (round 2, E05)

`sternhalma.selfplay.SelfPlay` (run with `devenv shell -- scripts/selfplay.sh`) plays N games of one alpha-beta spec against itself and writes the shards above. It never loads a network.

```text
selfplay.sh run --out DIR --spec SPEC --games N --seed S [--threads T] [--shard 50] [--control all|none|F]
                [--s1 1] [--margin 500] [--margin-moves 2] [--eval-threshold 0] [--eval-plies 4]
                [--opening uniform|softmax] [--opening-min 4] [--opening-max 10] [--opening-temp 30]
                [--opening-depth 1] [--tt auto|spec] [--samples 1] [--reuse 1]
                [--teacher-depth 2|3 [--teacher-tau T] [--teacher-alpha 0.7,0.3,100]]
selfplay.sh run --status --out DIR
selfplay.sh report DIR... [--sweep 400,500] [--check-s1 1]
selfplay.sh strong-check --from DIR --out DIR2 --spec SPEC [--limit 300]
selfplay.sh teacher-check --out DIR --seed S [--positions 300] [--depths 2,3] [--cost-games 20]
```

- **Layered adjudication** (`Adjudication`), two players only (other player counts are refused): layer 1 is S1 ([endgame and opening](endgame-and-opening.md#the-criterion-s1-adjudication)), checked before every move in a passed position. Layer 2 fires when the same player reports `|score| >= 500` with the same sign on 2 consecutive own moves; scores in the race band (`Scores.kind == RACE`, `|score| >= Scores.RACE_BASE` = 5000 below the mate bound; produced by E08's `racelin` evaluators, see [search and evaluation](search-and-evaluation.md#race-arithmetic-as-a-leaf-value-e08-racelin-and-racelin1-measured-2026-11-08)) and moves without a search (race play) are "no information" and reset the streak; the report counts the band scores the layer saw and checks that no margin label rests on one. Layer 3 (`sigmoid(eval/100) >= p` on 4 consecutive plies, ordinary values only) is off by default. Layers 2 and 3 never fire in a passed position: there only S1 labels. The first firing sets the label and the cut.
- **Control games:** a control game records the label and plays on to the end, so the label can be checked; `--control all` for acceptance runs, a 10 % default (deterministic per game id) for production. The cut and the samples of a control game equal those of the same game played without control (`SelfPlayGameTest`).
- **Openings** (`OpeningSampler`): k plies with k uniform in [4, 10], each uniformly random or drawn from a softmax over the scores of all root moves at temperature 30 (evaluation after the move, or a depth-1 search from the child with `--opening-depth 2`). Game `id` of seed `s` draws from `new Random(s * 1000003 + id)`.
- **Searcher reuse:** each pool thread keeps two players and calls `AlphaBetaPlayer.clearSearch()` between games; the games equal those of fresh players move for move. `--tt auto` sets `tt = clamp(ceil(log2(nodes)) + 6, 14, 18)` when the spec has no `tt` key (16 at 1000 nodes; at 1000 nodes `tt=16` and `tt=18` played identical games, see the measurements).
- **Job conventions** (`Job`, as in [Job conventions](#job-conventions)): the state comes from the shards (a shard that reads and validates is SKIP, a corrupt one is renamed `*.corrupt` and redone, `*.tmp.*` leftovers are removed, a shard written with other settings stops the run with 1); `FileChannel.tryLock` on `.selfplay.lock` (75 when held); `--status` (0 not running, 75 running, with pid, elapsed time and the last progress line); a shutdown hook on SIGINT and SIGTERM stops new games, writes the shards whose games all finished and prints the summary; the JVM then ends with 130 or 143 (verified by `SignalExitTest`, open question 11). Progress lines on stderr with rate, ETA and elapsed time count only games of this process.
- **`report`** prints, over the played-out games, per layer the firings, the wrong labels (the game played on by the same players is not won by the labelled side; a move-limit game counts as wrong), the one-sided 95 % Clopper-Pearson bound and the plies saved; the combined saving; S1 play-out disagreements, each checked by `VerdictCheck` (the plan against the loser's recorded moves and against every loser move, capped at 2 M nodes); diversity per 1000 games; the colour advantage; the cost; and with `--sweep` layer 2 recomputed offline from `game_scores` for other margins. **`strong-check`** continues the first layer-2 firings with a stronger spec on both sides and counts overturned labels.

The measurements of E05 are in [search and evaluation](search-and-evaluation.md#self-play-data-tool-e05-measured-2026-11-07).

### Symmetric augmentation

**Project reasoning:**

- **Rotations add nothing.** The encoder already maps every position into canonical orientation, so a rotated copy of a position encodes to the same input.
- **Two players: the mirror doubles the data.** In canonical orientation the mover starts in R_NEG and aims at R_POS. The mirror (q, r, s) → (s, r, q) (`Geometry.mirror`) keeps r, so it keeps both corners, the central hexagon and the playable cells; it swaps the two forbidden side corners. The canonical map of mover 1 is the 180° rotation, which commutes with the mirror (both are linear maps of (q, r, s), and negation commutes with every permutation). So the encoding of `position.transformed(Geometry::mirror, false)` is the encoding of `position` with its rows permuted by the mirror table. The player symmetry (`Geometry.playerFlip` with a colour swap) gives the same canonical image as the mirror, because playerFlip = mirror ∘ rotate180 ([rules](rules.md#symmetry)). The two-player symmetry group therefore gives exactly two canonical images: the sample and its mirror.
- **Three players: no augmentation.** The mirror swaps S_NEG and Q_NEG, the start corners of relative players 1 and 2, and reverses the play order ([network open question 6](network.md#open-questions)).

**Proposal:** for two players the batch builder mirrors each drawn sample with probability 1/2: row c' of the planes goes to row mirror(c'), and each move (from', to') becomes (mirror(from'), mirror(to')). Targets and weights stay. Positions that equal their own mirror image, such as the start position, are not special: their mirrored copy is a duplicate, which is harmless.

Tests: (1) the encoding identity above for random positions with each mover; (2) the legal moves of the mirrored position, mapped to canonical cells, equal the mirrored canonical moves of the original, as sets; (3) the network's loss on a sample and on its mirror differ for random weights (so the test of (1) cannot pass with an identity table) and agree for a network that has been made mirror-symmetric by averaging; (4) for three players, the builder never mirrors.

### Replay buffer

**Proposal:**

- The buffer holds the samples of the last generations in the compact file form (U8 cells, U8 moves), in memory. A window of W = 500,000 samples (to measure) costs about 310 MB.
- Loading a file appends its samples. When the buffer exceeds W, the oldest whole generation is dropped.
- A step draws its batch uniformly with replacement from the window, with the seeded sampler of [Checkpoints](#checkpoints). The steps per generation follow from a reuse factor R: steps = ⌈R · (new samples) / (batch size)⌉, with R = 4 to start (to measure).
- The builder expands the drawn samples into a `TrainingBatch`: bits to planes, plane 6 from ply, the mirror permutation, the CSR move arrays.

The buffer is rebuilt from the sample files at start; it has no file of its own. Its contents are therefore always derived from the artefacts.

### Bootstrap: generation 0 from a classical teacher

**Proposal:** generation 0 is supervised. `Strategies.BEST_SPEC` plays games against itself (two players), and every position becomes a sample with a teacher distribution:

- **Policy target:** (1 − ε) on the move that alpha-beta plays and ε spread uniformly over all legal moves, with ε = 0.05. `SearchResult` returns only the best move and its score; with PVS and aspiration windows the other root moves have only bounds, not exact scores. A softer target needs a multi-PV root search, which costs more (open question 4).
- **Value target:** z = the game result, optionally blended with the linear evaluation: z' = (1 − α) z + α · (2 σ(eval) − 1) for the mover, with α = 0.5 in generation 0 (**proposal**, to measure). `LinearEvaluator` is a logistic regression of the game result, so σ(eval) is its win probability.
- **Openings:** the first 4 plies are random, as in the arena; these samples get wp_s = 0 (the move was random) and flag bit 2.

**Yardstick:** on 400 held-out games, the linear evaluation predicts the winner of 78.8 % of the positions ([search and evaluation](search-and-evaluation.md#learned-linear-evaluation-evallinear-measured-2026-11-07)). The value head of generation 0 must at least reach this on the same kind of data, or the training has a problem.

Three players have no classical teacher of that strength yet: `BEST_SPEC` is alpha-beta for two players. Generation 0 for three players uses MCTS with the distance heuristic at the leaves, as [architecture](architecture.md#self-play-and-training-loop) describes (open question 5).

### Training loop

The loop follows [architecture](architecture.md#self-play-and-training-loop): self-play with the best network, training of a candidate, gating. This section makes it resumable.

#### Run directory

**Proposal:** one directory per run, `data/<run>/`:

```text
data/<run>/
  run.json                          configuration; written once at creation, never changed
  .lock                             lock file, held by the running process
  .status                           pid and start time, for display only
  gen-0000/
    samples/samples-*.safetensors   complete games and their samples
    train/step-00000500.safetensors training checkpoints (keep the last two)
    candidate.safetensors           network after the last training step
    gate/vs-best-*.json             gating games, written in shards of 10 games
    gate/vs-baseline-*.json         games against Strategies.BEST_SPEC
    decision.json                   promoted or rejected, with the match statistics
```

The best network is the candidate of the highest generation whose `decision.json` says "promoted"; generation 0 is always promoted. There is no pointer file such as `best.txt`, because such a file is state that the job wrote and that could contradict the artefacts.

#### State from the artefacts

At start the job derives, for the first generation g without `decision.json`:

| Phase | Done when | Resume |
|---|---|---|
| Self-play | the complete sample files hold at least the configured number of games | play the missing games; games that were running at the abort are lost |
| Training | `candidate.safetensors` exists and its `step` metadata equals the planned step count | load the highest checkpoint that reads without error; else start from the best network |
| Gating | the gate shards hold the configured number of games | play the missing games with the same seeds |
| Decision | `decision.json` exists | next generation |

"Exists" always means "reads and validates": a checkpoint that fails to load is skipped (and reported as CLEANUP after a rename to `*.corrupt`). Because `Safetensors.write` writes a temporary file and renames it, a file under its final name is complete. At start, the job removes leftover `*.tmp` files in the run directory and counts them as CLEANUP.

#### Gating

**Proposal:**

- **Against the best network:** 200 games, as 100 pairs: each pair starts from the same random 4-ply opening, and each network plays each side once. Equal search budget, no Dirichlet noise, move temperature 0 after the opening. Promotion needs more than 50 % of the points (the [architecture](architecture.md#self-play-and-training-loop) rule). The decision also records the 95 % interval; a statistical test such as SPRT is open question 3.
- **Against `Strategies.BEST_SPEC`:** 100 pairs at equal time per move (two players only). This match does not gate on its own, but promotion also requires that the candidate's score against `BEST_SPEC` is not lower than the best network's by more than two standard errors. It catches a candidate that beats its parent by exploiting it and gets weaker in general.

#### Teacher samples (round 2, E10)

**As implemented:** `selfplay.sh run ... --teacher-depth D [--teacher-tau T] [--teacher-alpha START,END,PLIES]` writes the same shards as the bootstrap above with teacher targets (class `Teacher`, used by `SelfPlayGame.samples`). BEST_SPEC still chooses the moves of the games (the node counts of a game do not change); the labels come from a second searcher with the spec's evaluator and options, futility, late move reductions and quiescence off (`qs` does not exist on this branch; it is off by definition), asked for the exact score of every root move at depth D (`AlphaBeta.rootScores`, [search and evaluation](search-and-evaluation.md#exact-root-scores-and-teacher-samples-e10-measured-2026-11-08)).

- **Policy target:** `softmax(tau * score)` over the legal moves in `Position.legalMoves()` order, tau = 0.1 at depth 3 and 0.06 at depth 2 (defaults; `--teacher-tau` overrides, and depths other than 2 and 3 need it). Flag bit 0 is set. Random opening plies keep their target but get wp = 0 and flag bit 2; adjudicated games set bit 1 as before.
- **Value target:** `z' = (1 - alpha(ply)) z + alpha(ply) (2 sigma(score / 75) - 1)` for the mover (the opponent gets the negative), where `score` is the best root score and `alpha(ply)` falls linearly from 0.7 at ply 0 to 0.3 at ply 100 and stays there. The reason for the ramp: early in a game the result is a noisy label of the position (random opening, 100 plies to go) and the search score is the better signal; late the result is almost certain. The schedule is a first choice and a command-line parameter, not a measured optimum.
- **Band rule (E08):** only ORDINARY and MATE scores enter. If the best root score is in the RACE band (`Scores.kind`), the sample gets `alpha = 0` (the label alone) and the bootstrap policy target with wp = 0 and no bit 0: race-band values come from region tables (upper bounds that ignore blockades). A passed position (`RaceAdjudication.passed`) takes its value only from S1 or the game result, whatever the search said. Measured share of race-band best scores at depth 2 in BEST games: 0.7%.
- **Metadata** (besides the keys above): `teacher_search` (the teacher's search configuration as text, e.g. `ab:eval=racelin1-r3,pvs,...,fut=0,lmr=0,qs=0,asp=0,depth=3,window=full`), `teacher_depth`, `teacher_labels` (depth, tau, alpha schedule, sigma scale). `selfplay` (the settings text that a resumed run must match) now ends with `;teacher=<labels>`; without teacher it is unchanged, so old shards still resume.
- **Format:** `sternhalma-samples` v1, the same tensors; `ReplayBuffer` (branch exp/T2c) reads such a shard without change (its checks: policy weight needs usable targets, and the fallback target of a wp = 0 sample is the bootstrap target).
- **Tests** (`TeacherTest`, `RootScoresTest`): targets sum to 1 and have exactly one entry per legal move in the order of the legal moves; they equal the softmax of the exact scores of an independent searcher; value targets equal the formula for ORDINARY and MATE scores and the label for RACE-band best scores and passed positions (with non-vacuous counts of both kinds); expansion equals `Encoder.encode`; teacher mode does not change the games; a tiny run writes and reads a shard with the metadata; an aborted teacher run resumes to identical tensors and a run with other teacher settings is refused.
- **Open tuning item:** the temperature that minimises the NLL of the 100k-node move is 0.06 at depth 3 and 0.05 at depth 2 (the defaults are 0.1 and 0.06 as planned); see search and evaluation. `TeacherReplayTest` loads a depth-2 teacher shard set into `ReplayBuffer` and runs one `ReferenceTrainer` step with a finite loss.
- **Progress line** of the tool shows `teacher x.xx ms/sample` next to plies per CPU second.

#### Job conventions

The job follows the [long-running-job conventions](architecture.md#long-running-jobs). How this maps onto Java (**proposal**):

- **Lock:** `FileChannel.tryLock()` on `data/<run>/.lock`. On macOS and Linux this is an operating-system record lock that the kernel releases when the process dies, also after `kill -9`. If the lock is held, the job prints `running already: pid=<pid> since <start>` from `.status` and exits with 75.
- **Status:** `--status` probes the lock and releases it at once. Lock free: `not running` (a `.status` file is then a leftover). Lock held: pid, start time and the elapsed time from `ProcessHandle.of(pid)`, then the phase, generation and last progress line.
- **Abort:** a shutdown hook (it runs on `SIGINT` and `SIGTERM`) sets a stop flag and waits up to 30 s for the main loop. The loop checks the flag between items: between training steps, between gating games, and between self-play games (running games are dropped, never written half). The loop then prints the summary and releases the hook. Exit status: 130 after `SIGINT`, 143 after `SIGTERM` (the JVM's 128 + signal convention, to verify on the target JDK).
- **Progress:** one line per 10 s on stderr, without carriage returns, for example `gen 3 train: 1200/4000 steps  31.5 steps/s  ETA 1m29s  elapsed 38s  loss 2.41 (kl 0.62, value 0.31)`. The rate counts only items done in this process, never skipped or resumed ones, so the ETA after a restart is right.
- **Summary:** counts of SUCCESS, SKIP, ERROR and CLEANUP per phase, and what a further run would do, for example `gen 3: 2000 games skip, 4000 steps ok, 0 error, 1 cleanup; next: gating`.
- **Exit codes:** 0 done or nothing to do, 1 error, 75 running already, 130 or 143 aborted.

#### Run monitor

**Project decision (owner):** every training run produces regular (a) checkpoints, (b) ETA estimates and (c) playing-strength estimates, not necessarily at the same cadence. The overhead of this is measured, and the cadences are adjusted to it; after every run the report is checked and the starting values below are changed if it asks for it.

`RunMonitor` (module `selfplay`) measures the wall time and the thread CPU time of six categories (`train`, `selfplay`, `checkpoint`, `progress`, `strength`, `gate`) and appends JSON lines to `data/<run>/metrics.jsonl`. The file is for display only; no decision of a run is derived from it. `monitor-report <run>` prints the share of each category against its budget and proposes an interval: **interval = cost per event / budget share, rounded up to 1, 2 or 5 times a power of ten.**

| What | Category | Budget (share of the wall time of the training loop) | Starting cadence (changed by the measurements of block 2e, see below) |
|---|---|---|---|
| (a) checkpoint (parameters and optimiser state, atomic, the last 2 kept) | `checkpoint` | 1 % | every 200 steps or 120 s, the earlier one |
| (b) progress line with ETA on stderr | `progress` | 0.1 % | every 10 s |
| (c1) cheap proxies on a fixed held-out set: policy KL to the teacher, top-1 agreement with the teacher, value loss, winner prediction (yardstick 78.8 %) | `strength` / `proxy` | 2 % | at the first and the last checkpoint and else at least 120 s apart |
| (c2) Elo estimate: raw policy of the network against a fixed classical ladder, 50 pairs, Elo with a 95 % interval | `strength` / `elo` | 5 % | a separate step on a copy of a checkpoint; in the loop only on request, at least 500 s apart |

The cadences are command-line options and are not part of the run configuration hash: a run with other cadences gives the same network, bit for bit, because the resume is bit-exact.

The loop's own metering is not duplicated: `TrainingRun.Timing` supplies the time of the steps, the checkpoints and the progress lines, and the monitor measures what the loop cannot see (the strength estimates) with the same `TrainingRun.Clock`. Indirect costs (cache, garbage collection) show up in the comparison of the samples per second with the monitor on and off.

## Part B: the GPU trainer with TornadoVM

Part B implements Part A as TornadoVM kernels. The forward kernels are those of the inference path (`docs/tornado.md`, written in parallel), with one change: the training variant writes each layer's output to its own slot of an activation store instead of reusing two buffers.

### Kernels of one training step

Every kernel writes each output element from exactly one thread. No kernel uses atomic operations, so a step is deterministic: the same inputs give the same bits on every run (a test checks this).

| # | Kernel | Threads | Work per thread | Part A twin |
|---|---|---|---|---|
| 1 | expand: cells, ply, mirror flag → input planes (**not built**: the host builds the planes and uploads them, [block B1-B5](#as-implemented-block-b1-b5)) | B · 122 | 7 writes | batch builder |
| 2 | forward: `conv_in`, block convolutions (bias, residual, ReLU fused), policy head, mean, dense1, dense2 | as inference | as inference | trainer forward |
| 3 | loss: masked softmax and cross-entropy per sample → dP, per-sample Lp | B | loop over the sample's moves, sequential | loss to outputs |
| 4 | value loss and value-head backward per sample → dy, dz1, dpooled | B | P · H + H · C multiply-adds | steps 2 to 4 |
| 5 | trunk gradient of the heads: dx_B = mask · (Wpᵀ dP + dpooled / 121) | B · 122 · C | 2 multiply-adds | steps 5, 6 |
| 6 | weight permute: W → W' for both convolutions of a block | 7 · C · C | 1 copy | (none; tested through 8) |
| 7 | weight gradient, split-K partial sums | S · C_out · 7 C_in | (B · 121 / S) multiply-adds | conv dW |
| 8 | input gradient: hex convolution of dz with W', then mask (and + dpre) | B · 122 · C_in | 7 · C_out multiply-adds | conv din (scatter form) |
| 9 | reduction: dW = Σ over the S partials; db = Σ over samples and cells | C_out · 7 C_in, C_out | S, or B · 121 | conv dW, db |
| 10 | dense and policy-head weight gradients | one per weight element | B (or B · 121) | dense dW, db |
| 11 | update: AdamW or SGD over the flat parameter buffer, finite check | one per parameter | about 15 FLOPs | optimiser |

Kernels 6 to 9 run once per convolution, in the order of [Order of the backward pass](#order-of-the-backward-pass). For LARGE (B = 6 blocks) a step launches about 50 kernels. A TornadoVM task graph holds them all, so one `execute()` call runs a step.

#### Avoiding atomic adds

Three places in the backward pass have many writers per output in the naive form. Each has a gather form:

- **Input gradient of the hex convolution.** The scatter form adds into row g(c, k) from up to 7 cells. The gather form of [Hex convolution](#hex-convolution) computes each din[r][ch] from 7 rows of dz with the permuted weight W'. It is the forward kernel with other arguments; kernel 6 builds W' each step, because W changes each step (7 · C² floats, 28,672 for LARGE).
- **Weight gradient.** Each dW element sums over all B · 121 (sample, cell) pairs. That is a matrix product dzᵀ [C_out × B·121] times the gathered input [B·121 × 7 C_in]. Its output is small (64 × 448 = 28,672 elements for LARGE) and its inner dimension is large (123,904 for a batch of 1024). Kernel 7 therefore splits the inner dimension into S chunks (split-K): each thread sums one chunk for one output element into partial[s][o][j], and kernel 9 sums the S partials in a fixed order. S = 32 (to measure) gives 917,504 threads for LARGE and a partial buffer of S · 28,672 floats (3.5 MB) per convolution, reused across layers.
- **Policy gradient.** Moves share "from" and "to" cells. Kernel 3 gives each sample one thread, which loops over the sample's moves and accumulates df and dt in its own row of dP. With about 80 moves per sample this loop is short; B threads are enough, because kernel 3 is a tiny part of the step.

**Tiling (proposal):** the first versions of kernels 7 and 8 use `@Parallel` loops, one thread per output element. The second version of kernel 7 uses `KernelContext` with a work-group of 16 × 16 output elements that loads 16 rows of dz and of the gathered input into local memory per iteration. Each version is tested against Part A before it replaces the previous one.

### Memory

On the device, per training step:

| Buffer | Size | LARGE, batch 1024 |
|---|---|---|
| parameters, gradients, Adam m and v (flat, file order) | 4 · parameters floats | 5.3 MB |
| split-K partials | S · 7 C² floats | 3.5 MB |
| stored activations | 102,698 floats per sample (LARGE) | 401 MB |
| two gradient activations | 15,616 floats per sample | 61 MB |
| compact batch (cells, CSR moves, targets) | about 1.3 KB per sample | 1.3 MB |

The parameters, gradients and optimiser state stay on the device across steps; the parameter buffer has the tensors in the order of `Parameters.shapes`, so a download is one copy per tensor. For SMALL, batch 1024 needs about 172 MB of activations. The Metal device reports 51.8 GB, so memory is no constraint; recomputing activations instead of storing them is not needed.

**What stays on the host:** the replay buffer and the batch sampler (random numbers, mirror flags, CSR arrays), the learning-rate schedule and the Adam bias corrections (computed in double, passed as float scalars), the loss log (one download of B per-sample losses and one finite flag per step), checkpoints (a download of parameters and optimiser state every 500 steps), self-play, gating and all files.

### Batch sizes

**Proposal:** 1024 samples per step on the GPU for SMALL and LARGE, as one microbatch; 8 to 64 samples in the CPU reference tests. A batch of 1024 gives kernel 8 B · 122 · C = 8 million threads for LARGE and kernel 7 enough split-K threads, and it keeps the launch overhead of about 50 kernels small against the compute. Larger effective batches use gradient accumulation: kernel 9 adds to the gradient buffer instead of overwriting it, and kernel 11 runs after the last microbatch.

**To measure:** the samples per second at 256, 512, 1024 and 2048, and the loss after a fixed number of samples. A larger batch with the same learning rate takes fewer, larger steps.

### Precision

**Proposal:** float32 everywhere on the device: master weights, Adam state, activations, gradients.

- **No double on the GPU.** The Metal Shading Language has no 64-bit floating-point type (verified in block B1-B5: a `double` kernel fails at the first execution with `TornadoDeviceFP64NotSupported`). Kernel 3 therefore computes the softmax in float: maximum, then the sum of exp(l − max), each term in (0, 1]. With at most a few hundred legal moves the float sum has a relative error of about 1e-5, which the tolerance below covers. The move logit f + t is formed in float; float logits near the float limit could overflow, but such logits mean broken weights, and the finite check of kernel 11 stops the run.
- **bfloat16 or float16 (option, not planned).** TornadoVM has a half-float array type, and Apple GPUs compute in float16. Storing activations in 16 bits would halve the memory traffic of kernels 7 and 8. It needs loss scaling against gradient underflow and new tolerances for every kernel test. It is worth trying only if a measurement shows that the step is limited by memory bandwidth.

### Verification against Part A

Each kernel has a twin in Part A with the same boundaries (the table above). A kernel test (**proposal**):

1. builds its inputs on the CPU from seeded data: `Parameters.uniform(config, seed, √(6 / 7C))` (the init bound, so that the tolerance of [network](network.md#numeric-conventions) applies), encoded positions of random games, and gradients produced by Part A for a real batch;
2. runs the kernel and the twin;
3. compares every output element and reports the largest error as a fraction of the bound.

Tolerances (**proposal**, to be confirmed by the reported errors):

| Output | Bound |
|---|---|
| forward activations, logits, values | the network bound: \|gpu − ref\| ≤ 1e-4 + 1e-3 · \|ref\| |
| gradients of activations and parameters | \|gpu − ref\| ≤ 1e-3 · \|ref\| + 1e-4 · max over the tensor of \|ref\| |
| update kernel, with the same float gradient as input | \|gpu − ref\| ≤ 1e-6 · \|ref\| + 1e-9 |

The gradient bound has a scale term because a gradient element is a sum of up to 123,904 terms of both signs: an element near zero can be the difference of large terms, the effect that [network](network.md#numeric-conventions) measured for large logits.

Whole-step tests:

- **Determinism:** two runs of 3 steps from the same state give bit-identical parameters on the GPU.
- **One step:** after one AdamW step from the same state and batch, the GPU and CPU parameters agree within 2 · lr for each element (Adam's step size is about lr · sign(g) where g is tiny, so the sign of a near-zero gradient may differ) and within 1e-3 · lr for elements with |g_ref| ≥ 1e-3 · max |g_ref|.
- **Twenty steps:** the losses of the two trainers after 20 steps on the same batches differ by less than 1 %. Parameters are not compared after many steps: rounding differences grow with the number of steps.
- **Skip rule:** every GPU test skips with a JUnit assumption when the SDK or a device is missing, as the module's build file provides.

### Expected speed

**Assumptions:**

- Training FLOPs per sample: 42.4 million for SMALL and 251.4 million for LARGE (2.97 and 2.99 times the forward pass; see [How the numbers were computed](#how-the-numbers-were-computed)).
- The CPU scalar trainer runs at about 1.5 GFLOP/s on one core, like the scalar forward pass ([network](network.md#measured-cpu-speed)).
- A CPU Vector API trainer, if one were written, might reach 25 GFLOP/s per core like `VectorNetwork`, and 400 GFLOP/s on 16 cores if it scaled perfectly. Neither the trainer nor the scaling exists or is measured.
- The M3 Max GPU has a peak of the order of 14 to 16 TFLOP/s in float32 (public figures; not measured by the project). Generated kernels without hand tuning typically reach a few per cent to about 15 % of the peak on such workloads. The estimate assumes 0.5 to 2 TFLOP/s effective. The measured GFLOP/s of the TornadoVM forward kernels, once known, replace this assumption: the backward kernels have the same shape.

| Trainer | SMALL samples/s | LARGE samples/s |
|---|---|---|
| CPU scalar reference, 1 core | about 35 | about 6 |
| CPU vector, 16 cores (hypothetical, ideal scaling) | about 9,400 | about 1,600 |
| GPU, 0.5 to 2 TFLOP/s | about 12,000 to 47,000 | about 2,000 to 8,000 |

**Project reasoning:** against the scalar reference the GPU is 300 to 1,300 times faster; against a hypothetical well-scaled vector trainer on all 16 cores it is only 1.25 to 5 times faster. The honest case for the GPU trainer is therefore the second effect: the CPU stays free for MCTS and move generation in self-play, which run at the same time.

**Measured (block B6-B10, [measurements](#measurements-1)):** SMALL `classic1`, whole steps on the M3 Max: **49,300 samples/s at batch 256, 60,800 at 1,024** (the estimate was 12,000 to 47,000), at 2.1 to 2.6 TFLOP/s of matrix work, above the 0.5 to 2 TFLOP/s that the estimate assumed. The CPU trainer that exists (the scalar reference on 8 threads) does about 900 samples/s in loop run 1, so the GPU is 55 to 68 times faster; the hypothetical vector trainer of the table was never written. The LARGE figures above are still the estimate (about 10,000 samples/s by the flop count and 2.6 TFLOP/s). Inside the loop the step is limited by building the batch on the host (2.3 ms of 8.5 ms).

#### Where the time goes

An example generation with LARGE: 500 games of about 100 plies give 50,000 samples (to measure). With 200 MCTS simulations per move, self-play costs 10 million network evaluations, 843 TFLOP. Training with reuse factor R = 4 costs 200,000 sample passes, 50 TFLOP. Self-play is about 17 times the training work. Every ply that classical code plays instead of MCTS (see [What classical code covers](#what-classical-code-covers)) saves more GPU time than any speed-up of the training kernels can.

### How the numbers were computed

With N = 121 cells, I = 7 input planes, H = 32, multiply-adds counted per sample:

```text
forward          = N*C*7*I + B*2*N*C*7*C + N*2*C + H*C + P*H          (network.md)
weight gradients = forward                                               (every multiply-add appears once)
input gradients  = forward - N*C*7*I                                     (conv_in needs no input gradient)
training         = forward + weight gradients + input gradients
FLOPs            = 2 * training                                          (element-wise work ignored)
stored floats    = 854 + 122*C*(1 + 2*B) + 242 + C + H + P
```

| Preset | Forward multiply-adds | Training multiply-adds | Ratio | Training MFLOP per sample |
|---|---|---|---|---|
| TINY | 158,104 | 426,880 | 2.70 | 0.85 |
| SMALL | 7,137,184 | 21,221,824 | 2.97 | 42.4 |
| LARGE | 42,028,800 | 125,706,944 | 2.99 | 251.4 |

These counts use P = 2 and were computed by a script on 2026-11-07. A batch of 1024 costs 43.5 GFLOP with SMALL and 257 GFLOP with LARGE. The example generation: 50,000 · 200 · 84,313,526 FLOPs ≈ 843 TFLOP for self-play, and 4 · 50,000 · 251,413,888 FLOPs ≈ 50 TFLOP for training.

## Tests of Part A

An earlier review found that weak tests were the main risk ([network](network.md#what-the-tests-must-catch)). Each test below names the plausible bug it must catch. Every numeric test reports its largest error as a fraction of its bound.

### Per operation

- **Adjoint test for every linear operation** (hex convolution, 1×1 convolution, dense, mean, residual add): for random v and r, ⟨op(v), r⟩ = ⟨v, opᵀ(r)⟩, in double; for the weight gradient with zero bias, ⟨conv_W(in), r⟩ = ⟨W, dW(r, in)⟩. Bound: 1e-6 · (Σ |terms|). This is exact up to rounding, so it is far stronger than a finite difference. It catches a wrong π (an identity π fails, because the neighbour directions are not symmetric), a transposed weight index, a missing 1/121, and a gradient leaking into row 121.
- **Scatter against gather:** the reference din (scatter form) equals the gather form of [Hex convolution](#hex-convolution) within 1e-6 relative, on TINY and SMALL shapes. This test also protects Part B, which uses the gather form.
- **Hand-computed convolution gradient:** one channel, one cell with known weights, where only neighbour direction k contributes; for each k, din must appear exactly in the neighbour's row.
- **Finite differences for the non-linear operations,** in double with step h = 1e-4: ReLU at points with |z| > 1e-2, tanh, and the policy cross-entropy. The perturbed float logit is rounded to float, and the difference of the two rounded inputs is the denominator. Bound: 1e-6 + 1e-4 · |gradient|.
- **Policy loss bugs:** a target that differs between "from" and "to" (from and to swapped must fail); two moves with the same "from" cell (overwrite instead of add must fail); logits 1e4 apart where the first move is not the largest (subtracting the first logit instead of the maximum must fail); targets with zeros (no NaN); KL = 0 when the target equals the softmax.
- **Value loss bugs:** 1 − y² instead of 1 − v²; targets in absolute instead of relative order (positions with mover 1 and mover 2 of three players).

### Whole network

- **Forward equality:** the trainer's forward outputs equal `Network.forward` bit for bit.
- **Finite-difference gradient check on TINY, for every parameter** (1,684 for two players, 1,717 for three): a test-only double-precision forward pass, written from the layer table and checked against `Network` (agreement within 1e-6 relative), gives the numerical gradient by central differences with h = 1e-6 · max(1, |θ|). **Kink guard:** if the sign pattern of any pre-activation at θ ± h differs from the one at θ, the coordinate is skipped and counted; the test fails if more than 5 % of the coordinates are skipped. Bound: |analytic − numeric| ≤ 1e-3 · |numeric| + 1e-5 · max |numeric|. Use a batch of 4 samples with legal moves, both value weights and policy weights non-zero, weights from `Parameters.uniform` (biases non-zero), and both player counts.
- **Batch and accumulation:** the gradient of a batch of 8 equals the weighted mean of 8 single-sample gradients, and two microbatches of 3 and 5 equal one batch of 8, within 1e-6 relative.
- **Mixed movers:** a batch that mixes movers gives the same per-sample gradients as single batches.

### Optimiser and training

- **Optimiser arithmetic:** SGD with momentum and AdamW against values computed by hand for two steps on a three-element tensor. Must catch: bias correction starting at t = 0, weight decay applied to biases (a bias with zero gradient must not change, a weight with zero gradient must shrink by exactly the factor 1 − lr · wd under AdamW), momentum applied before instead of after adding the gradient.
- **One SGD step lowers the loss, by the predicted amount:** on a fixed batch of 16 encoded positions, with lr chosen so that the predicted decrease lr · |g|² lies between 1e-4 and 1e-2 of the loss (far above the float rounding of the loss, far below the range where second-order terms matter), the loss after one step must be lower, and the decrease must match the first-order prediction lr · |g|² within 10 %. A gradient with the wrong sign fails the first check; a gradient with a wrong factor (2 g, or g / N twice) still lowers the loss but fails the second.
- **Overfitting 8 positions reaches the target entropy:** a teacher network of the same config (TINY with `Parameters.uniform` and a bound chosen so that the mean target entropy is at most 0.6 of the mean log of the move count) produces the policy and value targets for 8 positions. The targets are then exactly representable, which matters: the factorised logit from + to cannot represent every distribution over moves ([network](network.md#policy-head-and-move-logits)), so arbitrary targets could not be fitted. A student from `Parameters.init` with another seed trains with AdamW (lr 1e-2, at most 3,000 steps). Pass: mean policy KL below 0.02 nats and value MSE below 1e-3 (to measure on the first implementation and tighten).
- **Resume equivalence:** see [Checkpoints](#checkpoints).

### As implemented (block 2a)

**Work plan steps A1 to A3** are in module `nn`, package `sternhalma.nn` (measured and tested 2026-11-08). A4 and later are in [block 2b](#as-implemented-block-2b).

| Class | Content |
|---|---|
| `ReferenceBackward` | The rules of [Operations](#operations): `reluBackward`, `tanhBackward`, `addBackward` (residual), `pointwiseBackward` (1×1 convolution), `denseBackward`, `meanBackward`, `hexConvBackward` (scatter form). The gather form is `permutedWeights` (W') with the table `PI` and `hexConvInputGradientGather`. |
| `ReferenceLoss` | `policy` (masked, factorised softmax cross-entropy in double, gradient into `dPolicy`), `entropy`, `value`. The KL divergence is `policy` minus `entropy`. |
| `TrainingBatch` | The record of [Batch layout](#batch-layout), validated in the constructor, plus `Builder` (normalises the targets in double, computes H_s) and `relativeValues(Outcome.values, mover)`. |
| `ReferenceTrainer` | Forward pass with stored activations, backward pass in the documented order, `Gradients` (one `double[]` per tensor), `accumulate(batch, microbatch or sizes, threads)` returning `Result(loss, policyLoss, valueLoss, kl)`. |

Deviations and details that the text above leaves open:

- **Gradient activations are `double[]`.** Parameter gradients are double as documented; so are the gradient activations (dx, dt, dP), which keeps the adjoint tests at 1e-6 of the bound with room to spare. Part B computes them in float and is compared against this oracle with the tolerances of [Verification against Part A](#verification-against-part-a).
- **Every backward rule adds (`+=`) to its gradient outputs**, including `din`; the caller zeroes buffers. An operation that owns a whole activation gradient (`meanBackward`, `hexConvBackward`, the gather form) sets row 121 to 0 and never adds to it.
- **The value loss returns the gradient with respect to y**, with the tanh derivative inside (`dy_i` of [Loss to network outputs](#loss-to-network-outputs)). The trainer therefore starts the backward pass at `head_value.dense2` and never calls `tanhBackward`; that rule exists for the finite-difference test.
- **`hexConvBackward` skips terms with dz = 0** (about half of them after a ReLU). They add 0; the only difference is that a NaN in the input behind such a term no longer turns the gradient to NaN. The loss check catches NaN activations anyway.
- **The trainer computes the data term of the loss only.** The L2 term enters through the optimiser (`g + lambda * theta` for SGD, decay for AdamW), as the optimiser formulas of this document already say. c_v is a constructor argument.
- **`TrainingBatch` has one more field, `players`**, and copies its arrays on construction; the accessors return the internal arrays without copying. Further checks: targets sum to 1 ± 1e-5 when wp > 0, the stored entropy equals the entropy of the targets ± 1e-5, input finite with row 121 equal to 0, value targets in [−1, 1].
- **Microbatches:** `accumulate` takes the sizes of consecutive microbatches (or one size). Each microbatch has its own gradient buffers and runs sample by sample in ascending order; the buffers are added to the trainer's in microbatch order, also with several threads, with at most 2 * threads results waiting at a time (the oldest is merged first when the window is full) (a test checks bit-identical gradients for 1, 2 and 8 threads). Calling `accumulate` again adds to the gradients until `zeroGradients()`. NaN or infinity in a loss or in a gradient element raises `IllegalStateException`; the gradients are undefined afterwards.
- `ReferenceOps.gatherRow` became package-private so that the scatter form computes its rows from `Geometry.neighbor` like the forward oracle, not from the `HexGrid` table.

**Tests:** 42 new tests (`ReferenceBackwardTest` 14, `ReferenceLossTest` 10, `TrainingBatchTest` 5, `ReferenceTrainerTest` 13) of the lists in [Tests of Part A](#tests-of-part-a), "Per operation" and "Whole network". Measured errors, as a fraction of the bound: adjoint tests at most 0.007; scatter against gather 3e-7; finite differences over every parameter of TINY at most 0.0003 (2 players, 1,684 parameters) and 0.003 (3 players, 1,717 parameters), none skipped by the kink guard. A configuration with 3 blocks (C = 4, 2 and 3 players) is checked too, because TINY has one block and cannot show a bug that acts only from the second block on; the microbatch and thread tests use 3 blocks as well. The finite-difference batch has a fifth, synthetic sample whose moves touch cells 0 and 120: without it, real positions leave the gradient of the last cell of the policy head at 0, and a bug there survived a mutation check. Test-only helpers: `DoubleNetwork` (double-precision forward pass from the layer table, agrees with `Network` within 0.04 of a 1e-5 relative bound), `TestBatches`.

**Mutation checks:** 27 deliberate bugs (the 22 listed, plus: swap of the gradient buffers only in the first block iteration, block 0's conv1 weights in every block, newest-first merge of microbatch results, and the two new `Builder.add` length checks removed) in the backward code, the losses, the batch validation and the trainer (identity π, missing 1/121, overwrite instead of add in the convolution weight gradient, the policy gradient and the microbatch merge, ReLU mask `<0` instead of `<= 0`, residual identity path dropped, `1 − v` instead of `1 − v²`, softmax shifted by the first logit, division by a wrong N, transposed 1×1 weight gradient, bias gradient of one cell, scatter into row 121 or into the own cell, lost factor 2/P, missing ReLU mask of `conv_in`, skipped mean backward, unchecked duplicate move, stale `dt`, residual add from the wrong slot, unpermuted W', policy head without its last cell) were all caught (the whole set was re-run after the review additions); the first run of the last one survived and led to the synthetic sample above.

**Speed (measured, `devenv shell -- ./gradlew --no-build-cache :nn:trainerBenchmark`, one thread, batch of 16, about 30 legal moves per sample, Apple M3 Max, JDK 25.0.3, load average 8 on 16 cores, so indicative):**

| Preset | Samples/s (median of 5) | Range | Matrix work |
|---|---|---|---|
| TINY | 4,075 | 3,962 to 4,169 | 3.5 GFLOP/s |
| SMALL | 114 | 112 to 115 | 4.9 GFLOP/s |

SMALL is 3 times faster than the estimate of [Expected speed](#expected-speed) (about 35 samples/s), mostly because the double accumulation of the convolution gradients skips zero gradients. LARGE was not measured.

### As implemented (block 2b)

**Work plan steps A4 to A6** are in module `nn`, package `sternhalma.nn` (measured and tested 2026-11-08), together with a measurement hook for every training run that the project owner asked for. A7 and later are not.

| Class | Content |
|---|---|
| `Optimizer` | SGD with momentum and AdamW as in [Optimiser](#optimiser). `Settings` (kind and the six hyperparameters), `step(gradients, lr)`, `restore(...)` for a checkpoint. |
| `LearningRateSchedule` | `rate(generation, step)`, a pure function of the record's fields: [Learning-rate schedule](#learning-rate-schedule). |
| `BatchSampler` | `mix(runSeed, generation, step)`, `indices(...)`, `batch(pool, ...)` (uses the new `TrainingBatch.select(int[])`). |
| `Checkpoint` | `write`, `read`, and for a directory `file`, `stepOf`, `list`, `recover`, `removeTemporaries`, `keepLast`. |
| `TrainingObserver` | `onStep(step, loss, klPolicy, valueLoss, wallNanos)` and `onCheckpoint(path, wallNanos, bytes)`; both default to nothing. |
| `TrainingRun` | The loop around all of the above: checkpoint cadence, progress lines, observers, `Timing`. |

Deviations and details that the text above leaves open:

- **State is float, arithmetic is double.** m, v, u and the parameters are float arrays; each update reads them as double, computes, and stores the rounded float. The next step starts from the stored values, so a checkpoint holds the whole state and a resumed run is bit-identical. The gradient is rounded to float when read. `Math.pow` of the bias correction is `StrictMath.pow`, and the cosine is `StrictMath.cos`: `Math.pow` and `Math.cos` may differ in the last bit between interpreter and compiled code, which would break bit-identical resume across processes.
- **Step counting.** The update of step number `steps + 1` uses `t = steps + 1`; the first AdamW step divides by `1 - beta^1`. A step with a non-finite lr or a negative lr is rejected before anything changes; a parameter that becomes non-finite raises `IllegalStateException`.
- **Defaults.** `Settings.adamw()`: 0.9, 0.999, 1e-8, wd 0.01. `Settings.sgd()`: momentum 0.9, lambda 1e-4. Both kinds store all six values in a checkpoint.
- **Schedule.** The rate of 0-based step s in warm-up is `lr (s + 1) / warmup`, so the first step moves and the last warm-up step has the full rate. Generation 0: the cosine runs from step `warmup` (rate lr) to the last planned step `steps - 1` (rate `0.1 lr`) and stays there afterwards. Self-play generations: the same linear warm-up, then constant; a milestone `(g, r)` replaces lr by r from generation g on, as an absolute rate (not a factor). Milestones do not apply to generation 0. The record needs `generationZeroSteps > warmup + 1`.
- **Checkpoint file.** As in [Checkpoints](#checkpoints); `optimizer` is `adamw` or `sgd`; `run_config_sha256` must be 64 lowercase hex digits (the caller hashes its run configuration). The reader returns `Loaded(parameters, optimizer, generation, runConfigSha256)`; the caller compares the hash. It rejects, with `IOException` only: other format or version, unknown optimiser, missing or malformed counts and hyperparameters, a tensor set that does not match the optimiser exactly (missing, extra, wrong shape), non-finite numbers, negative v. Unknown extra metadata keys are tolerated. **There is no checksum**: the safetensors format has none, so flipped bits inside the tensor data that leave every number finite are not detected (a test flips random bytes and only requires that nothing but `IOException` escapes).
- **Directory helpers.** Files are named `step-<8 digits>.safetensors`. `recover(dir)` tries the newest file first; a file that fails to read, or whose name disagrees with its `step` metadata, is renamed to `<name>.corrupt` and reported in `Recovery.quarantined()` (the CLEANUP of [State from the artefacts](#state-from-the-artefacts)); then the next older file is tried. A disk read error looks like a damaged file to it. `removeTemporaries` deletes the `*.tmp` leftovers of `Safetensors.write`; `keepLast(dir, n)` deletes all but the n newest checkpoint files.
- **Sampler.** `mix` runs SplitMix64's finaliser over each argument in turn, so (generation 1, step 0) and (generation 0, step 1) differ. A batch is drawn uniformly with replacement from the pool (`SplittableRandom.nextInt`); the replay buffer of A8 will replace the pool.
- **`TrainingRun`** is a final class around a `Setup` (parameters, optimiser, schedule, pool, batch and microbatch sizes, threads, c_v, seed, generation, planned total steps), `Checkpointing` (directory, every N steps, every T nanoseconds, keep, hash), `Reporting` (observers, progress stream or null, interval), a `Clock` (wall nanoseconds and thread CPU nanoseconds, injectable) and a stop supplier that is asked between steps. Order inside one step: update, observers, checkpoint if due, progress line if due. A checkpoint is due when the completed steps are a multiple of N (absolute, so a resumed run keeps writing `step-00000500` and so on), or when T has passed since the last checkpoint (the timer restarts at each checkpoint), whichever comes first. A last checkpoint is written at the end or after a stop if steps are not yet covered. At the start it removes leftover `*.tmp` files (CLEANUP). The progress line is the one of [Job conventions](#job-conventions); loss, KL and value are means over the steps since the previous line; the rate is the steps of this process divided by the elapsed time of this process. At the end it prints a summary `gen G train: S ok, K skip, 0 error, C cleanup; L left; nothing more to do` (or `; aborted`), where K is the number of steps that the starting checkpoint already covered. An exception in an observer ends the run: a measurement hook that fails silently would hide the failure. Exit codes are for the caller (A10).
- **Overhead accounting.** `TrainingRun.timing()` (also in `Summary`) returns `Timing`: wall and thread CPU time (`Spent`) of four categories, `STEP`, `CHECKPOINT` (write plus `keepLast`), `OBSERVER` (all observer calls, including those for `onCheckpoint`) and `PROGRESS`, plus the loop's own wall and CPU time. `share(category)` is the wall share of the loop, `cpuShare(category)` the CPU share; `unaccountedWallNanos()` is what no category claims. The `wallNanos` that the observers receive are the same measured values that the timing adds up. Limit: thread CPU time covers the loop thread only; with `threads > 1` the work of the trainer's pool threads is not in `STEP` CPU time (the wall time is). A `RunMonitor` can read `timing()` from another thread; the fields are volatile and written by the loop thread only.

- **Failure and durability (review fixes).** An exception from an observer, a step or a checkpoint write ends the run at once, with no final checkpoint; the next run resumes from the latest checkpoint that reads. Checkpoints are not fsynced: a power loss may lose or damage the newest file, and `recover` then falls back to an older one. `Timing.loopCpuNanos` while the run is live is the loop thread's CPU time as published at the end of its latest measured interval (a monitor thread calling `timing()` must not, and does not, read its own CPU time); the start is flagged by a boolean, so a negative `nanoTime` is fine.
- **One optimiser per generation.** `optimizer.steps()` is at once the Adam t, the index into the learning-rate schedule and the step of the batch sampler, all counted from 0 within a generation. The training loop of A10 must create a fresh `Optimizer` for each generation and not carry state or step count over (documented on `Optimizer.steps` and `TrainingRun.Setup`; it cannot be checked from the objects).

**Tests:** 64 new tests (`OptimizerTest` 12, `LearningRateScheduleTest` 6, `OverfitTest` 1, `BatchSamplerTest` 7, `CheckpointTest` 17, `ResumeEquivalenceTest` 2, `TrainingRunTest` 19). The cadence, ETA and accounting tests inject a clock that only moves when a test observer advances it, so their numbers are exact; one more test uses the real clock and checks that the categories are consistent (step and checkpoint times equal the sums the observers received, an observer that sleeps has large wall and small CPU time, the shares add up to at most 1).

- **Optimiser arithmetic:** hand-computed values for two steps of AdamW and SGD (computed outside the code with a double-precision script that rounds the state to float), including a bias with gradient 0 that must not move, a weight with gradient 0 that shrinks by exactly `1 - lr wd`, and an eps test with eps = 0.5, because with eps = 1e-8 "eps under the root" gives the same float.
- **One SGD step lowers the loss by the predicted amount:** TINY, 16 positions, lr set so that the prediction is 1e-3 of the loss; measured decrease / prediction = 0.990 (bound 0.9 to 1.1).
- **Overfitting 8 positions:** the teacher is TINY with `Parameters.uniform` and the weight bound `1.0 ... 4.0` times the init bound in steps of 0.05, the first one whose mean target entropy is at most 0.6 of the mean log move count; measured: scale 2.1, ratio 0.591, mean |value| 0.92. The student (`Parameters.init`, other seed, AdamW lr 1e-2, no schedule, batch of 8 in microbatches of 4) starts at policy KL 0.85 and reaches KL 0.0001 and value MSE 9e-4 after 135 steps (limit 3,000; bounds 0.02 and 1e-3). The loop stops at the first step that meets both bounds, which is why the value MSE sits just below its bound. Note the teacher's values are close to saturated (0.92 mean |value|); that is what the unnormalised uniform weights give.
- **Resume equivalence:** 10 steps against 5 steps, checkpoint, a fresh `Checkpoint.recover`, 5 steps, for AdamW and SGD, generations 0 and 1, microbatches of 2 and 2 threads, a schedule with a 4-step warm-up (so that the rate still changes at step 4); parameters and optimiser state are bit-identical. A control test that resumes with a zeroed optimiser state differs.

**Mutation checks:** 49 deliberate bugs, all caught (47 by a failing assertion, 2 by a hang that the 150 s limit of the mutation script ended: "bias correction from t = 0" and "step counter not advanced", where the run loop never finishes). AdamW: no bias correction of m or of v, correction of m with beta2, bias correction from t = 0, decay on biases, no decay, decay not scaled by lr, eps under the root, v not squared, m with beta1 twice. SGD: momentum before adding the gradient, L2 on biases, L2 added to the update, no lr, velocity reset each step, gradient sign, gradient doubled. Step counter not advanced. Schedule: warm-up from step instead of step + 1, cosine end off by one, strict milestone, self-play warm-up length, final fraction ignored, no clamp beyond the plan. Checkpoint: step, generation or one hyperparameter not saved, v saved as m, no finite check of parameters or of state, extra tensors accepted, restore with step 0, name-step mismatch accepted, `keepLast` off by one, `recover` takes the oldest. Sampler and batch: mix as a plain sum, step ignored, pool index off by one, wrong value target or policy weight in `select`. Run: rate counting resumed steps, step cadence relative to the start, time cadence not restarted, no final checkpoint, CPU time measured as wall time, ETA without the done steps, wrong byte count, batch index or learning-rate step relative to the start. After the review, 6 more were added and caught: timer stamped before the write (a checkpoint that takes longer than T), no finite check of the updated parameters, progress sums or count not reset after a line, `timing()` reading the calling thread's CPU time, and a negative-start sentinel; the 49 were run again (one now also ends in a hang: "gradient sign"). The eps test above was added before the mutation runs because "eps under the root" gives the same float as the right formula for eps = 1e-8, so the hand-computed tests could not catch it.

**Checkpoint cost (measured, `./gradlew --no-build-cache :nn:checkpointBenchmark`, JDK 25.0.3, Apple M3 Max, AdamW state):**

| Preset | Parameters | File size | Write (median of 20) | Read |
|---|---|---|---|---|
| TINY | 1,684 | 23,640 bytes | 0.64 ms (0.53 to 1.53) | 1.4 ms |
| SMALL | 60,388 | 731,384 bytes | 1.07 ms (0.82 to 2.80) | 2.5 ms |

A SMALL run of 500 steps (batch 16, one thread, 8.2 steps/s, checkpoint every 100 steps, keep 2, a do-nothing observer, 60.6 s): 5 checkpoints took 7.3 ms in all, 0.012 % of the loop time; the observer calls 2.8 ms (0.0045 %), the progress lines 1.9 ms (0.003 %); the steps 99.97 %. At this size checkpoints are free; LARGE (about 4.2 MB per file) was not measured.

### As implemented (block 2c)

**Work plan steps A7 and A8** (measured and tested 2026-11-08). The typed safetensors reader and writer of A7 already existed (`SampleFile`, `GameShard`, E05); this block adds the expansion, the mirror and the replay buffer.

| Class | Module | Content |
|---|---|---|
| `SampleExpander` | `nn` | `expand(players, cells, cellsOffset, ply, moveLimit, mirror, out, outOffset)`: canonical cell bytes to the 7 planes, equal to `Encoder.encode` float for float; `mirrorCell` (`Geometry.mirror`). Needs no selfplay class, so it lives next to `Encoder`. |
| `ReplayBuffer` | `selfplay` | Window of generations in compact form, `addGeneration`, `batch(runSeed, generation, step, batchSize)`, `batchOf(indices, mirror)`. |

`selfplay` already depended on `nn` (`Orientation` gives the canonical cells of the sample writer), so no build file changed apart from the benchmark task `:selfplay:replayBenchmark`.

Deviations and details that the text above leaves open:

- **Expansion.** Planes 3 to 5 come from a template per player count and mirror flag, built once from `Layout.of(players)` for player 0; per sample the template is copied and plane 6 and the piece planes are written. Cell bytes above the player count are rejected. `mirror` with three players throws. The template of the mirrored case is permuted by the mirror table as well, although planes 3 to 5 are mirror-invariant for two players (the mutant that skips this permutation is equivalent and survives, as it must).
- **Window.** After each `addGeneration`, while the buffer holds more than W samples and more than one generation, the oldest generation goes as a whole (all its files). The newest generation is never dropped, even if it alone exceeds W. A window of exactly W samples keeps everything. Generations must be added in increasing order (`IllegalArgumentException` otherwise). The data generation of the buffer is not the training generation of the sampler seed.
- **Loading.** Files are read with `SampleFile.read` and validated completely: format and version, tensor lengths, offsets from 0 to M and non-decreasing, ply >= 0, move limit >= 1, cell bytes <= players, cells and moves in 0 to 120, no duplicate (from, to) pair per sample, targets finite and >= 0, value targets in [-1, 1], weights finite and >= 0, and a positive target sum where the policy weight is positive. A file that fails is skipped. `addGeneration(generation, directory)` reads the files `samples-*.safetensors` in name order and renames a failed file to `<name>.corrupt` (the CLEANUP of the [job conventions](#job-conventions), listed in `Loaded.quarantined()`); `addGeneration(generation, files)` skips without renaming. A read error looks like a damaged file, as in `Checkpoint.recover`. A file with another `players` value is not corrupt but a wrong configuration: `IllegalArgumentException`, no rename. A file with 0 samples is valid. Because everything is checked at load time, `TrainingBatch`'s constructor cannot reject a batch of this buffer.
- **Targets.** `move_target` is consumed as stored (the E05 bootstrap target, 1 - eps on the played move) and normalised to sum 1 in double at load time, like `TrainingBatch.Builder`; the entropy H_s is computed once at load time. Policy weight 0 (random opening plies) passes through, so the trainer ignores their policy term; `value_weight` and `policy_weight` come from the file.
- **Draw.** `batch` takes the indices from `BatchSampler.indices(runSeed, generation, step, size(), batchSize)` over the whole window (oldest sample first, files in the order they were loaded). For two players with augmentation one more stream, `new SplittableRandom(BatchSampler.mix(runSeed ^ salt, generation, step))`, gives one `nextBoolean()` per drawn sample: mirror or not. The same seed, generation and step therefore give the same batch, bit for bit, also from a second buffer loaded from the same files. For three players the augmentation flag is ignored and `batchOf` refuses a mirror.
- **Mirror.** A mirrored sample has its planes expanded with row c' in row `mirror(c')` and each move (from', to') as (mirror(from'), mirror(to')); targets, value targets and weights stay.

**Tests:** 9 in `nn` (`SampleExpanderTest`), 21 in `selfplay` (`ReplayBufferTest` 19, `ReplayBufferEndToEndTest` 2, plus the test helper `SyntheticSamples`, which writes sample files for two and three players from random playouts, because the E05 tool plays two players only).

- **Expansion equals the encoder:** positions of seeded random games with 2 and 3 players (every mover occurs), compared float for float with `Encoder.encode`; the progress plane at and beyond the move limit; slices at an offset in a buffer filled with garbage.
- **The four tests of [Symmetric augmentation](#symmetric-augmentation):** (1) the mirrored expansion equals the rows of `Encoder.encode` permuted by the mirror table, and equals `Encoder.encode` of `position.transformed(Geometry::mirror, false)` (at ply 0, because the transformed position has no history), for both movers; more than half of the test positions are not mirror-symmetric, so an identity table cannot pass. (2) The legal moves of the mirrored position, in canonical cells, equal the mirrored canonical moves of the original, as sets, for more than 100 positions of both movers. (3) On TINY with random weights the loss of a sample and of its mirror differ in more than 80 % of the positions; for the network averaged over the mirror they agree (largest relative difference below 1e-5). The averaging ties each hex-convolution tap with the tap that the mirror maps it to (the permutation is computed from `Geometry.neighbor`, not written down), the policy head acts per cell and the value head on the mean over cells, so they need no change. (4) Three-player samples are never mirrored, by the expander and by the buffer.
- **Buffer:** the window drops whole generations (also a generation of several files, also several generations at once), keeps an oversized newest generation and a window of exactly W; same seed, generation and step give the same batch, a changed seed, generation or step does not; the draw is uniform over the whole window across generations; batch rows equal `Encoder.encode` of the original positions, moves, normalised targets, values and weights are those of the file, for 2 and 3 players with every mover; about half of 2,000 drawn samples are mirrored, consistently in planes and moves; 13 kinds of damage and a truncated file, a foreign file and a wrong format version are skipped, and renamed to `.corrupt` by the directory variant only.
- **End to end:** 4 real games of the E05 tool code (`SelfPlayGame` with `BEST_R1_SPEC`, 100 nodes per move, no adjudication), written with `GameShard.write`, loaded, every sample compared with `Encoder.encode` of the position that a replay of the recorded moves gives (both movers occur, the random opening plies have policy weight 0, the moves equal the legal moves in canonical cells in file order); a batch of 32 trains one `ReferenceTrainer` step on TINY with a finite loss and a non-zero finite gradient, and the same step gives the same loss twice.

**Mutation checks:** 24 deliberate bugs, 23 caught, 1 equivalent. Caught: mirror table replaced by the 180-degree rotation; plane 6 with ply + 1; plane 6 without the clamp; playable plane of player 1; goal and start corners swapped; piece plane off by one; window drops one file instead of the generation; window `>=` instead of `>`; window never drops (2 W); mirror coins from an unseeded generator; step ignored in the draw; moves not mirrored with the planes; mirror probability 1/4; augmentation for three players; policy and value weights swapped; targets not normalised; block of an index off by one; duplicate moves unchecked; corrupt file not renamed; value target of the wrong sample; cell byte range unchecked; negative ply accepted; policy weight without targets accepted. Equivalent: the template of the mirrored planes not permuted (planes 3 to 5 are invariant under the mirror).

**Speed (measured, `devenv shell -- ./gradlew --no-build-cache :selfplay:replayBenchmark`, one thread, 20,000 samples from random playouts with 46 legal moves per sample, Apple M3 Max, JDK 25.0.3, load average 9 on 16 cores, so indicative):**

| Players | Batch | Samples/s (median of 5) | Range |
|---|---|---|---|
| 2 (mirror on) | 64 | 655,000 | 493,000 to 795,000 |
| 2 (mirror on) | 256 | 755,000 | 692,000 to 860,000 |
| 2 (mirror on) | 1024 | 818,000 | 802,000 to 896,000 |
| 3 | 256 | 726,000 | 709,000 to 780,000 |

Batch building is more than 5,000 times faster than the CPU trainer on SMALL (114 samples/s), so it never limits the CPU path. The compact form takes per sample 121 bytes of cells, 2 + 2 + 4 of ply, move limit and offset, 8 + 8 + 8 of values, weights and entropy for two players, and 6 bytes plus a float target per legal move (1 + 1 + 4): about 0.43 kB at 46 moves, so W = 500,000 costs about 215 MB (computed, not measured).

### As implemented (block 2e)

**Work plan steps A9 and A10 for generation 0** (the gating and the self-play phases of A10 are not in this block). Measured and tested 2026-11-08, JDK 25.0.3, Apple M3 Max, load average 4.5 to 8.6 during the dry run, so the timings are indicative.

| Class | Module | Content |
|---|---|---|
| `Gen0` | `selfplay` | The tool (`scripts/gen0.sh`): `run DATA RUN`, `run --status RUN`, `strength RUN`, `monitor-report RUN`. |
| `RunMonitor` | `selfplay` | Categories, `metrics.jsonl`, `monitor-report` (also `selfplay.sh monitor-report RUN`). |
| `HeldOutMetrics` | `selfplay` | The cheap proxies (c1). |
| `RawPolicyPlayer`, `StrengthEstimate` | `selfplay` | The raw-policy player and the mini-arena (c2). |
| `BatchSource` | `nn` | `TrainingRun.Setup` takes a batch source (a pool or a `ReplayBuffer`) instead of one expanded pool; the old constructor with a pool stays. |
| `ReplayBuffer` | `selfplay` | Implements `BatchSource`; `addGeneration(generation, files, GameFilter)` takes only the games that the filter accepts; `result(index)` is the result of the game for the mover. |

```text
gen0.sh run DATA RUN [--preset small|tiny] [--planes plain|classic1|classic1-planes] [--steps 4000] [--batch 64] [--microbatch 16] [--threads 4] [--lr 1e-3]
        [--warmup 200] [--seed 1] [--holdout 0.1] [--augment 1] [--value-coefficient 1]
        [--checkpoint-steps 200] [--checkpoint-seconds 120] [--keep 2] [--progress-seconds 10]
        [--eval-samples 2500] [--proxy-every 1] [--proxy-seconds 120]
        [--elo-every 0] [--elo-seconds 500] [--ladder 200] [--ladder-spec BEST_SPEC] [--elo-pairs 50]
        [--elo-seed 1] [--elo-threads 1] [--monitor 1]
        # --monitor 0 also switches off the proxies and the Elo estimate in the loop (they report through the
        # monitor); the run prints a note. Elo estimates of checkpoints stay available with `gen0.sh strength`.
gen0.sh strength RUN [--checkpoint FILE] [--ladder 100,400,1600] [--elo-pairs 50] [--elo-threads 4]
gen0.sh proxies RUN --data DIR [--checkpoint FILE] [--samples 2500] [--threads 4]
```

- **Data and split.** DATA is a directory of `samples-*.safetensors` from `selfplay run --teacher-depth D`. Two `ReplayBuffer`s read the files, one with the games that are not held out (mirror augmentation on) and one with the held-out games (never mirrored). A game is held out iff `u(seed, game id) < holdout`, with `u` in [0, 1) from `BatchSampler.mix` of a fixed salt, the `seed` of the file and the `game_id`. The split depends only on these two numbers, so it does not depend on the file order, the run or the sample order, and a game is in exactly one set. The data enters the run configuration as a SHA-256 over `name:size` of its files.
- **Training.** SMALL (or `--preset tiny`), AdamW, the generation-0 schedule (warm-up and cosine to 0.1 lr), `c_v` = 1, through `TrainingRun` with `generation` 0. Run directory: `run.json` (written once; any other configuration or data in the same directory is refused with 1), `gen-0000/train/step-*.safetensors`, `gen-0000/candidate.safetensors` (a plain network file with `step` and `run_config_sha256` as extra metadata; the training phase is done when it reads and says the planned step), `gen-0000/strength/step-*.jsonl`, `metrics.jsonl`, `.gen0.lock`, `.gen0.status`. The run configuration holds the preset, seed, steps, batch, microbatch, lr, warm-up, `c_v`, augmentation, holdout and the data fingerprint. The thread count is not in it (the trainer adds its microbatches in microbatch order, so the result does not depend on it); the cadences are not in it.
- **Job conventions.** The state comes from the artefacts; a checkpoint that does not read is renamed to `.corrupt` (CLEANUP) and the next older one is used; `*.tmp` leftovers are removed (in the generation directory at once, in the run directory and in `strength/` only when older than 10 minutes, because a separate strength process writes there under its own lock; a file that cannot be removed is not an error); `FileChannel.tryLock` on `.gen0.lock` (75 if held); SIGINT and SIGTERM stop between steps, write the final checkpoint, print the summary and end the JVM with 130 and 143. A second run after success exits with 0 before it loads any data. Strength steps use their own lock `.strength.lock`.
- **ETA of the whole run.** The progress line is the one of `TrainingRun` (rate from the steps of this process only). In addition each phase prints a summary line with `whole run: L steps left, ETA ...`: `gen 0 load:` (rate of an earlier run, read from `metrics.jsonl`, else "ETA unknown"), `gen 0 train:` (the rate of this process) and `gen 0 finish:`. The ETA covers the checkpoints and the proxies, because the rate is steps over wall time.
- **Proxies (c1)** (`HeldOutMetrics`), on `--eval-samples` evenly spaced samples of the held-out buffer, the same ones at every checkpoint, forward passes on `--threads` threads (the sums are added in batch order, so the result does not depend on the thread count): KL = `sum(wp KL) / sum(wp)` over samples with a policy weight (random opening plies have none); top-1 = the share (by `wp`) where the highest-logit legal move is the highest-target move; value loss = `sum(wv MSE) / sum(wv)`; winner prediction = among the samples whose game has a winner for one of the two players, the share with `sign(v_mover - v_opponent)` = result of the game (a tie of the two outputs is wrong). The result is the label of the game (`game_values`, absolute players) seen by the mover; the mover of ply p is p mod 2 for two players. `game_values` is [G, P] with P the player count of the buffer; the winner results exist for two players only. The yardstick of 78.8 % is a winner prediction on the same kind of data. Proxies run at the first and at the last checkpoint of the process whatever `--proxy-every` says, and else at every `--proxy-every`-th checkpoint when at least `--proxy-seconds` have passed. The default is 2500 samples (see "Generation 0 run 1"). `gen0 proxies RUN --data DIR [--checkpoint FILE] [--samples N] [--threads T]` evaluates one network (default: the candidate, else the newest checkpoint; a checkpoint or a plain network file of the run) in its own process on the same evenly spaced held-out samples, prints one line with the winner prediction and its standard error, takes no lock and writes nothing. `--data` is needed because `run.json` does not record where the data is; its fingerprint must match the run's, and the split is the run's `holdout`. A test checks that it prints the numbers the loop printed for the same checkpoint.
- **Elo estimate (c2)** (`StrengthEstimate`, `RawPolicyPlayer`). The player takes the legal move with the highest masked policy logit (`Encoder.moveLogit`, first of equal ones; deterministic, two players). A rung of the ladder is `BEST_SPEC` with `nodes=<rung>,tt=16`; `--ladder` lists the node limits (default 200). A search always completes depth 1 before it looks at the node limit, so every limit below the cost of a depth-1 search (47 nodes on average) plays the depth-1 moves: **the rungs are 1 (= depth 1, the weakest) or at least 100 nodes, and 2 to 99 are refused** (usage error 2), as is a rung listed twice (measured in "Generation 0 run 1"). The weakest rung stronger than depth 1 is 100 nodes. Each rung plays `--elo-pairs` pairs (50): the same seeded random 4-ply opening (`seed * 1000003 + pair`, as `Arena`) twice with the colours swapped, the pair score is the mean of `(1 + value) / 2`, and `Elo.fromScore`, `Elo.standardError` and `Elo.confidence95` give the Elo difference to the rung with its 95 % interval (`clamped` when the score is exactly 0 or 1: the Elo is then only a bound). The result of a checkpoint is `strength/step-<8 digits>.jsonl`, one line per rung, written atomically; a rerun with the same ladder, pairs and seed skips it. `gen0 strength RUN [--checkpoint FILE]` runs it as its own process on any checkpoint file of the run (also a copy; the run configuration hash must match) or on a plain network file of the run such as `gen-0000/candidate.safetensors` (it carries `step` and `run_config_sha256`; a network file without them is refused); SIGINT discards the estimate and ends with 130. An estimate cut short by a stop writes no result and no `elo` cost line; its time is booked as the kind `elo-partial` with count 0, so that the cost per event stays that of finished estimates. In the loop it runs only with `--elo-every K` (every K-th checkpoint of this process and at least `--elo-seconds` apart).
- **Contract.** `RawPolicyContractTest` runs the `StrategyContract` of the search module against the raw-policy player of untrained TINY networks (the registry of `Strategies` is in a module that does not know the networks). The player is registered as deliberately weak (`mustBeatRandom` false, like the random player): an untrained network does not beat a random player, so the "beats a random player" property is skipped (aborted by `Assume`) and the self-play property (never an illegal move, clean games from 120 varied start positions) runs. `RawPolicyPlayerTest` adds: the chosen move has the highest prior of `Evaluator`; a network that puts huge logits on cells without legal moves still plays a legal move (the best legal one by an independent computation); a non-finite logit is an error.

**Tests:** 44 new tests in `selfplay` (`Gen0Test` 14, `Gen0ProcessTest` 2, `RunMonitorTest` 9, `ReplayBufferGamesTest` 6, `HeldOutMetricsTest` 4, `StrengthEstimateTest` 4, `RawPolicyPlayerTest` 3, `RawPolicyContractTest` 2 of which 1 skipped); `selfplay` had 114 tests of which 1 skipped (not 113 plus a skipped one) and `nn` 271, all green. The review follow-up (block 2e2) added 9 tests: `selfplay` has 123 tests of which 1 skipped (openings differ per pair, the evenly spaced held-out samples by index, ties of the teacher target and of the raw-policy logits, the clamped flag at score 1, the cleanup of foreign temporary files, the first proxy checkpoint, the partial Elo cost line, three-player game values). Mutation check of the follow-up: the reviewer mutants M2, M5, M9, M13, M18 and 6 of the changes (the proxy cadence, the temporary-file age, the partial cost, the player count, the monitor note) were caught; the one survivor, a tie of equal logits in the top-1 proxy (taking the last instead of the first), is outside the documented behaviour.

- **Resume.** For aborts after 0, 1, 3, 4, 5, 8 and 11 of 12 steps the second run ends with 0, says `start at step k`, and its candidate equals the candidate of an uninterrupted run tensor for tensor (`Gen0Test`); the same for a kill without the final checkpoint, a half-written `.tmp` file, a truncated newest checkpoint (renamed `.corrupt`, the older one used) and a run that died after the last step but before the candidate. The cadences (checkpoint steps and seconds, keep, proxy cadence, progress interval, threads) and `--monitor 0` change no tensor. A second run after success changes no file and adds no metric line.
- **Processes** (`Gen0ProcessTest`): a second process gets 75, `--status` names the pid of the first, SIGINT gives 130, SIGTERM after the restart gives 143 with `start at step N`, no `.tmp` file is left, and the strength step stops with 130 and keeps no half result.
- **Split:** disjoint and complete over three files of two seeds with overlapping game ids, deterministic, independent of the file order, keyed on (seed, id), about the held-out fraction.
- **Monitor arithmetic** with injected numbers: shares, status against the budget, cost per event, proposed interval and steps, several processes, a separate process, `ceil125` (including the floating-point noise of 0.5 / 0.1), the conversion of `TrainingRun.Timing` (strength time is subtracted from the observer time), concurrent appends from four threads, a torn line.
- **Proxies against a hand-computed case** (four samples: an opening ply, a sample without moves, tie of the two value outputs; computed outside the code): KL 0.81786, top-1 1/3, value loss 0.58, winner prediction 2/3; flipping the sign gives 0 and a tie is wrong.

**Mutation checks:** 33 deliberate bugs. 32 were caught by the first run and the missing one (the score of the mini-arena taken from the opponent's side, hidden behind a condition that did not hold in the test) was caught after the test was changed to unconditional bounds. Split: the comparison inverted, seed ignored, the training buffer or the held-out buffer taking every game. Proxies: wrong winner sign, a tie counted as right, entropy not subtracted, policy weight ignored, lowest instead of highest logit in the top-1, value weights not summed. Tool: checkpoint ignored on resume, off by one at the planned step, checkpoint cadence N + 1, proxies before their interval, no proxy at the last checkpoint, a checkpoint option read as the batch size (cadence in the configuration), ETA multiplied instead of divided, the earlier rate computed wrong, the candidate step off by one, exit code 76 for a second process, an abort reported as success, progress lines not counted. Monitor: share over wall plus cost, interval multiplied by the budget, rounding 2 to 3, strength time counted twice, cost per run instead of per event, budget status inverted. Raw policy: lowest logit. Strength: other seed accepted in a result file. Results: mover of the wrong parity, results lost in the subset.

#### Dry run for the overhead (2026-11-08)

Data: 100 games of `BEST_SPEC` with `nodes=150` and `--teacher-depth 2` (`selfplay run`, seed 2026, 3 s on 6 threads): 9,774 samples in 10 shards, of which 9,194 training and 580 held-out samples (the split by game id at 10 %). Training: SMALL, 800 steps, batch 32, microbatch 8, 4 threads, a checkpoint every 100 steps (8), proxies on 500 held-out samples.

| Variant | Steps/s | Samples/s | vs monitor off |
|---|---|---|---|
| monitor off (two runs) | 16.77, 16.63 | 536.5, 532.2 | 0 |
| on, proxies at all 8 checkpoints, 1 thread (first version; two runs) | 12.39, 12.29 | 396.6, 393.2 | -26 % |
| on, proxies at all 8 checkpoints, 4 threads | 15.33 | 490.5 | -8 % |
| on, defaults (proxies at the first and the last checkpoint) | 16.33 | 522.7 | -2 % |
| on, defaults, and the Elo estimate in the loop twice (50 pairs against `BEST_SPEC` at 200 nodes, 1 thread) | 8.76 | 280.2 | -48 % |
| on, defaults, and a separate strength process (2 threads, 50 pairs) for 11 s of the run | 16.36 | 523.6 | -2 %, no more than the defaults |

The overhead shares as `monitor-report` printed them for these runs, and the intervals it proposes (cost per event over the budget, rounded up to 1, 2 or 5 times a power of ten):

| Category | Events | Cost per event | Share | Budget | Proposed interval |
|---|---|---|---|---|---|
| checkpoint | 8 | 1.7 to 2.1 ms (731 kB with state) | 0.015 to 0.034 % | 1 % | 0.2 to 0.5 s (5 to 10 steps) |
| progress lines | 5 to 7 | 0.6 to 0.7 ms | 0.004 to 0.008 % | 0.1 % | 1 s |
| the monitor's own writes | total | 6 to 12 ms | 0.01 to 0.02 % | none | none |
| proxies, 500 samples, 1 thread | 8 | 2.1 to 2.2 s | 26.6 % | 2 % | 200 s |
| proxies, 500 samples, 4 threads | 8 | 0.57 s | 8.7 % at every checkpoint (6.5 s apart), 2.3 % with 2 events in 49 s | 2 % | 50 s |
| Elo estimate, 50 pairs, 1 thread | 2 | 21.3 s | 46.6 % (2 in 91 s) | 5 % | 500 s |
| Elo estimate, 50 pairs, 2 threads, separate process | 1 | 11.3 s | no loss of training speed measured | 5 % | 500 s |

**Changes of the defaults that follow:**

- **Checkpoints:** the report allows a checkpoint every 0.2 s; the starting values of the plan (500 steps or 10 min) were far inside the budget. The default is now every 200 steps or 120 s, the earlier one: a kill then loses at most about 12 s of SMALL training (0.02 % overhead).
- **Proxies:** the cost is 4 ms per sample and thread, not "cheap". The default was 500 samples (planned as the whole held-out set) and is 2500 since run 1, forward passes on the training threads, and at least 120 s apart (about 0.5 % at 0.57 s per event); the first and the last checkpoint are always evaluated. A cadence in checkpoints alone would have taken 26 % at the plan's starting values.
- **Elo:** off in the loop by default (`--elo-every 0`); when on, at least 500 s apart. The normal way is the separate step on a copy of a checkpoint.
- **Progress lines:** 10 s stay (the budget would allow 1 s).

**What the dry run says about the network:** after 800 steps (about 2.8 epochs of the training samples) the held-out proxies are KL 0.629, top-1 44.2 %, value loss 0.443 and winner prediction 51.8 % of 500 positions (yardstick 78.8 %); at step 100 they were KL 0.823, top-1 35.1 %, winner 50.2 %. These runs are for the overhead and not for strength: the 9.8k samples and 800 steps are far below what generation 0 needs. The Elo estimate was clamped (0 wins in 100 games against `BEST_SPEC` at 200 nodes, at step 400 and 800); a ladder for early checkpoints needs weaker rungs than 200 nodes.

**Known limits:**

- `Timing` covers the loop thread only: the CPU column of `monitor-report` shows the loop thread's CPU time, so the `train` row has almost none; the budgets use wall time.
- The Elo of a strength step that runs in another process costs the training run throughput only while it runs and shares cores; the report shows it as a separate row with its wall time against the loop's wall time, which is an upper bound.
- The mini-arena has no opponent weaker than a classical search; a raw policy that loses every game gives only a bound.
- The "beats a random player" property of the strategy contract does not run for the untrained networks of `RawPolicyContractTest` (see above); it needs a trained network.
- Checkpoint cadence counts "checkpoints of this process" for `--elo-every` and `--proxy-every`; after a resume the count starts again.
- Only generation 0 and two players. The gating, the self-play phase and three players are not in this block.

### Generation 0 run 1 (measured 2026-11-08)

First real generation-0 supervised run. JDK 25.0.3, Apple M3 Max, **load average 12 to 13 from other jobs during the whole run, so all timings are indicative.**

**Data:** `selfplay run` with `BEST_SPEC` at `nodes=2000,tt=17`, seed 2001, `--teacher-depth 2` (tau 0.06, defaults), 4,300 games in 86 shards, 8 threads: 10 m 16 s (6.98 games/s, 84 plies per CPU second, teacher 10.2 ms per sample). The run loaded 369,934 training and 40,839 held-out samples (split by game id at 10 %).

**Training:** `gen0.sh run data/teacher-s2001 data/gen0run1 --preset small --steps 5800 --batch 256 --microbatch 32 --threads 8 --seed 1 --augment 1 --monitor 1` (lr 1e-3, warm-up 200, cosine to 0.1, `c_v` 1). 5,800 steps = 1.485 M samples = about 4.0 epochs of the training samples (with mirror augmentation). Wall time 31 m 59 s (1,919 s), **3.02 steps/s = 773.6 samples/s**, 29 checkpoints. Block-2e dry run for comparison (batch 32, 4 threads, 16.3 steps/s = 523 samples/s): 1.5 times more samples per second now, from 8 threads and larger microbatches, at a much higher machine load.

**Held-out proxies** (500 fixed held-out samples, 15 evaluations; yardstick for the winner prediction 78.8 %):

| Step | policy KL | top-1 | value loss | winner prediction |
|---|---|---|---|---|
| 200 | 1.314 | 31.1 % | 0.549 | 51.2 % |
| 600 | 1.148 | 35.3 % | 0.490 | 55.2 % |
| 1000 | 1.078 | 38.6 % | 0.304 | 69.4 % |
| 1400 | 1.030 | 38.4 % | 0.250 | 70.4 % |
| 1800 | 1.015 | 39.5 % | 0.223 | 73.6 % |
| 2600 | 0.985 | 41.1 % | 0.202 | 74.8 % |
| 3400 | 0.973 | 39.7 % | 0.194 | 74.6 % |
| 4200 | 0.949 | 40.4 % | 0.189 | 74.0 % |
| 5000 | 0.927 | 40.4 % | 0.186 | 75.8 % |
| 5400 | 0.923 | 40.6 % | 0.188 | 74.4 % |
| 5800 (final) | 0.928 | 40.4 % | 0.186 | 75.2 % |

The training loss at the end is KL about 0.80 and value about 0.17 (running means of the steps), so the gap to the held-out set is about 0.12 in KL: little overfitting after 4 epochs. KL, top-1 and value loss are flat since about step 4,600 (the learning rate is then at 0.1 to 0.3 of its peak). With 500 samples the winner prediction has a standard error of about 1.9 points, so 75.2 % is 1.9 standard errors below 78.8 %.

**Strength** (raw policy against `BEST_SPEC` with `nodes=<rung>,tt=16`, 50 pairs = 100 games per rung, 2 threads; score from the network's side; Elo with 95 % interval):

| Checkpoint | 1 / 5 / 20 nodes (identical) | 100 nodes | 400 nodes | 1600 nodes |
|---|---|---|---|---|
| step 200 | -449 +- 202 (score 0.07, 7-0-93) at 5 and 20 | -449 +- 263 | -676 +- 339 (0.02) | not run |
| step 4800 | -191 +- 72 (0.25, 25-0-75) | -210 +- 87 (0.23) | -323 +- 98 (0.135) | not run |
| step 5800 (candidate) | **-49 +- 64 (0.43, 43-0-57)** | -182 +- 79 (0.26) | -346 +- 107 (0.12) | -676 +- 339 (0.02) |

No result is clamped; the ladder 1, 5, 20, 100, 400 is usable as it is, and 1600 only adds a bound. **The rungs 1, 5 and 20 nodes give bit-identical results** (the same wins and losses for the same checkpoint, at step 4800 and at step 5800, and at step 200 for 5 and 20): `BEST_SPEC` with at most 20 nodes plays one and the same move sequence, so these three are one rung. The cause (found afterwards, see "Rungs 1, 5 and 20 are one rung" below) is that the search completes depth 1 before it looks at a node limit; the rungs 1 and 5 can be dropped. The candidate is about 50 Elo below the cheapest rung (+- 64) and about 180 below 100 nodes (+- 79), and its Elo against the rungs falls with the node count, as expected. Not a bug: see below.

The `strength` command takes a training checkpoint (`gen-0000/train/step-*.safetensors`); `candidate.safetensors` is refused with `format is sternhalma-nn, expected sternhalma-train`. The checkpoint at step 4800 was copied while the run was still going, because only the last two are kept.

**Follow-up (block 2e-fix).**

- **Rungs 1, 5 and 20 are one rung.** `AlphaBeta.search` sets `timed = depth > 1` ("depth 1 always completes, so there is always a move to return"); the node limit is checked only in nodes of depth 2 and deeper, and an unfinished iteration is discarded. So every node limit below the cost of a full depth-1 search returns the depth-1 move, and a depth-1 search costs 47 nodes on average (all root moves). Not a bug of the search (the semantics are documented and pinned by the fingerprint tests, which are unchanged) but a flaw of the ladder. Measured on 100 positions of random play (2 to 81 random plies from the start, seed 5; one thread, load from other jobs, no timing taken), `BEST_SPEC` with `tt=16`, how many of the 100 moves are the same:

  | | 1 node | 5 | 20 | 100 | 400 | 1600 | depth 1 | depth 2 | 20000 |
  |---|---|---|---|---|---|---|---|---|---|
  | same as 1 node | 100 | 100 | 100 | 94 | 75 | 58 | **100** | 92 | 37 |
  | mean completed depth | 1.00 | 1.00 | 1.00 | 1.08 | 2.24 | 3.80 | 1 | 2 | 6.04 |
  | nodes per searched move | 47.4 | 47.4 | 47.4 | 100 | 400 | 1600 | 46.4 | 162.4 | 20000 |

  (1, 5, 20 also agree with each other and with depth 1 in all 100 positions; race moves: none of the 100 random positions was a race position, so this row says nothing about them. In games the race module plays passed positions without nodes, equally at every rung.) The rungs 1, 5 and 20 are thus the one-ply player of `BEST_SPEC`, and the three results of run 1 are three plays of one rung, not three measurements. A rung of its own begins at about 100 nodes (94 % of the moves are depth 1 already, 6 % are not) and 400 nodes is about depth 2.
- **The change.** Rung 1 is defined as "depth 1", the weakest rung; rungs 2 to 99 are refused with a usage error (`StrengthEstimate.requireDistinctRungs`, also a rung listed twice), because they would repeat it. The recommended ladder for early checkpoints is `--ladder 1,100,400` (and 1600 as a bound for strong ones). Search semantics, `BEST_SPEC` and the fingerprints are unchanged. A weaker rung than depth 1 would need another opponent (`--ladder-spec` with another evaluator); none was needed, the candidate scored 0.43 against depth 1.
- **`candidate.safetensors` is accepted** by `gen0 strength --checkpoint` (a plain network file carrying `step` and `run_config_sha256`; checkpoints still work; a network file without the metadata or of another run is refused).
- **Proxy samples: 2500 by default** (was 500). The new cost: 2500 samples are about 5 times the 359 ms per event measured above (8 threads), about 1.8 s per event, 1.4 % of the loop at the 120 s interval (0.28 % for 500), inside the 2 % budget. The standard error of the winner prediction goes from 1.9 to about 0.9 points. The interval stays at 120 s.
- **`gen0 proxies RUN --data DIR --checkpoint X [--samples N]`** evaluates the proxies of one checkpoint or network file in a separate process (about 2 s for 2500 samples on 8 threads, estimated from the 359 ms per 500 samples, not measured), prints the winner prediction with its standard error, and writes nothing. It is the cheap way to re-measure the winner prediction of the run-1 candidate on 2500 or more samples (`gen0.sh proxies data/gen0run1 --data data/teacher-s2001 --checkpoint data/gen0run1/gen-0000/candidate.safetensors --samples 5000`; the held-out set has 40,839 samples). Not yet run on the candidate, to leave the data generation its cores.

**Overhead** (`monitor-report`, 1,919 s of loop wall time):

| Category | Events | Cost per event | Share | Budget | Proposed interval |
|---|---|---|---|---|---|
| checkpoint | 29 | 1.8 ms | 0.003 % | 1 % | 200 ms (about 1 step) |
| progress lines | 189 | 0.66 ms | 0.007 % | 0.1 % | 1 s |
| the monitor's own writes | total | 61 ms | 0.003 % | none | none |
| proxies, 500 samples, 8 threads | 15 | 359 ms | 0.28 % | 2 % | 20 s |
| Elo estimate (separate process, 50 pairs, 4 rungs, step 200) | 1 | 55.3 s | 2.9 % | 5 % | 2000 s |

All categories are inside their budgets, the total is about 3.2 % with the separate Elo step and 0.3 % without it. Proposed changes: (1) The proxies use 0.28 % of 2 %, so they can use 5 times more samples (2,500 of 40,839 held out; about 1.8 s per event, 1.4 % at 120 s apart); the standard error of the winner prediction goes from 1.9 to 0.9 points, which is what the decision against 78.8 % needs. Done: the default is 2500 (follow-up above). (2) The interval of 120 s stays (one event per 400 steps at 3 steps/s is enough for the trend). (3) Checkpoints every 200 steps (66 s) stay: the report would allow more, but a checkpoint is only 1.8 ms and the kill loss of 66 s is acceptable; at 3 steps/s `--checkpoint-seconds 60` is the natural cap. (4) Progress lines every 10 s stay. (5) Elo: one separate step per checkpoint of interest, as done; with 2 threads and 5 rungs it costs about 1 minute and slows the run by no more than its load.

**Is the CPU trainer a bottleneck?** For generation 0: training 32 min against data generation 10.3 min, plus about 1 min of strength steps, so one full generation is about 43 to 44 minutes and **training is about 73 % of it**. For 10 such generations: about 7.3 hours at this load, if later generations have the same data volume and step count; self-play with a network in the search will be slower than the classical teacher games, which this number does not include. Part B (GPU) would pay off first if generations become 5 times longer; at this size one generation is short enough to run overnight several times.

**Decision criteria and verdict.** Winner prediction 75.2 % against 78.8 % (yardstick, -3.6 points, 1.9 standard errors): **not met yet (the curve rises slowly and is flat over the last 800 steps).** KL trend: flat (0.923 to 0.928 over the last 400 steps). Raw-policy Elo: -49 +- 64 against the 1-to-20-node rung and -182 against 100 nodes, so the network plays like a very shallow classical search without any search. Recommendation: **do not start self-play yet.** Cheapest order of changes: (a) re-measure the winner prediction with 2,500+ samples on this candidate (a second of CPU) to know whether the gap to 78.8 % is real; (b) more teacher data (the 4 epochs with a flat held-out KL and a gap of 0.12 say that data, not steps, is the limit; the teacher costs 10 minutes per 4,300 games, so 20,000 games are 50 minutes); (c) only then a bigger network or a different lr; teacher tau 0.06 is the documented optimum for depth 2 (0.05 measured), so it is not the first suspect. The top-1 agreement of 40 % with a soft target (tau 0.06, many near-equal moves) is a weak criterion on its own.

## Generation 0 run 2 (measured 2026-11-08)

**TL;DR:** Five times the teacher data (20,000 games, about 1.9 M samples, seed 2003, depth-3 teacher, mover at 2000 nodes) and 16,000 steps (about 2.2 epochs) improve every proxy over run 1 and lift the raw policy player (no search) from -49 Elo (run 1, against `BEST_SPEC` at depth 1) to +38 +- 45. It now plays on a par with `BEST_SPEC` at 100 nodes (+3 +- 46) and loses clearly only against 400 nodes (-166 +- 48). The winner prediction (75.7 +- 0.6 % on 5000 held-out samples) stays below the 78.8 % yardstick; the yardstick comes from another distribution, so it is a guide, not a gate. Load was 11-13 during the runs; every timing is indicative.

| | Run 1 (5800 steps, 370 k samples) | Run 2 (16,000 steps, 1.9 M samples) |
|---|---|---|
| held-out policy KL (5000 samples) | 0.925 | 0.795 |
| top-1 agreement with the teacher | 41.7 % | 45.8 % |
| value loss | 0.178 | 0.163 |
| winner prediction | 74.1 +- 0.6 % | 75.7 +- 0.6 % |
| Elo against depth 1 (`BEST_SPEC`, rung 1) | -49 +- 64 (50 pairs) | +38 +- 45 (100 pairs) |
| Elo against 100 nodes | -182 +- 79 | +3 +- 46 |
| Elo against 400 nodes | -346 +- 107 | -166 +- 48 |

The held-out sets differ (game ids of different data sets), so the proxy columns compare trends, not paired values. In run 2 the KL flattened at about 0.78-0.80 from step 12,000 on; the winner prediction moved between 73.3 and 75.7 % without a trend from step 5000. Training took 1h22m (3.25 steps/s, 832 samples/s with 8 threads), data generation 53 min (6.2 games/s on 8 threads at load 11-12), the strength step (300 games) 2m08s on 8 threads. The monitor in the loop stayed far inside its budgets (80 checkpoints, proxies every 120 s, no Elo in the loop).

**Decision:** the raw policy is strong enough to act as a prior for the search (about depth 1 to 2 of the classical engine); the next block is MCTS with the network (block 2g), then the classical input planes (E11) as an A/B on the same data.

## As implemented (block 2g)

**TL;DR:** `Mcts`, `MctsPlayer` and `MctsSpec` in `search` play two-player games with PUCT, the network as prior and value, batched leaf evaluation with virtual loss, exact leaves for the end of the game and for S1 verdicts, and the race module at the root. With the generation-0 candidate at **100 simulations (29 ms per move) the player matches `BEST_SPEC` at about 23,000 nodes** (-10 ± 42 against 25,600 nodes, +139 ± 48 against 6400, +272 ± 66 against 1600) and beats the raw policy of the same network by +729; 25 simulations match about 3000 nodes, 400 simulations are above 25,600 nodes (+175 ± 49). At equal CPU time the classical search is still a little ahead (30 to 120 Elo, an estimate). Defaults chosen by a sweep on one seed: 100 simulations, **batch 1** (batch 16 costs 325 Elo at 100 simulations and the CPU gains nothing from batching), c = 1.0. 61 new tests, 28 of 28 mutants caught.

**MCTS with PUCT for two players, with the network as prior and value** (work plan step of the architecture's "MCTS with the network in `search`"). Implemented, tested and measured 2026-11-09 with JDK 25.0.3 on an Apple M3 Max, load average 5 to 11 from other work, so the CPU times are indicative; the Elo numbers count simulations or nodes, not seconds, and do not depend on the load.

**Placement.** The search lives in module `search` (package `sternhalma.search`) and the dependency `search -> nn` now exists in the build (`api`, because `Mcts` takes a `ForwardPass` in its constructor), as the [architecture](architecture.md#module-map) planned. There is no cycle: `nn` depends on `engine` only (checked in the build files). The network loading and the choice of the forward pass are not in `search`: a spec names the network file, and the module `selfplay` loads it and picks `nn.Networks.fastest` (the Vector API path when the JVM has `jdk.incubator.vector`, else the scalar path), so `search` needs no incubator module.

| Class | Module | Content |
|---|---|---|
| `Mcts` | `search` | The search: tree, PUCT selection, virtual loss, batches, exact leaves, root noise, move choice, tree reuse. One instance per player, no shared mutable state. |
| `MctsNode` | `search` | A node with the statistics of its edges (visits, value sum, virtual loss) and the PUCT arithmetic. Package-private. |
| `MctsOptions`, `MctsSpec` | `search` | The options and their text form `mcts:net=PATH,sims=N,batch=B,cpuct=C,...`. |
| `MctsPlayer`, `MctsResult` | `search` | `Player` (root race play, counters) and what a search found (visits, values, node count). |
| `Networks` | `nn` | `Networks.fastest(Parameters)`: `VectorNetwork` if the module is present, else `Network`. |
| `NetPlayers`, `MctsMatch`, `MctsBench` | `selfplay` | Player factories for `raw` and `mcts:...`; paired games against ladder rungs, resumable per pair; simulations per second. |

### Design

**One simulation.** From the root each node takes the edge with the highest `Q + c * P * sqrt(N_parent) / (1 + N)`. `Q` is the mean value of the edge for the side that plays it; an edge without visits counts as the mean value of its node minus `fpu` (the network value counts as one visit; `fpu` is 0). `N_parent` is the sum of the visits and the virtual losses of the node's edges, and at least 1, so that the priors decide the first choice of a node. Ties take the first move in move order. The walk ends at an edge whose child does not exist yet; the child is one of three things:

1. **The game is over there** (`Position.outcome()`; no legal move gives `noMovesOutcome()`). Every visit backs up the engine's values of that outcome, so a draw by the 200-ply limit counts with its `SCORE_BY_DISTANCE` value, exactly as in a game, and a win is +1 and -1 (two players).
2. **A passed position with an S1 verdict** (`RaceAdjudication.passed` and `RaceAdjudication.adjudicate`): +1 for the winner and -1 for the other player, whoever is to move. S1 is the only source of an exact value in a passed position. The race table and `RaceEvaluator` (E08, the race band) are not values here; the band rule of [E08](search-and-evaluation.md#race-arithmetic-as-a-leaf-value-e08-racelin-and-racelin1-measured-2026-11-08) (never a label from an unsound source) is kept by construction: no race-band score exists in this search at all. S1 is switched off with `s1=0` (then a passed position is an ordinary leaf).
3. **Otherwise a leaf for the network.** The position goes into the batch; `nn.Evaluator` evaluates the batch in one forward pass and returns, per position, the legal moves, the softmax of their move logits (`Encoder.moveLogit`, so only legal moves have a prior) and the values of all players in absolute order. The prior temperature `ptemp` turns the softmax into `p^(1/ptemp)`, renormalised. The leaf is expanded with these priors and backs up the network's value of **every player**: each edge on the path adds the value of its own mover. So there is no sign to flip, and the same code serves three players (tested with a fake network; no trained three-player network exists). The value head is used as it is, `tanh` output per player, without a projection to zero sum.

**Win in one.** When a node is expanded, every legal move is tried on the leaf's copy of the position; a move whose `Outcome` is a win for the mover is the node's `winEdge`. The node then backs up that exact win instead of the network's value, and its selection takes the winning edge every time (the child is an exact leaf at once). A mate in one is therefore found with one simulation whatever the network says (`win1=1`, default). Without it (`win1=0`) the exact value of the terminal child still finds the mate, but only after the move has been visited.

**Batches and virtual loss.** Up to `batch` descents run before the network is called. Every edge on a path gets one virtual loss, which counts as a visit that lost, so `Q = (sum - vl) / (n + vl)` and the parent count of the U term includes the virtual losses. Backing up a leaf takes exactly its virtual losses off again and records the real visit; an exact leaf does the same at once. A descent that reaches a node that is still waiting for the network undoes its virtual loss and ends the batch without being counted (a collision), so the simulations run are exactly `sims`. Leaves are backed up in the order of the descents and each position is evaluated alone (the fake network of the tests is a function of the position, so the batch composition cannot matter), so the result does not depend on how the batch was laid out in the forward pass. After any search no virtual loss is left; a network failure (a non-finite output) drops the tree before the exception goes on.

**Root.** The root can get Dirichlet noise (`noise` weight, `alpha` concentration, default 0.3, seeded Marsaglia-Tsang gamma sampler); it changes the selection priors of that search only, the stored priors stay clean. The move is the most visited one (ties: the higher mean value, then the higher prior, then the first), or with `temp > 0` a draw proportional to `visits^(1/temp)`. A position with a single legal move is returned without a search. With `race=1` or `race=2` the **player** plays a passed position with `RaceMove` (as `BEST_SPEC` does, `racerows` chooses the table) and starts no search; that move costs no simulation and is counted in `MctsPlayer.raceMoves()`. With `reuse=1` the next search starts from the subtree of the position it is asked about (found by hash and ply two plies down), if the tree has it; the carried visits count in the node, the `sims` of the new search are added.

**Budgets.** `sims` simulations per move is the strength control and the default way to bound the cost (the Elo numbers below are per simulation count, so they do not depend on the load). `ms` is an optional time budget checked between batches; with it the result depends on the machine, and `sims` stays an upper bound.

**Determinism.** The only randomness is one `SplittableRandom` per player from the seed (noise, sampling). With `noise=0, temp=0` (the default) the player is a function of position, options and network and ignores the seed. All state is per instance; the network is used as it is, so give each thread a thread-safe forward pass (`Network`) or its own (`VectorNetwork`: `NetPlayers` keeps one per thread). `MctsPlayerTest` plays the same games with 1, 3 and 4 threads and compares the move lists.

### Options

| Key | Default | Meaning |
|---|---|---|
| `net` | none | Network file (plain safetensors); a spec without it uses the network at hand (`gen0 strength` uses its checkpoint). |
| `sims` | 100 | Simulations per move. |
| `batch` | 1 | Leaves per forward pass, 1 to 64 (see the measurement: 1 on the CPU). |
| `cpuct` | 1.0 | The constant c. |
| `ptemp` | 1.0 | Prior temperature. |
| `noise`, `alpha` | 0, 0.3 | Dirichlet weight and concentration at the root. |
| `temp` | 0 | Move temperature (0 = most visited). |
| `fpu` | 0 | First-play reduction. |
| `race`, `racerows` | 1, 2 | Race play at the root (0 off, 1, 2 as in `PlayerSpec`) and its table (the measurements use `racerows=1`, the table of `BEST_SPEC`). |
| `s1`, `win1`, `reuse` | 1, 1, 0 | S1 leaves, win in one, tree reuse. |
| `ms` | none | Time per move. |

**Tools.** `scripts/mctsmatch.sh --out DIR --net FILE --subject mcts:sims=100 --vs ab:100 --vs raw [--pairs 100] [--seed 3001] [--threads 6]` plays paired games (the openings and the arithmetic of the strength estimate, `StrengthEstimate`) against `ab:N` (the `--ladder-spec`, default `BEST_SPEC`, at N nodes; 1 is depth 1), `raw` or another `mcts:` spec. It follows the [job conventions](#job-conventions): one JSON line per finished pair in `DIR/pairs-<hash>.jsonl` and the state of a rerun only from those lines (a torn last line is ignored), a lock (exit 75), SIGINT and SIGTERM stop after the running pairs (130, 143), progress with ETA, and a summary that says what a rerun would do. `scripts/mctsmatch.sh bench --net FILE --subject mcts:... --threads 1,8` measures simulations per second. `scripts/gen0.sh strength RUN --player mcts:sims=100,...` estimates an MCTS player of a run's checkpoint into `strength/step-N.player-<hash>.jsonl`, outside the run's strength metric (no monitor line); without `--player` (or with `raw`) nothing changes.

### Tests

**52 new tests in `search` (one skipped on purpose: the beat-random property of the contract has no strong strategy in the fake-network list) and 9 in `selfplay`; the whole build has 696 tests, none failed.** Test-first, in `search` and `selfplay`. The network of most tests is `HashedNetwork`, a fake whose logits and value are a function of the encoded position alone (so every position gets the same output in any batch, order or thread, and thousands of simulations are cheap); a real `Network` (TINY, random weights) and the trained candidate are used where the claim is about them.

| Test class | What it pins |
|---|---|
| `MctsNodeTest` (11) | The PUCT arithmetic on hand-built trees (`0.25 + 1.5 * 0.4 * sqrt(9) / (1 + 2) = 0.85`, the roles of `sqrt(N_parent)` and `1 + N`), a hand-computed `select` on a three-edge node, the first-play value, virtual loss as a lost visit in Q and in the parent count, exact add and remove (removing one that was never added fails), ties to the first move, the winning edge. |
| `MctsTest` (27) | An **independent sequential reference** (written again in the test, 70 lines, no batches, no options) gives the same visit counts as `Mcts` with `batch=1` for 3 networks x 14 positions x 3 values of c (both movers), also with a real network; every node holds the evaluation of its own position whatever the batch composition; batch sizes never exceed the option and every node of the tree was evaluated once; a mate in one is found with 1, 2 and 8 simulations for 6 networks and has value exactly 1; without `win1` the exact terminal value finds it with 400; an immediate loss is not chosen; positions at the 30-ply limit are exact with the engine's `SCORE_BY_DISTANCE` values and cost no network call; an S1 verdict backs up +1 to the mover who wins and -1 to the mover who loses, and only S1 (or the end of the game) makes an exact leaf; the root priors stay clean, the noise is seeded, `temp` samples in proportion to the visits (600 seeds, 5 sigma) and is seeded; tree reuse; the time budget; a network that fails once (NaN) leaves no virtual loss; three players; a finished game or a network of another player count is refused. |
| `MctsPropertyTest` (2 properties, 150 and 40 cases) | For any seed, rule variant of the contract, start (also just before the end), `sims` 1 to 90, `batch` 1 to 64, noise, `s1`, `win1`, `reuse`, c, prior temperature and `fpu`: no virtual loss is left, the root has exactly `sims` visits (plus the carried ones with reuse), every edge has `child.totalVisits + 1` visits, the sums stay within [-1, 1], the move is legal; the search is a function of its inputs. |
| `MctsPlayerTest` (3) | The same games with 1, 3 and 4 threads (noise, sampling and race play on; a shared hashed network and a shared TINY `Network`); every move legal and the counters consistent. |
| `MctsSpecTest` (4) | Every key, the defaults, every refusal. |
| `MctsContractTest`, `MctsTrainedContractTest` | The strategy contract of `search` testFixtures (clean self-play from varied starts in four rule variants, beat-random): the first with the hashed network (3 strategies, flagged weak), the second with the trained candidate at 8 simulations (beat-random included; 100 + 120 games in 17 s; skipped without the file or the Vector API module). `MctsTrainedNetworkTest` runs 16 games against a random player and 6 clean self-play games at 16 simulations with batch 8. |
| `NetPlayersTest` (4), `MctsMatchTest` (4), `Gen0Test` (+1) | The text forms; one forward pass per thread; a match writes one line per pair, a rerun skips and redoes only a missing or torn pair, the lock (75), usage errors; `gen0 strength --player` writes its own file, no monitor line, the default player unchanged. |

**Contract by hand against the trained candidate.** `MctsTrainedContractTest` is that run (all of `StrategyContract`, including `strategyBeatsARandomPlayer`, 220 games at 8 simulations, no failure); a longer one was run once with the match tool: the more than 6000 games of the measurements below did not raise `IllegalMoveException` once (`GameRunner` rejects an illegal move).

### Mutation check

28 hand-made mutants of `Mcts` and `MctsNode`, each run against the MCTS tests; **28 caught, 0 missed**. One mutant (M05) was first missed and led to the test of the parent count with virtual losses.

| Mutant | Caught by |
|---|---|
| M01 sign error in the backup | reference, mate in one |
| M02 the value of player 0 for every mover (second mover not flipped) | reference, S1 signs |
| M03 virtual loss not undone at the backup | properties |
| M04 virtual loss not undone after a collision | properties |
| M05 parent visits without the virtual losses of the other edges | `theParentVisitsOfTheSelection...` (the first version of the suite missed it) |
| M06 edge visits instead of parent visits in the U term | reference |
| M07 `1 + N` replaced by `2 + N` | reference, arithmetic |
| M08 no square root | reference, arithmetic |
| M09 prior temperature inverted | `thePriorTemperatureFlattens...` |
| M10 noise not seeded | seeded noise, determinism |
| M11 noise written into the stored priors | clean priors |
| M12 evaluations applied in reverse order of the batch | node values equal single evaluations |
| M13 S1 verdict with the sign swapped | S1 signs |
| M14 move-limit outcome as zeros | the limit test |
| M15 exact leaf not backed up | properties |
| M16 win in one for the wrong side | mate in one |
| M17 temperature exponent the wrong way | sampling |
| M18 collision counted as a simulation | properties |
| M19 virtual loss not a loss in Q | node test |
| M20 network value of player 0 as the node value | node values |
| M21 tree reuse without the hash | reuse |
| M22 unvisited edge at 0 instead of the node mean | node test, reference |
| M23 first edge instead of the most visited | reference |
| M24 winning edge not preferred | node test, mate in one |
| M25 end of the game not exact (goes to the network) | the limit test |
| M26 uniform priors instead of the network's | node values, reference |
| M27 the win probe leaves the move on the board | almost everything |
| M28 the search priors (noise) kept after the search | clean priors |

### Measurements

Network: `data/gen0run2/gen-0000/candidate.safetensors` (SMALL, 2 players; its raw policy plays about like `BEST_SPEC` at 100 nodes, [run 2](#generation-0-run-2-measured-2026-11-08)). Games: the paired games of the strength estimate (4 random plies from the start, the same opening for both colours), 100 pairs = 200 games per cell, Elo with the 95 % interval of `Elo`/`StrengthEstimate`, opponent `BEST_SPEC` at a node limit (`ab:N`; `ab:1` is depth 1), both sides with race play from the rows >= 1 table. The tuning used seed 3001 only; the tables below use the new seed 3002. Everything ran with `--threads 6`, one `VectorNetwork` per thread. "CPU ms" is thread CPU time per move of the player (the 15 % of the moves that the race module plays cost almost nothing and are in the average).

**Tuning (seed 3001, 100 simulations, vs `BEST_SPEC` at 400 nodes with batch 16 first, then at 1600 nodes with batch 1).** The first sweep started from batch 16 and c = 1.5 and found a low c best, which is what virtual loss does (it spreads the batch by itself); the batch sweep that followed showed that the batch itself costs strength, so c was tuned again at batch 1, the default.

| Sweep | Setting: Elo (± 95 %) |
|---|---|
| c, batch 16, vs 400 nodes | 0.1: +291 ± 64; 0.25: +275 ± 61; **0.5: +323 ± 67**; 0.75: +230 ± 54; 1.0: +230 ± 52; 1.5: +191 ± 51; 2.5: +149 ± 47 |
| batch, c = 0.5, vs 400 nodes | **1: +542 ± 136**; 4: +478 ± 109; 16: +323 ± 67; 64: +85 ± 43 |
| c, batch 1, vs 1600 nodes | 0.5: +252 ± 60; **1.0: +338 ± 70**; 1.5: +269 ± 58; 2.5: +108 ± 46; 4.0: +33 ± 36 |
| prior temperature, c = 1.0, batch 1, vs 1600 nodes | 0.7: +249 ± 57; **1.0: +338 ± 70**; 1.5: +282 ± 65; 2.5: +230 ± 54 |
| first-play reduction, c = 1.0, batch 1 | 0: +338 ± 70; 0.2: +363 ± 76 (not different; 0 kept) |

The curves are flat within the noise between c = 0.5 and 1.5 (and for the prior temperature between 1 and 1.5); c = 2.5 and above and a flattened or sharpened prior are clearly worse. On the fresh seed 3002 (below) c = 0.5 gave +372 ± 83 and c = 1.0 +272 ± 66 at batch 1, so the choice of 1.0 over 0.5 is not supported by a significant difference; both lie inside the flat part.

**Defaults chosen:** `sims=100, batch=1, cpuct=1.0, ptemp=1.0, fpu=0, noise=0, temp=0, s1=1, win1=1, reuse=0, race=1, racerows=2` (the measurements use `racerows=1`, the table of `BEST_SPEC`).

**Strength against the classical ladder (seed 3002, 100 pairs, `racerows=1`).** Elo of MCTS minus the rung, with the cost per move of both sides:

| Opponent | 25 simulations | 100 simulations | 400 simulations |
|---|---|---|---|
| depth 1 (`ab:1`) | +604 ± 186 | +727 ± 303 | +798 ± 277 |
| 100 nodes | +604 ± 186 | +727 ± 303 | +920 ± 235 |
| 400 nodes | +323 ± 74 | +604 ± 186 | +636 ± 228 |
| 1600 nodes | +92 ± 44 | +272 ± 66 | +463 ± 104 |
| 6400 nodes (extra rung) | -111 ± 45 | +139 ± 48 | +350 ± 72 |
| 25,600 nodes (extra rung) | -275 ± 61 | -10 ± 42 | +175 ± 49 |
| raw policy (no search) | +655 ± 243 | +729 ± 298 | +1200 (200-0-0, clamped) |
| MCTS CPU ms per move | 7.4 | 29 | 108 |
| `BEST_SPEC` CPU ms per move at 1600 / 6400 / 25,600 nodes | 1.5 / 5.2 / 19.5 | 1.4 / 4.7 / 17.9 | 1.5 / 5.0 / 18.7 |

The first two rungs repeat each other (a search of 1 to 100 nodes plays depth 1, see `StrengthEstimate.MIN_RUNG_ABOVE_ONE`); the win rate of 97 to 99 % makes their intervals wide. A cell with 0.985 or more is close to saturation, and its interval is only a rough bound.

**Position of MCTS(sims) in classical nodes.** The Elo against the rungs falls by 75 to 90 per doubling of the nodes between 6400 and 25,600 nodes, and the point where the score is 50 % (linear interpolation between the two bracketing rungs on a log scale, so only a rough figure with an uncertainty of a factor 1.4 either way) is:

| | Equal strength at | At equal CPU time per move |
|---|---|---|
| MCTS 25 simulations | about 3000 nodes of `BEST_SPEC` | the classical search gets about 7600 nodes for the same 7.4 ms: MCTS about 120 Elo behind |
| MCTS 100 simulations | about 23,000 nodes | about 35,000 nodes for 29 ms: about 55 Elo behind |
| MCTS 400 simulations | above 25,600 (+175 there); extrapolated with the same slope about 100,000 nodes | about 125,000 nodes for 108 ms: about 30 Elo behind (extrapolated) |

**Nodes-equal and time-equal.** Counted in simulations against nodes, one simulation is worth 120 to 230 nodes: 100 simulations match 23,000 nodes, 25 simulations match 3000. That is not the fair comparison, because a simulation costs about 0.29 ms (SMALL network, Vector API, one thread) and a node of `BEST_SPEC` about 0.0008 ms, 350 times less. At equal CPU time the two are close, the classical search a little ahead (the estimates above, which carry the interpolation error); the network player has the advantage that its strength per simulation will grow with the next generations while the classical one is frozen, and that a GPU evaluates a batch of leaves for less than the sum of CPU evaluations (the batch costs strength on the CPU, see below, so that gain would have to come with a better handling of the batch). MCTS against the raw policy: +655, +729 and a clean sweep for 25, 100 and 400 simulations (the raw policy of this network was -166 against 400 nodes).

**Throughput** (`MctsBench`, 40 mid-game positions, `sims=100`, no race module, SMALL, Vector API): 1 thread **3410 simulations/s**, 29.3 CPU ms per move; 8 threads **18,650 simulations/s** in all, 2330 per thread and 42.5 CPU ms per move, at a load average of 7.7 on 16 cores (the 8-thread number is lower than 8 times the single thread number because the machine was shared; indicative). In the games the average was 3200 to 3600 simulations per CPU second at 25, 100 and 400 simulations (400 simulations: 2900 to 3300). The CPU forward pass is not faster per position in a batch (batch 1: 3098 simulations per CPU second, batch 64: 3124, at c = 0.5), so batching gains nothing on the CPU.

**What the options cost or gain (seed 3002, 100 simulations, vs 1600 nodes, default +272 ± 66 unless noted).**

| Option | Elo | Remark |
|---|---|---|
| `batch=4` / `16` / `64` | +263 ± 69 / **-53 ± 47** / **-196 ± 50** | Virtual loss at a small budget costs 325 Elo at batch 16 and 470 at 64; with 100 simulations a batch of 16 is 6 rounds. |
| `cpuct=0.5` / `2.5` | +372 ± 83 / +123 ± 46 | |
| `race=0` (no root race play) | +238 ± 59 | -34 Elo, within the noise; CPU per move about the same (29.8 against 29.3 ms). |
| `racerows=2` | +272 ± 66 | The same result as rows 1 (165-1-34, 93 plies): the two tables give the same moves in these games. |
| `s1=0`, `win1=0` | +272 ± 66 each | The same 200 games as the default: neither changes a move in them. Both matter in constructed positions (tests), not in these games. |
| `reuse=1` | +323 ± 71 | +51, within the noise; costs 11 % more CPU per move (the carried tree is larger). |
| `noise=0.25,temp=1` (self-play settings) | **-372 ± 78** | Sampling by visits at 100 simulations is far weaker than the most visited move; for data generation only. |

Determinism check: the configuration `batch=16, cpuct=0.5` against 400 nodes on seed 3001 gave +323 ± 67 (173-0-27, 94 plies) in two separate runs of different builds and the same games.

### Known limits

- **Batches cost strength.** At 100 simulations, batch 16 is 325 Elo weaker than batch 1 (virtual loss, collisions end the batch early); the CPU gains nothing from batching, so the default is 1. A GPU player must either use few leaves per batch with many parallel games (one search per game, many games in the batch) or add a better collision handling (for instance evaluating the colliding leaf only once).
- **No proven results beyond one ply.** Only a win in one is recognised at expansion; a position in which every move loses at depth two still gets its visits by the PUCT rule, and a single safe move with a tiny prior can stay unvisited for hundreds of simulations (seen with a sharp fake network: 600 simulations did not find the only defence; flat priors find it). Propagating proven wins and losses up the tree (an MCTS solver) is not done.
- **S1 and the race table.** An S1 leaf needs the S1 verdict (the rarer, sound kind); the race arithmetic of E08 is not used as a value. At the root `race=1` plays the race from the table, like `BEST_SPEC`.
- **Tree, not a graph.** Transpositions are not merged, so the same position reached by two move orders is evaluated twice.
- **Two players.** S1, race play and the tests with a trained network are two-player; the search itself runs with three players (fake network test) but has never played one.
- **The time budget** makes a move depend on the machine; the strength numbers use simulations.
- **One network, one seed per cell.** The Elo columns are for the generation-0 candidate and 100 pairs per cell (interval ±40 to ±300 near saturation); the tuning of c, the prior temperature and the first-play reduction used one seed and one opponent, and differences below about 100 Elo in these sweeps are noise.
- **Vector API.** The Vector API forward pass needs `--add-modules jdk.incubator.vector`; without it `NetPlayers` uses the scalar network, about 15 times slower. `scripts/mctsmatch.sh`, `scripts/gen0.sh` and the Gradle tasks set the flag.

## As implemented (block 2f / E11)

**TL;DR:** The network can read six classical planes (single-agent distance of both sides, one-move reachability of both sides, passed flag, linear evaluation) and adds a classical prior to its outputs (`beta` times the distance gain to the policy logits, `k` times the linear evaluation inside the value's tanh, both learnable), selected by the plane set `classic1` ([network.md](network.md#classical-planes-and-residual-priors-format-version-2); file format version 2, version 1 files read as the plain network). On the same 20,000 teacher games, the same seed, the same batches and the same 16,000 steps as [run 2](#generation-0-run-2-measured-2026-11-08), the SMALL network with `classic1` **reaches every held-out KL level in half the steps or less** (KL 0.9 at about 1,900 steps instead of 3,700, the final KL of run 2 at about 5,500 steps), ends better (held-out KL 0.717 against 0.795, top-1 48.8 % against 45.8 %, value loss 0.158 against 0.163), and its raw policy gains **+135, +124 and +62 Elo** against the same rungs (depth 1, 100 nodes, 400 nodes); the MCTS player of block 2g gains **+78 Elo** against 1600 nodes of `BEST_SPEC` (+350 ± 77 against +272 ± 66, inside the noise). The acceptance rule of the plan (not worse at the end, a fixed KL in fewer steps) is met; **decision: adopt `classic1` as the default plane set of the self-play loop.** 55 new tests; 38 of 38 mutants caught.

**What was built** (module `nn` unless noted; details in [network.md](network.md#classical-planes-and-residual-priors-format-version-2)):

| Class | Content |
|---|---|
| `PlaneSet` | `plain` (7 planes, format version 1), `classic1` (13 planes and the priors), `classic1-planes` (13 planes, no priors: the ablation). In `NetworkConfig.planes()`; the metadata key `planes`. |
| `ClassicalPlanes` | The six planes from a `Position` and an `Orientation` (distance, `Position.generate` for the mover and, on a copy with the side swapped, for the next player, passed flag, the linear evaluation), with an optional mirror. `Encoder.encode(PlaneSet, ...)` and `SampleExpander.expand(PlaneSet, ...)` both call it, so the two give the same floats. |
| `ResidualPriors` | The prior terms added by `Network`, `VectorNetwork` and the trainer; the names `prior.beta`, `prior.k` and their initial values (1 and 0.5). |
| `NetworkConfig`, `Parameters`, `Checkpoint` | The plane set in the config and the metadata (`format_version` 1 for `plain`, 2 otherwise, plus `planes`), the wider first convolution, the two scalars at the end of the tensor list, `Parameters.init` with the output layers at zero. |
| `ReferenceTrainer`, `TrainingBatch` | A batch has 7 or 13 planes per cell; the trainer gives `prior.beta` and `prior.k` their exact gradients. |
| `ReplayBuffer`, `Gen0`, `RawPolicyPlayer` (`selfplay`) | The buffer recomputes the classical planes of each drawn sample at batch build time (they are never in a sample file); `gen0 run ... --planes classic1`; the planes of a network come from its config in the proxies step, the raw player and every MCTS player. `run.json` has the key `planes` only when it is not `plain`, so the `run.json` of earlier runs stays valid. |
| `TornadoNetwork` (`nn-tornado`) | The first convolution reads `config.inputPlanes()`; `classic1` is refused (the device value head applies the tanh), `classic1-planes` runs and agrees with the reference. No kernel changed. |
| `Mcts` (`search`) | Only `noisy(float[])` lost its `private` for the new test of part 0. MCTS and the gen0 tools load both variants through `Networks.fastest`; `ForwardPass` and `Evaluator` are unchanged for callers. |

**Part 0 (two tests from the review of block 2g).** `theNoiseMixesPriorAndDirichletSampleWithTheDocumentedWeights`: for a known seeded sample, noise 1 is the Dirichlet sample, noise 0 the prior and noise 0.25 the exact blend `0.75 · prior + 0.25 · d` (a swapped blend fails all three). `reuseNeedsTheSamePlyNotOnlyTheSameHash`: the start position comes back after four plies (both sides undo their move), with the same hash and another ply; the tree of the first search must not be reused (a reuse by hash alone carries 60 visits over).

### Tests

**55 new tests; the whole build has 947 tests (`engine` 58, `tablebase` 21, `nn` 314, `search` 248, `selfplay` 142, `nn-tornado` 164), none failed, 2 skipped (the beat-random properties without a strong strategy, as before).**

| Test class | What it pins |
|---|---|
| `ClassicalPlanesTest` (17) | Hand-checked distance, reachability (lone pieces, a jump chain, the leave-goal rule), passed and evaluation planes; an independent breadth-first distance and an independent landing oracle (written in the test) on 80+ positions for both sides; the identity of `Encoder.encode` and `SampleExpander.expand` for every mover of 2 and 3 players (and the passed positions), the mirror both as permuted rows and as the encoding of the mirrored position; every float of the slice written (NaN-filled buffer), offsets, the padding row. |
| `ResidualPriorsTest` (15) | A zero network is exactly `softmax(gain)` and `tanh(0.5 · logit)` for `Network` and `VectorNetwork` and both movers; the same from `Parameters.init`; `beta` and `k` scale the terms (0, 2, negative); the prior logit of a move is `beta · gain` on top of the net logit; `Network`, `VectorNetwork` and the double-precision `DoubleNetwork` agree with priors for two and three players; the trainer's forward equals `Network` bit for bit; the finite-difference gradients of `prior.beta` and `prior.k`; a sample and its mirror give the same loss and gradients for a symmetric network; the optimiser moves the scalars (no decay); width checks of batches. |
| `ReferenceTrainerTest` (+1) | Finite differences of **every** parameter of TINY with classical planes and priors, 2 and 3 players, with and without priors, and a deeper net (largest error / bound 0.00016 to 0.00056 over 1,134 to 2,055 parameters, none skipped by the kink guard). |
| `PlaneSetFormatTest` (9), `CheckpointTest` (+1) | Version 1 for `plain` and 2 with `planes` last for the rest; every combination of version, `planes` and `input_planes` that must be rejected; the pinned parameter counts (TINY 1,684 / 2,020 / 2,022, SMALL 60,388 / 61,732 / 61,734); file round trips, a file whose tensors do not match its plane set, the checkpoint with the optimiser state of the scalars; `VectorNetwork.reload()` picks up `prior.beta` and `prior.k`. |
| `ClassicalPriorsTest` (5, `search`) | `ClassicalPlanes.linearLogit` equals the weighted sum of `LinearEvaluator.features` **bit for bit** and `round(100 · logit)` equals `LinearEvaluator.evaluate` on 120,000 positions of both leave-goal rules (random and greedy playouts and scattered setups), the weights equal `LinearWeights.ROUND2`, the passed flag equals `RaceAdjudication.passed` on 80,000 positions; an untrained `classic1` network gives `tanh(0.5 · score / 100)` and `softmax(gain)` for 300 positions; it plays clean MCTS games and beats a random player (100 simulations, `mustBeatRandom`). |
| `MctsTest` (+2) | Part 0 above. |
| `selfplay`: `ReplayBufferEndToEndTest` (+1), `Gen0Test` (+1), `RawPolicyPlayerTest` (+1), `NetPlayersTest` (+1) | The buffer's classical planes equal the encoder's on every sample of real games, mirrored too, and train; a `classic1` run records its planes, resumes bit-identically after an abort, reports the same proxies in its own process, and cannot be resumed as `plain`; the raw player and the MCTS player of a `classic1` file. |
| `TornadoNetworkTest` (+1) | `classic1-planes` agrees with the reference on the device (it ran on the Metal device here); `classic1` is refused. |

### Mutation check

38 hand-made mutants of the new code (and of the two tests of part 0), each run against the tests of the three modules; **38 caught, 0 missed.** One (M31, the trainer's width check removed) was first missed: the wrong width failed somewhere else by chance, so the test now checks the message. The script is a Python loop that applies one textual replacement, runs the tests and restores the file.

| Group | Mutants (all caught) |
|---|---|
| Planes | reach planes swapped; the next player's moves generated without swapping the side; the next player's distance plane equal to the mover's; distance divided by 15; passed with `>=`; passed without the piece count; evaluation with the opponent added; no tempo term; mirror ignored; steps counted towards the farther cell |
| Priors | the "to" term with the wrong sign; the plane of the next player; value sign of player 1 wrong; evaluation term subtracted in `Network`; `VectorNetwork` without `prior.beta` after packing; its evaluation term without the sign; the gradient of `beta` with from and to added or without the 16; the gradient of `k` without the evaluation factor; no policy prior in the trainer; initial `beta` and `k` changed; output layers not zeroed |
| Plumbing | classical planes only for `classic1`; the expander without the mirror, or with the pieces on the wrong side; format version 1 for a classical file; a version 1 file with a `planes` key accepted; `input_planes` unchecked; the parameter count without the two scalars; the trainer without the width check; the evaluator, the replay buffer, the proxies step and the raw player with plain planes; `run.json` without the planes |
| Part 0 | the noise blend swapped; the tree reused by hash without the ply |

### The A/B measurement

**Setup.** `data/teacher-s2003` (400 files, 20,000 games, 1,708,277 training and 196,686 held-out samples), SMALL, 2 players, `gen0 run ... --preset small --steps 16000 --batch 256 --microbatch 32 --threads 8 --seed 1 --augment 1 --monitor 1`, **and for run 3 only `--planes classic1`** (`data/gen0run3`, 1h27m33s, 3.05 steps/s = 780 samples/s at a load of 10 to 12 with other work on the machine, against 1h22m and 3.25 steps/s for run 2 at a load of 11 to 13: about 6 % slower, indicative). The batch of a step is a pure function of the run seed and the step, so **both runs trained on the same samples in the same order**; the held-out split is the same (checked by `run.json`: same data fingerprint, hold-out fraction and salt). The only differences are the 13 planes and the priors, and that the two output layers start at zero in run 3 (part of the variant).

**Held-out curves at equal steps** (the monitor's proxies on 2,500 held-out samples, the same samples in both runs; the proxy steps of the two runs differ by timing, so run 2 is interpolated linearly between its own proxy steps, which are 600 steps apart; the checkpoints of run 2 other than the last two are not kept):

| Step | KL run 2 / run 3 | Top-1 % | Value loss | Winner % |
|---|---|---|---|---|
| 200 | 1.324 / 1.273 | 33.3 / 34.1 | 0.542 / 0.204 | 58.6 / 72.9 |
| 800 | 1.076 / 1.010 | 38.0 / 39.6 | 0.269 / 0.201 | 71.6 / 72.9 |
| 2,000 | 0.971 / 0.893 | 41.4 / 44.2 | 0.246 / 0.203 | 72.9 / 72.2 |
| 4,000 | 0.898 / 0.801 | 43.8 / 47.2 | 0.212 / 0.194 | 72.4 / 72.8 |
| 8,000 | 0.826 / 0.748 | 45.3 / 47.5 | 0.179 / 0.172 | 73.7 / 74.3 |
| 12,000 | 0.792 / 0.713 | 45.6 / 48.9 | 0.169 / 0.164 | 74.3 / 74.4 |
| 16,000 | 0.777 / 0.700 | 46.7 / 50.2 | 0.162 / 0.159 | 74.8 / 74.6 |

**Early speed.** Steps until the held-out KL first reaches a level (interpolated between proxies): 1.0 at 1,490 (run 2) and 861 (run 3); 0.95 at 2,358 and 1,171; **0.9 at 3,745 and 1,915**; 0.85 at 7,066 and 2,878. Run 3 reaches the final KL of run 2 (0.777) at about 5,500 steps and its top-1 of 46.7 % at about 3,500. The value starts calibrated: the value loss is 0.20 from the first proxy (run 2: 0.54 at step 200, 0.27 at step 800) and the training-batch value loss of the first 250 steps is 0.21 against 0.56. The training-batch KL (mean per 250 steps on the same batches) is 1.37 against 1.59 in the first 250 steps and 0.667 against 0.738 in the last 250.

**Final proxies** (`gen0 proxies` on the candidates, 5,000 held-out samples, the same ones):

| | Run 2 (`plain`) | Run 3 (`classic1`) |
|---|---|---|
| policy KL | 0.795 | **0.717** |
| top-1 agreement | 45.8 % | **48.8 %** |
| value loss | 0.163 | **0.158** |
| winner prediction | 75.7 ± 0.6 % | 76.0 ± 0.6 % |

**Strength** (paired games of the strength estimate, 100 pairs per cell, Elo with 95 % intervals; the ladder is `BEST_SPEC` at the given nodes; both games of a pair start from the same opening):

| | Run 2 | Run 3 | Difference |
|---|---|---|---|
| raw policy vs depth 1 | +38 ± 45 | **+173 ± 52** | +135 |
| raw policy vs 100 nodes | +3 ± 46 | **+127 ± 50** | +124 |
| raw policy vs 400 nodes | -166 ± 48 | -104 ± 48 | +62 |
| MCTS, 100 simulations (`mcts:sims=100,racerows=1`, seed 3002), vs 1600 nodes | +272 ± 66 (165-1-34) | +350 ± 77 (176-1-23) | +78 (not reproduced: with seed 9200 the independent review measured run 2 +308 ± 68 and run 3 +279 ± 66; treat as noise) |
The differences of the raw policy against depth 1 and 100 nodes are significant (z about 3.8 and 3.5, from the two 95 % half-widths); against 400 nodes (z about 1.8) and in the MCTS cell (z about 1.5) they are suggestive, not significant alone. The MCTS cell of run 2 was run again here and gives exactly the number of block 2g (same games: the search is limited by simulations and has no noise). The two MCTS lines used the same tool, seed and settings.

**The classical prior alone** (the untrained `classic1` network of seed 1, SMALL: no training at all): held-out KL 1.876, top-1 19.9 %, value loss 1.526, winner prediction 50.2 % (the evaluation says "the mover wins" in almost every early position because of the tempo term, and it is confidently wrong in passed positions, where `linear-r2` was not fitted: the value base is calibrated against the value targets as a whole, MSE 0.205 at `k = 0.5`, against 0.553 for 0, but not against the game results of the weighted samples; the plain untrained network has KL 4.03 and value loss 0.752). As an MCTS player at 100 simulations (no training) it scores **+186 ± 51 against depth 1 and -449 ± 99 against 1600 nodes**, so the priors alone are a weak but sound player, about depth 1 to 2 of the classical engine, and a trained network adds about 800 Elo against 1600 nodes (+350). The trained scalars stayed near their starting values: `prior.beta = 1.051`, `prior.k = 0.592`.

**Cost.** Per MCTS simulation the 13 planes and the move generation for both sides cost about 2.7 % (`MctsBench`, 100 simulations, SMALL, one thread, 2,385 and 2,381 simulations per CPU-second for the untrained `classic1` network against 2,464 and 2,434 for the `plain` candidate, two runs each at a load of 10 to 12); in training about 6 % per step (see above); the extra inference work of the priors themselves is negligible. The first convolution has 1,344 more weights (2.2 % of SMALL).

### Decision

**Adopt `classic1` as the default plane set of the self-play loop: yes.** It meets the rule of the plan, is not worse at the end on any measure (KL, top-1, value, winner prediction, raw policy, MCTS), and reaches a given KL in about half the steps or fewer, with a small cost. The ablation `classic1-planes` exists in the code and was **not** run: the full set did not lose, so the "keep only the planes that helped" branch of the plan was not needed, and **which part helps (the planes, the policy prior, the value base) is not measured**: the early value loss (0.20 at once) is the value base and the planes together, the policy KL gain the planes and the distance prior. If the cost of 13 planes on a GPU matters, that ablation is the next run.

### Deviations from the plan and limits

- **The value base is the 12-feature `linear-r2`, not `BEST_SPEC`'s 13-feature `linear-r3`** (`bestGain` needs the jump-component search of `LinearEvaluator`, which `nn` cannot call and would have to copy). The copy of the 12 features is pinned bit for bit to `LinearEvaluator`.
- **Race counts were not added** (option (d) of the plan, "skip if it complicates"): the table lives in `tablebase`, and the counts exist in passed positions only, where the passed flag and the distance planes carry most of it. The linear evaluation is unreliable in passed positions (it was fitted without them), so a passed-position value base from the race table is the natural next step.
- **Two players only for the value base, the passed flag and the evaluation plane** (0 for three players); the reachability and distance planes of three players cover the mover and relative player 1 only. No three-player network exists to test them beyond the identity and gradient tests.
- **`TornadoNetwork` refuses `classic1`** (the value prior has to be added before the device's tanh); the GPU trainer of Part B does not know the planes or the priors.
- **The planes assume the default rules.** `SampleExpander` rebuilds a position without a rule set; the sample files are written with the default rules.
- **The A/B is one seed and one data set.** The proxies are exact, the Elo cells have intervals of ± 45 to ± 77, and the two smaller differences (raw policy vs 400 nodes, MCTS vs 1600 nodes) are not significant by themselves; the paired direction of all five strength cells and of every proxy is the same. Run 2's curve is interpolated between its proxy steps. The held-out proxies of the monitor use 2,500 samples (standard error of the winner prediction about 0.9 points); the final ones 5,000.
- **No step-limited MCTS equal-time comparison** was made: the cells count simulations and nodes, not seconds (the loads of the runs differ).
- **Not measured:** LARGE, three players, the ablation, a second seed, the effect on self-play generations after generation 0 (the point of E11).

## As implemented (block 2h)

**TL;DR:** `Loop` (module `selfplay`, `scripts/loop.sh`) closes the AlphaZero loop on the CPU: per generation self-play of the best network with PUCT MCTS (Dirichlet noise, temperature in the first plies, race play and S1 adjudication), training of a candidate from the best network's parameters with a fresh AdamW optimiser over the replay window, a gate (candidate against the best network, and against a fixed `BEST_SPEC` rung), and a strength record (Elo against ladder rungs). Everything is derived from the files of the run directory, so any phase can be aborted and restarted and gives the bits of an uninterrupted run; checkpoints, ETA lines and strength estimates come from `TrainingRun` and `RunMonitor`, and `monitor-report` shows the six categories (selfplay, train, gate, strength, checkpoint, progress). First measurement: [Loop demo run 1](#loop-demo-run-1-measured).

Implemented, tested and measured 2026-11-10, JDK 25.0.3, Apple M3 Max, load average 5 to 12 from other work, so the timings are indicative; the Elo and score numbers count simulations, nodes and games, not seconds.

| Class | Module | Content |
|---|---|---|
| `Loop` | `selfplay` | The tool: `run RUN --init NETWORK`, `run --status RUN`, `strength RUN`, `monitor-report RUN`. The phases, the lock, the status, the ETA lines, the signals. |
| `LoopConfig` | `selfplay` | What `run.json` fixes and hashes (every option that changes a result), `fromJson`, the derived numbers (planned steps, seeds, the evaluation player). |
| `LoopGame` | `selfplay` | One self-play game with MCTS and its samples (policy = root visit shares, value = result or S1 verdict). |
| `LoopGamesJob` | `selfplay` | The self-play of a generation as a `Job`: shards of games, complete-or-redo. |
| `Gate` | `selfplay` | The promotion rule as a pure function (`decide`) and `PairJob`, the resumable job that plays a match in shards of 10 pairs (gate, baseline and strength matches). |
| `Eta` | `selfplay` | Cost per unit of each phase from earlier phases and the metrics file; the ETA of the generation and of the run. |
| `RunMonitor` | `selfplay` | Extended: `gen` lines, the loop shares of the six categories, the Elo frequency in generations. |
| `SelfPlayGame.Labeler` | `selfplay` | The sample builder of E05 takes the labels (policy target, value, weight, flag) from a labeler; the classical teacher is one labeler, the loop another. |
| `Job` | `selfplay` | A `label` for the progress lines, `doneItems`, and `status` for other lock and status file names. |

### Run directory and the state of a run

```text
data/<run>/
  run.json                           the configuration, written once (hash of every option that changes a result)
  .lock  .status                     lock held by the process; pid, start time and the last progress line
  metrics.jsonl                      monitor lines (display only: cost, run, gen, phase, metric)
  gen-0000/candidate.safetensors     the network of --init, copied once; decision.json says promoted, init
  gen-NNNN/samples/samples-s<seed>-<shard>.safetensors   self-play shards (25 games each)
  gen-NNNN/train/step-*.safetensors  checkpoints (the last two); candidate.safetensors after the last planned step
  gen-NNNN/gate/vs-best-<shard>.jsonl, vs-baseline-<shard>.jsonl   10 pairs per shard (the baseline shards of a network are in its own generation)
  gen-NNNN/decision.json             promoted or rejected, with the statistics
  gen-NNNN/strength/ab<rung>-<shard>.jsonl   the strength record of the network of generation NNNN
```

There is no pointer file. The best network is the candidate of the highest generation whose decision says promoted; generation 0 is always promoted. The state of a run is read from the files at every start:

| Phase | Done when | Resume |
|---|---|---|
| Self-play | every shard reads, names these settings and this network, and holds its games | the missing shards; a shard that does not read is renamed `*.corrupt` (CLEANUP) and redone; a shard of another network or other settings is an error (exit 1) |
| Training | `candidate.safetensors` reads, carries the run hash, the generation and `step` equal to the plan from its `new_samples` | the newest checkpoint that reads (damaged ones are quarantined), else the best network with a fresh optimiser |
| Gate | every shard reads, names this match (candidate, best network, settings, seed) and holds its pairs | the missing shards; a shard of another match is redone |
| Decision | `decision.json` reads and names its generation | next generation |
| Strength | all ladder shards of the best network read | the missing shards |

`*.tmp*` leftovers anywhere in the run directory are removed at start (CLEANUP; in `strength/` only when they are older than 10 minutes, because a separate `loop strength` process writes there). The replay window needs every shard of the earlier generations: a generation with missing shards stops the run with exit 1 instead of silently training on a hole.

### Self-play

`LoopGame` plays a game with the best network on both sides:

- **Opening:** 4 to 10 random plies (`OpeningSampler`, uniform), policy weight 0, flag bit 2.
- **Search:** `MctsPlayer`, 100 simulations, batch 1, c = 1.0, Dirichlet noise 0.25 with alpha 0.3 at the root. The search itself runs with `temp=0` (most visited); during the first 14 plies of the game the loop draws the move proportional to `visits^(1/T)` with T = 1 from the visit counts, afterwards it plays the most visited move.
- **Race play and S1:** a passed position is played by the race module without a search (as in the gate and strength games), and S1 (`Adjudication`, production configuration: only S1) ends the game with its verdict before the first move of a passed position that it can judge. Margin and evaluation layers are off, so no search score ever labels a position.
- **Samples:** one per ply before the end or the cut, in the format of E05 (`GameShard`, canonical cells; the classical planes are rebuilt at batch time). Policy target: the root visit shares over the legal moves, flag bit 0 (full search), weight 1. A position without a search (opening ply, race move) has the played move alone as target and weight 0; a single legal move is not searched either, but its target is exact and its gradient zero, so it keeps weight 1. Value target: the game result for the mover, or the S1 verdict for an adjudicated game (flag bit 1). The stored per-move score is the root value in thousandths, never a race-band score.
- **Determinism:** a game is a function of settings, network and game id: the opening, the noise and the move sampling are seeded from `(generation seed, id)`; the sampling has its own random stream, independent of the noise. The result does not depend on the thread count or the order (tested with 1 and 2 threads and with games played in reverse order).

### Training

The candidate starts from the best network's parameters with a fresh `Optimizer` (AdamW), a warm-up of 100 steps and then a constant rate (3e-4 by default; the 1e-3 of generation 0 is for training from scratch). Its step count is at once Adam's t, the index of the learning-rate schedule and the step of the batch sampler, all counted from 0 within a generation, as the review of [block 2b](#as-implemented-block-2b) demanded; `TrainingRun` and the checkpoints carry the generation. Steps = ceil(R x new training samples / batch) with R = 4 and batch 256. The batches come from a `ReplayBuffer` of the samples of the last generations (window W = 500,000, whole generations dropped when the total exceeds W, mirror augmentation); `--teacher DIR` puts teacher shards in as generation 0, which the window drops as soon as it is full (a share that decays by generations, not by weight). Every tenth game (`--holdout 0.1`: every round(1/0.1)-th game id with an offset from the shard's seed) is held out of the training buffer. A generation with fewer games than the period holds out the one game that the seed picks, so **a generation of two or more games always holds out at least one game and keeps at least one to train on** (a generation of 4 games at `--holdout 0.1` holds out 1 game, 25 %, not 10 %; the first version let the offset run past the 4 ids and held out nothing for 5 of 6 seeds, which ended the run with exit 1). With 10 or more games it is one game in ten, plus one more when the count is not a multiple of the period; the cheap proxies run on the held-out games of the newest generation at the last checkpoint of the generation and else at least 120 s apart (the first version also ran them at the first checkpoint and spent 2.9 % of the training loop against a budget of 2 %, see [Loop demo run 1](#loop-demo-run-1-measured)). Checkpoints are written every 50 steps or 60 s (the last two are kept).

### Gate

Candidate against best network: 100 pairs by default (50 in the demo), MCTS against MCTS with the same simulations, no noise, the most visited move, each pair from the same random 4-ply opening with the colours swapped. **Promotion needs a score significantly above 0.5 and no regression against the fixed rung:** `score - Z * se > 0.5` with `--promote-z Z` (default 1.28: one-sided 90 %, so `z = (score - 0.5) / se` must exceed 1.28; `--promote-z 0` is the old rule `score > 0.5`). Reason: [loop run 1](#loop-run-1-measured-2026-11-10) promoted four generations at 0.528, 0.543, 0.515 and 0.532 over 150 pairs (standard error about 0.024, so z about 1.2, 1.8, 0.6 and 1.5: all four intervals contain 0.5, and the old rule promotes half of all equal candidates). At Z = 1.28 an equal candidate is promoted in 10 % of the gates; of those four, 0.528 and 0.515 would have been rejected and 0.532 (z 1.46, standard error 0.022) would pass with a small margin. A candidate of 0.55 (z 2.1) promotes, one of 0.53 (z 1.25) does not. The baseline clause is unchanged. The standard error is that of the pair scores; with a standard error of 0 (all pairs equal) the rule is `score > 0.5`. `decision.json` and the log line `gen N decision: ...` carry `promote_z` and `z`. `promote_z` is part of `run.json` and the hash, but written only for a value other than 0: a run made before the option resumes as `--promote-z 0`, and a new run without the option has another hash than that of an old run. The clause: the candidate's pair scores against `BEST_SPEC` at 1600 nodes (50 pairs, the same openings for every network) must not be lower than the best network's by more than two standard errors of the paired differences (`Gate.decide`; the clause is inclusive, a standard error of 0 allows no drop). The best network's matches against the rung are played once, in its own generation directory, and reused. When the gate is not passed the candidate is rejected whatever the rung says, and the baseline matches are not played (`baseline_skipped` in `decision.json`). The decision file holds the score, its standard error and 95 % interval, the wins, draws and losses, both baseline scores and their paired difference.

### Strength record, ETA, status

After a decision, the best network gets the Elo estimate of the c2 plan (`--strength-every K`, default every generation; 0 switches it off in the loop): paired games of its MCTS player against the ladder rungs (default `ab:100,400,1600,6400`, 50 pairs, fixed openings), in shards of 10 pairs, with an `elo` line per rung in `metrics.jsonl`. A generation that rejects its candidate has the best network of the generation before, whose record exists already (it lies in that network's generation directory; the rejected generation has no `strength/` directory). **After a rejection the next generation plays and trains from the best network, not from the rejected candidate, and its replay window keeps the samples of the rejected generation**: the rejected candidate is thrown away, the games it was trained on are not (they were played by the best network, so they are as good as any other data of the window). `loop strength RUN [--generation N]` does the same as a separate step.

Every progress line of every phase gets the ETA of the phase (from the job or the trainer, which count only the items of this process), the ETA of the generation and of the run with the generations that remain, e.g. `gen 1 train: 208/369 steps  3.4 steps/s  ETA 47s  elapsed 1m00s  loss ... | gen ETA 1m51s, run ETA 11m26s (2 more generations)`. The cost per unit of a phase comes from the `phase` lines that earlier processes wrote with the units they did themselves (a resumed phase writes only the games it played), a `+` marks a lower bound when a later phase has never been measured. **Baseline and strength of the running generation are counted only once its gate score is above 0.5**, because only then are they played (a rejected candidate gets no baseline match and no strength record). Before that the generation ETA leaves them out, carries the `+` and ends with `; this generation without baseline and strength until the gate score is above 0.5`. The generations that follow are counted with all their phases, as if every candidate were promoted, so the run ETA is a lower bound for the running generation and pessimistic for the rest. `loop run --status RUN` prints the process, its elapsed time and the last progress line, which names the phase and the generation.

### Run monitor

`recordRun` of the training loop, `measure` of the proxies and a cost line per self-play, gate and strength phase feed `metrics.jsonl`; a `gen` line per process and generation gives the wall time against which the shares of the whole loop are computed. `monitor-report RUN` prints, besides the rows of [Run monitor](#run-monitor), the line `loop shares of ... wall in N completed generation(s) (promoted ones only; ...): train ..., selfplay ..., checkpoint ..., progress ..., strength ..., gate ..., other (loading, buffers, decisions) ...` and the number of generations between two Elo estimates that keeps them within their 5 % budget. N counts the finished, promoted generations after generation 0 (the `gen` line says `full` 1 for them): generation 0 has a strength record and nothing else, a rejected generation has no baseline and no strength record, so both would make the mean generation shorter than a generation that pays for an Elo estimate (a toy run showed 14.6 % for the estimate where 10.3 % is right). Their time is in the wall time of the shares, only the count and the mean leave them out; a generation that was resumed counts once, with the time of all its processes. Gate intervals are cut to [0, 1] (a score cannot exceed 1: the 95 % interval of 4 pairs read `to 1.033`). The budgets of the checkpoints, progress lines and proxies stay shares of the training loop, the Elo estimate is a share of the whole loop.

### Options

`Loop` documents every option in its class comment; the ones to know: `--init NETWORK` (generation 0, first start only), `--generations N`, `--games` (600), `--sims` (100), `--noise` (0.25), `--temp-plies` (14), `--lr` (3e-4), `--reuse` (4), `--window` (500000), `--gate-pairs` (100), `--baseline-rung` (1600) and `--baseline-pairs` (50), `--strength-every` (1), `--ladder`, `--strength-pairs` (50), `--threads` (8), `--checkpoint-steps` (50), `--checkpoint-seconds` (60). Every option that changes a result must be repeated on a resume (`run.json` is compared); cadences, thread counts, `--generations` and the strength options may change.

### Tests

**71 new tests; the whole build has 1018 tests (`engine` 58, `tablebase` 21, `nn` 314, `search` 248, `selfplay` 213, `nn-tornado` 164), none failed, 2 skipped (the beat-random properties without a strong strategy, as before).** Test-first, with TINY networks of random weights (`classic1` planes) and budgets of 4 games, 4 simulations and 4 pairs, so that a whole run of two generations takes about 2 seconds and the module's new tests together about a minute.

| Test class | What it pins |
|---|---|
| `GateTest` (10) | The promotion inequality with injected pair scores: 0.5 exactly does not promote, above does; the interval and the statistics; the baseline clause rejects a regression of more than two standard errors of the paired differences, is inclusive at the boundary (the sigma at which it turns over is computed in the test), allows nothing at 0 sigma, uses the paired differences and not two independent standard errors (a constant drop has standard error 0); a rejected gate stays rejected whatever the rung says; bad input. The shards of a match (`PairJob`): shards of 10, an aborted match resumes and a third run does nothing, a shard of another match or a short one is renamed `*.corrupt` and redone. |
| `LoopGameTest` (8) | Policy targets sum to 1 and **equal the visit shares of an independent search** of the replayed position (3 games, more than 100 searched samples, noise off so the search can be repeated); flags and weights of searched, opening, race-played and single-move positions (adjudication off, games played out); the move is sampled from the visits early and the most visited afterwards (a game without opening and noise does not depend on the seed with 0 sampling plies, does with 30, and the move after the sampled plies equals a fresh search); `sample` draws proportional to `visits^(1/T)` for T = 1, 2, 0.5; **value targets equal the S1 verdict (checked by a second S1 run on the position of the cut) or the result for the mover, for both movers**, with bit 1 on adjudicated games; no score of the race band, no margin or evaluation layer; a game is a function of settings, network and id (other order, a fresh instance, three threads with a forward pass each); the settings text names every option. |
| `LoopConfigTest` (12) | The documented defaults; `run.json` round-trips and **every option that changes a result changes the hash** (31 options, all different hashes), while cadences, threads and `--generations` do not; planned steps = ceil(R x new / batch); the seeds of 50 generations are distinct; warm-up then constant; the evaluation players have no noise and no sampling and the same simulations as the self-play; options out of range; **every `round(1/holdout)`-th game is held out, and a generation of 2 or more games always holds out at least one** (4 games at 0.1: exactly one, for 220 seeds), with an offset that follows the shard seed. |
| `EtaTest` (6) | The estimate with injected costs (running phase at its live rate, later phases, further generations), history cost without a live rate, the lower bound (`+`) for a phase never measured, the mean of the latest three observations, the history from `phase` lines of the metrics file (a torn line and other events are ignored), the text. |
| `RunMonitorLoopTest` (4) | With `gen` lines the shares are against the whole loop and the budgets of checkpoints, progress lines and proxies against the training loop; the printed `loop shares` line with the six categories and the Elo frequency in generations; an aborted generation counts its wall time but not as completed; without `gen` lines the report is the one of a training run. |
| `LoopTest` (30) | **Artefacts** (layout, no pointer file, candidate metadata, steps = ceil(R x new / batch), generation 0 is the given network byte for byte); **the best network** is the candidate of the highest promoted generation and the shards of generation g carry its hash; **the decision follows the gate shards** (recomputed in the test); a **second run after success** does nothing (no job, no new file, exit 0) and **more generations** continue to the same bytes as one long run; configuration, usage and init errors; **abort and restart** with 1 thread in self-play (after 1 and 3 games), training (after 3 steps: resumes at step 3), gate (after 1 and 3 pairs), the strength record and the baseline matches, each ending with the results of the uninterrupted run (samples, candidates, decisions, gate and strength pairs; CPU times left out); **state from the artefacts** by deleting artefacts of a finished run (decision, gate shards, candidate with a damaged newest checkpoint, candidate and checkpoints, a shard of the current generation, a corrupt shard, a shard of another network, leftover temporary files, a gate shard of another match) and a hole in an earlier generation (exit 1); thread counts and cadences change no result; **every candidate equals the one of an independent trainer** written from the documentation (best network, fresh AdamW, warm-up, replay window without the held-out games, batches of generation g from step 0) for generations 1 and 2; the first checkpoint of every generation is at step 1; **the replay window** drops the oldest whole generation exactly when the total exceeds W (five windows around the boundary); a teacher directory is generation 0 of the window and ages out; the lock (a second run exits 75 with `running already`, `--status` names the process, the aborted run resumes); the strength cadence and the separate `strength` step; the monitor accounting of all six categories; **the `phase` lines count the work of the process** (the resumed self-play writes 2 games, not 4) and every progress line carries the ETA of the generation and the run. |
| `LoopProcessTest` (1) | Real processes: SIGTERM in self-play ends with 143 and the summary and no `.tmp` file, a second process while the first runs ends with 75, `--status` names the pid and the last progress line (phase and generation), SIGINT ends with 130, the restart ends with the results of the uninterrupted run, `monitor-report` prints the loop shares. |
| `ClassicalPlanesTest` (17, no new test) | Part 0 of the block: the reach planes are compared with the independent landing oracle row by row, **exactly 1f inside and 0f outside** (the E11 review found that only membership was checked); the mutants `REACH_OWN = 0.5f` and `REACH_NEXT = 0.5f` now fail. |

### Mutation check

30 hand-made mutants of the new code, each run against the tests that should catch it (`mutants.py`, a Python loop that applies one textual replacement, runs the tests and restores the file); **30 killed, 0 missed.** One (`opening-plies-weighted`, the opening weight in the shared sample builder) was first reported missed because the loop path gives such samples weight 0 by itself and only the classical path needs the builder's own rule; it is killed by `SelfPlayGameTest`.

| Group | Mutants (all killed) |
|---|---|
| Promotion | `score >= 0.5`; the clause ignored; the clause with the wrong sign; one standard error instead of two; the paired difference with the wrong direction; the header check of a gate shard removed |
| Evaluation players | Dirichlet noise on in the gate, baseline and strength games |
| Training | steps floored instead of ceiled; warm-up one step longer; training from a random network instead of the best one; the sampler seeded with generation 0; **the optimiser step count carried over (7 instead of 0)**; **replay window off by one**; the held-out games trained on; the resume ignores the checkpoint; held-out period one longer |
| Self-play | S1 verdict with the wrong sign; the label not reoriented for the second mover (shared builder); sampling plies compared the wrong way; visit shares divided by `total + 1`; the full-search flag off; race-played positions weighted; the temperature exponent inverted; random opening plies weighted (shared builder) |
| Resume and accounting | **ETA units after a resume counted as the whole phase**; the network check of a self-play shard removed; the strength cadence inverted; the decision written inverted; the ETA of the later phases counting the running one; the monitor shares against the training loop |

### Review fixes (exp/T2h-fix)

An independent review found the loop sound (real-process kill and restart at 10 points gave byte-identical results) and six defects, fixed in one commit: the held-out guarantee (see Training), no test for a rejected generation followed by another (now `LoopTest`, with `Loop.PairFake` making the gate result a given: self-play and training of the next generation use the best network, `best_before`, no `strength/` directory in the rejected generation, the window keeps its samples), the mean generation of the monitor (generation 0 and rejected generations no longer count), the ETA of generations that may be rejected (see Strength record, ETA, status), and the interval cut to [0, 1]. New tests: 18 (`selfplay` 231, whole build 1,036, 2 skipped). Mutants: the 30 of the table above plus 30 new ones for these findings; 59 of 60 killed, 1 missed (`eta.observe("train", ...)` with the end step instead of the steps done: only the in-memory cost per step of the same process changes, nothing a file or a line shows; the same mistake in the `phase` line is killed).

### Deviations from the task and limits

- **The baseline clause is skipped when the gate score is not above 0.5** (the candidate is rejected whatever the clause says), which saves 2 x 50 pairs in a rejected generation.
- **The per-generation strength record is the record of the best network**; a rejected generation points at the network of the generation before.
- **Race-played positions get policy weight 0** (the task says weight 1 except random opening plies): the race module plays them without a search, so their target is not a search result, and a network that is never asked for a move there gains nothing from learning it.
- **The held-out games come out of the training data** (about 10 % of each generation, at least one game); a pure function of shard seed, game id and the number of games per generation (which `run.json` fixes), so the split never changes.
- **Loading the window reads every generation again** (the replay buffer is rebuilt from the files at each training start, as designed); the cost grows with the number of generations in the window, about 0.1 s for 47,000 samples.
- **Only the CPU reference trainer** (3.4 steps/s at batch 256 with SMALL on 8 threads); the GPU trainer of Part B is not part of the loop.
- **Two players only; one run, one seed, 3 generations** in the demo: no statement about the long-run behaviour of the loop (strength growth, window effects, forgetting).

## Loop demo run 1 (measured)

**TL;DR:** Starting from the E11 candidate (classic1, 16,000 steps), four generations of 300 games each (100 simulations, 8 threads, R = 4, lr 3e-4, gate and baseline of 50 pairs, strength record of 3 rungs x 50 pairs after every generation) took **19 min 10 s for generations 1 to 3 and 5 min 44 s for generation 4** on a shared machine (load 5 to 12). Generations 1, 2 and 4 were promoted (gate scores 0.58, 0.54, 0.51, none of them clear of 0.5 at 50 pairs), generation 3 was rejected (0.48). The strength of the best network against `BEST_SPEC` at 6400 nodes goes +63 ± 67 (generation 0) → +156 ± 69 → +200 ± 74 → +241 ± 84 (generation 4), and against 1600 nodes +270 → +269 → +402 → +382 (± 91 to 130); a rise of about 180 Elo over four generations, 1.7 standard errors, so the loop trains and the direction is right but 300 games per generation and 50-pair gates do not show a significant step per generation. **Self-play (33 %) and training (29 %) dominate; the gate (18 %) and the strength record (20 %) are the part to tune.** A real SIGTERM in each phase ends the process in 0.3 to 6 s, and the resumed run ends with the candidate and decision of the uninterrupted run, byte for byte.

Command: `scripts/loop.sh run data/loopdemo1 --init data/gen0run3/gen-0000/candidate.safetensors --generations 3 --games 300 --sims 100 --gate-pairs 50 --baseline-pairs 50 --ladder 400,1600,6400 --strength-pairs 50 --threads 8` (generation 4: the same command with `--generations 4` and the adapted cadences below). Apple M3 Max, JDK 25.0.3, load average 5 to 12 from other work: timings are indicative, the scores and Elo values count games.

| | Generation 0 (init) | 1 | 2 | 3 | 4 |
|---|---|---|---|---|---|
| Self-play, games/s (300 games, wall) | | 2.61 (1m55s) | 2.17 (2m18s) | 2.27 (2m12s) | 2.75 (1m49s) |
| Samples: training + held out | | 23,565 + 2,816 | 23,640 + 2,562 | 23,845 + 2,719 | 23,465 + 2,624 |
| Steps (R = 4, batch 256), train time | | 369, 1m48s (3.41/s) | 370, 1m59s (3.11/s) | 373, 2m00s (3.11/s) | 367, 1m42s (3.59/s) |
| Proxies at the last step, on the held-out games of the generation: KL / top-1 / value loss / winner | | 0.455 / 71.6 % / 0.598 / 75.8 % | 0.509 / 67.4 % / 0.671 / 74.1 % | 0.448 / 74.4 % / 0.437 / 84.8 % | 0.451 / 72.5 % / 0.451 / 83.3 % |
| Gate: score of the candidate against the best network, 50 pairs (W-D-L games) | | 0.580 ± 0.037 (57-2-41) | 0.540 ± 0.040 (54-0-46) | 0.480 ± 0.043 (48-0-52) | 0.510 ± 0.036 (51-0-49) |
| Baseline: candidate / best network against `BEST_SPEC` 1600 nodes (pair difference) | | 0.900 / 0.835 (+0.065 ± 0.034) | 0.950 / 0.900 (+0.050 ± 0.036) | not played | 0.960 / 0.950 (+0.010 ± 0.023) |
| Decision | promoted (init) | promoted | promoted | rejected | promoted |
| Strength of the best network, Elo against 400 nodes | +676 ± 339 | +1200 (100-0-0, a bound) | +604 ± 380 | (generation 2) | +918 ± 236 |
| against 1600 nodes | +270 ± 98 | +269 ± 91 | +402 ± 130 | (generation 2) | +382 ± 121 |
| against 6400 nodes | +63 ± 67 | +156 ± 69 | +200 ± 74 | (generation 2) | +241 ± 84 |

The proxies of different generations are measured on different games (each generation's own held-out games, played by a different network), so they do not form a curve; the winner prediction of 74 to 85 % is the value head's accuracy on fresh self-play positions (yardstick of the classical teacher: 78.8 %).

**Where the time goes** (`monitor-report`, 1495 s of loop wall time over the five iterations, the first of which is the strength record of generation 0):

| Category | Share of the loop | Per generation of 300 games |
|---|---|---|
| selfplay | 33.1 % | 1m49s to 2m18s (0.41 s per game, 2,650 simulations per CPU second) |
| train | 29.3 % | 1m42s to 2m00s |
| gate | 17.9 % | vs best network 41 to 46 s (50 pairs); baseline 23 s per network (the best network's once) |
| strength | 19.7 % | 70 to 78 s (150 pairs of 3 rungs) |
| checkpoint, progress, other | < 0.01 % | 1.3 ms per checkpoint, 0.7 ms per progress line |

**Overhead of checkpoints, ETA and strength, and the changes it asked for** (the owner requirement; the report was read after run 1 and the cadences changed before generation 4):

| What | Measured in generations 1 to 3 | Change | Generation 4 |
|---|---|---|---|
| (a) checkpoints | 1.3 ms each (0.002 %), every 200 steps or 120 s: 2 per generation | every 50 steps or 60 s | 8 per generation, 0.004 % |
| (b) ETA / progress lines | 0.7 ms per line, 0.002 % | none (every 10 s; 30 s in the demo) | 0.002 % |
| (c1) proxies on the held-out games | 1.7 s per evaluation, two per generation (first and last checkpoint): **2.9 %, over the 2 % budget** | only at the last checkpoint of a generation (and else 120 s apart) | 1 per generation, 1.7 % |
| (c2) Elo estimate (3 rungs x 50 pairs) | 71 s each: **24 % of a 5-minute generation, over the 5 % budget** | none in the code: it is a cost of the demo's short generations; the report prints `--strength-every 5` for them. At about one hour per generation the same 71 s are 2 % (200 s for 4 rungs: 5.5 %, so 4 rungs every second generation or fewer pairs). | 70 s |

**Abort and restart at real scale** (a generation of 60 games, a gate of 20 pairs; `SIGTERM` after the phase had run for 1.5 s): self-play 143 after 3.0 s (the games of the unfinished shard are dropped), training 143 after 0.3 s (final checkpoint), gate 143 after 6.0 s, the strength record of generation 0 143 after 1.6 s. The run killed in self-play, training and gate in turn and restarted each time ended with `candidate.safetensors` and `decision.json` of the uninterrupted run (SHA-256 prefix 3e9b3ffc3a5eb8c3 and e443811914b2c882, identical); so did a run killed in the strength record.

**A realistic production generation.** Measured costs (from the table): self-play 0.41 to 0.46 s per game (88 samples per game, S1 cuts the games at about 88 plies), training 3.1 to 3.6 steps/s at batch 256 with R = 4, which is 1.24 steps per game or 0.35 s per game, gate 0.9 s per pair, baseline 0.46 s per pair, strength 0.5 s per pair. One game costs about 0.8 s of self-play and training together. For one generation per hour: 100 gate pairs (90 s), one baseline of 50 pairs (23 s) and a strength record of 4 rungs x 50 pairs every generation (100 s) leave 3,390 s, so **about 4,000 games (350,000 samples, about 5,000 steps) per hour**; at R = 2 about 5,000 games. A generation of 1,000 games takes about 17 minutes.

**Recommendations for the first production run:**

- **Games per generation:** 1,000 to start (17 min, 20 generations in 6 hours, to find problems early), 4,000 for an hour per generation later; **sims 100** (the cost per game is linear in it; 200 only when a generation is clearly saturated); R = 4.
- **Learning rate:** 3e-4 was used for all four generations and trained without trouble; 1e-3 was not tried (a fresh optimiser with 1e-3 on a trained network risks a loss spike; measure it once with a 1-generation run before using it).
- **Window:** 500,000 samples is 1.4 generations of 4,000 games and 21 of 300; for 1,000 to 4,000 games per generation use **1,000,000 (620 MB)**: three to ten generations of data.
- **Gate:** 100 pairs at least, better 200: with 50 pairs the standard error is 0.04, and the promotions at 0.54 and 0.51 are within the noise of equal strength (the rule "above 0.5" promotes half of the equal candidates). The non-regression clause is not yet useful against `ab:1600`: the candidates score 0.90 to 0.96 there, a ceiling with a standard error of 0.02 to 0.03; **use `--baseline-rung 6400`** (the best network scores 0.76 to 0.80 there) from generation 1 on.
- **Ladder:** `100,400,1600,6400`, 50 pairs, every generation once a generation takes more than 20 minutes (the 100-node rung is saturated at +1200; add `25600` when 6400 passes 0.9).
- **Cadences:** checkpoints every 50 steps or 60 s, progress every 10 s, proxies at the end of the generation (the new defaults).
- **Known limits of the demo:** one run, one seed, 300 games per generation; the Elo intervals are ± 70 to ± 380.

## Loop run 1 (measured 2026-11-10)

**TL;DR:** The first production loop (`data/loop1`: 10 generations of 4000 MCTS self-play games at 100 simulations, SMALL `classic1` network from generation-0 run 3, replay window 1 M samples, lr 3e-4, reuse 4, gate 150 pairs, baseline rung 6400 nodes) took 8 h 45 min on one machine at a load of 10 to 12 and a 60 W adapter (battery stayed at 78 %, no thermal warning in the whole run). Nine of ten generations were promoted. Re-measured with 150 pairs against `BEST_SPEC` at 6400 and 25,600 nodes the network climbs from -19 +- 34 Elo (generation 0) to +267 +- 51 (generation 10) against 25,600 nodes: the 50-pair strength records of the loop (flat at about +350 against 6400 nodes from generation 4 on) were a measuring artefact, the rung and the pair count did not resolve the progress. Gains per generation shrink: about +115 from 0 to 1, +68 from 1 to 3, +63 from 3 to 5, +23 from 5 to 8, +17 from 8 to 10.

| Generation | gate score vs previous best | vs 6400 nodes (150 pairs) | vs 25,600 nodes (150 pairs) |
|---|---|---|---|
| 0 (init) | - | +143 +- 39 | -19 +- 34 |
| 1 | 0.708 | +210 +- 45 | +96 +- 33 |
| 3 | 0.528 | +302 +- 51 | +164 +- 42 |
| 5 | 0.600 | +343 +- 56 | +227 +- 45 |
| 8 | 0.515 | +378 +- 62 | +250 +- 45 |
| 10 | 0.532 | +398 +- 66 | +267 +- 51 |

Gate scores of the other generations: 0.565 (2), 0.560 (4), 0.562 (6), 0.543 (7), 0.460 (9, rejected). Strength seed 5001, `scripts/loop.sh strength data/loop1 --generation N --ladder 6400,25600 --strength-pairs 150 --strength-seed 5001`.

**Findings**
- **Rung and pair count:** a rung scored above about 0.85 does not resolve differences. The in-loop record (50 pairs, rungs 400/1600/6400) saturated; rung 25,600 with 150 pairs resolves the whole run. For the next run use the ladder 6400, 25600, 102400 and at least 100 pairs in the loop, or the strength step as a separate process on checkpoints.
- **Promotion rule:** `score > 0.5` promoted generations whose 95 % interval contains 0.5 (generations 3, 7, 8, 10). The curve still rises, so the rule did no visible harm here, but a stricter rule (lower bound above 0.5, or a threshold of 0.52) is cheaper than the risk of drift; decide before the next run, it changes `run.json`. **Decided for the next run:** `--promote-z` (default 1.28, one-sided 90 %), see [Gating, as implemented](#as-implemented-block-2h).

- **Overhead (monitor-report, 13,909 s of loop wall time):** checkpoints 0.008 % (budget 1 %), progress lines 0.005 % (0.1 %), held-out proxies 1.2 % (2 %), Elo estimate 2.1 % (5 %), gate 4.5 % (0.75 % baseline, 3.7 % against the best network), self-play 49 %, training 43.5 %. No cadence needs to change.
- **Training is 43.5 % of the generation time**, above the 25 % threshold of the plan for starting the GPU trainer (Part B). Self-play on 8 CPU threads (about 2.6 games/s) stays on the CPU unless the GPU forward pass batches leaves.
- Timings are indicative (load 10 to 12 from other jobs); simulation-limited Elo is robust.
- Not measured: the effect of the learning rate (only 3e-4), of more simulations per move, of the window size, and a LARGE network.

## Loop run 2 and hyperparameter sweep 1 (measured)

**TL;DR:** Loop run 2 (`data/loop2`: init = the best network of run 1, 2500 games per generation at 200 simulations, GPU trainer, lr 3e-4 constant after 100 warm-up steps, reuse 6, batch 256, window 1.5 M, gate 200 pairs, `--promote-z 1.28`) rejected generations 1, 2 and 3 (gate scores 0.509 +- 0.020, 0.491 +- 0.018, 0.496 +- 0.021); the best network is still generation 0 (+338 +- 69 Elo against `BEST_SPEC` at 25,600 nodes, +173 +- 51 at 102,400). Sweep 1 asks whether the stall is the training recipe rather than the network. It trains eight variants offline from the generation 0 network on the self-play data of generations 1 to 3 (588 k samples) with the GPU trainer and plays the loop's gate against generation 0. **The recipe is the cause, at least for this step:** a cosine decay of the learning rate from 3e-4 to 1e-5 over 15,000 steps (variant F) gives a candidate that beats generation 0 with 0.5625 +- 0.018 in the first gate and **0.5887 +- 0.0136 (400 pairs, fresh openings, z 6.5)** in the confirmation, about +62 Elo, on the very data from which the constant-lr candidate scored 0.496. Network capacity is not shown to be the limit. For the next run: `--lr 3e-4 --lr-final 1e-5 --reuse 20`.

Measured 2026-11-13 on an Apple M3 Max, 60 W adapter (battery 80 % and "AC attached; not charging" before and after every long run, `pmset -g therm`: no thermal, performance or CPU power warning recorded, load average 4 to 7 from other work). Timings are indicative; scores come from paired games with fixed seeds and do not depend on them.

### The tool

`Sweep` (module `selfplay`, `scripts/sweep.sh`, test `SweepTest`) is the structure of `GpuCheck` with a gate behind it. One call takes the loop directory (`run.json` and the sample shards are only read), the start network (default generation 0 of the loop) and a list of variants. Per variant: a fresh `Optimizer` (AdamW) on the start network, the replay window of generations 1 to `--generation` (the loop's held-out split), `TrainingRun` with the GPU trainer, the loop's seed and generation number for the batches (so every variant sees the same batches in the same order), the held-out proxies of the loop (2500 samples), then the gate of the loop: `Gate.PairJob` with the loop's evaluation player (`mcts:sims=200,batch=1,cpuct=1.0,noise=0,temp=0,race=1`), opening scheme (4 random plies) and seed of generation `--gate-generation` (default: the generation itself, so the pairs are those of the loop's own gate; another number gives fresh openings). Optionally the strength record against `BEST_SPEC` rungs (the loop's player, openings, identity and seed 3001, so it is comparable with `data/loop2/gen-0000/strength`). Candidate, gate shards and strength shards are resumable (a second call skips what exists); one sweep at a time (lock, exit 75).

A variant is a built-in name or `NAME:lr=..../final=..../steps=..../cv=....` (`final`: cosine to this rate over all steps, `steps` 0: the loop's planned 4596, `cv`: value coefficient). `LearningRateSchedule` got the optional in-generation cosine (`selfPlayDecaySteps`, `selfPlayFinalLr`, both 0 = constant as before; the 6-argument constructor is unchanged). Since variant F won, `Loop` has `--lr-final R` (default 0 = constant; the cosine runs from `--lr` after the warm-up to R at the last planned step of every generation; `run.json` names `lr_final` only when it is set, so no hash of an earlier run changes; a generation with at most `warmup + 1` planned steps stays constant). Tests: `LearningRateScheduleTest` (the curve), `LoopConfigTest` (json, hash, validation, the schedule), `LoopTest` (a loop run with `--lr-final` equals an independent trainer with the decaying schedule, and differs from the constant one), `SweepTest` (a TINY variant reproduces the loop's candidate to the bit, another schedule differs, the gate is played and summarised, a second call skips).

```
scripts/sweep.sh --loop data/loop2 --out data/sweep1 --variants BASE,A,B,C,D,E,F,G,H --pairs 200
scripts/sweep.sh --loop data/loop2 --out data/sweep1 --variants F,H --pairs 400 --gate-generation 50 --tag confirm
scripts/sweep.sh --loop data/loop2 --out data/sweep1 --variants F --pairs 0 --strength-rungs 25600,102400 --strength-pairs 100
```

### Baseline: the tool is faithful

BASE (lr 3e-4 constant, 4596 steps, the planned steps of generation 3) reproduces the loop's generation 3 candidate **bit for bit** (all 26 tensors identical, the GPU trainer is deterministic; the file differs only in its metadata) and its gate is the loop's: 0.4963 +- 0.0207, 198-1-201 games, z -0.18, the same numbers as `data/loop2/gen-0003/decision.json`. The held-out proxies match the log of the loop (`gen 3 proxies at step 4596`: KL 0.420, top-1 72.1 %, value loss 0.440, winner 84.0 % of 2477) to the printed digits.

### Results (first pass, 200 pairs, the openings of the loop's gate 3)

Proxies on 2500 held-out samples of generation 3 (the start network: KL 0.4173, top-1 72.01 %, value loss 0.4568, winner 82.60 %). Score is the mean pair score of the candidate against the generation 0 network, z = (score - 0.5) / se, "lower bound" = score - 1.28 se (promotion needs more than 0.5).

| Variant | Schedule | Steps | KL | Top-1 | Value loss | Winner | Gate score +- se | z | Lower bound | W-D-L games |
|---|---|---|---|---|---|---|---|---|---|---|
| BASE | 3e-4 constant (loop 2) | 4596 | 0.4201 | 72.10 % | 0.4399 | 84.01 % | 0.4963 +- 0.0207 | -0.18 | 0.470 | 198-1-201 |
| A | 1e-4 constant | 4596 | 0.4141 | 72.05 % | 0.4384 | 83.85 % | 0.5200 +- 0.0189 | 1.06 | 0.496 | 206-4-190 |
| B | 3e-5 constant | 4596 | 0.4129 | 71.87 % | 0.4387 | 83.85 % | 0.5025 +- 0.0190 | 0.13 | 0.478 | 200-2-198 |
| C | 3e-4 cosine to 1e-5 | 4596 | 0.4106 | 72.19 % | 0.4380 | 83.89 % | 0.5212 +- 0.0183 | 1.16 | 0.498 | 207-3-190 |
| D | 1e-3 cosine to 1e-5 | 4596 | 0.4125 | 71.50 % | 0.4378 | 83.93 % | 0.5275 +- 0.0182 | 1.51 | 0.504 | 210-2-188 |
| E | 3e-4 constant | 15,000 | 0.4157 | 70.81 % | 0.4494 | 83.69 % | 0.4812 +- 0.0191 | -0.99 | 0.457 | 192-1-207 |
| **F** | **3e-4 cosine to 1e-5** | **15,000** | **0.4080** | **72.37 %** | 0.4382 | 83.93 % | **0.5625 +- 0.0184** | **3.40** | **0.539** | 225-0-175 |
| G | 1e-4 cosine to 1e-6 | 15,000 | 0.4084 | 71.91 % | 0.4376 | 83.69 % | 0.5200 +- 0.0190 | 1.05 | 0.496 | 208-0-192 |
| H | as C, value coefficient 2 | 4596 | 0.4140 | 71.87 % | 0.4376 | 83.81 % | 0.5412 +- 0.0192 | 2.15 | 0.517 | 215-3-182 |

Training took 36 s for 4596 steps and 116 s for 15,000 (128 steps/s on Metal), a 200-pair gate 4 min 20 s (0.77 pairs/s on 8 threads). The shards are in `data/sweep1/<variant>/gate/`, the rows in `data/sweep1/results.jsonl`.

### Confirmation (the two best by gate score, 400 pairs, fresh openings: gate seed of generation 50)

| Variant | Gate score +- se | z | Lower bound (score - 1.28 se) | W-D-L games | Elo vs generation 0 |
|---|---|---|---|---|---|
| F | 0.5887 +- 0.0136 | 6.52 | 0.571 | 467-8-325 | about +62 |
| H | 0.5650 +- 0.0133 | 4.88 | 0.548 | 447-10-343 | about +45 |

Both pass the promotion rule of the loop (`--promote-z 1.28`) on openings that did not take part in the selection. Neither fell back towards 0.5 (the winner of eight noisy gates is expected to; here the fresh result is higher, which is within the noise of 200 pairs: se 0.018).

### Strength against `BEST_SPEC` (F, 100 pairs, seed 3001, the loop's player; the baseline numbers are generation 0 of `data/loop2`)

| Rung | Generation 0 (baseline) | F | Difference |
|---|---|---|---|
| 25,600 nodes | +338 +- 69 (score 0.875, 174-2-24) | +354 +- 74 (score 0.885, 177-0-23) | +16 |
| 102,400 nodes | +173 +- 51 (score 0.730, 145-2-53) | +210 +- 50 (score 0.770, 153-2-45) | +37 |

Both differences are positive and in the direction of the gate, but they are well inside the noise of 100 pairs (the 95 % intervals of the two networks overlap almost completely, and a rung scored near 0.88 resolves little). The direct gate is the sharper instrument; the rung records say only that F is not weaker than generation 0 and probably a little stronger against the classical player.

### Reading the table

- **Constant lr 3e-4 is the problem, more steps with it is worse.** E (the same lr, 15,000 steps) scores 0.481 and has the worst held-out value loss (0.449) and top-1 (70.8 %): with a constant high rate the longer run does not converge, it jitters around and overfits the window (6.5 passes over it). Lower constant rates (A 0.520, B 0.503) are no worse than BASE and A is a little better; B is probably too slow for 4596 steps.
- **The decay carries the effect, the steps pay on top of it.** The same 15,000 steps give 0.481 constant (E) and 0.5625 with the cosine (F): about 0.08, 3 standard errors of the difference. The same cosine over 4596 steps gives 0.521 (C); F against C is +0.04 (1.6 standard errors of the difference at 200 pairs, so only suggestive, but F also has the best KL). A short run with a higher peak (D, 1e-3) is no worse than C.
- **G (peak 1e-4) is no better than C:** with the decay a peak of 3e-4 is enough or better; a lower peak does not help.
- **The value coefficient 2 (H against C, +0.02) is not resolved:** one standard error of the difference is 0.026. F and H also do not differ significantly in the confirmation (0.589 against 0.565, se of the difference 0.019). The value loss does not move between variants (0.438 in all but E), so there is no sign that the value head is under-trained.
- **The held-out proxies hardly discriminate** (KL within 0.408 to 0.420 and top-1 within 70.8 to 72.4 % across all variants; the start network has 0.417 and 72.0 %), as in the loop; they rank E and BASE as the worst and F, G, C as the best, which agrees with the gate except for G. The gate stays the criterion.
- **Is it capacity?** Not in this step: the same network from the same data moves from 0.496 to 0.589 against its parent by the schedule alone, so the network can still use the data. The proxies suggest the room is small (KL 0.417 to 0.408), so many generations may again reach a plateau that this experiment does not locate.

### Recommendation

1. **Next loop run: `--lr 3e-4 --lr-final 1e-5` and about 15,000 steps per generation, i.e. `--reuse 20`** (15,000 steps is reuse 19.6 at 196 k new training samples per generation of 2500 games; the cost is about 2 min training against 30 min of self-play per generation, so cheap). `--lr-final` is part of `run.json` (repeat it on a resume).
2. Keep `--promote-z 1.28` and the gate of 200 pairs: the gate then separates the variants (F against BASE is 0.59 against 0.50 at se 0.014 to 0.02).
3. To tell the effect of the value coefficient and the peak rate apart, a second sweep from F: F with `cv=2`, F with peak 1e-3, F with 30,000 steps; each is 2 to 4 min of training and 4 min of gate. This sweep did not combine them.
4. Check on the loop itself: run 3 generations from `data/loop2/gen-0000` with these options; if the gate keeps rejecting, the plateau is the network or the data (then: LARGE network, more simulations per move, or the window), not the recipe.

### Limits

- **One start point, one data set:** all variants start from generation 0 and train on generations 1 to 3 of run 2; whether the effect holds for later generations (a larger window, a better parent) is not measured.
- **Selection bias:** F was the winner of eight noisy first-pass gates (se 0.018 to 0.021 each) and is optimistic until confirmed; the confirmation on 400 fresh pairs (0.5887) removes the selection from the estimate, but it is one data set, one seed of training (the same batches for every variant; the training seed was not varied, so a seed-to-seed spread of the networks is not part of the error bars), and H as the runner-up was chosen by the same first pass.
- **Not tried:** the number of steps between 4596 and 15,000 for the cosine, more than 15,000, value coefficient together with 15,000 steps, window composition (H of the plan, "weight the newest generation", needed new machinery and was replaced by the value coefficient as agreed), batch size, weight decay.
- **The strength step** has 100 pairs per rung and a rung scored near 0.88 (see [Loop run 1](#loop-run-1-measured-2026-11-10)): it does not resolve a difference of 20 to 40 Elo.
- Held-out proxies are on 2500 samples of generation 3 only. `results.jsonl` holds two BASE rows (the first run without a gate, then the one with it).

## As implemented (block B1-B5)

**TL;DR:** The device now runs the training forward pass with a per-layer activation store, the float losses and the backward pass of both heads (blocks B3 to B5), and the AdamW and SGD update with a finite check (B1), each against its CPU twin. `TornadoTrainer` runs forward, losses and head backward for a `TrainingBatch` in one task graph (`run(batch)` returns the `ReferenceTrainer.Result`); `DeviceOptimizer` adds the update tasks to that graph later. Everything is float32, deterministic (bit-identical runs) and free of atomic adds, and `classic1` is supported end to end: the policy prior, the value prior and the gradients of `prior.beta` and `prior.k`. Measured for SMALL `classic1`: **forward, losses and head backward take 6.6 ms for a batch of 1,024 (155,000 samples/s) and 2.3 ms for 256 (112,000 samples/s)**; the forward convolutions alone reach 2.8 TFLOP/s of matrix work including the batch upload. 29 new tests, 193 in the module, 0 skipped; 44 of 44 mutants killed. The convolution backward (B6, B7) and the whole step (B8) came in [block B6-B10](#as-implemented-block-b6-b10); a step is then about 17 ms for 1,024 samples by estimate, against about 290 ms for 256 samples on the CPU in [loop run 1](#loop-run-1-measured-2026-11-10) (3.5 steps/s).

Implemented, tested and measured 2026-11-10, JDK 25.0.3, Apple M3 Max, Metal, on AC power (78 %, not charging), no thermal or performance warning, load average 6 to 7 from other work: the timings are indicative.

| Class (module `nn-tornado`) | Content |
|---|---|
| `TornadoTrainer` | One task graph for a fixed batch size: `conv_in`, the block convolutions (the inference kernels of `KernelsHexConvLayer.addTask`, each output in its own slot of the store), the policy head (`OpsKernels.policyHead`), the prior, the training value head, the loss kernels and the head backward. `run(TrainingBatch)`, `reload()`, `gradient(name)`, `downloadParameters(Parameters)`, `close()`. Package-private: `activation(slot)`, `trunkGradient()`, `buffer(name)`, `download(array)`, the interface for B6. |
| `KernelsTrain` | The kernels: `policyPrior`, `valueHead` (stores pooled and hidden, adds the value prior), `policyLoss`, `valueLoss`, `valueHiddenGradient`, `valuePooledGradient`, `trunkGradient`, `denseGradient`, `policyWeightPartial` and `policyWeightReduce` (split-K), `priorGradientPartial` and `priorGradientReduce`. |
| `KernelsOptimizer` | `adamw`, `sgd` (one tensor, 16 elements per work item, finite flag per chunk) and `sumFlags`. |
| `DeviceOptimizer` | The state (m and v, or the velocity), the per-step scalars (`prepare(steps, lr)`: the bias corrections and 1 - beta in double, sent in an 11-float array), `addTasks(graph)` (one task per tensor plus the flag sum), `nonFinite()`, `loadState(Optimizer)` for a checkpoint, `firstState()` and `secondState()`. |
| `DeviceTensors` | One zero-filled `FloatArray` per tensor in `Parameters.shapes` order (gradients, optimiser state), `flatten()` and `unflatten()`. |
| `OpsParameters` (extended) | `declareHostReadback`, `arrays()` and `storeInto(Parameters)`: the parameters can be read back on demand. |
| `DeviceMath.notFinite` | The finite test on the exponent bits ([tornado](tornado.md#pitfalls), item 11). |

### Design as built

- **Input planes come from the host** (decision of this block): the replay buffer's `TrainingBatch` is uploaded as `float[N][122][planes]` plus the CSR moves, targets, weights (about 6.5 MB for 1,024 samples with 13 planes). Kernel 1 (expand) of the table is not written, and step B2 of the work plan is dropped.
- **Per-tensor device arrays, not one flat buffer.** The forward kernels read one array per tensor, so parameters, gradients and optimiser state are one `FloatArray` per tensor in the order of `Parameters.shapes`; "the flat order" is that order, and `DeviceTensors.flatten()` gives the file-order copy.
- **Activation store.** `1 + 2 B` arrays [N][122][C]: x_0, then t_i and u_i of each block; the residual of conv2 reads x_i from its slot and the output goes to u_i. Row 121 is 0 in every slot (the convolution kernel writes it). One batch of 1,024 for SMALL holds 9 arrays of 16 MB (144 MB).
- **Loss kernels** (kernels 3 and 4): one work item per sample loops over its moves in float (maximum, sum of exp, `Lp`, then `g_m = wp/N (p_m - pi_m)` into the row of `dPolicy` that the item zeroed first: moves that share a cell accumulate in one item, no atomics). `Lp` is written unweighted per sample, with `Lv`; the host forms the weighted sums, the KL and the finite test in double and returns the `ReferenceTrainer.Result`. A sample with `wp = 0`, no moves or a single move gets 0 and a zero gradient. The value backward is three small kernels (`dy` with the loss, `dz1`, `dpooled`).
- **Head backward** (kernels 5 and 10): `trunkGradient` writes dx_B = `dpooled / 121 + Wp^T dP` (row 121 = 0) for B6; the dense layers' weight and bias gradients are one work item per element summing over the batch in ascending order; the policy head weight (2 x C, 124,000 terms per element at batch 1,024) is split-K over 64 chunks of samples and a second kernel adds the chunks in order. The gradients of `prior.beta` and `prior.k` are per-sample terms plus a two-item ordered sum.
- **Update kernel** (kernel 11): a work item updates 16 consecutive elements of one tensor and writes one flag for the chunk (a gradient or an updated parameter is NaN or infinite); `sumFlags` adds the flags to 64 floats that come back each step. A bad element is flagged, **not skipped** (the parameters are undefined afterwards, as after `Optimizer.step` threw). The learning rate and the corrections change every step, so they travel in a small array that is sent before every execution; scalar kernel arguments are fixed at compile time.
- **Transfers per execution:** the batch and the 11 optimiser scalars in, the `2 N` per-sample losses and the 64 flag sums out. Parameters, gradients, state and every activation stay on the device; tests and `gradient(name)` read arrays on demand (`TornadoExecutionResult.transferToHost`).

### Tests

29 new tests, all skipped cleanly without the SDK (`TornadoAssumptions`; checked with `TORNADOVM_HOME` unset: the task is skipped with the module's message); 193 tests in `nn-tornado` pass, 0 skipped. TINY, SMALL, an odd channel count (5), 300 channels with 300 hidden units, plain, `classic1` and `classic1-planes`, two and three players, `Parameters.uniform` at the init bound (also 2 times it) and `Parameters.init`, batches of 6 to 256.

| Test class | Tests | What |
|---|---|---|
| `TornadoTrainerTest` | 15 | Per fixture (11 networks): every slot of the store, policy with prior, pooled, hidden, values against the reference forward pass (B3); per-sample losses, `dPolicy` and `dy` on the device's own logits against `ReferenceLoss`, and the batch result against `ReferenceTrainer` (B4); `dz1`, `dpooled`, dx_B and the gradients of the head tensors and priors against `ReferenceTrainer` (B5); not-degenerate data; edge samples (one move, `wp = 0`, `wv = 0`, no moves, moves on cells 0 and 120 that share cells); extreme logits (head weights times 500); NaN in a weight (the reference's exception; nothing read with zero weights); bit-identical repeated runs and a new trainer; `reload()`; misuse; a second batch does not leak; value head barriers (C = 1, H = 1024; C = 2, H = 512; C = 8, H = 1024; C = H = 1024; batch 256, four runs). |
| `DeviceOptimizerTest` | 10 | AdamW and SGD against `Optimizer` on 4 networks, 6 steps with different rates, from the same state and free-running; the sign-flip tolerance; learning rate 0; huge and tiny finite gradients; NaN and infinity in every tensor at its first, middle and last element; a NaN parameter and an overflow; bit-identical steps; a loaded checkpoint state before and after an execution; misuse. |
| `DeviceFiniteTest`, `DeviceExpLogTest`, `DeviceDoubleTest` | 1, 2, 1 | The finite test on the device and the alternatives that fail; the accuracy of Metal's `exp` and `log`; a `double` kernel is rejected with `TornadoDeviceFP64NotSupported`. |

### Tolerances as measured

Largest error as a fraction of the bound over all cases (bounds as in [Verification against Part A](#verification-against-part-a)):

| Output | Largest error / bound | Case |
|---|---|---|
| store slots x_0, t_i, u_i | 0.020 | SMALL `classic1`, `Parameters.init` |
| policy logits with the prior | 0.014 | SMALL, 3 players, `init` |
| pooled, hidden, values | 0.0005, 0.0026, 0.0036 | |
| per-sample `Lp`, `Lv` (device inputs) | 0.0003, 0.0001 | |
| `dPolicy` on the device's own logits | 0.0031 (0.27 with logits that differ by hundreds) | 3 players; extreme |
| `dy`, `dz1`, `dpooled` | 0.0002, 0.0022, 0.0017 | |
| dx_B | 0.0041 | TINY |
| batch loss, policy, value, KL (bound 1e-4 + 1e-3 abs) | 0.0003 | |
| gradients `head_policy.weight`, `dense1.weight`, `dense2.weight` | 0.016, 0.0026, 0.0026 | |
| gradients of the three biases | 0.35 (policy bias, see below), 0.0012, 0.0010 | |
| gradients `prior.beta`, `prior.k` | 0.0004, 0.0002 | |
| value head barrier shapes | 0.0054 | C = 1, H = 1024 |
| SGD update from the same state (bound 1e-6 abs + 1e-9) | 0.11 | |
| **AdamW update from the same state** | **1.13 of the bound of the document**, 0.11 of the bound below | one element |
| AdamW and SGD, 6 steps free-running | 0.040 of the bound below | |
| m, v, u (bound below) | 0.15, 0.007; 0.34 and 0.02 free-running | |

Three bounds of the table above had to change, each for a reason that the measurement shows:

- **AdamW update, same float gradient and state: `|gpu - ref| <= 1e-6 |ref| + 1e-9 + 1e-5 lr`.** The bound of the document is exceeded by up to 1.13 times for single elements (about 1.8e-8 against 1.7e-9, at lr 0.005). The cause is the float rounding of m, `m = b1 m + (1 - b1) g`, which is relative to the two terms; when they cancel, the result is small but its absolute error is not, and the step `m / (sqrt(v) + eps)` carries it as a fraction of lr. SGD stays within the document's bound (0.11). The device computes `1 - beta` in double on the host: `1f - 0.999f` is 1.3e-5 off and had put every AdamW step 5e-6 relative off.
- **State m, v, u: `1e-6 |ref| + 1e-9 + 2e-7 max |ref|`** (the max over the tensor), for the same reason: an element of the state that is the difference of two terms of the tensor's size has their rounding error.
- **Free-running steps:** parameters within `1e-6 |ref| + 1e-9 + 1e-3 (sum of lr so far)`: the sign-flip term of the document, scaled to the steps taken. In the sign-flip test (gradients from 1e-12 to 1e-1 plus a perturbation of 1e-4 of the largest) 32,702 of 351,000 elements flipped; the largest difference was 0.9993 of 2 lr, and the elements with `|g| >= 1e-3 max |g|` (12,688) stayed within 0.0045 of 1e-3 lr.
- **Gradient of `head_policy.bias`:** the exact value is 0 (the logit of a move is `f + t`, so a constant added to f or t changes nothing), the reference holds 1e-9 of rounding noise, and the document's bound `1e-4 max |ref|` would be 1e-13. The test scales the bound by 1e-2 of the sum of the absolute terms `|dP|` instead; the device value is a few ulps of the terms (1.8e-7 for terms of size 1).

### Mutation check

44 distinct mutants of the kernels and the host class, each put in alone and run against the three test classes: all 44 killed (the 41 of the first round in 5 to 7 seconds each, the barrier mutants with three runs each). Groups:

| Part | Mutants (all killed) |
|---|---|
| Priors | wrong sign of the "to" term of the policy prior; sign of the player-1 value prior; gradient of `prior.k` with the wrong sign; gradient of `prior.beta` without the distance scale |
| Value head | pooling divided by 122 (both paths, only the path with C < 256 is reached by most fixtures, the path with C >= 256 by `wide-300`); hidden stored at the wrong index; **the barrier after the pooling and the barrier after dense1 removed (survived all tests until `theValueHeadBarriersHoldUnderLoad` was added; 3 of 3 runs each killed since)**; the barrier after the partial sums removed |
| Policy loss | missing 1/N; wrong sign of the cross-entropy; the "to" gradient overwrites instead of adds; single move not special-cased; CSR end off by one; the `wp = 0` mask missing |
| Value loss and backward | wrong tanh derivative (`1 - v`); c_v missing; the `wv = 0` mask missing; the ReLU mask of dz1 missing; dense1 weight transposed |
| Head backward | pooling part of dx_B divided by 122; padding row of dx_B not zeroed; the "to" row of the policy head with the "from" weight; `denseGradient` transposed; split-K chunk size drops the remainder; last chunk not clamped; the reduction drops the last chunk |
| Optimiser | decay on biases (AdamW) and L2 on biases (SGD); no bias correction; sqrt missing; no momentum; the flags of all tensors share one base; the flag sum skips the first group; wrong exponent mask in `notFinite`; step count off by one; `1 - beta2` taken from beta1; loaded state never sent after an execution |
| Trainer | residual of conv2 from the wrong slot; value prior switched off; KL without the target entropy; policy weights uploaded from the value weights; c_v dropped in the batch loss |

Two tests exist because mutants survived or the measurement failed: batches above 64 samples (the split-K chunk logic holds several samples only then: `tiny2-plain-130`), and C = 300 with H = 300 (`wide-300`). Without the first, "chunk size drops the remainder" cannot show.

### Measurements

SMALL `classic1`, two players, batches from seeded random games (10,472 moves at 256, 42,208 at 1,024), `Parameters.uniform`; `:nn-tornado:trainerBenchmark`; median of 30 runs after 10 warm-up runs; the wall time is that of `run()`: batch copy and upload, all kernels, the loss copy-back and the host sums, without the profiler. The graph was cut to measure by difference.

| Batch | Convolutions only | + heads | + losses and head backward (whole `run`) | Samples/s |
|---|---|---|---|---|
| 256 | 1.55 ms (2.4 TFLOP/s) | 1.78 ms | **2.30 ms** | 111,500 |
| 1,024 | 5.36 ms (2.8 TFLOP/s) | 5.39 ms | **6.62 ms** | 154,700 |

- The "TFLOP/s" counts the matrix work of the 9 convolutions (3.73 GFLOP at 256, 14.93 GFLOP at 1,024) over the wall time of the cut graph, so it includes the batch upload; the kernel benchmark of [tornado](tornado.md#measured-kernel-speed) had 2.2 to 3 TFLOP/s for the best convolution kernel and the inference forward pass reached 1.7 TFLOP/s at batch 1,024 ([tornado](tornado.md#measured-speed), measured under load 8 to 18). The forward pass of the trainer is therefore as fast as the inference kernels allow; it has no extra transfer and no readback.
- The losses and the head backward cost 0.5 ms at 256 and 1.2 ms at 1,024 (19 % of the 6.6 ms). At 1,024 the profiler's per-task times add up to that: `policyWeightPartial` 0.71 ms, `policyLoss` 0.10 ms, `priorGradientPartial` 0.08 ms, `trunkGradient` 0.08 ms, the dense gradients 0.04 ms each, the rest below 0.03 ms. `policyWeightPartial` is the one kernel worth tuning: 4,224 work items with 1,936 terms each; more chunks (S = 256) would give more items with shorter loops.
- **The profiler's per-task numbers are not trustworthy below about 0.5 ms.** At 256 the nine convolutions show 0.56 to 0.59 ms each, 4.9 ms in all, against a wall time of 1.55 ms for the whole cut graph; at 1,024 they show 0.54 to 0.58 ms and add up to 4.7 ms of 5.4 ms. A fixed cost per task of about 0.5 ms is in these numbers ([tornado](tornado.md#measured-speed), finding 1). The differences between cut graphs are the honest figures.
- Estimate for the step: the convolution backward has as many multiply-adds as the forward pass twice over (input gradient and weight gradient, [How the numbers were computed](#how-the-numbers-were-computed)); at the measured 2.4 to 2.8 TFLOP/s that is about 10 ms at 1,024, so a step of 1,024 samples takes about 17 ms (60,000 samples/s), the CPU trainer of loop run 1 about 3.5 steps/s at 256 (900 samples/s). Not measured until B8.

### Deviations from the plan

- **No kernel 1, no flat device buffer** (see Design). Step B2 of the work plan is dropped; the "flat order" is the tensor order of `Parameters.shapes`.
- **The value head for training is a new kernel** (`KernelsTrain.valueHead`), a copy of `OpsKernels.valueHead` that also stores pooled and hidden and adds the value prior. `TornadoNetwork` is unchanged and **still refuses `classic1`** for inference: adding the priors to the inference value head would mean a prior argument in the shared kernel; the training copy shows how (about 6 lines), but the inference side was not needed for the loop's trainer.
- **The loss output is per sample and unweighted**; the weighted sums, KL and finite test of the loss are on the host (B floats per step).
- **Kernel 4 is five kernels** (`valueLoss`, `valueHiddenGradient`, `valuePooledGradient`, two `denseGradient` calls) instead of one work item per sample; the single-item version would have P H + H C sequential multiply-adds per item.
- **Gradients are overwritten, not accumulated**, so microbatches (the doc's kernel 9 adding into the buffer) are not available yet.
- **Capacity for moves:** the CSR arrays have a fixed size (`maxMoves`, default 256 per sample), because a task graph fixes array sizes; a batch with more moves is refused.
- **No skip of a bad step:** the update runs for every tensor and the flag says that the parameters are undefined.
- **Tolerances** changed as in "Tolerances as measured".

### Open problems for B6 and later

All of them were taken up in [block B6-B10](#as-implemented-block-b6-b10); the list stays as it was written.

- **B6, B7:** the gradient buffers of all convolution tensors exist (`gradients()`, written as 0 and never transferred: TornadoVM has no device copy of an array that no task uses, [tornado](tornado.md#pitfalls) item 12) and `trunkGradient()` is dx_B; the backward needs two more [N][122][C] buffers (dx and dt) and the permuted weights W'. Every backward kernel must write all its elements (the tests fill buffers with NaN to show a skipped element).
- **B8:** add `DeviceOptimizer.addTasks` to the trainer's graph behind the last backward kernel, send `prepare(step, lr)` before each `execute()`, check `nonFinite()`. The one-step and twenty-step comparisons with `ReferenceTrainer` and `Optimizer` need the conv gradients.
- **Gradient accumulation** over microbatches (add instead of overwrite) and the **mirror augmentation** stay on the host (`ReplayBuffer`), already applied in the batch.
- **B10 (loop integration):** `ReplayBuffer.batch` to `TrainingBatch` is on the host and uploads 6.5 MB per step (part of the 6.6 ms measured above); overlapping the next batch's build with the running step would hide it. `TornadoTrainer` takes the parameters of a `Parameters` and gives them back with `downloadParameters`; the optimiser state comes back with `DeviceOptimizer.firstState()` and `secondState()` after `transferToHost(stateArrays())` for a checkpoint.
- **Inference with `classic1`** on the device (MCTS) is not supported; the training kernels show what the value head needs.
- **Open question 8** is answered: a `double` kernel is rejected on this device and the float softmax meets the gradient bound.

## As implemented (block B6-B10)

**TL;DR:** The GPU trainer is complete and in the loop. The convolution backward (B6, B7), the whole step with the update in one task graph, gradient accumulation over microbatches and the finite check (B8) are on the device; `TornadoTrainer.step` is one optimiser step. SMALL `classic1` takes **5.2 ms per step at batch 256 (49,300 samples/s) and 16.9 ms at batch 1,024 (60,800 samples/s)**, against about 900 samples/s for the CPU trainer with 8 threads: 55 to 68 times faster, at 2.1 to 2.6 TFLOP/s of matrix work. The loop has `--trainer cpu|gpu` (B10); the GPU run of one generation of loop run 1 (4,936 steps, batch 256) took **42 s instead of 23 minutes**, with held-out proxies and a 100-pair gate that are not worse than the CPU candidate's. 203 tests in `nn-tornado` (10 new), 316 in `nn` (2 new), 231 in `selfplay` (1 skipped: `RawPolicyContractTest.strategyBeatsARandomPlayer`, skipped before this block) and 5 in `selfplay:gpuTest` (all new), 0 failures. Every gradient of the network agrees with `ReferenceTrainer` within the bounds of Part B (largest error 0.012 of the bound), two GPU runs give the same bits, and a run that is killed and resumed gives the same bits as one that was not. 26 of 26 mutants killed.

Implemented, tested and measured 2026-11-10, JDK 25.0.3, Apple M3 Max, Metal, on AC power (78 %, not charging), no thermal or performance warning (`pmset -g therm`), load average 3 to 7 from other work: the timings are indicative.

| Class | Content |
|---|---|
| `KernelsConvBackward` (`nn-tornado`) | `permuteWeight` (kernel 6: W' with the permutation pi), `weightPartial` (kernel 7, plain `@Parallel`, the oracle and the variant for tiny batches), `weightPartialSimdgroup` (kernel 7 on the Metal SIMD-group matrix units, with the bias column), `weightReduce` (kernel 9, fixed chunk order, replace or add), `maskGradient` and `maskInto` (the ReLU rule with the stored activation). The input gradient (kernel 8) is the forward convolution kernel of `KernelsHexConv` with W'. |
| `TornadoTrainer` (extended) | `addConvBackward`; the constructor `TornadoTrainer(parameters, optimizer, batch, microbatch, valueCoefficient, device)`; `step(batch, lr)`, `sync()`, `steps()`; implements `nn.StepTrainer`. `run(batch)` still computes the gradients without the update. |
| `KernelsTrain`, `KernelsOptimizer`, `DeviceOptimizer` (extended) | The loss kernels divide by the size of the whole batch (`total`); the gradient kernels take a `control` array and add instead of overwrite (`accumulate`); the update kernels read an update switch (`HYPER_APPLY`), 0 for every microbatch but the last; `DeviceOptimizer.prepare(steps, lr, apply)` and `storeInto(Optimizer)`. |
| `GpuTrainers` (`nn-tornado`) | The entry for modules that compile without the TornadoVM API: `unavailableReason()`, `description()`, `microbatchFor(batch)`, `create(...)`. |
| `StepTrainer` (`nn`) | The seam of `TrainingRun`: `step(batch, lr)`, `sync()`, `close()`; `StepTrainer.cpu(...)` is the CPU reference trainer and the optimiser. `TrainingRun` has a constructor that takes one and calls `sync()` before every checkpoint; `Optimizer.completeSteps` sets the step count of the host optimiser. |
| `Loop`, `LoopConfig`, `Gen0` (`selfplay`) | `--trainer cpu|gpu`; the trainer kind in `run.json`; `scripts/loop.sh` and `scripts/gen0.sh` add the TornadoVM flags for `--trainer gpu`. `GpuCheck` (test sources) re-trains one generation of a finished run with either trainer. |

### Design as built

- **Backward order and buffers.** Per block, from the last: dpre = dx_{i+1} masked with u_i (into `dpre`, out of place: the first mask must not destroy dx_B, which the tests read, and a network without blocks has dx_B = dx_0); dW and db of conv2 from dpre and t_i; dt = forward convolution of dpre with W'_2 (zero bias); dz_1 = dt masked with t_i, in place; dW and db of conv1 from dz_1 and x_i; dx_i = forward convolution of dz_1 with W'_1 plus dpre (the residual argument of the forward kernel), into `dxNext`, which the next block reads as dx_{i+1}. Then `conv_in`: mask with x_0, dW and db from the input planes, no input gradient. Four extra [N][122][C] buffers (`dpre`, `dxNext`, `dt`, and `dx` of the heads), and one W' of 7 C^2 floats and one partial buffer that all convolutions share (the tasks of a graph run in order).
- **Input gradient.** The forward kernels need no change: `KernelsHexConvLayer.addTask` with the gradient as input, W' as weight, a zero bias of C floats, and the same variant as the forward pass of that batch size (naive up to batch times channels of 128, else the SIMD-group kernel). Row 121 of dz is 0 in every buffer (the mask and the convolution write it), which is what the gather form needs for neighbours off the board.
- **Weight gradient.** Split-K over the rows of the batch (batch times 122, so a chunk may start in the middle of a sample): `S = 32` chunks (`-Dsternhalma.tornado.convSplits`), each chunk a matrix product dz^T [C_out x rows] times the gathered input [rows x 7 C_in] for its rows. The plain kernel has one work item per (chunk, o, j) and loops over its rows; the SIMD-group kernel has a work group per (chunk, tile of 32 channels, tile of 32 columns) and per step of 32 rows gathers a 32 x 32 tile of dz and one of the input into local memory and multiplies 2 x 2 blocks of 8 x 8 per SIMD group (dz read transposed). The bias column is summed in the same kernel: the groups of the first column tile add the dz values they load (every item its own, four items per channel, added in a fixed order). `weightReduce` adds the chunks in ascending order. Nothing uses an atomic add; the order of the sums is fixed, so two runs give the same bits.
- **One graph for a step.** Forward, losses, head backward, convolution backward and the update (`DeviceOptimizer.addTasks`) are one task graph of about 90 tasks for SMALL. Per execution the batch and the optimiser scalars go in and the 2 N losses and the 64 flag sums come back. The learning rate, the Adam step count and the bias corrections of a step are computed in double on the host (`DeviceOptimizer.prepare`) and travel in the `hyper` array, as in B1.
- **Microbatches.** A batch of N samples is cut into N / M consecutive microbatches (`TrainingBatch.select`), each one execution of a graph built for M samples. The loss kernels divide by N, not by M, the host sums the terms of the microbatches, the gradient kernels replace (`control[0] = 0`) for the first and add for the others, and the update switch is 1 for the last only, so that the update runs in the same execution as the last backward pass. `GpuTrainers.microbatchFor` takes the whole batch up to 2,048 samples and else the largest divisor that is at most 2,048.
- **The finite flag.** The update runs for every tensor and flags a chunk whose gradient or new parameter is NaN or infinite; it does not skip the update (B1). `step` therefore reads the flag after the last execution and throws `IllegalStateException` ("the update ran, the parameters are undefined"). A NaN loss throws as before. `Loop` catches the exception, prints "stopped" and the reason, and returns the error code 1 with the newest checkpoint as the last sound state; `TrainingRun` writes no checkpoint of the bad step.
- **Host copy and checkpoints.** `TornadoTrainer` takes the `Parameters` and the `Optimizer` of the run, copies them to the device (the optimiser state with `DeviceOptimizer.loadState`, so a trainer built from a checkpoint continues exactly) and keeps its own step count; `Optimizer.steps()` of the host follows it. `sync()` transfers parameters and state back and writes them into the host objects, and `TrainingRun` calls it before every checkpoint and the observers: the checkpoint is the usual `Checkpoint` format, from the device state, and the proxies of the loop evaluate the synchronised parameters. It costs 1.3 ms at batch 256 (the 1.4 MB of parameters and 2.8 MB of state of SMALL), once per checkpoint.
- **The trainer is part of the configuration.** The GPU's float sums differ from the CPU's double sums, so a GPU run and a CPU run give different networks (each is deterministic). `run.json` therefore names `"trainer":"gpu"` for a GPU run and nothing for a CPU run, so that the hash of every run that existed before is unchanged, and a run directory is refused with the other trainer ("holds another configuration"), like any other option. `--trainer gpu` without a usable device prints the reason (for example the missing TornadoVM flags) and trains on the CPU, and then `run.json` says CPU: the trainer that ran is the one that is recorded. A run that `run.json` calls GPU is not continued on the CPU.
- **Not done: overlapping the host.** The host builds the batch (2.3 ms for 256 samples) and then the device runs the step (5.2 ms); a second thread that builds the next batch while the device runs would hide the first (1.5 times as many steps). A generation takes 42 s of training in about 30 minutes, so the gain is 15 s per generation; the code (one more thread and a hand-over of the batch, in `TrainingRun`) is not worth it yet.

### Tests

203 tests in `nn-tornado` (10 new), 316 in `nn` (2 new), 231 in `selfplay` (1 skipped: `RawPolicyContractTest.strategyBeatsARandomPlayer`, skipped before this block) and 5 in `selfplay:gpuTest` (all new), 0 failures. All GPU tests skip cleanly without the SDK or a device (`TornadoAssumptions`; `gpuTest` is skipped without the SDK and the plain `test` of `selfplay` excludes `*Gpu*`).

| Test | What |
|---|---|
| `TornadoTrainerTest.convolutionGradientsMatchTheReferenceTrainer` | Weight and bias gradients of every convolution (`conv_in`, 2 per block) against `ReferenceTrainer` for the 11 fixtures of B3 to B5: TINY, SMALL, odd channels, 300 channels, a network without blocks, plain and classic1, 2 and 3 players, init and uniform weights, batches of 6 to 130 (both variants of the convolution kernels, the chunks of the split-K hold several samples above batch 64). The Parameters.init classic1 case must be exactly 0. |
| `TornadoStepTest.oneStepFromTheSameStateAfterOneStep` | Four steps; before each, the device trainer is built from the CPU's parameters and optimiser state (m, v and the step count from the second step on), the learning rate doubles from step to step, and both take the same batch: parameters, m, v, step count and the loss terms agree. |
| `twentyStepsOnTheSameBatchesGiveTheSameLosses` | 20 free-running steps on four batches, 5 networks: the losses agree within 1 % at every step, and decrease. |
| `microbatchesAddUpToTheWholeBatch`, `accumulatedConvolutionGradientsAgreeWithOnePass` | A batch of 64 as 1, 2 and 4 microbatches (48 as 3): same loss, parameters as the CPU's, all convolution gradients of the accumulated sums against the reference; misuse (batch not a multiple, wrong size, `run` on a microbatch trainer). |
| `stepsAreBitIdentical`, `resumingFromACheckpointIsBitIdentical` | Two device runs of 3 steps give the same parameters and state, bit for bit, on 5 networks; 2 steps, `sync`, `Checkpoint.write`, `Checkpoint.read`, a new trainer, 3 steps give the bits of 5 uninterrupted steps. |
| `aNonFiniteStepThrowsAfterTheUpdateRan`, `aTrainingRunWithTheDeviceTrainerStopsOnTheFiniteFlagAndCheckpointsTheDeviceState` | A NaN weight with a batch of zero weights gives a finite loss and NaN gradients: only the flag sees it; `step` throws and the parameters on the device are NaN afterwards (the update ran). `TrainingRun` ends with the exception and no checkpoint; a sound run gives checkpoints that equal the host copy and end close to the CPU run. `runDoesNotUpdateAndStepNeedsAnOptimizer`. |
| `twoMovesAndCornerCellsOfThePrior` | The follow-ups of the B1 to B5 review: a sample with exactly two moves (`TrainTesting.edgeBatch` has one now, as sample 5), and `prior.beta` with the whole gradient on cell 0, 60 or 120. |
| `StepTrainerTest` (`nn`) | The run syncs before every checkpoint and once at the end and not otherwise; the CPU trainer through the seam equals the run's default; an exception of a step ends the run with the earlier checkpoints only. |
| `LoopGpuTest` (`selfplay`, task `gpuTest`) | The loop on a TINY network with `--trainer gpu`: `run.json` names the trainer, two runs give the same bits, an abort during the training resumes at the newest checkpoint with the bits of an uninterrupted run, the other trainer is refused, a JVM without the TornadoVM flags falls back with the message and records CPU, and **real processes**: SIGKILL (exit 137) after two checkpoints, a restart that is stopped with SIGTERM, and a last restart end with the results of an uninterrupted run. |

### Tolerances as measured

Largest error as a fraction of the bound over all cases (bounds as in [Verification against Part A](#verification-against-part-a)).

| Output | Largest error / bound | Case |
|---|---|---|
| weight and bias gradients of all convolutions | **0.0115** (0.0027 TINY plain, 0.0079 TINY classic1, 0.0034 SMALL classic1, 0.0115 SMALL plain `init`, 0.0042 300 channels, 0.0053 batch 130) | `small3-plain-init` |
| parameters after one step from the same state, elements with a gradient of at least 1e-3 of the tensor's largest (bound 0.01 lr) | **0.007** of 0.01 lr, steps 1 to 4 at lr 1e-3 to 8e-3 | SMALL plain, 3 players |
| the same, the bound for every element (2 lr, the sign of a near-zero gradient) | holds for all | |
| m and v after one step from the same state | within `1e-6 abs + 1e-9 + 2e-7 max + 2e-3 abs + 1e-4 max` (the first terms of B1, plus the gradient's own error of 1e-3) | all |
| losses of 20 free-running steps | 0.027 of 1 % (2.7e-4 relative) | SMALL plain, 3 players |
| loss and parameters of a step in microbatches of 64, 32, 16 | loss identical to the double, parameters 0.001 of 0.01 lr | TINY classic1 |
| 12 steps through `TrainingRun`, parameters against the CPU run | largest difference 5.7e-3 for lr up to 1e-3 (12 lr = 12e-3; the sign of near-zero gradients) | TINY plain |

Two bounds of the table in "Verification against Part A" needed a change, each for a reason that the measurement shows:

- **`head_policy.bias` in the one-step comparison.** Its exact gradient is 0 (a constant added to every logit changes nothing) and both sides hold rounding noise; an Adam step follows the sign of a noise gradient, so the parameter differs by up to 2 lr (measured 0.7 lr) and m differs by 1e-8. The test applies the 2 lr bound and leaves the bias out of the tight comparison and of the state comparison.
- **The one-step bound of the document (2 lr for every element, 1e-3 lr for the large ones) is tightened to 0.01 lr** for the elements with a gradient of at least 1e-3 of the tensor's largest, because the measurement is 0.007 of that, and a stale or wrong learning rate (a mutant) is a multiple of it.

### Mutation check

26 distinct mutants of the new kernels and host code, each put in alone and run against `TornadoStepTest`, `TornadoTrainerTest` and `DeviceOptimizerTest` (the host mutants of `TrainingRun` against `StepTrainerTest`, the one of the loop against `LoopGpuTest`):

| Part | Mutants (all killed) |
|---|---|
| Input gradient | W' built without the permutation pi; W' with the channel indices swapped; the input gradient with W instead of W'; the residual path dx_i = din + dpre dropped |
| Masks | the ReLU mask of dz missing (in place); the ReLU mask of dpre missing; the mask of dz_1 with the activation of the wrong slot |
| Weight gradient (split-K) | the chunk size drops the remainder (plain kernel; SIMD-group kernel); the reduction drops the last chunk; the bias column drops one of its four partial sums |
| Accumulation | `accumulate` overwrites; the flag inverted; the flag never sent; every microbatch takes the first slice; the loss divided by the microbatch instead of the batch |
| Step and update | a constant learning rate instead of the step's; the Adam step count off by one; the update runs after every microbatch (host switch); the update kernel ignores its switch; the finite flag ignored |
| Resume and host copy | the second moment of a loaded checkpoint taken from m; the state never loaded into the trainer; `sync` leaves the state on the device; `TrainingRun` does not sync before a checkpoint |
| Loop | `run.json` does not name the GPU trainer |

Each is killed by several tests (the weight and input gradient mutants by `convolutionGradientsMatchTheReferenceTrainer` and the step tests, the accumulation ones by `microbatchesAddUpToTheWholeBatch`, the learning-rate and step-count ones by `oneStepFromTheSameStateAfterOneStep` and `twentyStepsOnTheSameBatches...`, the resume ones by `resumingFromACheckpointIsBitIdentical`, the flag one by `aNonFiniteStepThrowsAfterTheUpdateRan`). **Not covered by a test that would catch a mutant:** `MAX_MICROBATCH` (batches above 2,048 are cut by `GpuTrainers.microbatchFor`; the cutting itself is tested with 64 in 16s), the cut graphs of the benchmark, `GpuCheck`, and `Gen0 --trainer gpu` (see above).

### Measurements

SMALL `classic1`, two players, batches from seeded random games (10,472 moves at 256, 21,383 at 512, 42,208 at 1,024, 83,639 at 2,048), `Parameters.uniform`, AdamW; `:nn-tornado:trainerBenchmark --args='256 512 1024 2048'`; median of 30 runs after 10 warm-up runs; the wall time is that of `TornadoTrainer.step`: the copy of the batch into the device arrays, the one execution (batch transfer, all kernels, update, the loss and flag copy-back) and the host sums, without the profiler. The CPU figure is that of [loop run 1](#loop-run-1-measured-2026-11-10): 3.6 steps/s at batch 256 with 8 threads, about 900 samples/s (it does not change much with the batch size).

| Batch | Step | Samples/s | Steps/s | Matrix work | Against the CPU | Device arrays |
|---|---|---|---|---|---|---|
| 256 | **5.20 ms** | **49,300** | 192 | 2,120 GFLOP/s (11.0 GFLOP) | 55 times | 56 MB |
| 512 | 8.96 ms | 57,200 | 112 | 2,460 GFLOP/s | 64 times | 111 MB |
| 1,024 | **16.85 ms** | **60,800** | 59 | 2,615 GFLOP/s (44.1 GFLOP) | 68 times | 219 MB |
| 2,048 | 33.63 ms | 60,900 | 30 | 2,620 GFLOP/s | 68 times | 437 MB |

"Matrix work" counts the multiply-adds of the nine convolutions of the forward pass, the weight gradient and the input gradient without `conv_in` (2 FLOP each, [How the numbers were computed](#how-the-numbers-were-computed)); the heads, losses and the update are not in it. The step reaches its speed at batch 1,024 and does not gain above it. The memory is the sum of the device arrays of the trainer (parameters, gradients, state, the store of 9 activations, the backward buffers and the batch); the Metal device has 51.8 GB, so nothing limits the batch. The numbers of the first version, with the plain weight-gradient kernel, were 64 ms and 16,000 samples/s at batch 1,024.

**Where the time goes**, by difference of cut graphs (`run`, no update; the smallest batch is noisy to 0.5 ms, "heads" at 256 came out as -0.4 ms), and by the profiler's kernel times, which add up to more than the wall time (the profiler lengthens every task, [tornado](tornado.md#measured-speed) finding 1):

| Part | 512 | 1,024 | 2,048 | Profiler at 2,048 and the rate of the matrix work there |
|---|---|---|---|---|
| forward convolutions (9) | 2.81 ms | 5.21 ms | 9.98 ms | 9.4 ms, 3.2 TFLOP/s |
| heads (policy head, prior, value head) | 0.04 | 0.12 | 0.19 | below 0.1 each |
| losses and head backward (13 kernels) | 0.86 | 1.14 | 2.86 | `policyWeightPartial` 0.7 ms at 1,024 is the largest |
| convolution backward (masks, W', 9 weight gradients, 8 input gradients) | 4.93 | 10.05 | 20.16 | weight gradient 9.0 ms (3.3 TFLOP/s), input gradient 8.8 ms (3.2), masks 2.1, permute and reduce 0.1 |
| update (AdamW, 26 tensors, the flag sum), `step` minus the cut graph | 0.3 | 0.3 | 0.4 | 0.02 |

At batch 256 the profiler's numbers are the floor of about 0.5 ms per task (nine convolutions show 4.9 ms against a cut graph of 1.6 to 2.2 ms), and the whole graph of about 90 tasks runs in 5.2 ms: a task costs well under 0.1 ms when its kernel is small. All three convolution groups run at 3.0 to 3.3 TFLOP/s of matrix work inside the graph at 1,024 and 2,048. **The host side of `step`** is small: the copy of the batch into the device arrays is 0.04 ms at 256 and 0.28 ms at 2,048, the loss sums 0.01 ms, `sync()` (parameters and state to the host, once per checkpoint) 1.2 ms at 256 and 0.4 ms at 2,048. **Building the batch from the replay buffer** is the host cost that counts: 2.3 ms per batch of 256 on one thread (`GpuCheck`), which is 31 % of the 7.5 ms of host and device together, and in the real check below the step took 8.5 ms (118 steps/s) where the device alone takes 5.2 ms (192 steps/s). Building the next batch on a second thread while the device runs the current step would give at most 5.5 ms per step, about 1.5 times as many steps; it is not done (see Open problems).

**Power and thermal state** (`pmset`, before and after the benchmark and the real check): AC power, 78 % and not charging, "No thermal warning level has been recorded", "No performance warning level has been recorded"; the runs are a few minutes each. No power figure in watts: `powermetrics` needs root. The load average of the machine was 4 to 6 from other work, so the timings are indicative.

### Loop integration

`Loop run RUN --init NETWORK ... --trainer gpu` trains the candidate of every generation on the device: the parameters from the best network, a fresh optimiser, the batches from the `ReplayBuffer` exactly as for the CPU (the same sampler, the same mirror flags, `TrainingBatch`), the checkpoints in the `Checkpoint` format every `--checkpoint-steps` steps (and every `--checkpoint-seconds`), the proxies, the ETA and the monitor categories unchanged, and the steps per second in the progress line of `TrainingRun` (`gen 1 train: 32/32 steps  15.2 steps/s ...`). One line says what trains: `gen 1 train: GPU trainer on Apple M3 Max (METAL), batch 64 in executions of 64`. `--microbatch` is the CPU's option; the device takes the whole batch up to 2,048 samples. A resume from a checkpoint works for GPU runs (the state is in the file and `DeviceOptimizer.loadState` sends it), and the abort and restart behaviour is that of the loop: SIGINT 130, SIGTERM 143, SIGKILL leaves a run that continues from the newest checkpoint that reads. The TornadoVM flags come from `scripts/loop.sh` (the SDK's `tornado-argfile`), or from Gradle (`gpuTest`, `gpuCheck`). **GPU runs are deterministic per run but not bit-identical to CPU runs**, so the trainer kind is part of `run.json` and its hash (above). **Gen0** has the same option (`Gen0 run ... --trainer gpu`), a change of the same size: the config field, the fallback and the `StepTrainer`; it is not tested beyond compiling and the shared code paths (no Gen0 run on the device was made).

### The real check

One generation of loop run 1, trained again on the device (`GpuCheck`, `./gradlew :selfplay:gpuCheck`): generation 10, which trained the candidate that the loop wrote from the candidate of generation 8 (the best network then, `parent` in its metadata) on the replay window of generations 8 to 10 (943,675 samples, 315,898 of them new, 34,886 games' worth held out), with the loop's configuration (`run.json` of `data/loop1`: AdamW, lr 3e-4, warm-up 100, batch 256, reuse 4, mirror augmentation) and its 4,936 steps. The start network is the candidate of generation 8, not the one named in the task (`gen-0010`, which is the CPU result of the generation): the task's start would train a network that already saw this window. The loop data was only read; the output is in `data/loopgpu1`.

| Held-out proxies, 2,500 of the 34,886 samples of the 10 % of the games that the loop holds out (`HeldOutMetrics`, as in the loop's lines) | policy KL | top-1 | value loss | winner |
|---|---|---|---|---|
| start network (candidate of generation 8) | 0.3907 | 77.40 % | 0.4648 | 81.23 % of 2,472 |
| CPU candidate of the loop (4,936 steps, 22.9 minutes) | 0.3985 | 76.07 % | 0.4740 | 80.78 % |
| **GPU candidate** (4,936 steps, **41.8 s**) | **0.3985** | **76.48 %** | **0.4685** | **81.03 %** |

- **Time:** 4,936 steps in 41.8 s = 118 steps/s = 30,200 samples/s inside the loop's `TrainingRun` (the batches come from the replay buffer; 5 ms of every 8.5 ms are the device), against 22.9 minutes (3.6 steps/s) for the CPU: **0.7 minutes against 23 minutes per generation, 33 times**; 10 checkpoints with the copy of parameters and state cost 2 to 5 ms each. A generation of loop run 1 took 52 minutes (8 h 45 min for ten), 43.5 % of it training; with the trainer on the device a generation takes about 30 minutes, 2 % of it training, and self-play is the larger share. Two runs of the check gave the same proxies to all printed digits.
- **Proxies:** the GPU candidate is equal to the CPU candidate in policy KL (0.3985 both), 0.4 points better in top-1 and 0.005 better in value loss; both are a little worse than their start network on these held-out games, as the loop's own lines show for this generation. One run each: the difference between the two is not claimed to be more than the difference of two trainers.
- **Gate** (`mctsMatch`, the loop's evaluation player `mcts:sims=100,batch=1,cpuct=1.0,noise=0,temp=0,race=1`, GPU candidate against the CPU candidate, 100 pairs, seed 3001, 83 s): **score 0.518 +- 0.027, Elo +12 +- 36 (95 %), 101 wins, 5 draws, 94 losses**. Not worse; equal within the noise, as expected for two trainers that differ in rounding.
- Through the script of the loop (`scripts/loop.sh run ... --trainer gpu`, the SMALL network of generation 0, 8 games, batch 64) the generation trains on `Apple M3 Max (METAL)` (15 steps/s including the kernel compilation), writes the candidate and plays the gate; `LoopGpuTest` runs the same on TINY.

### Deviations from the plan

- **Kernel 8 is the forward kernel** and kernel 6 only builds W' (the plan's table said so); **kernel 9 reduces the bias, too**, in the same pass as the weights (one extra column of the partial buffer), and for the SIMD-group variant the bias column is summed inside kernel 7 (a separate kernel with one work item per (chunk, channel) cost 1.1 ms per convolution, 10 ms of a 27 ms step, because its reads are strided).
- **The tiled version was needed.** The plain `@Parallel` kernel of the weight gradient took 51 of 64 ms at batch 1,024; the SIMD-group version takes 4.7 ms (11 times faster) and the step 16.8 ms. The `tiled` (non-SIMD-group) variant of the forward kernels is not used for the weight gradient; the plain kernel stays for batches whose forward pass uses the naive kernel and as the oracle.
- **Microbatches by cutting the batch on the host** (`select`), one execution each, instead of one execution with the cut inside the graph: the graph is built for the microbatch size.
- **`TornadoTrainer` implements `StepTrainer`** (a seam in `nn`) so that `TrainingRun` serves both trainers; the loop's CPU path goes through `StepTrainer.cpu` and is bit-identical to before (`TrainingRunTest`, `LoopTest` unchanged).
- **The start of the real check** is the parent of the generation, as above.
- **`ALL_STAGES` of the benchmark cuts** now includes the convolution backward; `HEADS_BACKWARD` is the old value.

### Open problems

- **Overlap of the host batch build and the device step** (see above): about 1.5 times, and 15 s per generation.
- **LARGE is not measured.** By the flop count (251 MFLOP per sample) and the rate of 2.6 TFLOP/s, about 10,000 samples/s at batch 1,024; an estimate, not a measurement. The activations would be 401 MB per 1,000 samples (6.4 GB at 1,024): memory is no problem on this device, but the kernels with 64 channels (two tiles of channels per group) were run in the tests only with 300.
- **Fusing** the masks (9 tasks, 0.8 ms at 1,024) and the permutation into the neighbouring kernels would save about 5 %. The kernel rate is 3 TFLOP/s of about 14 to 16 peak.
- **Capacity for moves:** a batch with more than 256 moves per sample on average (`DEFAULT_MOVES_PER_SAMPLE`) is refused with `IllegalArgumentException` and the loop stops with a stack trace; the replay data of loop run 1 has 82 on average (10,472 moves in a batch of 256).
- **`Gen0 --trainer gpu`** is wired and compiles but has not been run; `GpuCheck` covers the same code path for one generation of the loop.
- **Inference with `classic1`** on the device (MCTS) is still not supported, so self-play stays on the CPU.
- **An aborted GPU run** stops after the current step like the CPU one; a step takes 5 ms against 280 ms, so the stop is faster.

## Loop run 3 (measured 2026-11-14)

**TL;DR:** The learning-rate decay of sweep 1 (`--lr 3e-4 --lr-final 1e-5 --reuse 20`, about 15,000 steps per generation on the GPU trainer) lifts the loop out of the stall of run 2. `data/loop3` ran 14 generations of 2500 self-play games at 200 simulations in 10 h 34 min (promotion needs `score - 1.28 se > 0.5` over 200 pairs; start network = loop 1's best network; the 7500 rejected games of run 2 serve as the generation-0 window via `--teacher`). Eight generations were promoted (1, 2, 3, 5, 8, 9, 12, 13), six rejected (4, 6, 7, 10, 11, 14); the best network is generation 13. Measured with 200 pairs against `BEST_SPEC`, the network gains about +90 Elo at 102,400 nodes from generation 0 to its best generations (+211 +- 35 to +354 +- 54 for generation 9) and then stays on that level; the gate gains of the promoted generations shrink from +74 Elo (generation 1) to +15 to +35 Elo and the later promotions are not visible against the classical rungs.

| Generation (best network) | vs 6400 nodes | vs 25,600 nodes | vs 102,400 nodes |
|---|---|---|---|
| 0 (start) | +511 +- 80 | +363 +- 52 | +211 +- 35 |
| 5 | +541 +- 89 | +430 +- 63 | +288 +- 44 |
| 9 | +590 +- 105 | +498 +- 77 | +354 +- 54 |
| 13 (final) | +564 +- 96 | +490 +- 75 | +298 +- 46 |

200 pairs per rung, seed 5002, MCTS at 200 simulations, `scripts/loop.sh strength data/loop3 --generation N --ladder 6400,25600,102400 --strength-pairs 200 --strength-seed 5002`. The in-loop records (100 pairs, seed 3001) read +173 (generation 0), +246 (3), +312 (5), +246 (9), +266 (12) against 102,400 nodes; the two series agree within their intervals, but neither separates generation 9 from generation 13.

Gate scores against the previous best network (200 pairs): 0.605 (generation 1), 0.550, 0.522, 0.517 (rejected), 0.545, 0.508 (rejected), 0.511 (rejected), 0.551, 0.524, 0.483 (rejected), 0.518 (rejected), 0.539, 0.524, 0.490 (rejected, generation 14).

**Findings**
- **Constant learning rate was the cause of the stall in run 2** (three rejections at 0.509, 0.491, 0.496 with lr 3e-4 constant, reuse 6). Sweep 1 isolated the decay: the same data, 15,000 steps constant gave 0.481, with cosine decay 0.563 (confirmed 0.589 over 400 pairs).
- **The gain per generation falls quickly.** Rejections cluster from generation 4 on and the promotions of the last six generations are one to two standard errors above 0.5 (z between 1.3 and 2.8); the strength against `BEST_SPEC` is flat within the intervals from generation 9 on. For this configuration (SMALL network, 200 simulations, window 1.5 M samples) the recipe is exhausted after about 8 to 9 generations.
- **Time:** about 31 to 37 minutes of self-play, 2 minutes of GPU training (15,000 steps at 117 to 128 steps/s), 5 to 6.5 minutes of gate, and in the promoted generations 2 minutes of baseline matches. The self-play rate varied between 2150 and 2520 simulations per CPU second without an identified cause (no thermal warning, the owner saw no throttling; competing processes such as the window server were present). Power: the owner replaced the 60 W adapter by a 100 W adapter during the run; the battery went from 80 % to 77 % under full load.
- **Not tried:** more simulations per move, a larger network (the LARGE preset needed GPU inference for `classic1` in the MCTS; it exists since [block G1](#as-implemented-block-g1)), a learning-rate schedule across generations, a smaller promotion margin, and the effect of the window size.

## As implemented (block G1)

**TL;DR:** The self-play of the loop can take its evaluations from the GPU: `--evaluator gpu` runs `--games-in-flight` games (default 256) at the same time, each with its own tree and batch-1 simulations (no virtual loss, so no cost in strength), and one device thread computes the positions of all games in batches (`PooledEvaluator`, module `nn`). `TornadoNetwork` now runs `classic1` networks (the host adds the priors, [tornado](tornado.md#residual-priors-on-the-host)), which is the precondition for a LARGE network in the loop. A game is the same code as on the CPU and gives the same moves for any number of games in flight, any thread count and any batch composition (tested bit for bit with a CPU backend; the device returns the same bits for a position in any slot and bucket). Strength neutrality: GPU subject against CPU opponent, same network, 100 pairs at 100 simulations: score 0.503 +- 0.032 (Elo +2 +- 43); with an independent seed stream 0.449 +- 0.033, which the CPU-against-CPU control reproduces game for game, and 0.483 +- 0.034 for another stream. **Speed, on a machine whose GPU another job used at the same time: 0.92 to 1.85 games/s against 0.75 on the CPU path for SMALL (200 simulations, 8 compute threads; 1.2 to 2.5 times), 0.10 against 0.06 for LARGE (1.8 times). The device thread is the bottleneck (93 to 100 % of its time is the kernel execution, the host part is about 1 to 2 %), so the 3 to 4 times of the plan were not reached here; see [Bottleneck](#bottleneck-g1).** The evaluator and its options are not part of `run.json`.

### What was built

| Class | Module | Content |
|---|---|---|
| `TornadoNetwork` | nn-tornado | `classic1` support: the value head kernel has a scalar `applyTanh` (0 with priors), `forward` adds the priors on the host. |
| `PooledEvaluator` | nn | One device thread, any number of models (networks), each with cohorts. A game thread calls `Client.forward` like any `ForwardPass`; the call returns when its batch is computed. `warmup`, `abort`, `close`, `stats`. Backends are called by the device thread only (a `TornadoNetwork` is not thread-safe) and closed on it. |
| `GpuEvaluators` | nn-tornado | The entry without TornadoVM types in its signatures: `unavailableReason`, `network`, `open` (a session: the evaluator, one model, every plan warmed up). |
| `LoopGamesJob.Device`, `EvaluatorOptions`, `Loop` | selfplay | `--evaluator cpu\|gpu`, `--games-in-flight N`, `--eval-cohorts K`; falls back to the CPU with a message ("loop: --evaluator gpu: ...; playing the self-play games on the CPU instead") where no device is usable. `scripts/loop.sh` passes the TornadoVM flags for it. |
| `LoopSelfPlayBench`, `MctsBench --stub`, `MctsMatch --evaluator gpu`, `GpuFeedBenchmark` | selfplay, nn-tornado | The measurements below (Gradle tasks `selfplayBench`, `mctsBench`, `mctsMatch`, `gpuFeed`). |

**Design deviation from the plan (project reasoning).** The plan was rounds with a parallel phase A (select, expand, planes into slot g), one evaluator thread and a parallel phase C (backup). Implemented instead: every game is its own thread (virtual) that calls a blocking `ForwardPass`; phase A, the wait and phase C are then simply the code of the game. Reasons: (1) the whole game code stays as it is (`LoopGame`, `Mcts`, adjudication, race play, openings), so the samples cannot differ from the CPU path by anything but the forward pass; (2) the determinism needs no argument: a game depends on its seed and on the evaluation of each of its own positions only; (3) there is no second implementation of the search to keep equal to the first. The cost is one `Encoder.encode` into a per-call array and a 6 KB copy into the slot instead of writing the planes into the slot (copy in is 0.3 to 2 % of the device thread, below). The two cohorts ping-pong as planned: the CPU fills one while the device computes the other.

**Dispatch.** A cohort is computed when all its running games have published their position (a full batch), or the device thread is idle and nothing arrived for 250 microseconds, or the oldest position has waited 20 ms. Games that are not running (not between `begin` and `end`) are not waited for: the end of a generation, with fewer games than the cohort, does not idle (tested with timers of one hour: a batch that waits for absent games would hang). The games that are left refill continuously from the remaining ids (the pool of the Job takes the next id as soon as a game ends).

**Threads.** The games are virtual threads on a scheduler with `--threads` carriers (`jdk.virtualThreadScheduler.parallelism`, set when the pool is made; the first value in a JVM wins), so `--threads` still bounds the compute threads, plus the device thread, which waits for the device. A first version limited the compute threads with a semaphore on 256 platform threads: it reached 29,000 to 46,000 evaluations per second in the feed benchmark against 57,000 to 82,000 with virtual threads (same machine, minutes apart), because every evaluation parked and unparked its thread twice and the whole batch fought for the permits (`PooledEvaluator(n > 0)` still works that way and is tested). A virtual thread has no CPU time of its own: `game_cpu_us` is 0 for device games, and the progress line shows the CPU time of the process.

**Stop and resume.** A stop (SIGINT, SIGTERM, hook) aborts the evaluator: the games in flight get `PooledEvaluator.Aborted` after at most one batch and are discarded (a game takes about a minute with 256 in flight, so waiting for them would break the rule that a stop costs one item); shards whose games are all done are written, as before. The device session is opened by the first game, so a resume that finds every shard complete never touches the device.

**The evaluator is not part of `run.json` (decision).** The two evaluators play the same games up to the float differences of the forward passes (CPU Vector API against GPU kernels, both float, and already different from the scalar path), so a result is a function of the configuration and of float noise, and a resume with the other evaluator is allowed. Each shard records its evaluator in the metadata key `evaluator` (`cpu` or `gpu`; not part of the `selfplay` string that a resume compares), and a resume says for every kept shard of the other evaluator: "was written by the cpu evaluator, this run uses the gpu one: kept". The hash of every run is unchanged (tested).

### Tests

`PooledEvaluatorTest` (14, CPU backends): every game gets its own result for 1, 7 and 40 games and 1, 2 and 3 cohorts; slots are not swapped and results not stale; one device thread serves two networks; a batch does not wait for games that are not running (hour timers) and shrinks as games end; the quiet time takes a partial batch; the other cohort fills while one is on the device; the permits bound the compute threads; virtual threads without permits; a failing backend, abort and close; misuse; warm-up and statistics. `TornadoClassic1Test` (7, device): see [tornado](tornado.md#residual-priors-on-the-host). `LoopGameLockstepTest` (7): a game (moves, scores, labels, policy targets, values) is bit for bit the same on the CPU path and with 1, 7 and 14 games in flight, 1, 3 and 8 permits or virtual threads and 1 and 2 cohorts (14 games); the visit counts of 12 fixed positions equal those of the sequential search; the job writes the shards of the CPU path (11 games, 5 in flight, shards of 4); the tail; a stop discards the games in flight and the restart gives the same shards; a resume with the other evaluator keeps the shards; the options and the hash. `LoopGpuEvaluatorTest` (4, task `gpuTest`, skipped without a device): a GPU run repeats its bits and names the evaluator in the shards and not in `run.json`; a resume with the other evaluator; real processes killed with SIGKILL and SIGTERM during the self-play restart to the results of an uninterrupted run; a JVM without a device falls back with the message.

**Mutation check:** 23 of 23 mutants caught, each put into the code alone: the slot index always 0; policy results read from slot 0; value results read from slot 0; stale results (the batch object not renewed); every client in cohort 0 (wrong cohort); a batch that waits for games that are not running; `end()` not waking the device thread (first survived, then caught by `aGameThatEndsCompletesTheBatchOfTheOthers` and the determinism test); padding rows (the backend gets the capacity, not the count); the compute permit not given back while waiting; a lost backend failure; abort ignored; no quiet-time dispatch; priors applied twice; priors not applied; the value prior with the wrong sign; the plane offset of the plain planes; no host tanh; tanh on the device and on the host; the chunk offset of the host priors; `reload()` not reading the prior scalars; a stop that does not abort the device; no `evaluator` key in the shard metadata; a game that does not leave its cohort. Four of them (wrong cohort, waiting for absent games, permit leak, no quiet-time) are caught by a test that hangs until its own timeout.

### Measurements

Apple M3 Max, JDK 25.0.3, 2026-11-13. **Another job used the CPU and the GPU at the same time** (load average 5 to 9, an unknown GPU share), so every time is indicative, and the device figures vary by a factor of 2 to 3 between runs of the same configuration minutes apart (the SMALL feed benchmark gave 18,100 and 57,300 evaluations per second at batch 64 in two runs). The simulation-limited scores of the neutrality matches do not depend on the load.

**CPU ceiling (`MctsBench --stub classic1`, SMALL, 100 simulations, noise 0, 24 s per row):** a forward pass that costs nothing (uniform priors, value 0); what remains is tree, move generation, the classical planes and the softmax over the legal moves.

| Threads | Simulations per second | per thread |
|---|---|---|
| 1 | 192,000 | 192,000 |
| 8 | 793,000 | 99,000 |

(12 threads were not measured: a job may start at most 8 compute threads.) One Vector API thread computes 3,000 SMALL evaluations per second, so tree and planes are about 2 % of the CPU path's work; at the 110,000 evaluations per second of the uncontended device they would need about 1.1 cores.

**GPU feed (`GpuFeedBenchmark`: SMALL and LARGE classic1, virtual-thread games with real planes and priors, 8 carriers, 2 cohorts, 60 s per row, batch = positions per execution):**

| Net | Batch | Evaluations per s | Device thread busy | Upload / download MB/s | CPU cores |
|---|---|---|---|---|---|
| SMALL | 64 | 18,100 (57,300 in a 20 s run) | 100 % | 115 / 18 | 0.5 |
| SMALL | 128 | 67,700 | 99 % | 430 / 66 | 1.7 |
| SMALL | 256 | 64,500 (81,500 in a 20 s run) | 99 % | 409 / 63 | 1.4 |
| SMALL | 512 | 47,000 | 100 % | 298 / 46 | 0.8 |
| SMALL, 1 cohort | 128 / 256 | 22,800 / 32,300 | 95 % / 93 % | 145 / 205 | 0.4 / 0.5 |
| LARGE | 64 | 6,900 | 100 % | 44 / 7 | 0.2 |
| LARGE | 128 | 9,200 | 100 % | 59 / 9 | 0.2 |
| LARGE | 256 | 12,000 | 100 % | 76 / 12 | 0.2 |
| LARGE | 512 | 14,500 | 100 % | 92 / 14 | 0.25 |

(Upload is the whole bucket of `inputSize` floats per position, download the bucket's 244 floats per position.) The device thread spends 99 % of its time in `plan.execute()` (kernels and both transfers); copy in 0.3 to 2 %, copy out 0.1 to 0.3 %, host priors 0.3 to 1.5 %. The uncontended rates of [tornado](tornado.md#measured-speed) are 111,000 (SMALL, 256) and 21,000 (LARGE, 256); the GPU gave 60 % to 70 % (SMALL, best row, 73 % in the 20 s run) and 57 % of them here.

**Self-play (`LoopSelfPlayBench`, loop defaults, 200 simulations, 8 compute threads):**

| Net | Evaluator | Games | Wall | Games per s | Notes |
|---|---|---|---|---|---|
| SMALL (loop 3 best) | CPU, 8 threads | 200 | 266 s | 0.75 | 11,400 nodes/s; 1.2 to 1.3 in earlier, quieter measurements |
| SMALL | GPU, 2 cohorts, 200 in flight | 200 | 217 s | 0.92 | device busy 97 %, mean batch 44 (tail), 3.1 ms per execution |
| SMALL | GPU, 1 cohort, 200 in flight | 200 | 108 s | 1.85 | device busy 67 %, idle 31 s, mean batch 85 |
| LARGE (random classic1 weights) | CPU, 8 threads | 80 | 1392 s | 0.058 | |
| LARGE | GPU, 2 cohorts, 80 in flight | 80 | 779 s | 0.103 | device busy 100 %, mean batch 39, 10 ms per execution |

The runs were made one after the other; the load of the other job changed between them, so the comparison is rough.

**Strength neutrality (`MctsMatch`, `mcts:sims=100,noise=0.25,temp=1,race=1`, loop 3 best network, 100 pairs, seed 3001, subject on the GPU, opponent on the CPU):**

| Match | Score | Elo | Games (W-D-L) |
|---|---|---|---|
| same seed on both sides | 0.503 +- 0.032 | +2 +- 43 | 97-7-96 |
| subject seed + 7777, GPU | 0.449 +- 0.033 | -36 +- 46 | 83-14-103 |
| subject seed + 7777, **CPU control** | 0.449 +- 0.033 | -36 +- 46 | 83-14-103 |
| subject seed + 31337, GPU | 0.483 +- 0.034 | -12 +- 47 | 94-5-101 |

The 0.449 is the seed stream, not the device: the CPU control with the same seed offset gives the same 83-14-103 games.

### Bottleneck {#bottleneck-g1}

1. **The device execution.** In every device run the device thread spent 93 to 100 % of its time in `plan.execute()`. The host work around it (6 KB per position in, 1 KB out, the priors, the lock) is about 1 to 2 %, so a second host thread would not help; only a faster or fuller GPU does. With the GPU shared, one execution took 2 to 5 ms at batch 40 to 128 for SMALL (0.9 ms uncontended at 64) and 10 ms for LARGE at batch 40.
2. **Batch size.** With 200 games in 2 cohorts a batch is 100, and at the end of a generation it shrinks to the games that are left (mean batch 44 and 39 over the runs). The 1-cohort run (batch 200) was twice as fast as the 2-cohort run, but its device idled 31 s (the CPU side and the tail). Two cohorts of 256 games each (`--games-in-flight 512`) should combine both; not measured.
3. **The tail.** The last games of a generation run with a few positions per execution; each simulation is then a full device round trip (0.5 to 1 ms), so a game of 87 plies x 200 simulations takes 10 s or more however few games are left. In the 217 s run the last 12 games took 36 s. A deterministic remedy would play the last K game ids on the CPU path in a second pool from the start (not done: the Job runs one pool).
4. **The CPU** is not the limit: 0.2 to 1.7 cores for the whole job, against 8 allowed.

### Recommendation for stage 2

Put the gate and strength matches on the same `PooledEvaluator` (`MctsMatch --evaluator gpu` is the pattern: one model per network, the candidate and the best network share the device thread, the pairs are the games in flight). Measure the GPU rate when no other job uses it before settling `--games-in-flight` (512 with 2 cohorts looks right for SMALL), and play the last games of a generation on the CPU by id. A LARGE network is possible but slow: about 12,000 evaluations per second at best here, 0.1 games/s with a shared GPU, 1.8 times the CPU path.

## Open questions

1. **Residual scaling at initialisation.** Does `Parameters.init` without normalisation layers let the block outputs grow too much for LARGE? Measure first; if yes, add `residualInitScale` (see [Weight initialisation](#weight-initialisation)).
2. **Value loss weight and zero sum.** Is c_v = 1 with the mean over players right? Should the value head enforce a zero sum ([network open question 4](network.md#open-questions))?
3. **Gating statistics.** More than 50 % of 200 games has a wide interval (about ±7 percentage points). Use a fixed threshold such as 55 %, or a sequential test (SPRT)? [Loop demo run 1](#loop-demo-run-1-measured): with 50 pairs the standard error is 0.04, and the promotions at 0.54 and 0.51 are within the noise of equal strength; 100 to 200 pairs, and the non-regression clause against a rung that is not saturated, are the first remedy.
4. **Teacher policy targets.** One-hot plus ε, or a multi-PV root search with exact scores for all root moves, at a higher cost?
5. **Three players.** No classical teacher and no `BEST_SPEC` exist for three players. Which baseline gates three-player generations?
6. **Race endgames in self-play.** The rule of [What classical code covers](#what-classical-code-covers) needs a conservative contact test and exact solitaire distances. Which of the two arrives first, and what share of plies does it save?
7. **Distance input plane.** Answered by E11: the distance planes are an addition, not a replacement (planes 3 to 5 stay), as the plane set `classic1` with format version 2; the A/B of [block 2f](#as-implemented-block-2f--e11) shows a faster and better policy. Still open: whether planes 3 to 5 can go.
8. **Float softmax on Metal.** Answered by [block B1-B5](#as-implemented-block-b1-b5): TornadoVM rejects a `double` kernel on the M3 Max with `TornadoDeviceFP64NotSupported`, and the float softmax meets the gradient bound (largest error 0.0031 of the bound for ordinary logits, 0.27 for logits that differ by hundreds).
9. **Integer dtypes in safetensors.** Extend the project's reader for sample files (U8, I16, I32), while `Parameters.load` stays F32-only? E05 keeps a typed reader and writer in module `selfplay` (`SampleFile`) and leaves `nn.Safetensors` unchanged.
10. **Replay window and reuse.** W = 500,000 samples and R = 4 are starting values. The demo trained with W = 500,000 (three to four generations of 24,000 samples) and R = 4 (370 steps per generation) without trouble; for generations of 1,000 to 4,000 games W = 1,000,000 is recommended ([block 2h](#as-implemented-block-2h)); R is still unmeasured.
11. **Shutdown in Java.** Confirm that the shutdown-hook design gives exit status 130 and 143 on the target JDK, and that 30 s suffice for the longest item. E05 verified 130 and 143 on JDK 25 with a shutdown hook (`SignalExitTest`).
12. **Distillation.** The [architecture](architecture.md#neural-network) plans to train LARGE and distil into SMALL. The trainer supports it with teacher distributions from LARGE; the schedule is open.
13. **References.** Adam, AdamW, SGD with momentum and AlphaZero need entries in [references](references.md) before this document cites them.

## Work plan

Each step ends with passing tests and can be reviewed alone. Part A steps go into `nn` (A7 and later into `selfplay`), Part B steps into `nn-tornado`.

1. **A1, backward operations:** `ReferenceBackward` with the rules of [Operations](#operations) and the scatter-form hex convolution. Tests: adjoint tests, hand-computed convolution gradient, finite differences for ReLU and tanh.
2. **A2, losses:** policy cross-entropy and value loss with their gradients, `TrainingBatch`. Tests: the policy and value loss bugs, finite differences of the cross-entropy.
3. **A3, whole network:** the trainer's forward pass with stored activations, the backward order, gradient accumulation. Tests: forward equality with `Network`, the TINY finite-difference check with the kink guard, batch and microbatch equality, mixed movers, padding rows.
4. **A4, optimisers:** SGD with momentum, then AdamW, the learning-rate schedule. Tests: hand-computed steps, one SGD step with the first-order prediction.
5. **A5, overfitting:** the teacher-student test on 8 positions.
6. **A6, checkpoints:** `Checkpoint` read and write, seeded batch sampler. Test: resume equivalence, bit for bit.
7. **A7, sample files:** typed safetensors tensors, the sample writer and reader, expansion to planes, mirror augmentation. Tests: expansion equals `Encoder.encode`; the symmetry tests of [Symmetric augmentation](#symmetric-augmentation).
8. **A8, replay buffer and batch builder.** Tests: window drops whole generations; the same seed and step give the same batch.
9. **A9, bootstrap:** teacher games with `BEST_SPEC`, generation 0 training on the CPU with SMALL on a small data set. Check: value winner prediction against the 78.8 % yardstick.
10. **A10, training loop:** run directory, state from artefacts, lock, status, abort, progress, gating; CPU only, TINY, a few games. Tests: abort and restart at each phase; a second run after success does nothing and exits with 0; a second process exits with 75. Done in [block 2h](#as-implemented-block-2h).
11. **B1, flat buffers and update kernel** against A4. Done in [block B1-B5](#as-implemented-block-b1-b5) (per-tensor arrays in the flat order).
12. **B2, expand kernel** against A7. Dropped: the host computes the planes ([block B1-B5](#as-implemented-block-b1-b5)).
13. **B3, training forward kernels** with stored activations, against A3 (reusing the inference kernels). Done.
14. **B4, loss kernel** in float, against A2. Done.
15. **B5, head backward kernels** (kernels 4, 5, 10) against A3. Done.
16. **B6, convolution input gradient** (kernels 6, 8) against the scatter form of A1. Done in [block B6-B10](#as-implemented-block-b6-b10).
17. **B7, convolution weight gradient:** split-K with `@Parallel` (kernels 7, 9), then the tiled `KernelContext` version. Done, both.
18. **B8, full step:** determinism, one-step and twenty-step comparisons with Part A. Done, with gradient accumulation and the finite flag.
19. **B9, benchmark:** samples per second and GFLOP/s for SMALL and LARGE at batch 256 to 2048; replace the estimates of [Expected speed](#expected-speed) with the measurements. Done for SMALL (LARGE is not measured).
20. **B10, integration:** the training loop uses the GPU trainer when a device is present and Part A otherwise. Done as the option `--trainer cpu|gpu` (default cpu), with a fallback to the CPU and a message; also for Gen0.
