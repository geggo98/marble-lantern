# Records of the five-row expert run

**TL;DR:** Copies of the run records of the 5-row expert network that [docs/training.md, Five-row expert net](../../../docs/training.md#five-row-expert-net) cites. The originals are in the git-ignored `data/net5/` and `data/energy/` on the training machine. The networks of the run are in [`../../nets/net5/`](../../index.md).

| File | Original | What it holds |
|---|---|---|
| `driver.zsh` | `data/net5/driver.zsh` | the driver script that ran pilot, ladder check, teacher data, Gen0, E1, the loop and E2; the repository path on line 6 is replaced by `<repository>` |
| `pilot.txt` | `data/net5/pilot.txt` | game length and move-limit share of 20 teacher games |
| `ladder-check.txt` | `data/net5/ladder-check.txt` | `ab:6400` against `ab:1600` on 5 rows, 100 pairs |
| `e1.txt`, `e2.txt`, `e3.txt` | `data/net5/e1.txt`, `e2.txt`; E3 ran outside the driver | the match summaries of the checks E1, E2 and E3 |
| `decisions.jsonl` | `data/net5/loop-small/gen-NNNN/decision.json` | one line per loop generation 0 to 10: decision, gate score, baseline clause |
| `loop-run.json` | `data/net5/loop-small/run.json` | the configuration of the loop run |
| `half-precision-r5.txt` | output of `HalfPrecisionStudy` | the F16 check of the shipped file on 2,000 5-row positions |
| `net5.report.md`, `.json` | `data/energy/` | energy per phase of the run (`scripts/energy/report.py`) |
| `net5-ship-eval.report.md`, `.json` | `data/energy/` | energy of the E3 ladder run; its "above baseline" column uses the run's own lowest 10-minute mean, which is the load itself, so docs/training.md recomputes it with the 13.64 W baseline of the main run |

The terms E1, E2, E3, Gen0 and `ab:N` are defined in the cited section of docs/training.md. The files are copied unchanged, except for the path in `driver.zsh` and the repository prefix of the net path in `half-precision-r5.txt`.
