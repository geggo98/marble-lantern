#!/bin/zsh
# Static checks of the CI setup and a secret scan over the whole Git history (plan section 9.5).
# Run through `devenv tasks run ci:security` (pinact, actionlint, zizmor, gitleaks come from devenv).
#
# Checks, in this order; every check runs even when an earlier one failed, and the summary names each result:
#   pinact      every `uses:` is a full commit SHA with a version comment (`pinact run --check`; resolves tags online)
#   actionlint  syntax and expressions of .github/workflows/*.yml (an invalid file is not reported by GitHub; it just does not run)
#   zizmor      static analysis of workflows and dependabot.yml: template injection, permissions, credential persistence
#   gitleaks    secret scan over every commit, configured by .github/gitleaks.toml
# Each check first runs on a workflow or file that is wrong on purpose and must fail there: a scan that cannot find
# anything reports the same as a clean one (rule filter-validation). gitleaks also has its commit count checked
# against git's own count.
#
# usage: security.zsh
# env:   GITHUB_TOKEN or GH_TOKEN  token for the online lookups of pinact and zizmor (read-only is enough).
#        If neither is set and `gh auth token` works, that token is used for the child processes only.
#        Without any token, pinact is reported as FAIL (it cannot resolve tags); zizmor runs --offline and the
#        summary says so.
# Exit: 0 = all checks passed, 1 = at least one check failed or could not run, 2 = wrong call, 130/143 = interrupted.
# No state is kept between runs (about 10 s), so a rerun after a failure just runs everything again.
set -u
setopt pipefail

