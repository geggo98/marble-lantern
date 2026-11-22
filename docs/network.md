# Network

**TL;DR:** The network is a small residual network on the 121 board cells. Each layer is a hex convolution: a gather of each cell and its 6 neighbours, then a matrix multiplication. Input is 7 planes per cell, seen from the player to move, with the board rotated so that this player always starts in the top corner. The policy head gives a "from" logit and a "to" logit per cell; a move scores the sum. The value head gives one value per player. Presets: TINY (8 channels, 1 block, for tests), SMALL (32 × 4, about 60,000 parameters, about 14 MFLOP per position) and LARGE (64 × 6, about 350,000 parameters, about 84 MFLOP per position). Weights are F32 safetensors files. Format version 2 (round 2, E11) adds an optional set of classical input planes and two learnable scalars that add a classical prior to the policy and the value; the plain 7-plane network of version 1 stays fully supported, and version 1 files read as that plain network (see [Classical planes and residual priors](#classical-planes-and-residual-priors-format-version-2)). Format version 3 adds the plane set `classic1v`: two more planes for the jump rule and the player count, so that one network serves two and three players under both jump rules (see [Format version 3 (variants)](#format-version-3-variants)). A scalar CPU reference implementation is the test oracle for every faster path; a fast path agrees with it if every output satisfies |fast − ref| ≤ 1e-4 + 1e-3 · |ref|.

This document refines the section [Neural network](architecture.md#neural-network) of the architecture and uses the board, corners and play order of [rules](rules.md). The Java code is in the module `nn`, package `sternhalma.nn`.

## How to read this document

The labels are those of [rules](rules.md#how-to-read-this-document):

- **Project decision (owner):** fixed by the project owner (2026-11-07). The layer types, the input planes, the canonical orientation, the presets, the heads and the file format belong here.
- **Project decision:** a detail that this document fixes so that parallel implementations agree. It can change.
- **Project reasoning:** a conclusion that the project derives itself.

Every number in this document comes from a computation, not an estimate. The section [How the numbers were computed](#how-the-numbers-were-computed) gives the formulas.

## Goals and non-goals

Goals:

- One network definition that runs unchanged as a scalar CPU reference, as a CPU Vector API path and as TornadoVM kernels.
- Only standard operations (**project decision (owner)**): gather, matrix multiplication, bias, ReLU, element-wise addition, mean pooling, tanh.
- A reference implementation that is obviously correct, so that it can serve as the oracle for every other implementation.
- One weight file format that tools outside the JVM can read.

Non-goals:

- Batch normalisation, attention, dropout, or any operation outside the list above.
- One network for several player counts, except with the plane set `classic1v`. Every other network has a fixed number of players (2 or 3), because the value head has one output per player (**project decision**). A `classic1v` network has three value outputs and serves two and three players (see [Format version 3 (variants)](#format-version-3-variants)).
- Bit-exact agreement between implementations. See [Numeric conventions](#numeric-conventions).

## Terms

- **Cell:** an engine cell id from 0 to 120 (`Geometry`, ordered by r, then by q).
- **Row:** a row of an activation array. Rows 0 to 120 are the cells; row 121 is the padding row.
- **Mover:** the player to move in the position (`Position.sideToMove()`).
- **Relative player i:** the player who moves i plies after the mover, that is absolute player (mover + i) mod players. Relative player 0 is the mover. Play order is clockwise (see [rules](rules.md#screen-orientation-and-clockwise-order)).
- **Canonical cell:** the cell after the board has been rotated into canonical orientation (see [Canonical orientation](#canonical-orientation)). The network sees canonical cells only.

## Tensor layout

**Project decision (owner):** activations are flat `float[]` arrays, row-major, channels last.

| Array | Shape | Index of element (row, channel) |
|---|---|---|
| Activation with C channels | [122][C] | row · C + channel |
| Batch of activations | [B][122][C] | b · 122 · C + row · C + channel |
| Input (one position) | [122][7] ([122][13] with the classical planes, [122][15] with `classic1v`) | row · planes + plane |
| Policy logits (one position) | [121][2] | cell · 2 + k, with k = 0 "from", k = 1 "to" |
| Values (one position) | [P] | relative player |
| Convolution weight | [C_out][7 · C_in] | o · 7 · C_in + k · C_in + c (see [Gather table](#gather-table)) |
| Dense weight | [N_out][N_in] | o · N_in + i |
| Bias | [C_out] | o |

Rules for the padding row 121:

- Every layer that writes an activation writes zeros to row 121, in all channels. A reader may rely on that.
- The padding row does not take part in the policy head or in mean pooling. Only the gather reads it.
- The policy logits and the values have no padding row.

Batches add a leading dimension and nothing else. Positions in a batch are independent.

## Input planes

**Project decision (owner):** seven planes per cell, relative to the mover, written in canonical orientation. For a position with mover m, let `map` be the canonical cell map of m (next section), and `allowed` be `Layout.allowed`. For every board cell c, with canonical cell c' = map(c):

| Plane | Name | Value at row c' |
|---|---|---|
| 0 | own | 1 if c holds a piece of relative player 0 (the mover), else 0 |
| 1 | next | 1 if c holds a piece of relative player 1, else 0 |
| 2 | third | 1 if c holds a piece of relative player 2, else 0; always 0 with two players |
| 3 | goal | 1 if c is in the goal house of the mover (`Layout.inGoal`), else 0 |
| 4 | start | 1 if c is in the start house of the mover (`Layout.inStart`), else 0 |
| 5 | playable | 1 if `allowed(mover, c)`, else 0 |
| 6 | progress | min(ply, moveLimit) / moveLimit, the same value in every cell row |

Details (**project decision**):

- Row 121 is 0 in all seven planes, also in plane 6.
- `ply` is `Position.ply()` and `moveLimit` is `Position.rules().moveLimit()`. `Position.ply()` counts plies since the position was set up. A position built with `Position.put` therefore starts at ply 0, even if it shows a mid-game board. A caller that needs the real game ply must set the position up by playing moves.
- "Playable" means the cells that `Layout` lets the mover stand on: the central hexagon, the own start corner and the own goal corner. It does not depend on occupancy.
- The encoder overwrites all 122 · 7 = 854 floats of its output slice. It does not rely on a zeroed buffer.
- **Board sizes** (**project decision (owner)**, 2026-11-22, decisions D1 and D2 of the 5-row network plan): on the 4-row board a house is the 10-cell corner, so planes 3 and 4 are the corners, and every 4-row encoding is the one from before the 5-row board (`EncoderGoldenTest`). On the 5-row board ([rules](rules.md#board-size-triangle-rows)) a house is the corner plus the hexagon row in front of it, 15 cells. Plane 5 is the same for both sizes, because a 5-row house adds only hexagon cells. Of the classical planes, 7 and 8 use the distances of the 5-row layout (largest 12, below the divisor 16), plane 11 counts the 15 pieces of the layout, and plane 12 is 0 on 5 rows: the linear weights were fitted on 10 pieces, so the value prior `k · eval` switches itself off there, as for three players. A network reads a position only of a size that its metadata key `triangle_rows` lists ([Metadata keys](#metadata-keys)); `Evaluator.encode` refuses any other with `IllegalArgumentException`.

**Project reasoning:** in canonical orientation, planes 3, 4 and 5 are the same for every position of a player count, and they are also the same for two and three players. The goal is always the bottom corner R_POS, the start is always the top corner R_NEG, and the playable cells are always the hexagon plus these two corners. The planes still help: together with the zero padding they tell the network where the board edge and the forbidden side corners are. They cost 3 of 49 input weights per output channel of the first layer. See [Open questions](#open-questions).

A network can also read six more planes that classical code computes (distance, reachability, passed flag, linear evaluation): [Classical planes and residual priors](#classical-planes-and-residual-priors-format-version-2). The plane set `classic1v` adds two more for the jump rule and the player count: [Format version 3 (variants)](#format-version-3-variants). The seven planes above are the plane set `plain`; every statement in this document that does not say otherwise is about it.

## Canonical orientation

**Project decision (owner):** the network always sees the board as if the mover had started in the top corner R_NEG. The encoder applies a cell map before it writes the planes, and the move mapping applies the same map to both cells of a move.

### The maps

All maps are rotations of the star. A rotation in cube coordinates is a cyclic permutation of (q, r, s), possibly with all signs flipped. The board condition max(q, r, s) ≤ 4 or min(q, r, s) ≥ −4 does not change under a cyclic permutation, and the sign flip exchanges the two conditions. Each map therefore takes the board onto itself (**project reasoning**).

| Players | Mover | Map (q, r, s) → | Axial form (q, r) → | On screen | Engine method |
|---|---|---|---|---|---|
| 2 | 0 | (q, r, s) | (q, r) | identity | none |
| 2 | 1 | (−q, −r, −s) | (−q, −r) | 180° | `Geometry.rotate180` |
| 3 | 0 | (q, r, s) | (q, r) | identity | none |
| 3 | 1 | (r, s, q) | (r, −q − r) | 120° counter-clockwise | none |
| 3 | 2 | (s, q, r) | (−q − r, q) | 120° clockwise | none |

"On screen" uses the pointy-top picture of [rules](rules.md#screen-orientation-and-clockwise-order), where a rising angle turns clockwise. Mover 1 of three players starts at the lower right (30°) and must reach the top (270°), a turn of −120°.

The inverse maps: the 180° rotation and the identity are their own inverses. The two 120° rotations are each other's inverse.

### Where the corners go

The project checked these tables with a script (2026-11-07) on the 121-cell board.

Two players, mover 1:

| Absolute corner | Holds | Canonical corner |
|---|---|---|
| R_POS | mover's start | R_NEG |
| R_NEG | relative player 1's start, mover's goal | R_POS |

Three players (`Layout` start corners: player 0 R_NEG, player 1 S_NEG, player 2 Q_NEG):

| Mover | R_NEG goes to | S_NEG goes to | Q_NEG goes to |
|---|---|---|---|
| 0 | R_NEG | S_NEG | Q_NEG |
| 1 | Q_NEG | R_NEG | S_NEG |
| 2 | S_NEG | Q_NEG | R_NEG |

In every row, relative player i starts in canonical corner `Layout.of(3).startCorner(i)`. The canonical picture of any position is therefore laid out like a position in which player 0 moves. This is the consistency with `Layout` that the tests must check.

Example cells (cell id, axial coordinates):

| Cell | Corner | Mover 1 of 2 | Mover 1 of 3 | Mover 2 of 3 |
|---|---|---|---|---|
| 0 = (4, −8) | tip of R_NEG | 120 = (−4, 8) | 98 = (−8, 4) | 110 = (4, 4) |
| 110 = (4, 4) | tip of S_NEG | 10 = (−4, −4) | 0 = (4, −8) | 98 = (−8, 4) |
| 98 = (−8, 4) | tip of Q_NEG | 22 = (8, −4) | 110 = (4, 4) | 0 = (4, −8) |
| 60 = (0, 0) | centre | 60 | 60 | 60 |

The centre is the only fixed cell of each non-identity map.

### What the tests must check

The class `Orientation` implements the maps. Its tests must fail if a map is wrong in any of these ways:

1. The map is not a bijection of the 121 cells, or `fromCanonical(toCanonical(c)) != c`.
2. The map does not preserve neighbourhood: for neighbours a and b, the images are neighbours.
3. The 10 cells of the mover's start corner do not map onto the 10 cells of R_NEG, or the goal corner not onto R_POS.
4. Relative player i's start corner does not map onto `Layout.startCorner(i)` (the tables above).
5. `Layout.allowed(mover, c) != Layout.allowed(0, toCanonical(c))` for some cell.
6. For two players, mover 1, the map differs from `Geometry.rotate180`.
7. Encoder consistency: for two players, the encoding of a position with player 1 to move equals the encoding of `position.transformed(Geometry::rotate180, true)`, which has player 0 to move. Plane 6 needs the same ply on both sides, so compare positions that were set up with `put`, or compare planes 0 to 5 only.

The test for item 2 is important: a cyclic permutation in the wrong direction or a mirror would still pass items 1 and 3 for some corners.

## Gather table

**Project decision (owner):** the hex convolution is a gather followed by a matrix multiplication.

The gather table has 121 × 7 entries, flat as `[cell][k]`:

- k = 0: the cell itself.
- k = 1 to 6: `Geometry.neighbor(cell, k − 1)`, that is the neighbour in Geometry direction k − 1, or the padding index 121 if the neighbour is off the board.

Geometry direction order is (+1, 0), (−1, 0), (0, +1), (0, −1), (+1, −1), (−1, +1) in axial (q, r); directions d and d ^ 1 are opposite. The table is the same for every player count and every mover: the network works on canonical cells, so it never needs a rotated table.

For an input activation `in` with C_in channels, the gathered vector of cell c has 7 · C_in entries; entry k · C_in + ch is `in[gather[c][k] · C_in + ch]`. A convolution with weight W [C_out][7 · C_in] and bias b computes, for each cell c and output channel o:

```text
out[c][o] = b[o] + sum over k in 0..6, ch in 0..C_in-1 of W[o][k * C_in + ch] * in[gather[c][k]][ch]
```

and writes 0 to `out[121][o]`.

Counts (computed by a project script, 2026-11-07): 73 cells have 6 neighbours, 6 cells have 5, 36 cells have 4 and the 6 corner tips have 2. The table has 121 · 7 = 847 entries: 121 are the cell itself, 624 are neighbours (the star has 312 adjacent pairs, and each pair appears twice), and 102 are padding. A test can pin these counts.

## Layers

C is the channel count, B the block count, P the number of players and H = 32 the hidden width of the value head. All weights are float32.

| # | Name | Operation | Input | Output | Weight shape | Bias shape |
|---|---|---|---|---|---|---|
| 1 | `conv_in` | hex convolution, bias, ReLU | [122][7] | [122][C] | [C][49] | [C] |
| 2 | `block{i}.conv1` | hex convolution, bias, ReLU | [122][C] | [122][C] | [C][7C] | [C] |
| 3 | `block{i}.conv2` | hex convolution, bias, add the block input, ReLU | [122][C] | [122][C] | [C][7C] | [C] |
| 4 | `head_policy` | 1×1 convolution (dense per cell), bias, no activation | [121][C] | [121][2] | [2][C] | [2] |
| 5 | (mean) | mean over the 121 cell rows per channel | [121][C] | [C] | none | none |
| 6 | `head_value.dense1` | dense, bias, ReLU | [C] | [H] | [H][C] | [H] |
| 7 | `head_value.dense2` | dense, bias, tanh | [H] | [P] | [P][H] | [P] |

Rows 2 and 3 repeat for i = 0 to B − 1. Block i reads the output x of the previous layer and computes ReLU(conv2(ReLU(conv1(x))) + x). Rows 4 and 5 both read the output of the last block (or of `conv_in` when B = 0; the presets all have B ≥ 1).

### Presets and parameter counts

**Project decision (owner):** SMALL and LARGE. **Project decision:** TINY, for fast tests.

| Preset | C | B | `conv_in` | blocks | policy head | value head (P = 2 / 3) | Total (P = 2) | Total (P = 3) |
|---|---|---|---|---|---|---|---|---|
| TINY | 8 | 1 | 400 | 912 | 18 | 354 / 387 | 1,684 | 1,717 |
| SMALL | 32 | 4 | 1,600 | 57,600 | 66 | 1,122 / 1,155 | 60,388 | 60,421 |
| LARGE | 64 | 6 | 3,200 | 344,832 | 130 | 2,146 / 2,179 | 350,308 | 350,341 |

At 4 bytes per parameter, a SMALL file holds 241,552 bytes of tensor data and a LARGE file 1,401,232 bytes (P = 2), plus the header.

`NetworkConfig.parameterCount()` implements the formula below, and a test pins it to this table.

### Size limits

**Project decision:** `NetworkConfig` accepts 1 ≤ C ≤ 1024 (`MAX_CHANNELS`), 0 ≤ B ≤ 64 (`MAX_BLOCKS`), P = 2 or 3 (exactly 3 for the plane set `classic1v`), and 1 ≤ H ≤ 1024 (`MAX_VALUE_HIDDEN`). The constructor throws `IllegalArgumentException` for any other value; `Parameters.load` reports it as an `IOException`.

**Project reasoning:** the limits are far above the presets (C = 64, B = 6, H = 32) and stop a file of a few hundred bytes from making the loader allocate by its metadata alone. Before the limits, a file of a few hundred bytes without tensors whose metadata said `blocks` = 100,000,000 ran out of memory while the loader built the list of expected tensors. `Parameters.load` keeps the tensors of the file and only compares their names and shapes with `Parameters.shapes` of the config, so its memory use is bounded by the file size. A config at the limits has 2 + 4 · 64 + 6 = 264 tensors.

### Cost per evaluation

FLOPs per position, with one multiply-add counted as 2 FLOPs and each bias add, ReLU, residual add, mean-pooling add or division and tanh counted as 1 FLOP. The gather is memory traffic and is not counted.

| Preset | Multiply-adds | FLOPs in matrix work | Element-wise FLOPs | Total FLOPs (P = 2) |
|---|---|---|---|---|
| TINY | 158,104 | 316,208 | 8,062 | 324,270 |
| SMALL | 7,137,184 | 14,274,368 | 89,398 | 14,363,766 |
| LARGE | 42,028,800 | 84,057,600 | 255,926 | 84,313,526 |

With P = 3, each total grows by 66 FLOPs. The trunk dominates: in LARGE, the 12 block convolutions are 41,631,744 of the 42,028,800 multiply-adds, about 99 %.

A batch of 256 positions, the upper end of leaf batching in the [architecture](architecture.md#how-the-project-uses-it), costs about 3.7 GFLOP with SMALL and 21.6 GFLOP with LARGE.

### How the numbers were computed

With N = 121 cells, I = 7 input planes:

```text
parameters   = (7*I*C + C) + B*2*(7*C*C + C) + (2*C + 2) + (H*C + H) + (P*H + P)
multiply-add = N*C*7*I + B*2*N*C*7*C + N*2*C + H*C + P*H
element-wise = 2*N*C + B*5*N*C + 2*N + (N*C + C) + 2*H + 2*P
```

The element-wise line counts: bias and ReLU of `conv_in`; per block, bias and ReLU of conv1, bias of conv2, residual add and ReLU; bias of the policy head; mean pooling; bias and ReLU of dense1; bias and tanh of dense2.

## Policy head and move logits

**Project decision (owner):** the policy head is a 1×1 convolution from C channels to 2 channels on the 121 cell rows. Channel 0 is the "from" logit of the cell and channel 1 the "to" logit.

For an engine move `m` (`Move.of(from, to)`) in a position with mover p, let f' = map(from) and t' = map(to) with the canonical map of p. Then:

```text
logit(m) = policy[2 * f' + 0] + policy[2 * t' + 1]
```

The policy over the legal moves L of the position is the softmax over L only:

```text
prior(m) = exp(logit(m) - max) / sum over m2 in L of exp(logit(m2) - max),   max = max over L of logit
```

Illegal moves get no probability. They are never scored, so no mask value such as −∞ is needed. The sum f + t is computed in double (`Encoder.moveLogit` returns a double), so two finite float logits always give a finite move logit, also near the float limit. Subtracting the maximum keeps `exp` finite; the reference sums in double. The priors of a position sum to 1 within 1e-6. A position without legal moves has an empty policy.

**Project decision** for non-finite network outputs: if the logit of a legal move is infinite or NaN, or a value is NaN, `Evaluator.evaluate` throws `IllegalStateException`. Such outputs mean broken weights, and NaN priors must not reach a search. Logits of cells that no legal move uses are not checked. A value of ±1 from tanh(±∞) is valid.

**Project reasoning:** the sum of two per-cell logits cannot express every distribution over (from, to) pairs: it cannot prefer "piece A to cell X" and "piece B to cell Y" over both crossed pairs at the same time. The owner accepted this for the first version; see [Open questions](#open-questions).

## Value head

Mean pooling over the 121 cell rows per channel (row 121 excluded, division by 121), dense C → 32, ReLU, dense 32 → P, tanh.

Output i is the expected game value for relative player i, so output 0 is the mover. The training targets are the `Outcome.values` of the engine, reordered to relative players. They lie in [−1, 1]: a win gives +1 to the winner and −1/(P − 1) to each other player, and a game without a winner gives scores that sum to zero. tanh covers this range. The network does not force the outputs to sum to zero (**project decision**).

`Evaluator` converts the outputs back to absolute players: the value for absolute player a is output (a − mover) mod P. A `classic1v` network has P = 3 and also evaluates two-player positions; there P is the player count of the position (see [Format version 3 (variants)](#format-version-3-variants)).

## Classical planes and residual priors (format version 2)

**TL;DR:** The plane set `classic1` adds six planes that classical code computes from the cells in microseconds and two learnable scalars that add classical priors to the network's outputs: the policy logit of a move becomes `beta · distance gain + net logit` and the value becomes `tanh(net + k · linear evaluation)`. With `beta = 1`, `k = 0.5` and the two output layers at zero, an untrained network is exactly the classical prior. `classic1-planes` is the same planes without the priors (an ablation). The planes are never stored in sample files; the sample expander and the live encoder compute them with the same code. Implemented in E11 (round 2); the measured effect is in [training.md](training.md#as-implemented-block-2f--e11).

**Project decision (owner, 2026-11-10):** the network format may change for E11. Version 1 files stay readable as the plain network.

### The planes

Planes 0 to 6 are those of [Input planes](#input-planes). The classical set adds, in canonical orientation and relative to the mover (`ClassicalPlanes`):

| Plane | Name | Value at row c' (canonical cell of board cell c) |
|---|---|---|
| 7 | dist_own | single-agent distance of c for the mover (`Layout.distance`, steps to the nearest goal cell over the cells the mover may stand on) divided by 16; 0 on cells the mover may not stand on |
| 8 | dist_next | the same for relative player 1 |
| 9 | reach_own | 1 if some legal move of the mover ends on c (`Position.generate`; steps and jump chains) |
| 10 | reach_next | 1 if some legal move that relative player 1 would have, were it to move, ends on c (the same generator on a copy with the side swapped) |
| 11 | passed | 1 in every cell row if the armies have passed each other (every piece of player 0 on a larger r than every piece of player 1, all 20 pieces on the board; two players), else 0 |
| 12 | eval | the linear evaluation of the position for the mover in logit units (below), in every cell row; 0 for three players |

Details (**project decision**):

- Row 121 is 0 in all 13 planes. The encoder still overwrites all 122 · 13 floats of its slice.
- The largest distance is 13 (the tip of the start corner); 16 is the next power of two, so that `16 · plane` is the integer distance without rounding. In canonical orientation planes 7 and 8 are constants of the player count, like planes 3 to 5; they are written by the same code as the dynamic planes and are not a template, so that every plane has one definition.
- With three players relative player 1 is the next to move; there is no plane for relative player 2's distance or moves, and planes 11 and 12 are 0 (the passed test and the linear evaluation are two-player notions).
- **The linear evaluation** is the sum of the 12 weights of `linear-r2` (`LinearWeights.ROUND2`) times the 12 features "side to move minus opponent" of `LinearEvaluator` (`ClassicalPlanes.linearLogit`). `nn` cannot depend on `search`, so it carries a copy of the features; `ClassicalPriorsTest` in `search` pins it to `LinearEvaluator.features` bit for bit on 120,000 positions of both leave-goal rules and the weights to `LinearWeights.ROUND2`. **The 13th feature `bestGain` (E07, the evaluator of `BEST_SPEC`) is not in it**: it needs the jump-component search of `LinearEvaluator.GainSearch`, which would be a second copy of 200 lines; the 12-feature `linear-r2` is the refit on passed-filtered self-play that `linear-r3` extends. A network that wants `bestGain` can learn it from the planes.
- **Race counts** (option (d) of the E11 plan: the exact counts of both sides when both configurations are in the table) are **not** a plane: the table lives in `tablebase` and the counts apply only to passed positions, where the passed flag and the distance planes already carry most of the information. Skipped to keep the encoder free of table I/O; it is the first candidate if the planes help.
- **Rules.** The planes follow the position's own rules (`Position.generate` honours leave-goal and the suicide rule). `SampleExpander` rebuilds a position from canonical cells without a rule set, so it assumes the default rules (`RuleSet.defaults(players)` with the stored move limit) except for the jump rule, which the caller passes from the sample's `rules` (`expand(planes, players, jumpRule, …)`; the overload without a jump rule means `ADJACENT`). For these positions it equals `Encoder.encode` float for float. A sample of another leave-goal or suicide rule would expand to planes that differ in planes 9 and 10 where the rules matter.
- **Mirror.** With the left-right mirror of a two-player sample, row c' of every plane goes to row `mirror(c')`. The reachability, passed and evaluation planes of the mirrored position equal the mirrored rows of the original (tested for both movers, also against the encoding of `Position.transformed(Geometry::mirror)`).

### The residual priors

With the plane set `classic1`, the forward pass adds two classical terms to the network's own outputs (`ResidualPriors`, in `Network`, `VectorNetwork` and the trainer):

```text
policy[c'][FROM] += beta * 16 * dist_own[c']        policy[c'][TO] -= beta * 16 * dist_own[c']
value_i           = tanh(net_i + k * sign_i * eval)  sign = +1 (relative player 0), -1 (player 1), 0 (player 2)
```

- **Policy.** The distance gain of a move is `DIST[from] − DIST[to]` of the mover. It is a sum of a "from" and a "to" term, so the factorised policy layout holds it exactly: the move logit is `beta · gain + net logit`. With `beta = 1` the prior alone is `softmax(gain)` over the legal moves (NLL 2.95 against 3.92 for the uniform prior on the teacher data; round-2 plan E11).
- **Value.** The linear evaluation is the logit of "the mover wins" (it is fitted by a logistic regression of the game result), so the expected value of a game, `2 p − 1`, is `tanh(logit / 2)`: **`k = 0.5` makes the value base the classical expected value.** The scale is chosen by that identity, not fitted. With two players the base of relative player 1 is the negative. The net value is added before the `tanh`.
- **Learnable.** `prior.beta` and `prior.k` are ordinary parameters (shape [1], no weight decay, as they do not end in `.weight`). The trainer has their exact gradients: `d beta = sum over cells (dPolicy[from] − dPolicy[to]) · 16 · dist_own` and `d k = sum over players dy_i · sign_i · eval`, where dPolicy and dy are the gradients of the loss at the policy output and at the value before the tanh (checked against finite differences for two and three players, mixed movers, and invariant under the mirror).
- **Initialisation.** `Parameters.init` sets `beta = 1`, `k = 0.5` and starts `head_policy.weight` and `head_value.dense2.weight` at zero (the random draws are still taken, so the other tensors equal those of `classic1-planes`). An untrained `classic1` network is then exactly the classical prior: `ResidualPriorsTest` checks policy and value of `Network` and `VectorNetwork` against the distance gain and the linear evaluation for both movers. (A network with only zero weights and biases gives the same, which a test also checks.)
- **On the device.** `TornadoNetwork` computes the raw outputs on the device (the logits as they are, the value head without its tanh) and adds the two priors on the host, after the results are back, with the code of `VectorNetwork`: no new kernel and no extra upload (the planes `dist_own` and `eval` are in the input that is on the host). See [tornado.md](tornado.md#residual-priors-on-the-host).

The number of parameters is `(7 · I · C + C) + …` of the [formula](#how-the-numbers-were-computed) with I = 13, plus 2 for `classic1`: TINY 2,020 / 2,022, SMALL 61,732 / 61,734 (`classic1-planes` / `classic1`, two players). For `classic1v` see [Format version 3 (variants)](#format-version-3-variants).

## Format version 3 (variants)

**TL;DR:** The plane set `classic1v` is `classic1` plus two constant planes, `long` and `three`, and a value head with three outputs. One network then serves all four variants: two or three players, each with the jump rule ADJACENT or LONG. It is the only plane set of format version 3. Files of versions 1 and 2 load and are written exactly as before.

### The planes

Planes 0 to 12 are those of `classic1`, at the same indices (`Encoder`, `ClassicalPlanes`). Two planes follow:

| Plane | Name | Value at every cell row |
|---|---|---|
| 13 | long | 1 if the position's jump rule is `LONG` (`Position.rules().jumpRule()`), else 0 |
| 14 | three | 1 if the position has three players, else 0 |

Row 121 is 0 in all 15 planes. The input of one position has 122 · 15 = 1,830 floats.

How the `classic1` planes behave in the four variants (**project decision**):

- **Distance planes 7 and 8** are `Layout.distance`, the geometric steps to the goal. They do not depend on the jump rule, so they are valid in all four variants.
- **Reach planes 9 and 10** are generated under the position's own rules (`Position.generate`), so they show long hops under `LONG`. `SampleExpander` takes the jump rule from the sample's `rules` through the overload `expand(planes, players, jumpRule, …)`; the old overload without a jump rule means `ADJACENT`.
- **Plane 11 `passed`** is 0 for three players, as in `classic1`.
- **Plane 12 `eval`** is 0 under `LONG` and for three players. Reason: the linear evaluation is fitted on two-player games with adjacent jumps. With the plane at 0, the value prior `k · eval` switches itself off in the variants it was not fitted for. Limit: this applies to `classic1v` only. Under `classic1` the encoder is unchanged and still writes the linear evaluation for a two-player `LONG` position.

### The priors

The residual priors are those of `classic1` (`ResidualPriors`, same formulas, same initial values `beta = 1` and `k = 0.5`):

- The policy prior `beta · distance gain` is on in all four variants, because the distance planes are valid in all of them.
- The value prior `k · sign · eval` has an effect only for two players with adjacent jumps, because plane 12 is 0 elsewhere.

An untrained `classic1v` network therefore gives the distance prior as its policy in every variant and the classical value only for two players with adjacent jumps (`VariantPlanesTest`).

### Value head and evaluator

The value head has P = 3 outputs, in the order of relative players. `NetworkConfig.withPlanes(CLASSIC1V)` sets the player count to 3, and the `NetworkConfig` constructor rejects `classic1v` with 2 players.

`Evaluator` accepts positions of 2 or 3 players for a `classic1v` network and uses the first `position.players()` value outputs: output r is relative player r, so the value of absolute player a is output (a − mover) mod n, with n the player count of the position. A batch may mix player counts and jump rules. Every other network still rejects a position whose player count differs from its own.

**Planned training convention** (not part of the module `nn`; see [training.md](training.md) when it is implemented): a two-player sample stores the value target z = (z0, z1, 0) and gets value weight 1.5.

### Tensors and parameter count

The tensor names and their order are those of `classic1`. Two shapes differ: `conv_in.weight` is [C, 105] (7 · 15 columns), and `head_value.dense2` has P = 3 rows. The [parameter formula](#how-the-numbers-were-computed) holds with I = 15 and P = 3, plus 2 for the priors. For TINY (C = 8, B = 1, H = 32): 848 (`conv_in`) + 912 (block) + 18 (policy head) + 387 (value head) + 2 (priors) = 2,167, pinned in `VariantPlanesTest`.

### File format rules

- Version 3 is written only for `classic1v`, with the metadata `format_version` = `3`, `players` = `3`, `input_planes` = `15` and `planes` = `classic1v`.
- The reader accepts version 3 only with exactly these values of `planes`, `players` and `input_planes`. It rejects `classic1v` in a version 2 file, and a version 3 file with another plane set.
- Files of versions 1 and 2 are written and read as before, byte for byte. `EncoderGoldenTest` pins this with SHA-256 hashes of the encoder output and of seeded files, taken from the code before the change.
- A reader of formats 1 and 2 rejects a version 3 file as an unknown `format_version`.

### C++ port

The forward passes in `cpp/src/nn` take the plane count from the config and add the priors when the plane set has them, so they needed no change for 15 planes. The config code (`cpp/src/nn/config.hpp`, `config.cpp`) knows the plane set `classic1v` (15 planes, with priors) and accepts `format_version` 3 under the rules above; `absolute_values` in `priors.hpp` is the C++ counterpart of the value mapping of `Evaluator`. C++ has no encoder: the golden files `nn-inputs-v3` and `nn-ref-*-v3` carry Java-encoded inputs and the Java outputs (see `cpp/README.md`).

## Initialisation

**Project decision:** `Parameters.init(config, seed)` fills the tensors in the order of the [tensor names](#safetensors-file) from one `java.util.Random(seed)`, elements in row-major order:

- every weight: uniform in [−a, a] with a = √(6 / fanIn), where fanIn = 7 · C_in for a hex convolution and the input width for a dense layer or the policy head;
- every bias: 0.

The value is `(random.nextFloat() * 2 - 1) * a`, computed in float. The same seed gives the same parameters in every run on every JVM, because `java.util.Random` is specified exactly. Other implementations load parameters from a file; they do not reproduce the initialisation.

Biases of 0 hide bias bugs. Tests of the forward pass therefore use `Parameters.uniform(config, seed, bound)`, which draws every tensor, biases included, uniform in [−bound, bound] in the same order.

## Safetensors file

The file format is safetensors: an 8-byte little-endian unsigned length N, then N bytes of UTF-8 JSON header, then the tensor data. The header maps each tensor name to `{"dtype", "shape", "data_offsets"}`, where `data_offsets` = [begin, end) counts bytes from the start of the data section. An optional entry `"__metadata__"` maps strings to strings. (The format is described in the safetensors project documentation; that source is not yet in [references](references.md).)

**Project decision**, for this project's reader and writer:

- dtype `F32` only, little-endian. The reader rejects any other dtype with an `IOException` that names the tensor and the dtype.
- The writer writes tensors in the iteration order of the given map, contiguously, without gaps, and pads the header with spaces (0x20) so that N is a multiple of 8. The data section then starts 8-byte aligned.
- The reader accepts any header padding, returns the tensors in order of their begin offset, and rejects: a header length larger than the file, a header that is not a JSON object, a duplicate name, offsets that do not match the shape (end − begin ≠ 4 · product of shape), overlapping tensors, gaps, and bytes after the last tensor.
- A shape of `[]` is a scalar with one element. A dimension of 0 is allowed and gives an empty tensor, whatever the other dimensions are (for example `[2147483647, 2, 0]`). Without a 0, a product of the dimensions above 2^31 − 1 is rejected.
- The writer writes a temporary file in the target directory and renames it to the target name, so a reader never sees a half-written file. The file gets the default permissions of a new file in that directory (the umask), not the owner-only mode of `Files.createTempFile`. If the write fails, the temporary file is removed.
- Metadata values are strings. The reader returns an empty map if `__metadata__` is missing.

### Tensor names

In this order (`Parameters.shapes(config)` returns exactly this list):

| Name | Shape |
|---|---|
| `conv_in.weight` | [C, 49] |
| `conv_in.bias` | [C] |
| `block{i}.conv1.weight` | [C, 7C] |
| `block{i}.conv1.bias` | [C] |
| `block{i}.conv2.weight` | [C, 7C] |
| `block{i}.conv2.bias` | [C] |
| `head_policy.weight` | [2, C] |
| `head_policy.bias` | [2] |
| `head_value.dense1.weight` | [32, C] |
| `head_value.dense1.bias` | [32] |
| `head_value.dense2.weight` | [P, 32] |
| `head_value.dense2.bias` | [P] |
| `prior.beta` | [1] (only with residual priors, plane sets `classic1` and `classic1v`) |
| `prior.k` | [1] (the same) |

With classical planes `conv_in.weight` is [C, 91] (7 · 13 columns), with `classic1v` [C, 105] (7 · 15 columns). The four `block{i}` lines repeat for i = 0, 1, …, B − 1, with the block number in decimal and without leading zeros (`block0`, `block1`, …).

### Metadata keys

| Key | Value | Example |
|---|---|---|
| `format` | always `sternhalma-nn` | `sternhalma-nn` |
| `format_version` | integer as text; `1` (plain planes), `2` (classical planes, adds `planes`) or `3` (`classic1v`) | `1` |
| `channels` | C | `32` |
| `blocks` | B | `4` |
| `players` | P; always `3` in version 3 | `2` |
| `value_hidden` | H | `32` |
| `input_planes` | `7` in version 1; the width of the plane set in version 2 (`13` for both classical sets); `15` in version 3 | `7` |
| `planes` | only in versions 2 and 3: `plain`, `classic1` or `classic1-planes` in version 2, `classic1v` in version 3 | `classic1` |
| `triangle_rows` | optional, any version: the board sizes the network was trained for, `4`, `5` or `4,5`. Written only when it is not `4`, so every 4-row file keeps its bytes; a file without it is a 4-row network. No new format version: the tensors do not depend on it, and the C++ reader ignores it (`nn_test`, `config_metadata_ignores_the_optional_key_triangle_rows`) | `5` |

`Parameters.load` rejects a file whose `format` or `format_version` is unknown, whose config keys are missing or not integers, whose sizes are outside the [size limits](#size-limits), or whose tensor names or shapes differ from `Parameters.shapes` of that config. Extra metadata keys (for example a training step or a seed) are allowed and kept. Version 1 is written for a plain network (so older readers still read it) and read as plane set `plain`; a version 1 file with a `planes` key, a version 2 file without one, with an unknown name or with `classic1v`, a version 3 file with another plane set or with `players` other than 3, and an `input_planes` that does not belong to the plane set are rejected. Version 3 is written for `classic1v` only; see [Format version 3 (variants)](#format-version-3-variants).

## Numeric conventions

- All tensors and activations are float32.
- The reference (`ReferenceOps`, `Network`) accumulates every dot product in double, in ascending index order, and rounds to float once per output element. It is the oracle, so it trades speed for a small, well-defined rounding error.
- Faster paths (Vector API, TornadoVM) may sum in any order and in float. Their results are therefore not bit-exact with the reference. They need not even be bit-reproducible themselves: `VectorNetwork` gave last-digit differences between its first calls and later calls on the same input in one thread (measured 2026-11-07, SMALL; probably interpreted against JIT-compiled Vector API code). Tests of fast paths therefore compare with the tolerance, not bit for bit.
- **Tolerance (project decision):** a fast path agrees with the reference if, for every policy logit and every value, |fast − ref| ≤ 1e-4 + 1e-3 · |ref|. A test that uses other weights than those below must state its own bound and its reason.
- **Where the bound holds (measured):** `VectorNetworkTest` (2026-11-07) compares `VectorNetwork` with `Network` on seeded inputs (half of them encoded positions of random games, half random planes in [0, 1)), batches 1, 3 and 64, two and three players, and reports the largest error as a fraction of the bound:
  - `Parameters.init`, and `Parameters.uniform` with the init bound √(6 / 7C): the bound holds for TINY, SMALL and LARGE (largest error 0.004, 0.031 and 0.042 of the bound).
  - `Parameters.uniform` with bound 0.5: the bound holds for TINY (largest absolute error 9.5e-7, below the absolute term 1e-4 alone). It does **not** hold for SMALL and LARGE: the activations grow by a factor of about 2 (SMALL) to 5 (LARGE) per layer, and a small logit that is the difference of huge terms gets an error of about 1e-6 of the largest logit of its position (measured: up to 1.03 times the bound). For these weights the tests allow an extra 1e-5 times the largest |logit| of the position; see [Measured CPU speed](#measured-cpu-speed).
- **Project reasoning** for the size of the bound: a float sum of n terms has a relative rounding error of at most about n · 2^−24 of the sum of the absolute terms. The longest sum has 7 · 64 = 448 terms (LARGE), which gives about 2.7e-5 per layer, and the longest path through LARGE has 15 layers (`conv_in`, 12 block convolutions, two dense layers). The bound leaves a margin over this and is still far below any difference that would change a move choice. Tests must report the largest observed error, so that the bound can be tightened later.
- Priors are compared with the same rule.

## Java API

The skeleton in `nn/src/main/java/sternhalma/nn` fixes the signatures. Three groups can be implemented in parallel; each group depends on the others only through the signatures and this document.

| Group | Classes | Content |
|---|---|---|
| A: file format | `Tensor`, `Safetensors` | named F32 tensors; read and write safetensors files |
| B: encoding | `HexGrid`, `Orientation`, `Encoder` | gather table, canonical cell maps, input planes, move-to-logit mapping |
| E11: classical planes | `PlaneSet`, `ClassicalPlanes`, `ResidualPriors` | the plane sets, the six classical planes with the linear evaluation, the prior terms added by every forward pass |
| C: network | `NetworkConfig`, `Parameters`, `ReferenceOps`, `Network`, `Evaluator`, `Evaluation` | presets, named parameters, scalar reference operations, forward pass, legal-move priors |

`Tensor` and `NetworkConfig` are complete in the skeleton, with tests, because the other groups need them at once. So are the constants and the index helpers `Encoder.index` and `Encoder.policyIndex`, and the one-line delegations `Orientation.of(Position)` and `ReferenceOps.dense`. All other method bodies throw `UnsupportedOperationException` until their group implements them.

Group C tests that need group A or B code (loading a file, encoding a position) wait for that group, or they build their input arrays directly.

### Forward passes, threads and the benchmark

Added after the three groups:

| Class or task | Content |
|---|---|
| `ForwardPass` | The interface of a forward pass: `config()`, `forward(input, batch, policyLogits, values)` and `threadSafe()`. `Network` and `VectorNetwork` implement it; `Evaluator` takes any `ForwardPass`. |
| `VectorNetwork` | The Vector API fast path (see [Measured CPU speed](#measured-cpu-speed)). It packs the weights in the constructor and reads the `Parameters` only there and in `reload()`: after an in-place update of the parameters, call `reload()`, or the network keeps computing with the old weights. |
| `VectorSupport` | The vector species in use (`VectorSupport.SPECIES`). |
| `nn:benchmark` | Gradle task (`JavaExec`, main class `NetworkBenchmark` in the test sources): evaluations per second of `Network` and `VectorNetwork`, one thread. Run `devenv shell -- ./gradlew --no-build-cache :nn:benchmark`. |

**Thread-safety rule (project decision):** a `ForwardPass` either allows concurrent calls (`threadSafe()` is true), or it detects a call that starts while another call on the same instance runs and throws `IllegalStateException`. It never returns wrong values because of concurrent calls. `Network` is thread-safe: every call allocates its own scratch buffers. `VectorNetwork` is not: its scratch buffers are fields, and an atomic busy flag (one compare-and-set per call) makes a concurrent `forward` or `reload()` throw. `Evaluator` has no mutable state and is exactly as thread-safe as its network: use one `Evaluator` with its own `VectorNetwork` per thread. No implementation allows changing the `Parameters` during a call. `Position` is not thread-safe either (it has scratch buffers for move generation), so threads must not share a position while they evaluate it.

### What the tests must catch

An earlier review of this project found that weak tests were the main risk. Each group's tests must fail for these plausible bugs:

- **A:** wrong byte order; header length read as signed or as 32 bits; a header without padding or with padding; offsets relative to the file start instead of the data section; a shape with a 0 dimension or a scalar; tensor order lost in a round trip; metadata lost; each rejection rule in [Safetensors file](#safetensors-file) (one malformed file per rule). Compare the writer against a byte array built by hand for a small file.
- **B:** the items in [What the tests must check](#what-the-tests-must-check); a gather table with the neighbours in the wrong order (compare against `Geometry.neighbor` for every entry); padding index used for an on-board neighbour; planes swapped (a constructed position where every plane differs from every other); relative player order reversed for three players; plane 6 not clamped at the move limit; stale data in the output buffer (fill it with NaN first).
- **E11 (classical planes and priors):** `Encoder.encode` and `SampleExpander.expand` differ for some mover, for three players, or under the mirror; reachability of the next player generated without swapping the side; planes 9 and 10 swapped; the passed flag with equal rows or fewer than 10 pieces; the linear evaluation with a sign or the tempo term wrong (`search` pins it to `LinearEvaluator` bit for bit); a prior term with the wrong sign, plane or scale; the value term for the wrong relative player; `prior.beta` or `prior.k` missing from the packed copy of `VectorNetwork` after `reload()`; their gradients (finite differences, mirror symmetry); an untrained network that is not exactly the classical prior; a metadata combination of version, `planes` and `input_planes` that is accepted but wrong.
- **C:** shapes and names against the tables above; parameter counts against the preset table; a hand-computed TINY-sized forward pass on a few cells (for example one channel with known weights); the gather order inside the convolution (weights that make only neighbour k contribute); the padding row stays zero after every layer; the residual add (B = 1 with conv2 weights at zero must give ReLU(x)); batch results equal single results, also for a batch that mixes movers (each position uses its own orientation); priors sum to 1 and follow the logit order; the softmax subtracts the largest logit, not the first (a first move with a much smaller logit than another); the value reordering from relative to absolute players for every mover of three players.

## Measured CPU speed

**TL;DR:** on one core of an Apple M3 Max, the Vector API path (`VectorNetwork`) evaluates about 1,700 SMALL or 300 LARGE positions per second, 15 to 17 times as many as the scalar reference (`Network`). Batch size changes little on the CPU, because both paths compute one position after the other. The machine was heavily loaded during the run, so the absolute numbers are a lower bound; the multiples are more reliable.

Measured on 2026-11-07 with `NetworkBenchmark` (test sources of `nn`), one thread, weights from `Parameters.init`, 64 encoded positions of seeded random games, 2 players:

| Preset | Batch | Path | Evaluations/s (median of 5) | Range of the 5 rounds | GFLOP/s | Multiple of scalar |
|---|---|---|---|---|---|---|
| SMALL | 1 | scalar | 115 | 94 to 120 | 1.6 | 1.0 |
| SMALL | 1 | vector | 1,760 | 1,671 to 1,918 | 25.1 | 15.3 |
| SMALL | 64 | scalar | 113 | 92 to 122 | 1.6 | 1.0 |
| SMALL | 64 | vector | 1,642 | 1,594 to 1,715 | 23.4 | 14.5 |
| LARGE | 1 | scalar | 18 | 15 to 20 | 1.5 | 1.0 |
| LARGE | 1 | vector | 297 | 276 to 311 | 25.0 | 16.6 |
| LARGE | 64 | scalar | 19 | 19 to 20 | 1.6 | 1.0 |
| LARGE | 64 | vector | 301 | 295 to 315 | 25.3 | 15.6 |

- **Machine:** Apple M3 Max, macOS, aarch64, JDK 25.0.3. `FloatVector.SPECIES_PREFERRED` has 4 float lanes (128-bit NEON).
- **Load:** other experiments ran at the same time. The 1-minute load average was 125 before and 107 after the run, on 16 cores. Each number is therefore lower than on an idle machine. Repeat the run on an idle machine before you use the absolute numbers for planning.
- **Method:** each case runs 3 s of warm-up, then 5 rounds of at least 1 s. GFLOP/s counts the matrix work of [Cost per evaluation](#cost-per-evaluation), 2 FLOPs per multiply-add.
- **Run it:** `devenv shell -- ./gradlew --no-build-cache :nn:benchmark` (about 1.5 minutes). The task prints this table and the load average.

How the vector path works (details in the class comment of `VectorNetwork`):

- Each convolution weight is repacked once into tiles of 2L output channels (L = lane count). The inner loop runs over the input channels of one gather entry. It loads 2 contiguous weight vectors, broadcasts the input value of 4 cells, and does 8 fused multiply-adds into 8 accumulators. The 4 cells share each weight load, and no horizontal reduction is needed.
- Tail lanes: inside `VectorNetwork`, every channel dimension is padded with zero weights and zero biases up to a multiple of 2L, so no loop has a scalar tail. The input and output arrays keep the layout of [Tensor layout](#tensor-layout).
- `forward` allocates nothing. The weights are packed in the constructor; after an in-place update of the `Parameters`, call `reload()`. An instance is not thread-safe; a concurrent call throws `IllegalStateException` (see [Forward passes, threads and the benchmark](#forward-passes-threads-and-the-benchmark)).
- A variant with 4 cells × 4 vectors (16 accumulators) was 4 times *slower* in the same setup. This is probably because HotSpot did not keep 16 vector values in registers; it was not analysed further.

Measured agreement with the reference (`VectorNetworkTest`, largest error as a fraction of the bound of [Numeric conventions](#numeric-conventions)):

| Weights | TINY | SMALL | LARGE |
|---|---|---|---|
| `Parameters.init`, or `Parameters.uniform` with the init bound √(6 / 7C) | 0.004 | 0.031 | 0.042 |

An earlier diagnostic run, with `Parameters.init` and 8 positions of random planes in [0, 1), gave 0.19 for LARGE. So the margin with init-size weights is only about 5; tightening the bound (open question 5) needs more measurements than these.
**Finding** (now recorded in [Numeric conventions](#numeric-conventions)): the documented bound does *not* hold for `Parameters.uniform` with bound 0.5 in SMALL and LARGE. With these weights the activations grow by about a factor of 2 (SMALL) to 5 (LARGE) per layer, and LARGE logits reach about 3·10^8. The float rounding error is relative to the size of the terms of a sum, not to the result. A small logit that is the difference of huge terms therefore has an error of about 10^-6 of the largest logit of its position, and this breaks the per-output bound (measured: up to 1.03 times the bound). `VectorNetworkTest` therefore uses an extra term for these weights: 1e-5 times the largest |logit| of the position. With weights of init size, the bound holds (see the table).

## Open questions

1. **Constant planes.** Planes 3 to 5 are the same for every position (see [Input planes](#input-planes)). Answered in part by E11: the informative planes (the single-agent distance of the cell for the mover and for the next player, and four more) are added as the plane set `classic1`, format version 2, and train faster and better ([training.md](training.md#as-implemented-block-2f--e11)); planes 3 to 5 were kept in that experiment, so whether they can be dropped is open.
2. **Player count in the input.** Answered by the plane set `classic1v` (format version 3): plane 14 encodes the player count and the value head has a fixed width of 3, so one network serves both ([Format version 3 (variants)](#format-version-3-variants)). The other plane sets keep separate two-player and three-player networks. Whether one shared network plays as well as separate ones was measured once: it does not, in two-player neighbour-jump play (see [Shared net for all four variants](training.md#shared-net-for-all-four-variants-measured-2026-11-19)).
3. **Factorised policy.** fromLogit + toLogit cannot express all pair preferences (see [Policy head and move logits](#policy-head-and-move-logits)). A bilinear or per-piece head would fix this at more cost. Measure the policy loss first.
4. **Value sum.** Should the value head enforce a zero sum, for example by subtracting the mean of the outputs, to match `Outcome.values`?
5. **Tolerance.** The bound 1e-4 + 1e-3 · |ref| is derived, not measured. Tighten it once the fast paths report their largest errors.
6. **Three-player symmetry.** The left-right mirror changes the play order (see [rules](rules.md#symmetry)), so it is not used for three-player data augmentation. For two players it is a candidate for augmentation.
7. **Reference entry.** The safetensors format description needs a key in [references](references.md).
