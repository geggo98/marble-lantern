#!/bin/zsh
# usage: scripts/offline/run-probe.sh [options]
#   --out DIR          output directory (default build/offline/probe; one subdirectory per source)
#   --sources LIST     comma list of best,net,random (default best,net,random; net needs --net or $PROBE_NET)
#   --games N          games per source (default 200)
#   --seed S           seed of the openings (default 33)
#   --threads T        game threads (default: half the cores, at most 4, the Mac load limit of the project)
#   --net FILE         network file for the net source (or $PROBE_NET)
#   --net-spec TEXT    MCTS player text for the net source (default mcts:sims=200,race=1,racerows=2)
#   --spec SPEC        player spec of the best source (default BEST_SPEC at 20000 nodes)
#   --table-dir DIR    directory of rows1.bin and rows2.bin (or $STERNHALMA_TABLE_DIR)
#   --smoke N          games of the smoke run that prices the real run (default 20; 0 = skip)
#   --status           show whether a run is going, and stop
#   --report           print the analysis of the logs in DIR, and stop
#   --foreground       run in the foreground instead of detached
# Run it inside the devenv shell: devenv shell -- scripts/offline/run-probe.sh --table-dir /path/to/tables
#
# What it does: builds the selfplay module, plays a smoke run of N games per source (own directory DIR/smoke), prints the
# estimated run time of the real run from the smoke run's measured rate, then starts the real run detached (a fork with its
# own session: the harness kills background jobs after about ten minutes, a detached run survives). The real run is
# resumable: starting the script again with the same options continues where it stopped. Progress: DIR/<source>.log.
# Estimate: the smoke rate includes the table load, so the estimate is on the high side for long runs; a net or random
# source with few passed positions can be much faster per game than best.
set -eu
root=${0:A:h:h:h}
cd "$root"

out=build/offline/probe sources=best,net,random games=200 seed=33 smoke=20 detach=1 mode=run
cores=$(sysctl -n hw.ncpu 2>/dev/null || echo 4)
threads=$(( cores / 2 < 4 ? (cores / 2 < 1 ? 1 : cores / 2) : 4 ))
net=${PROBE_NET:-} net_spec='mcts:sims=200,race=1,racerows=2' spec='' table_dir=${STERNHALMA_TABLE_DIR:-}
while (( $# )); do
  case $1 in
    --out) out=$2; shift 2;;
    --sources) sources=$2; shift 2;;
    --games) games=$2; shift 2;;
    --seed) seed=$2; shift 2;;
    --threads) threads=$2; shift 2;;
    --net) net=$2; shift 2;;
    --net-spec) net_spec=$2; shift 2;;
    --spec) spec=$2; shift 2;;
    --table-dir) table_dir=$2; shift 2;;
    --smoke) smoke=$2; shift 2;;
    --status) mode=status; shift;;
    --report) mode=report; shift;;
    --foreground) detach=0; shift;;
    -h|--help) perl -ne 'print substr($_, 2) if /^#/ && $. > 1; exit if $. > 1 && !/^#/' "$0"; exit 0;;
    *) echo "unknown option $1" >&2; exit 2;;
  esac
done
if [[ -z $table_dir || ! -f $table_dir/rows1.bin ]]; then
  echo "need --table-dir (or \$STERNHALMA_TABLE_DIR) with rows1.bin and rows2.bin; got '${table_dir}'" >&2
  exit 2
fi

cp="$root/engine/build/classes/java/main:$root/tablebase/build/classes/java/main:$root/search/build/classes/java/main"
cp="$cp:$root/nn/build/classes/java/main:$root/selfplay/build/classes/java/main"
jv=(java -Xmx4g --add-modules jdk.incubator.vector "-Dsternhalma.tablebase.dir=$table_dir" -cp "$cp")

# the extra options of one source go to $reply; returns 1 if the source cannot run
source_args() {
  reply=()
  case $1 in
    best) [[ -n $spec ]] && reply=(--spec "$spec");;
    net)
      if [[ -z $net || ! -f $net ]]; then
        print -u2 "source net skipped: no network file (--net FILE or \$PROBE_NET)"; return 1
      fi
      reply=(--net "$net" --net-spec "$net_spec");;
  esac
  return 0
}

