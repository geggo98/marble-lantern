# Training

**TL;DR:** Training has two implementations. Part A is a scalar CPU reference trainer in module `nn`: loss, exact gradients of every operation of [network](network.md), SGD with momentum and AdamW (the default), checkpoints, and the tests that make it the oracle. Part B is the GPU trainer in module `nn-tornado`: each Part A operation becomes a TornadoVM kernel, and each kernel is tested against its Part A twin. The input gradient of the hex convolution is itself a hex convolution with permuted weights, and the weight gradient is a split-K reduction, so no kernel needs an atomic add. One training sample costs about 3 times its forward pass (42 MFLOP for SMALL, 251 MFLOP for LARGE). Self-play needs about 17 times more GPU work than training, so the largest savings come from classical code that takes work away from the network and from the search: legal-move masks, canonical orientation, the mirror symmetry, a teacher bootstrap from `Strategies.BEST_SPEC`, and classical play of race endgames.

This document builds on [network](network.md) (layers, tensor layout, tolerance), [architecture](architecture.md#self-play-and-training-loop) (the self-play loop and the [long-running-job conventions](architecture.md#long-running-jobs)) and [search and evaluation](search-and-evaluation.md). The Java code of Part A goes into module `nn`, package `sternhalma.nn`; the code of Part B goes into module `nn-tornado`, package `sternhalma.tornado`; the training loop and the sample files go into module `selfplay` (see [architecture](architecture.md#module-map)).

## How to read this document

The labels follow [network](network.md#how-to-read-this-document):

- **Project decision (owner):** fixed by the project owner. This document only cites such decisions from other documents.
- **Proposal:** a detail that this document fixes so that parallel implementations agree. Nothing in this document has been implemented yet (2026-11-07). The owner can change any proposal.
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

A sample without legal moves has no policy loss: the builder sets wp_s = 0. A sample with one legal move has Lp_s = 0 and a zero gradient; the self-play writer sets wp_s = 0 for it to save work (**proposal**).

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
selfplay.sh run --status --out DIR
selfplay.sh report DIR... [--sweep 400,500] [--check-s1 1]
selfplay.sh strong-check --from DIR --out DIR2 --spec SPEC [--limit 300]
```

- **Layered adjudication** (`Adjudication`), two players only (other player counts are refused): layer 1 is S1 ([endgame and opening](endgame-and-opening.md#the-criterion-s1-adjudication)), checked before every move in a passed position. Layer 2 fires when the same player reports `|score| >= 500` with the same sign on 2 consecutive own moves; scores in the race band (`Scores.kind == RACE`, `|score| >= Scores.RACE_BASE` = 5000 below the mate bound) and moves without a search (race play) are "no information" and reset the streak. Layer 3 (`sigmoid(eval/100) >= p` on 4 consecutive plies, ordinary values only) is off by default. Layers 2 and 3 never fire in a passed position: there only S1 labels. The first firing sets the label and the cut.
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

#### Job conventions

The job follows the [long-running-job conventions](architecture.md#long-running-jobs). How this maps onto Java (**proposal**):

- **Lock:** `FileChannel.tryLock()` on `data/<run>/.lock`. On macOS and Linux this is an operating-system record lock that the kernel releases when the process dies, also after `kill -9`. If the lock is held, the job prints `running already: pid=<pid> since <start>` from `.status` and exits with 75.
- **Status:** `--status` probes the lock and releases it at once. Lock free: `not running` (a `.status` file is then a leftover). Lock held: pid, start time and the elapsed time from `ProcessHandle.of(pid)`, then the phase, generation and last progress line.
- **Abort:** a shutdown hook (it runs on `SIGINT` and `SIGTERM`) sets a stop flag and waits up to 30 s for the main loop. The loop checks the flag between items: between training steps, between gating games, and between self-play games (running games are dropped, never written half). The loop then prints the summary and releases the hook. Exit status: 130 after `SIGINT`, 143 after `SIGTERM` (the JVM's 128 + signal convention, to verify on the target JDK).
- **Progress:** one line per 10 s on stderr, without carriage returns, for example `gen 3 train: 1200/4000 steps  31.5 steps/s  ETA 1m29s  elapsed 38s  loss 2.41 (kl 0.62, value 0.31)`. The rate counts only items done in this process, never skipped or resumed ones, so the ETA after a restart is right.
- **Summary:** counts of SUCCESS, SKIP, ERROR and CLEANUP per phase, and what a further run would do, for example `gen 3: 2000 games skip, 4000 steps ok, 0 error, 1 cleanup; next: gating`.
- **Exit codes:** 0 done or nothing to do, 1 error, 75 running already, 130 or 143 aborted.

## Part B: the GPU trainer with TornadoVM

Part B implements Part A as TornadoVM kernels. The forward kernels are those of the inference path (`docs/tornado.md`, written in parallel), with one change: the training variant writes each layer's output to its own slot of an activation store instead of reusing two buffers.

### Kernels of one training step

Every kernel writes each output element from exactly one thread. No kernel uses atomic operations, so a step is deterministic: the same inputs give the same bits on every run (a test checks this).

| # | Kernel | Threads | Work per thread | Part A twin |
|---|---|---|---|---|
| 1 | expand: cells, ply, mirror flag → input planes | B · 122 | 7 writes | batch builder |
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

- **No double on the GPU.** The Metal Shading Language has no 64-bit floating-point type (to verify how TornadoVM's Metal backend reacts to `double` in a kernel). Kernel 3 therefore computes the softmax in float: maximum, then the sum of exp(l − max), each term in (0, 1]. With at most a few hundred legal moves the float sum has a relative error of about 1e-5, which the tolerance below covers. The move logit f + t is formed in float; float logits near the float limit could overflow, but such logits mean broken weights, and the finite check of kernel 11 stops the run.
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

## Open questions

1. **Residual scaling at initialisation.** Does `Parameters.init` without normalisation layers let the block outputs grow too much for LARGE? Measure first; if yes, add `residualInitScale` (see [Weight initialisation](#weight-initialisation)).
2. **Value loss weight and zero sum.** Is c_v = 1 with the mean over players right? Should the value head enforce a zero sum ([network open question 4](network.md#open-questions))?
3. **Gating statistics.** More than 50 % of 200 games has a wide interval (about ±7 percentage points). Use a fixed threshold such as 55 %, or a sequential test (SPRT)?
4. **Teacher policy targets.** One-hot plus ε, or a multi-PV root search with exact scores for all root moves, at a higher cost?
5. **Three players.** No classical teacher and no `BEST_SPEC` exist for three players. Which baseline gates three-player generations?
6. **Race endgames in self-play.** The rule of [What classical code covers](#what-classical-code-covers) needs a conservative contact test and exact solitaire distances. Which of the two arrives first, and what share of plies does it save?
7. **Distance input plane.** Replace the constant planes 3 to 5 with the single-agent distance per cell ([network open question 1](network.md#open-questions))? This is a classical feature the network would no longer need to learn, and it changes the file format version.
8. **Float softmax on Metal.** Confirm that TornadoVM rejects `double` on Metal and that the float softmax meets the gradient bound.
9. **Integer dtypes in safetensors.** Extend the project's reader for sample files (U8, I16, I32), while `Parameters.load` stays F32-only? E05 keeps a typed reader and writer in module `selfplay` (`SampleFile`) and leaves `nn.Safetensors` unchanged.
10. **Replay window and reuse.** W = 500,000 samples and R = 4 are starting values.
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
10. **A10, training loop:** run directory, state from artefacts, lock, status, abort, progress, gating; CPU only, TINY, a few games. Tests: abort and restart at each phase; a second run after success does nothing and exits with 0; a second process exits with 75.
11. **B1, flat buffers and update kernel** against A4.
12. **B2, expand kernel** against A7.
13. **B3, training forward kernels** with stored activations, against A3 (reusing the inference kernels).
14. **B4, loss kernel** in float, against A2.
15. **B5, head backward kernels** (kernels 4, 5, 10) against A3.
16. **B6, convolution input gradient** (kernels 6, 8) against the scatter form of A1.
17. **B7, convolution weight gradient:** split-K with `@Parallel` (kernels 7, 9), then the tiled `KernelContext` version.
18. **B8, full step:** determinism, one-step and twenty-step comparisons with Part A.
19. **B9, benchmark:** samples per second and GFLOP/s for SMALL and LARGE at batch 256 to 2048; replace the estimates of [Expected speed](#expected-speed) with the measurements.
20. **B10, integration:** the training loop uses the GPU trainer when a device is present and Part A otherwise.
