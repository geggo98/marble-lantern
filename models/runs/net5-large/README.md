# Records of the five-row LARGE run

**TL;DR:** Copies of the run records of the LARGE 5-row network (64 channels, 6 residual blocks) that [docs/training.md, Five-row LARGE net](../../../docs/training.md#five-row-large-net) cites. The run went from 2026-11-22, 23:14 UTC, to 2026-11-23, 20:35 UTC, on the Apple M3 Max of the training machine. The originals are in the git-ignored `data/net5/` and `data/energy/` on that machine. The networks of the run are stored in `models/nets/net5/` (`gen0-large-*` and `loop-large-*`, Git LFS) and listed in the [archive index](../../index.md).

| File | Original | What it holds |
|---|---|---|
| `driver-large.zsh` | `data/net5/driver-large.zsh` | the driver script that ran the idle baseline, Gen0 LARGE, E1, the loop, E2, H2H and E3; the repository path is replaced by `<repository>` |
| `e1.txt`, `e2.txt`, `h2h.txt`, `e3.txt` | `data/net5/e1-large.txt`, `e2-large.txt`, `h2h-large.txt`, `e3-large.txt` | the match summaries of the checks E1, E2, H2H and E3 |
| `equal-time.txt` | output of a match run after the driver (command below) | the best LARGE network at 200 simulations against the shipped SMALL network at 1,000 simulations |
| `decisions.jsonl` | `data/net5/loop-large/gen-NNNN/decision.json` | one line per loop generation 0 to 10: decision, gate score, baseline clause |
| `gen0-run.json`, `loop-run.json` | `data/net5/gen0-large/run.json`, `data/net5/loop-large/run.json` | the configuration of the Gen0 run and of the loop run |
| `gen0-metrics.jsonl`, `loop-metrics.jsonl` | `data/net5/gen0-large/metrics.jsonl`, `data/net5/loop-large/metrics.jsonl` | the event logs of the two runs: proxies, ladder estimates, phase times and step counts |
| `net5-large.report.md`, `.json` | `data/energy/` | energy per phase of the run (`scripts/energy/report.py`) |
| `net5large-analyze.py` | written for this analysis | training steps, training time and wall energy of the training phases (the check of the CPU-against-GPU estimate in docs/training.md) |

The term H2H is defined in the cited section of docs/training.md; Gen0, E1, E2, E3 and `ab:N` in its section [Five-row expert net](../../../docs/training.md#five-row-expert-net). The files are copied unchanged, except for the repository path in `driver-large.zsh`.

## Reproducing the run

The driver needs the teacher data and the SMALL generation 9 of the [five-row expert run](../net5/README.md) (`data/net5/teacher/r5-2p-adjacent` and `data/net5/loop-small/gen-0009/candidate.safetensors`, git-ignored), the TornadoVM SDK for the GPU trainer (`TORNADOVM_HOME`, set by the devenv shell; see the header of `scripts/loop.sh`) and the `+errlog` wrapper of the training machine (it writes the stderr of a command to a log file and returns the command's exit code; without it, delete `+errlog --dir $D/logs --tail 20 --` from the function `run` of the driver). It took 21 h 21 min.

**The Gen0 step used a separate checkout.** When the run started, `Gen0` accepted only `--preset small|tiny`. The driver therefore ran Gen0 from `<repository>/.worktrees/gen0-large`, a checkout of commit `b30673c` with the one switch case of [`../net5-cpu-power/gen0-large.patch`](../net5-cpu-power/gen0-large.patch); every other step ran the scripts of the main checkout. The switch case is now part of `Gen0` (commit `b9b4efa`, "feat(selfplay): Gen0 --preset large builds the 64x6 network"), and no other file that Gen0, the loop or the matches use changed between `b30673c` and that commit. To rerun the driver on a current checkout, replace `$WT/scripts/gen0.sh` by `scripts/gen0.sh` in the Gen0 step (`$WT` is the driver's variable for `<repository>/.worktrees/gen0-large`).

Checked on 2026-11-23: `scripts/gen0.sh run` of the current checkout with the driver's Gen0 options, on a copy of `data/net5/gen0-large`, accepted the stored `run.json` and reported "nothing more to do" (exit 0); with `--lr 1e-3` instead it refused the directory and printed both configurations. This shows that the merged preset produces the same run configuration. It does not show that a fresh run gives the same weights: the GPU trainer was not rerun.

**The equal-time match** ran after the driver, from the repository root:

```zsh
scripts/mctsmatch.sh --out <dir> --net data/net5/loop-large/gen-0010/candidate.safetensors \
  --subject mcts:sims=200,batch=1,cpuct=1.0,noise=0,temp=0,race=0 \
  --vs mcts:sims=1000,batch=1,cpuct=1.0,noise=0,temp=0,race=0,net=data/net5/loop-small/gen-0009/candidate.safetensors \
  --pairs 200 --seed 3003 --threads 14 --rows 5
```

**The analysis** reads the two metrics files of this directory and the sampler file `data/energy/net5-large.csv` (training machine only); run it from the repository root:

```zsh
python3 models/runs/net5-large/net5large-analyze.py models/runs/net5-large data/energy/net5-large.csv
```

Without the sampler file it prints the steps and times only.
