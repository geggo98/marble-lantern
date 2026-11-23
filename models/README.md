# Archive of the trained networks

**TL;DR:** This directory holds every network that a documented training run of this project produced: 89 distinct files from 17 runs, 53.0 MB, stored with Git LFS. Two of the runs, the SMALL and the LARGE 5-row run, share the run directory `net5`. The table of all of them is [index.md](index.md) (the same data as JSON: [index.json](index.json)). The three networks that the browser plays with are not loaded from here; their packed F16 copies are in `web/models/` ([models of the browser net engines](../docs/models.md)).

## Why the archive exists

Origin: the owner decided on 2026-11-22 that every trained network goes into the repository, for full disclosure. Before that, the networks existed only in `data/`, which is git-ignored, so only the machine that trained them had them. Each shipped model records the SHA-256 of its source file (`sourceSha256` in `web/models/models.json`); with the archive, anyone can fetch that source and check it.

## What is in it

- `nets/<run>/<name>.safetensors.gz`: one network, as the trainer wrote it (F32 safetensors, [network format](../docs/network.md)), compressed with gzip. `<run>` is the run directory under `data/` on the training machine; `<name>` is the path below it with `/` replaced by `-`, for example `data/loop3/gen-0013/candidate.safetensors` becomes `nets/loop3/gen-0013.safetensors.gz`.
- `index.json`, `index.md`: one row per source file, written by `scripts/models/archive-nets.py`. Do not edit them by hand.
- `runs/<run>/`: copies of the run records that `docs/training.md` cites but that live only in the git-ignored `data/` (driver scripts, match summaries, loop decisions, energy reports). Plain git, not LFS; they are a few KB. So far: [`runs/net5/`](runs/net5/README.md) (the SMALL 5-row run), [`runs/net5-large/`](runs/net5-large/README.md) (the LARGE 5-row run) and [`runs/net5-cpu-power/`](runs/net5-cpu-power/README.md) (the CPU against GPU power experiment, which trained no archived network).

Columns of the index:

| Column | Meaning |
|---|---|
| file | the stored file in `nets/` |
| source | the path of the original file on the training machine |
| run | the run, linked to its section in [docs/training.md](../docs/training.md) |
| preset, planes | channels x residual blocks (SMALL is 32x4, LARGE 64x6), and the plane set, the set of input planes the network reads (`plain`, `classic1`, `classic1v`; [network](../docs/network.md), [models, terms](../docs/models.md#terms)) |
| rows | the board sizes of the metadata key `triangle_rows`: `4`, `5` or `4,5`; a file without the key is a 4-row network |
| variants | what the plane set can play, written `jump:players[:rows]`: jump rule `adjacent` (neighbour jump) or `long` (long hop), 2 or 3 players, 5 for the 5-row board; for example `adjacent:2:5` ([models, terms](../docs/models.md#terms)) |
| stored / raw bytes | size of the gzip file and of the safetensors file |
| SHA-256 (raw) | hash of the uncompressed safetensors file; `sha256Stored` in `index.json` is the hash of the gzip file |
| gate | for a generation of the self-play loop: whether its candidate network was promoted to the new best or rejected, and its score in the gate match against the best network before it ([training, as implemented (block 2h)](../docs/training.md#as-implemented-block-2h)) |
| strength | the measured figures that the documentation reports for this network; "not measured beyond the gate" or "not measured" otherwise |
| kWh | wall energy of the whole machine for the training phases that made the network; only the two 5-row runs were measured ([training, five-row expert net](../docs/training.md#five-row-expert-net) and [five-row LARGE net](../docs/training.md#five-row-large-net)), every other row says "not measured". A loop generation shows its own phase and, in brackets, the sum of the teacher data, Gen0 and all generations up to it; for the LARGE run that sum includes the teacher data it reused from the SMALL run |

Two source files with the same bytes are stored once. Generation 0 of a loop is the network the loop started from, so its row points to the file of the run that trained it.

Not archived: the smoke and signal-test runs (`net5-smoke`, `rev-g2`, `loopgpu1`). They tested the tools, not the training, and no document reports them.

## How to use a file

```zsh
git lfs pull --include 'models/nets/**'
gzip -dc models/nets/loop3/gen-0013.safetensors.gz > /tmp/loop3-gen13.safetensors
shasum -a 256 /tmp/loop3-gen13.safetensors      # equals sha256Raw of its row in index.json
```

The Java tools read the decompressed file, for example `scripts/mctsmatch.sh --net /tmp/loop3-gen13.safetensors ...`.

## How to add a run

1. Add the run directory and its section of `docs/training.md` to `RUNS` in `scripts/models/archive-nets.py`, and its measured strength to `STRENGTH` if the documentation reports one. A run that shares its directory with another run, as the LARGE 5-row run shares `net5`, goes into `PARTS` instead, keyed by the path prefix of its subdirectories (for example `net5/loop-large/`). Energy per network is read only from the two 5-row energy reports (`--energy`, `--energy-large`); a new measured run needs its own reader in `energy()`.
2. On the training machine: `scripts/models/archive-nets.py --data <repository>/data`. A rerun keeps files whose bytes are already right; `--check` compares without writing (exit 1 on a difference).
3. `git add models` stores the new files as LFS pointers; `git lfs status` lists them.

## Rules

**Rule: F32 with gzip, not F16 and not zstd.**
Origin: measured on 2026-11-22 with the LARGE network of loop run 4 (`data/loop4/gen-0011/candidate.safetensors`): gzip -9 gave 1,309,017 bytes, zstd -19 1,309,440 bytes and xz -9e 1,302,720 bytes.
Rationale: the archive keeps the bytes the trainer wrote, so F16 is out; it would lose precision and change the SHA-256 that `sourceSha256` names. The three compressors are within 0.5 % of each other, because float bits look almost random to all of them. gzip needs no extra tool: Python, Java and every shell read it.
Limit: when a compressor clearly beats gzip on these files, reconsider.

**Rule: Git LFS here, plain git in `web/models/`.**
Origin: `.gitattributes`; the plain-git rule of the browser models is in [docs/models.md](../docs/models.md) ("Rule: plain git, not Git LFS").
Rationale: the archive is 53.0 MB and grows by about 0.25 MB per SMALL (32x4) and 1.3 MB per LARGE (64x6) network. A clone that does not need the files skips them (`GIT_LFS_SKIP_SMUDGE=1`). The web build reads only `web/models/`, three files of about 115 KB, so the site and its CI need no LFS.
Limit: if a single network grows to hundreds of MB, as the race tables of the endgame did (`tablebase/tables/`, [offline mode](../docs/offline-mode.md)), store it outside the repository instead.
