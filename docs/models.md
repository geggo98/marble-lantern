# Models of the browser net engines

**TL;DR:** The browser ships three trained networks ("models") for the net engines `net-wasm` and `net-webgpu` ([web UI, net engines](web-ui.md#net-engines)). The **expert** model plays two players with the neighbour jump on 4 rows and is the strongest there. The **5-row expert** plays the same variant on the 5-row board, the default of a new game. The **shared** model plays all four variants on 4 rows (2 or 3 players, neighbour jump or long hop). All three are SMALL networks (32 channels, 4 residual blocks). They are stored as F16 weights, compressed with gzip, about 115 KB each, in plain git under `web/models/`, and listed with lengths and SHA-256 hashes in `web/models/models.json`. The browser verifies every byte before it uses a model. `scripts/models/pack-model.py` packs and verifies models, and widens them (converts F16 back to F32) for checks. Every trained network, shipped or not, is archived in F32 under `models/` ([archive](../models/README.md)).

## Contents

1. [Terms](#terms)
2. [The shipped models](#the-shipped-models)
3. [Measured strength](#measured-strength)
4. [Packing: F16 and gzip](#packing-f16-and-gzip)
5. [Integrity checks in the browser](#integrity-checks-in-the-browser)
6. [Adding a model](#adding-a-model)
7. [Models trained but not shipped](#models-trained-but-not-shipped)

## Terms

- **Model:** one trained network file of the project's format ([network](network.md)), a safetensors file with the weights and metadata. The manifest `web/models/models.json` lists every model with its sizes, hashes, variants and strength figures.
- **Variant:** a jump rule (`adjacent`, the neighbour jump, or `long`, the long hop; see [rules](rules.md)) together with a player count (2 or 3) and a board size (4 or 5 rows, [rules](rules.md#board-size-triangle-rows)). Written `jump:players[:rows]` on the command line, for example `adjacent:2` (4 rows) or `adjacent:2:5`. In `models.json` a 5-row variant carries `"rows": 5`; a variant without the field means 4 rows.
- **Plane set, format version:** which input planes a network reads. `classic1` (format version 2) is the two-player set; `classic1v` (format version 3) adds the planes that tell the variants apart ([network](network.md#format-version-3-variants)).
- **SMALL:** the network size preset with 32 channels and 4 residual blocks ([network](network.md)).
- **F32, F16:** 32-bit and 16-bit floating-point numbers. The browser backends compute in F32; the files store F16.
- **`BEST_SPEC`, `ab:N`:** `BEST_SPEC` is the strongest classical alpha-beta player of the project. `ab:N` is the classical opponent with a budget of N search nodes per move ([training, two players](training.md#two-players-mctsmatch)). With the neighbour jump that opponent is `BEST_SPEC`. With the long hop it is `BROWSER_LONG_SPEC`, a classical alpha-beta without race table and race play, which is weaker at equal nodes; Elo against it cannot be compared with Elo against `BEST_SPEC`.
- **`ab3`:** the classical three-player alpha-beta. In a three-player match the network holds one seat and the other two seats each hold `ab3` with the same node budget, written "2 × `ab3`" ([training, three players](training.md#three-players-threeseatmatch)).
- **Opening pair:** two games from the same opening, with the sides swapped, so that an advantage of the opening cancels out.
- **Teacher:** a classical alpha-beta player whose games serve as training data ([training, bootstrap](training.md#bootstrap-generation-0-from-a-classical-teacher)).
- **Simulations:** steps of the Monte Carlo tree search (MCTS) per move; each step evaluates one position with the network.
- **Elo, score:** a two-player score is the share of points of the network; 0.5 is even. Elo is the strength difference derived from it; ± is the 95 % interval. A three-player score is the share of games the network won; one third is even.

## The shipped models

| Id | Plays | Plane set, format | Parameters | Source |
|---|---|---|---|---|
| `small-2p-adjacent-expert` | 2 players, neighbour jump, 4 rows | `classic1`, version 2 | 61,734 | generation 13 of self-play loop run 3 |
| `small-2p-adjacent-r5-expert` | 2 players, neighbour jump, 5 rows | `classic1`, version 2, `triangle_rows=5` | 61,734 | generation 9 of the 5-row loop |
| `small-shared-all` | all four variants, 4 rows | `classic1v`, version 3 | 62,215 | generation 0 of the shared run, trained on teacher games of all four variants |

**Expert.** Loop run 3 trained a SMALL network by self-play: 14 generations of 2,500 games at 200 simulations, of which generation 13 was the best ([training, loop run 3](training.md#loop-run-3-measured-2026-11-14)). The file is `data/loop3/gen-0013/candidate.safetensors`. The UI names it "Expert for 2 players".

**5-row expert.** Gen0 on 8,000 teacher games of the classical 5-row player, then 10 generations of self-play at 200 simulations, of which generation 9 was the last promoted one ([training, five-row expert net](training.md#five-row-expert-net)). The file is `data/net5/loop-small/gen-0009/candidate.safetensors`. Its metadata key `triangle_rows=5` lets it read only 5-row positions ([network, input planes](network.md#input-planes)). The UI names it "Expert for 5 rows".

**Shared.** One SMALL network trained for 40,000 steps on games of a classical teacher in all four variants, without self-play ([training, shared net for all four variants](training.md#shared-net-for-all-four-variants-measured-2026-11-19)). The file is `data/shared/gen0-shared/gen-0000/candidate.safetensors`. The UI names it "All variants".

`data/` is git-ignored. The source files are archived in Git LFS under `models/nets/` ([archive](../models/README.md)), and each manifest entry records the SHA-256 of its source file (`sourceSha256`), so the archived source can be matched against the shipped model.

The weights are numbers learned from games; they contain no personal data.

## Measured strength

The engine sheet prints only the figures in the field `strength` of `web/models/models.json`, each with the player count, jump rule, opponent and simulation count it was measured at. No figure is stated per simulation preset (50, 200 or 800, [web UI, choosing a net engine](web-ui.md#choosing-a-net-engine)).

**Expert**, from [loop run 3](training.md#loop-run-3-measured-2026-11-14) (200 opening pairs, seed 5002, 200 simulations, race play on, that is `race=1`: in a position without contact the player takes the move from the race table, see [offline mode](offline-mode.md); the note in `models.json` calls this "race adjudication"):

| Opponent | Elo |
|---|---|
| `ab:6400` | +564 ± 96 |
| `ab:25600` | +490 ± 75 (the figure in `models.json`) |
| `ab:102400` | +298 ± 46 |

**5-row expert**, from [training, five-row expert net](training.md#five-row-expert-net) (200 opening pairs per rung, seed 3001, 200 simulations, no race play). The opponent is `BROWSER_ROWS5_SPEC`, the classical 5-row player without race play; these Elo figures cannot be compared with 4-row figures. In `models.json` the keys end in `_r5`, so the sheet shows them only for 5-row games:

| Opponent | Score | Elo |
|---|---|---|
| `ab:1600` | 0.996 | +970 ± 193 |
| `ab:6400` | 0.990 | +798 ± 261 |
| `ab:25600` | 0.971 | +611 ± 112 |
| `ab:102400` | 0.950 | +512 ± 81 (the figure the sheet shows) |

The ladder is saturated at these scores; against its own Gen0 the network scored 0.991 over 400 pairs.

**Shared**, from [shared net for all four variants](training.md#shared-net-for-all-four-variants-measured-2026-11-19) (200 simulations; two players: 100 opening pairs per row; three players: 300 games per row):

| Variant, opponent | Result |
|---|---|
| 2 players, neighbour jump, `ab:1600` / `ab:6400` / `ab:25600` | +225 ± 57 / +69 ± 43 / -123 ± 51 Elo |
| 2 players, long hop, `ab:1600` / `ab:6400` | +191 ± 56 / +102 ± 50 Elo |
| 3 players, neighbour jump, 2 × `ab3` at 5,000 nodes | wins 274 of 300 games (0.913) |
| 3 players, long hop, 2 × `ab3` at 5,000 nodes | wins 212 of 300 games (0.707) |

The long-hop Elo is against `BROWSER_LONG_SPEC` and cannot be compared with the neighbour-jump Elo. In two players with the neighbour jump, the shared model is weaker than the expert: -123 against +490 Elo at `ab:25600`. These two figures come from separate matches with different seeds and opening sets, so the difference is only a rough guide.

## Packing: F16 and gzip

`scripts/models/pack-model.py pack` converts every tensor to F16 (round to nearest even; a value beyond the F16 range or a NaN is an error), keeps the metadata, and writes the file with `gzip -9` and mtime 0, so the same input gives the same bytes. The browser loader widens F16 back to F32 before the backend sees the weights (`web/src/engines/net/models.ts`).

| Form | Expert | 5-row expert | Shared |
|---|---|---|---|
| F32 safetensors | 249,384 B | 249,400 B | 251,252 B |
| F32, gzip | 230,372 B | 230,335 B | 232,086 B |
| F16 safetensors | 126,004 B | 126,020 B | 126,910 B |
| F16, gzip (shipped) | 114,957 B | 115,132 B | 115,866 B |

The shipped file is about 46 % of the F32 file. gzip alone saves only about 8 % on F32 weights, because float bits look almost random to it; F16 halves the size.

**Effect of F16 on the outputs.** `HalfPrecisionStudy` (`nn/src/test/java/sternhalma/nn/HalfPrecisionStudy.java`) compares the F32 network with the widened packed file on 2,000 mid-game positions from seeded random playouts:

- the best move (arg max of the priors) agrees in 2,000 of 2,000 positions for the expert, in 2,000 of 2,000 5-row positions for the 5-row expert, and in 1,998 to 1,999 of 2,000 for the shared model; every disagreement is a near tie between two moves
- the mean KL divergence of the move priors is 3 × 10⁻⁷ to 5 × 10⁻⁷
- the largest value difference is 1.0 × 10⁻³ to 2.1 × 10⁻³

**Rule: gzip, not zstd or brotli.**
Origin: the page inflates the model with the browser's built-in `DecompressionStream`, which accepts `gzip`, `deflate` and `deflate-raw`. Chrome supports neither `zstd` nor `brotli` there (measured on 2026-09-09 in Chrome 152, see [offline mode](offline-mode.md)).
Rationale: any other format would need a decoder in WebAssembly or JavaScript in the page. For these files that decoder would buy nothing: `zstd -19` saves nothing over `gzip -9` on the F16 weights.
Expiry: when every target browser decodes a better format in `DecompressionStream` and that format makes the files clearly smaller.

**Rule: plain git, not Git LFS.**
Origin: chosen when the models were packed, for their size.
Rationale: each file is about 115 KB, and a new model replaces or adds a file of that size. LFS pays off for files like the race tables of hundreds of MB (`.gitattributes`), not here, and plain git keeps a checkout and the CI simple.
Limit: if models grow to several MB each or change often, reconsider. The F32 archive of every trained network is a separate matter and uses Git LFS ([archive](../models/README.md), "Rules").

## Integrity checks in the browser

`loadModel` in `web/src/engines/net/models.ts` checks a model in this order and stops at the first difference:

1. The download has exactly `packedBytes` bytes and its SHA-256 is `sha256Packed`.
2. The inflated file has exactly `rawBytesF16` bytes and its SHA-256 is `sha256Raw`.
3. The safetensors header is readable, and its metadata (format version, plane set, planes, players, number of weights) matches the manifest entry. The board sizes of the metadata key `triangle_rows` (missing: 4) must equal the sizes of the entry's variants (`ModelInfo.rows`).

Each failure throws a `ModelError` with a code: `model-http`, `model-truncated`, `model-trailing`, `model-corrupt`, `model-checksum`, `model-header` or `model-mismatch` (`web/src/engines/net/model-manifest.ts`). The engine reports it as the warning `model-load-failed` with the reason `verify-failed` (codes `model-checksum` and `model-mismatch`) or `load-failed` (all other codes), and plays random legal moves ([web UI, warnings and fallbacks](web-ui.md#warnings-and-fallbacks)).

`pack-model.py` writes the lengths and hashes of an entry before its descriptive fields. A manifest cut short therefore loses descriptive fields first, never the lengths and hashes of an entry whose description it still holds.

`crypto.subtle` exists only in a secure context (HTTPS or localhost). Elsewhere a pure-JavaScript SHA-256 (`web/src/engines/net/sha256.ts`) computes the same hashes, so verification never disappears.

## Adding a model

1. Pack the F32 network. Give every variant it may play, and the strength figures that were measured, as JSON:

   ```bash
   scripts/models/pack-model.py pack <net.safetensors> --id <ID> \
     --variant adjacent:2 [--variant long:2 ... | --variant adjacent:2:5] \
     --created-from "<run and generation>" \
     [--strength-json '{"mcts200_vs_ab25600_adjacent": {"elo": 490, "ci95": 75}}']
   ```

   This writes `web/models/<ID>.safetensors.gz` and adds or replaces the entry `<ID>` in `web/models/models.json`. The board sizes of the variants must equal the network's metadata key `triangle_rows` (missing: 4), else the call ends with exit 2. The UI reads three shapes of strength keys (`web/src/engines/net/model-strength.ts`); other keys are ignored. In the examples, 200 is the simulation count, `abN` and the trailing number of the three-player key are node budgets, and `2x` is the number of opponent seats:

   | Shape | Example from `models.json` | Value |
   |---|---|---|
   | one Elo figure | `mcts200_vs_ab25600_adjacent` | `{"elo": 490, "ci95": 75}` (`ci95` optional) |
   | Elo against several budgets | `mcts200_adjacent_elo` | `{"vs_ab1600": 225, "vs_ab6400": 69}` |
   | three-player share of wins | `mcts200_3p_score_vs_2xab3_5000` | `{"adjacent": 0.913, "long": 0.707}` |

   A 5-row figure appends `_r5` to the jump rule of the first two shapes, for example `mcts200_vs_ab102400_adjacent_r5`; the sheet shows it only for 5-row games, and an older app ignores it.

2. Check the packed file against its source:

   ```bash
   scripts/models/pack-model.py verify --source <ID>=<net.safetensors>
   ```

3. Measure the effect of F16 on the outputs:

   ```bash
   scripts/models/pack-model.py widen <ID> /tmp/<ID>-f32.safetensors
   ./gradlew --no-build-cache :nn:halfPrecisionStudy --args='<net.safetensors> 2000 11 /tmp/<ID>-f32.safetensors'
   ```

   The numbers are the count of positions (2,000) and the seed (11). For a three-player or long-hop model, append the player count and jump rule to `--args`; for a 5-row model also the board size, for example `... 2 adjacent 5` (see the usage in `HalfPrecisionStudy.java`).

4. Add the texts `model.<ID>.label` and `model.<ID>.description` to `web/src/i18n/en.json` and `web/src/i18n/de.json`. The test `web/src/i18n/net-keys.test.ts` fails without them.

5. Run `cd web && npx vitest run src/i18n`. The web build checks the models again (`scripts/web/build.zsh` runs `pack-model.py verify` on `dist/models`).

## Models trained but not shipped

Three specialists, one SMALL network per variant, were trained with the same training budget as the shared model (40,000 steps on teacher games) (`data/shared/spec-2p-long`, `spec-3p-adjacent`, `spec-3p-long`) and compared with it in paired matches at 200 simulations (`data/shared/s2/report.txt`, written by `scripts/s2-report.zsh`; "s2" is the internal task name of this comparison):

| Variant | Opponent | Specialist minus shared | z |
|---|---|---|---|
| 2 players, long hop | `ab:1600` | +0.073 score | +1.82 |
| 2 players, long hop | `ab:6400` | +0.028 score | +0.59 |
| 3 players, neighbour jump | 2 × `ab3` at 5,000 nodes | -0.070 share of wins | -2.60 |
| 3 players, neighbour jump | 2 × `ab3` at 20,000 nodes | -0.050 share of wins | -1.57 |
| 3 players, long hop | 2 × `ab3` at 5,000 nodes | +0.053 share of wins | +1.48 |
| 3 players, long hop | 2 × `ab3` at 20,000 nodes | -0.007 share of wins | -0.22 |

A difference counts as measurable at |z| > 1.96. No specialist is measurably better than the shared model, and the three-player neighbour-jump specialist is measurably worse at 5,000 nodes. Shipping them would add about 115 KB per file and a choice in the UI without a measured gain, so only the shared model covers these variants.