run_source() { # source games dir shard
  local s=$1 g=$2 d=$3 sh=$4
  source_args $s || return 1
  "${jv[@]}" sternhalma.selfplay.RankProbe run --out "$d" --source "$s" --games "$g" --seed "$seed" --threads "$threads" \
    --shard "$sh" --progress 30 "${reply[@]}"
}

list=(${(s:,:)sources})
if [[ $mode == status ]]; then
  for s in $list; do
    echo "== $s"; "${jv[@]}" sternhalma.selfplay.RankProbe run --status --out "$out/$s" || true
  done
  exit 0
fi
if [[ $mode == report ]]; then
  exec "${jv[@]}" sternhalma.selfplay.RankProbe report "$out"/${^list}
fi

./gradlew --no-build-cache -q :selfplay:classes
mkdir -p "$out"

# 1. smoke run: prices the real run
if (( smoke > 0 )); then
  echo "smoke run: $smoke games per source, $threads threads"
  total=0
  for s in $list; do
    source_args $s 2>/dev/null || { echo "  $s: skipped (no network file)"; continue; }
    log=$out/smoke-$s.log
    rm -rf "$out/smoke/$s"
    if ! run_source $s $smoke "$out/smoke/$s" 5 2>"$log"; then
      echo "  $s: smoke run FAILED, see $log" >&2; tail -5 "$log" >&2; exit 1
    fi
    # the summary line of the job: "... 20 items in 1m12s - nothing more to do"
    secs=$(perl -ne 'if (/ (\d+) items in (?:(\d+)h)?(?:(\d+)m)?(?:(\d+)s)?(?:(\d\d)m)?/) { $t = ($2//0)*3600 + ($3//0)*60 + ($4//0) + ($5//0)*60; print $t }' "$log" | tail -1)
    if [[ -z $secs ]]; then echo "  $s: no timing line in $log" >&2; exit 1; fi
    est=$(perl -e 'my ($s, $n, $g) = @ARGV; my $t = $s / $n * $g; printf "%dh%02dm%02ds", $t / 3600, ($t % 3600) / 60, $t % 60' "$secs" "$smoke" "$games")
    lookups=$(perl -ne '$l = $1 if /(\d+) lookups/; END { print $l // 0 }' "$log")
    printf '  %-7s %ss for %s games (%s lookups) -> estimated %s for %s games\n' $s $secs $smoke $lookups $est $games
    total=$(( total + secs * games / smoke ))
  done
  echo "estimated total, sources one after the other: $(perl -e 'printf "%dh%02dm%02ds", $ARGV[0] / 3600, ($ARGV[0] % 3600) / 60, $ARGV[0] % 60' $total)"
fi

# 2. the real run, sources one after the other, resumable
runner=$out/run-all.zsh
{
  echo '#!/bin/zsh'
  echo 'cd '${(q)root}
  for s in $list; do
    source_args $s 2>/dev/null || continue
    echo "echo \"== $s \$(date)\" >> ${(q)out}/$s.log"
    echo "${(j: :)${(q)jv[@]}} sternhalma.selfplay.RankProbe run --out ${(q)out}/$s --source $s --games $games --seed $seed --threads $threads --shard 10 --progress 30 ${(j: :)${(q)reply[@]}} 2>> ${(q)out}/$s.log"
  done
} > "$runner"
chmod +x "$runner"
if (( detach )); then
  python3 - "$runner" "$out/run-all.log" <<'PY'
import os, sys
runner, log = sys.argv[1], sys.argv[2]
if os.fork():
    sys.exit(0)
os.setsid()
if os.fork():
    os._exit(0)
fd = os.open(log, os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o644)
os.dup2(fd, 1); os.dup2(fd, 2)
os.execv("/bin/zsh", ["zsh", runner])
PY
  echo "started detached: $runner"
  echo "status:   scripts/offline/run-probe.sh --status --out $out --table-dir $table_dir"
  echo "progress: tail -n 3 $out/<source>.log"
  echo "report:   scripts/offline/run-probe.sh --report --out $out --table-dir $table_dir"
else
  exec zsh "$runner"
fi
