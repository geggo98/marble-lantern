#!/bin/zsh
# usage: scripts/offline/run-arena-variants.sh [options] NAME VARIANT PAIRS SEED
#   NAME      label of the run; the result file is build/offline/arena/NAME-sSEED-pPAIRS.txt
#   VARIANT   race table variant of side A for both roles (leaf evaluator and race play): exact, rows2, blocks:FILE,
#             estimate[:c0,...,c8], estimate2, maxk:N[:estimate|rows2] (see RaceTableSource)
#   PAIRS     opening pairs (2 games each); a run of 300 pairs takes about 3 minutes
#   SEED      seed of the openings
# Options:
#   --play VARIANT     variant of A's race play, if it differs from VARIANT (VARIANT is then the evaluator's only)
#   --nodes N          node budget of both sides (default 20000)
#   --threads T        game threads (default 4, the Mac load limit of the project)
#   --table-dir DIR    directory of rows1.bin and rows2.bin (or $STERNHALMA_TABLE_DIR)
#   --force            play again although a complete result exists
# Run it inside the devenv shell: devenv shell -- scripts/offline/run-arena-variants.sh ...
#
# A is BEST_SPEC with the given race variants, B is BEST_SPEC with the exact tables, both at the same node budget, so the
# match measures what the variant costs in strength. Resumable by name: a complete result (it has a RESULT line) is not
# played again; the file is written under a temporary name and renamed, so no incomplete file carries the final name.
# Exit codes: 0 = result exists (played now or before), 1 = the match failed, 2 = wrong call.
set -eu
root=${0:A:h:h:h}
cd "$root"

nodes=20000 threads=4 table_dir=${STERNHALMA_TABLE_DIR:-} play='' force=0
while (( $# )); do
  case $1 in
    --play) play=$2; shift 2;;
    --nodes) nodes=$2; shift 2;;
    --threads) threads=$2; shift 2;;
    --table-dir) table_dir=$2; shift 2;;
    --force) force=1; shift;;
    -h|--help) perl -ne 'print substr($_, 2) if /^#/ && $. > 1; exit if $. > 1 && !/^#/' "$0"; exit 0;;
    --) shift; break;;
    -*) echo "unknown option $1" >&2; exit 2;;
    *) break;;
  esac
done
if (( $# != 4 )); then
  echo "usage: $0 [options] NAME VARIANT PAIRS SEED   (see --help)" >&2; exit 2
fi
name=$1 variant=$2 pairs=$3 seed=$4
[[ $name =~ '^[A-Za-z0-9._-]+$' ]] || { echo "NAME may contain letters, digits, . _ - only: '$name'" >&2; exit 2; }
[[ $pairs =~ '^[0-9]+$' && $pairs -gt 0 && $seed =~ '^-?[0-9]+$' ]] || { echo "PAIRS must be a positive integer and SEED an integer" >&2; exit 2; }
[[ -z $play ]] && play=$variant
if [[ -z $table_dir || ! -f $table_dir/rows1.bin ]]; then
  echo "need --table-dir (or \$STERNHALMA_TABLE_DIR) with rows1.bin and rows2.bin; got '${table_dir}'" >&2; exit 2
fi

outdir=build/offline/arena
result=$outdir/$name-s$seed-p$pairs.txt
if (( ! force )) && [[ -f $result ]] && perl -ne '$f = 1 if /^RESULT /; END { exit($f ? 0 : 1) }' "$result"; then
  echo "complete result exists, not played again: $result"
  perl -ne 'print if /^RESULT /' "$result"
  exit 0
fi

# BEST_SPEC of Strategies.java, at the node budget; the check keeps this copy from drifting away from the source
options='pvs=1,killers=1,history=1,lmr=2,asp=1,ttrep=1,mate=1,partial=1'
best="ab:eval=racelin1-r3,$options,fut=1,futk=20,fut2=1,race=1,racerows=1,nodes=$nodes"
perl -0777 -ne 'exit(/OPTIONS\s*=\s*"\Q$ARGV[0]\E"/ && /BEST_SPEC\s*=\s*"ab:eval=racelin1-r3,"\s*\+\s*OPTIONS\s*\+\s*",fut=1,futk=20,fut2=1,race=1,racerows=1"/ ? 0 : 1)' \
  "$options" search/src/main/java/sternhalma/search/Strategies.java \
  || { echo "BEST_SPEC in Strategies.java differs from the copy in this script; update the script" >&2; exit 2; }

./gradlew --no-build-cache -q :search:classes
mkdir -p $outdir
cp="$root/engine/build/classes/java/main:$root/tablebase/build/classes/java/main:$root/search/build/classes/java/main"
tmp=$result.tmp.$$
trap 'rm -f $tmp' EXIT
start=$(perl -MTime::HiRes=time -e 'printf "%.3f", time')
echo "arena $name: A=BEST($nodes nodes) race eval=$variant play=$play vs B=BEST($nodes nodes) exact, $pairs pairs, seed $seed, $threads threads" >&2
rc=0
{
  echo "name=$name variant_eval=$variant variant_play=$play nodes=$nodes pairs=$pairs seed=$seed threads=$threads"
  java -Xmx4g --add-modules jdk.incubator.vector "-Dsternhalma.tablebase.dir=$table_dir" -cp "$cp" sternhalma.search.Arena \
    --a "$best" --b "$best" --pairs "$pairs" --seed "$seed" --threads "$threads" \
    --a-race-eval "$variant" --a-race-play "$play"
} > $tmp || rc=$?
end=$(perl -MTime::HiRes=time -e 'printf "%.3f", time')
if (( rc != 0 )); then
  echo "arena failed (exit $rc); no result written" >&2; exit 1
fi
perl -ne '$f = 1 if /^RESULT /; END { exit($f ? 0 : 1) }' $tmp || { echo "no RESULT line in the output; no result written" >&2; exit 1; }
perl -e 'printf "wall_seconds=%.1f\n", $ARGV[1] - $ARGV[0]' $start $end >> $tmp
mv $tmp $result
trap - EXIT
perl -ne 'print if /^RESULT / || /^wall_seconds=/' $result
echo "result: $result"