[[ $# -eq 0 ]] || { print -u2 "usage: security.zsh"; exit 2 }
root=${0:A:h:h:h}
cd $root

# the token goes to the child processes through the environment, never into a command line or the log
if [[ -z ${GITHUB_TOKEN:-} ]]; then
  if [[ -n ${GH_TOKEN:-} ]]; then export GITHUB_TOKEN=$GH_TOKEN
  elif command -v gh >/dev/null && tok=$(gh auth token 2>/dev/null) && [[ -n $tok ]]; then export GITHUB_TOKEN=$tok GH_TOKEN=$tok
  fi
fi
[[ -n ${GH_TOKEN:-} || -z ${GITHUB_TOKEN:-} ]] || export GH_TOKEN=$GITHUB_TOKEN

typeset -a names results
stop=0
trap 'stop=130; print -u2 "security: interrupted, finishing the summary"' INT
trap 'stop=143; print -u2 "security: terminated, finishing the summary"' TERM

work=$(mktemp -d "${TMPDIR:-/tmp}/ci-security.XXXXXX") || { print -u2 "security: mktemp failed"; exit 1 }
trap 'rm -rf $work' EXIT

record() { names+=($1); results+=($2); print -u2 "security: [$2] $1${3:+ ($3)}" }

# usage: control_fails <tool> <what is wrong> <command...>   (the path of the bad workflow is appended to the command)
# The bad workflow has an unpinned action, no runs-on and an untrusted value inside run:. The command must exit
# non-zero, and not with 127 ("command not found").
control_fails() {
  local tool=$1 what=$2; shift 2
  if [[ ! -f $work/ctl/.github/workflows/bad.yml ]]; then
    mkdir -p $work/ctl/.github/workflows
    cat > $work/ctl/.github/workflows/bad.yml <<'BAD'
on: push
jobs:
  x:
    steps:
      - uses: actions/checkout@v4
      - run: echo ${{ github.event.head_commit.message }}
BAD
  fi
  (cd $work/ctl && "$@" .github/workflows/bad.yml) >$work/$tool.control.log 2>&1
  local rc=$?
  if (( rc == 0 || rc == 127 )); then
    record $tool FAIL "control run on a bad workflow ($what) exited $rc: the check cannot fail, so a pass would mean nothing"
    return 1
  fi
  return 0
}

step_pinact() {
  local files; files=(.github/workflows/*.yml(N))
  (( $#files )) || { record pinact FAIL "no workflow file found, the check would see nothing"; return }
  [[ -n ${GITHUB_TOKEN:-} ]] || { record pinact FAIL "no GITHUB_TOKEN/GH_TOKEN and no 'gh auth token': pinact needs the API to resolve tags"; return }
  control_fails pinact "unpinned action" pinact run --check || return
  if pinact run --check $files; then record pinact PASS "$#files workflow files"; else record pinact FAIL "unpinned actions, or the tags could not be resolved"; fi
}

step_actionlint() {
  local files; files=(.github/workflows/*.yml(N))
  (( $#files )) || { record actionlint FAIL "no workflow file found, the check would see nothing"; return }
  control_fails actionlint "runs-on missing" actionlint -no-color || return
  if actionlint -no-color $files; then record actionlint PASS "$#files workflow files"; else record actionlint FAIL; fi
}

step_zizmor() {
  local mode=online
  local -a flags; flags=(--no-progress)
  if [[ -z ${GH_TOKEN:-} ]]; then flags+=(--offline); mode="OFFLINE: no token, the online audits did not run"; fi
  control_fails zizmor "unpinned action, template injection" zizmor $flags || return
  # .github holds the workflows and dependabot.yml; the exit code is non-zero for any finding
  if zizmor $flags .github; then record zizmor PASS $mode; else record zizmor FAIL $mode; fi
}

step_gitleaks() {
  local cfg=.github/gitleaks.toml
  [[ -f $cfg ]] || { record gitleaks FAIL "$cfg is missing"; return }
  # Control: a planted fake token that the ruleset must find. It is built at run time, so the repository never holds
  # a string that looks like a secret.
  mkdir -p $work/plant
  local fake="ghp_$(perl -e 'srand(7); print join "", map { ("a".."z","A".."Z",0..9)[int rand 62] } 1..36')"
  print -r -- "token = \"$fake\"" > $work/plant/planted.txt
  gitleaks dir --no-banner --no-color --redact --exit-code 7 --config $cfg $work/plant >$work/plant.log 2>&1
  local rc=$?
  if (( rc == 0 )); then
    record gitleaks FAIL "control run found nothing in a file with a planted token: the scan cannot see secrets"; return
  elif (( rc != 7 )); then
    record gitleaks FAIL "control run failed with exit $rc: $(tail -n 3 $work/plant.log | tr '\n' ' ')"; return
  fi
  if [[ $(git rev-parse --is-shallow-repository) == true ]]; then
    record gitleaks FAIL "the clone is shallow: the history scan would miss commits (use fetch-depth: 0)"; return
  fi
  # gitleaks scans commits that change a file: no merges, no empty commits. `-- .` leaves empty commits out of git's count too.
  local commits; commits=$(git rev-list --all --no-merges --count -- .)
  (( commits > 0 )) || { record gitleaks FAIL "git rev-list sees no commits"; return }
  gitleaks git --no-banner --no-color --redact --exit-code 7 --config $cfg --log-opts="--all" . >$work/history.log 2>&1
  rc=$?
  local seen; seen=$(perl -ne 'print $+{n} if /\b(?<n>[0-9]+) commits scanned/' $work/history.log | tail -n 1)
  if [[ -z $seen ]] || (( seen != commits )); then
    record gitleaks FAIL "scanned ${seen:-?} commits, git counts $commits; last lines: $(tail -n 3 $work/history.log | tr '\n' ' ')"
  elif (( rc == 0 )); then
    record gitleaks PASS "$seen commits scanned, the control run found the planted token"
  else
    # rerun verbose for the details; --redact keeps the secret itself out of the log
    gitleaks git --no-banner --no-color --redact -v --config $cfg --log-opts="--all" . 2>&1 | grep -E 'RuleID|File|Line|Commit' >&2
    record gitleaks FAIL "exit $rc after $seen commits"
  fi
}

t0=$SECONDS
typeset -a all; all=(pinact actionlint zizmor gitleaks)
for s in $all; do
  (( stop )) && break
  print -u2 "security: --- $s ($(( SECONDS - t0 )) s since start)"
  step_$s
done

typeset -i fails=0; for r in $results; do [[ $r == PASS ]] || fails+=1; done
not_run=$(( $#all - $#results ))
print -u2 "security: $(( $#results - fails )) pass, $fails fail, $not_run not run, $(( SECONDS - t0 )) s"
(( stop )) && exit $stop
(( fails == 0 && not_run == 0 )) && exit 0
exit 1
