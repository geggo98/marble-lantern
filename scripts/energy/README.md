# Energy measurement for training runs

**TL;DR:** `sampler.py` records the power telemetry of the Mac's battery controller while a training run goes on, `mark.py` marks the start and end of each phase, and `report.py` turns both files into kWh per phase and per run. It measures the whole machine at the wall socket, with a resolution of about one minute. It cannot say how much of that was CPU and how much GPU.

## Why

The owner wants to state, after the training runs, how many kWh each network and each phase of a run (pilot, teacher data, Gen0, each loop generation, checks) cost. A power meter at the socket is not available. `powermetrics` would split CPU, GPU and neural engine, but it needs `sudo`, which the agents on this machine do not have. The battery controller of an Apple Silicon laptop publishes cumulative energy counters without `sudo`; these tools read them. Origin: owner request of 2026-11-22, fields measured the same day on a MacBook Pro with the M3 Max chip, on a 100 W adapter, macOS 26.

## Parts

| Tool | Job |
|---|---|
| `sampler.py` | Polls `ioreg -rn AppleSmartBattery` every 5 s and appends one row per poll to `data/energy/<name>.csv`. Detachable (`--detach`), one instance per name (`flock`, exit 75 when it runs), `--status`, safe on SIGINT and SIGTERM, resumable: a restart appends and writes a `gap` row. |
| `mark.py` | Appends `phase_start <phase>` or `phase_end <phase>` with a timestamp to `data/energy/<name>.markers.csv`: `mark.py start gen0-small`. |
| `report.py` | Reads both files, prints a Markdown table (per phase: start, duration, mean W, peak W, kWh, kWh above the idle baseline) and writes `<name>.report.md` and `<name>.report.json`. |
| `test/test_energy.py` | Offline tests with synthetic CSV files (a gap, a restart, a counter reset) and a stub in place of `ioreg`: `python3 scripts/energy/test/test_energy.py`. |

`<name>` is the run name. All three tools read it from `--name` or from the environment variable `ENERGY_NAME`; the data directory is `data/energy` (git-ignored) or `$ENERGY_DIR`.

```zsh
export ENERGY_NAME=net5
scripts/energy/sampler.py --detach        # once per run; prints the log path
scripts/energy/sampler.py --status        # exit 75 = running, 0 = not running
scripts/energy/mark.py start gen0-small
# ... the step runs ...
scripts/energy/mark.py end gen0-small
scripts/energy/report.py                  # Markdown on stdout, files next to the CSV
scripts/energy/sampler.py --stop          # stops it (clean stop row)
```

Stop the sampler after the last phase has ended. Starting it again later is safe: it appends, and the report treats the time in between as a gap. `--status`, `--stop` and `--detach` act on the sampler of the run name only.

## Which field to use

The controller (`PowerTelemetryData` in `ioreg -rn AppleSmartBattery`) was probed on 2026-11-22. Findings:

1. **The values refresh about once a minute.** Polled every second for three minutes, `SystemPowerIn` and all counters changed only at intervals of 49 s, 60.5 s and 59.7 s. Between those moments `ioreg` returns the same numbers. Polling every 5 s therefore costs little and sees each refresh within 5 s, but a finer sampling gives nothing.
2. **`SystemPowerIn` is a single one-second reading, not a mean.** Examples from one probe: 22.3 W, then 10.7 W, with unchanged values in between. Integrating it over time would use one reading per minute for 60 seconds each. It is the wrong quantity for energy.
3. **The accumulators are the reliable energy counters.** `AccumulatedSystemPowerIn` adds the one-second reading in milliwatt each second; `SystemPowerInAccumulatorCount` counts those seconds (59 or 60 per refresh). The delta of the first divided by the delta of the second was 12.9 W for a minute in which the single reading said 23.2 W. The one-second reading can differ that much from the minute's mean; the accumulator carries the mean.
4. **`AccumulatedSystemEnergyConsumed` is in microwatt hours.** Check: the minute above gave an accumulator delta of 759,902 mW-seconds, which is 759.9 J or 211,084 uWh; `AccumulatedSystemEnergyConsumed` rose by 211,059. Ratio of the two: 3.6, as 1 uWh is 3.6 mJ.
5. **`AccumulatedWallEnergyEstimate` is the same energy plus the adapter's loss, also in microwatt hours.** It rose by 222,226 uWh while the system counter rose by 211,059 (5.3 % more; over an 8-minute run 7.6 % more). One reading shows the sum: `SystemEnergyConsumed` 3436 + `AdapterEfficiencyLoss` 171 = `WallEnergyEstimate` 3607 (uWh per second). So `SystemPowerIn` is the power after the adapter, and the wall figure is an estimate that adds a modelled adapter efficiency.
6. **`BatteryPower` is signed and wraps.** Some counters (`AccumulatedBatteryDischarge`) are unsigned 64-bit values that hold negative numbers (18446744073661083606). The sampler converts `BatteryPower` with two's complement.

