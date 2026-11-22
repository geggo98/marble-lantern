# Records of the CPU against GPU power experiment

**TL;DR:** Copies of the records of the experiment that [docs/training.md, CPU against GPU training](../../../docs/training.md#cpu-against-gpu-training-measured-power-and-estimated-energy) cites. On 2026-11-22, 23:14 to 2026-11-23, 00:13 CET, the Gen0 trainer ran alone for 5.5 to 11 minutes per configuration (CPU and GPU, SMALL and LARGE) on the 5-row teacher data, while the energy tools of [`scripts/energy/`](../../../scripts/energy/README.md) recorded the wall power of the whole machine. The step times and the mean power are measured; the kWh of whole runs in docs/training.md are an estimate built on them.

The originals are in the git-ignored `data/net5-cpupower/` (the experiment directory) and `data/energy/` (the energy files of the sampler run `net5-cpu-power`) on the training machine. Both names belong to this experiment; the different spelling has no meaning.

| File | Original | What it holds |
|---|---|---|
| `experiment.zsh` | `data/net5-cpupower/experiment.zsh` | the script that ran the six phases (idle baseline, cpu-small, cpu-large, gpu-small, gpu-large, idle baseline-after) with 60 idle seconds between them; the repository path is replaced by `<repository>` |
| `experiment.log` | `data/net5-cpupower/experiment.log` | the script's progress lines, the last progress line of each Gen0 run, and the energy report |
| `cpu-small/gen0.log` and the same for `cpu-large`, `gpu-small`, `gpu-large` | `data/net5-cpupower/<phase>/gen0.log` | the progress lines of each Gen0 run, the source of the step times; the repository path in the JVM warning about the TornadoVM SDK's `jvmci` jar (GPU phases) is replaced by `<repository>` |
| `gen0-large.patch` | `data/net5-cpupower/gen0-large.patch` | the one switch case that lets Gen0 accept `--preset large`; main does not have it, so the LARGE phases ran from a separate checkout with this change |
| `net5-cpu-power.report.md`, `.json` | `data/energy/` | energy per phase (`scripts/energy/report.py`) |
| `cpupower-analyze.py` | written for this analysis | the power drift per phase (mean power over the counter minutes that lie fully inside the phase, first half against second half; the counters refresh about once a minute) and the steady-state step time per phase; the path of the energy directory is changed to the relative `data/energy` |

## How to rerun

The experiment needs the teacher data of the expert run (`data/net5/teacher/r5-2p-adjacent`, git-ignored, on the training machine only), a checkout with `gen0-large.patch` applied at `<repository>/.worktrees/gen0-large`, and the TornadoVM SDK for the GPU phases (`TORNADOVM_HOME`, set by the devenv shell; see the header of `scripts/loop.sh`). Copy `experiment.zsh` to `data/net5-cpupower/`, replace `<repository>` with the repository path, and run it from any directory. It takes about 59 minutes. Leave the machine alone meanwhile, because every other program counts in the wall power. The script is resumable: a phase with a file `data/net5-cpupower/<phase>.done` is skipped, and an interrupted phase starts again from a clean directory. `experiment.zsh --status` says whether it runs (exit 75 while it runs).

The analysis reads `data/energy/net5-cpu-power.csv` and `.markers.csv`, so run it from the repository root on the training machine:

```zsh
python3 models/runs/net5-cpu-power/cpupower-analyze.py net5-cpu-power models/runs/net5-cpu-power
```

The second argument is the directory that holds `<phase>/gen0.log`. The sampler CSV files are not copied here; they stay on the training machine.

## Limits

- The figures are the wall power of the whole machine, display on, with other programs running (load average 2.1 to 6.5 in the idle baseline).
- The GPU phases ran right after 22 minutes of CPU load and drew more power than the real Gen0 of the expert run (48.5 W against 36.5 W for SMALL), so their power is too high.
- The CPU phases used 14 threads, a best case; Gen0 without `--threads` uses 4.
- Each phase lasted 5.5 or 11 minutes. Whether the power and the step time stay at these values over a run of hours is not measured.

The full discussion of the uncertainty is in the cited section of docs/training.md.