**Choice:** the report integrates `AccumulatedWallEnergyEstimate` (default, `--metric wall`) or `AccumulatedSystemEnergyConsumed` (`--metric system`). It never integrates `SystemPowerIn`. The CSV keeps `SystemPowerIn` for display only. Wall is the default because the question is "what does the run cost on the electricity bill"; its figure rests on the controller's adapter model (5 to 8 % above the system energy in the probes), so system energy is the more direct measurement and the two differ by that factor only.

## What the numbers mean

- **Whole system.** Display, keyboard backlight, fans, SSD, USB devices, background processes and every other program on the machine count, not only the training. The machine is shared with other work during the runs (see the notes in `docs/training.md` on time-based measurements). A phase's kWh is therefore an upper bound for the training itself.
- **Baseline.** `report.py` subtracts a baseline power times the covered time to get "kWh above baseline". The baseline is the mean power of the phase `baseline` (about 10 idle minutes recorded before a run) when it has at least 2 minutes of data. Otherwise it is the lowest 10-minute mean in the file; the report prints which one it used. If the machine was never quiet for 10 minutes, the lowest window is above the idle power and "kWh above baseline" comes out too small.
- **Resolution.** One counter step per minute. A phase shorter than a minute gets a share of one step; its mean is the mean of that minute, which includes other work before or after the phase. Phase boundaries are placed within ±(poll interval / 2) = 2.5 s of the real step time, and an energy step is spread evenly over its minute. The error at each boundary is at most the power difference between the two phases times about 30 s. For phases of 10 minutes or longer that is below 5 %; below 2 minutes the figures are indications only. **Peak W** is the highest one-minute mean, not an instantaneous peak.
- **Battery.** On mains with a full battery the system power is the wall power. While the battery charges, the adapter delivers more than the system uses; while it discharges, less. The sampler flags a poll with `batt` when the adapter is not connected, the battery charges, or `|BatteryPower|` exceeds 1 W. The report names the share of flagged time per phase and a warning. For clean figures, run on mains with the battery at the charge limit.
- **Gaps.** A stretch of more than 3 poll intervals (15 s) without a valid poll, a sampler restart, a stop row, or a counter that goes down (a reboot) ends a segment. The report attributes no energy across the end of a segment, even though the counter went on counting, and lists each gap with the counter's change over it. A phase that overlaps a gap shows "only N % of the phase has data" and its mean is taken over the covered time. The first and last minute of a segment is lost, because the energy before the first counter step cannot be placed.
- **Sleep.** A sleeping machine is a gap. The runbook driver does not prevent sleep; start long runs with `caffeinate` or check the gap table.
- **No split by component.** The counters have no CPU, GPU or memory share. The tools cannot say how much of a phase went to the GPU trainer and how much to the CPU search. Doing that needs `powermetrics --samplers cpu_power,gpu_power` with `sudo`, which is not available here.

## Files

`<name>.csv` columns: `ts_utc, epoch, kind, system_power_mw, battery_power_mw, external, charging, battery_pct, acc_wall_uwh, acc_system_uwh, acc_count, loadavg1, flag`. `kind` is `sample`, `start` (sampler started, flag `interval=S`), `gap` (a restart; flag `previous_epoch=...`), `stop` or `error` (a poll that failed; the reason is in `flag`). The header of each tool's docstring lists all options and exit codes.

The data directory holds `<name>.csv`, `<name>.markers.csv`, `<name>.log` (detached sampler), `.<name>.lock`, `.<name>.status` and the report files.

## Measured check

On 2026-11-22 the tools ran for 8 minutes on the development machine: 16 `yes > /dev/null` processes (one per core) as load, idle before and after, phases marked with `mark.py`, then `report.py`. The machine was not quiet: other programs ran at the same time (load average 8 to 12 during the run), so "idle" here is a desktop at 13 to 17 W, not the bare machine at about 12 W.

| phase | covered | mean W (wall) | peak W | kWh |
|---|---|---|---|---|
| idle before | 1 min 34 s | 16.6 | 20.8 | 0.0004 |
| 16 `yes` processes | 2 min 10 s | 32.2 | 32.6 | 0.0012 |
| idle after | 1 min 46 s | 13.5 | 13.8 | 0.0004 |

The load added about 16 to 19 W. Whole file: 0.0029 kWh in 8 minutes at 21.7 W mean. The "covered" time is below the phase duration at the start of the file (the first counter step is the earliest usable point) and right after a load change, where the step that straddles the change is not attributed to either side. A first run, with phases that touched the load changes, put the minute after the load stopped at 38 W instead of 14 W; that is why the report's resolution is one minute and why phase edges should not be placed directly on a load change.

Other checks: over the 8 minutes, the wall counter rose by 3.169 Wh and the system counter by 2.945 Wh (ratio 1.076); the mean of the 114 single `SystemPowerIn` readings was 19.1 W against 19.8 W from the system counter (`SystemPowerInAccumulatorCount` rose by 535 s). A second sampler start while one ran exited with 75, `--stop` ended the sampler with a clean stop row, `--status` then said "not running". The unit tests (`python3 scripts/energy/test/test_energy.py`, 31 tests) check the report against hand-computed energies and kill nine deliberate faults of the code (see the commit message for the list).
