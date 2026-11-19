#!/bin/zsh
# Test of scripts/ci/run-with-mirror-fallback.zsh with a stub command instead of Gradle. No network, no Gradle.
# The stub counts its runs, prints what the case says while the mirror init script exists, and succeeds without it.
# Usage: scripts/ci/test/run-with-mirror-fallback.test.zsh      Exit: 0 = all cases pass, 1 = a case failed.
set -u
here=${0:A:h}
wrapper=${here:h}/run-with-mirror-fallback.zsh
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

host=mirror.example.test
fail=0

# case <name> <installed: yes|no> <stub output while the init script exists> <stub exit code> <want runs> <want exit> <want init left: yes|no>
run_case() {
  local name=$1 installed=$2 output=$3 code=$4 want_runs=$5 want_exit=$6 want_init=$7
  local home=$work/$name/home counter=$work/$name/count
  mkdir -p $home/init.d && : > $counter
  [[ $installed == yes ]] && print "def mirrorUrl = 'https://$host/maven2'" > $home/init.d/ci-mirror.init.gradle
  print -r -- "$output" > $work/$name/stub.txt
  cat > $work/$name/stub.zsh <<EOF
#!/bin/zsh
print x >> $counter
if [[ -f $home/init.d/ci-mirror.init.gradle || "$installed" == no ]]; then cat $work/$name/stub.txt; exit $code; fi
print 'resolved from Maven Central'; exit 0
EOF
  chmod +x $work/$name/stub.zsh
  $wrapper --gradle-home $home -- $work/$name/stub.zsh > $work/$name/out 2>&1
  local got_exit=$? got_runs=$(wc -l < $counter | tr -d ' ') got_init=no
  [[ -f $home/init.d/ci-mirror.init.gradle ]] && got_init=yes
  if [[ $got_runs == $want_runs && $got_exit == $want_exit && $got_init == $want_init ]]; then
    print "PASS $name (runs=$got_runs exit=$got_exit init_left=$got_init)"
  else
    print "FAIL $name: runs=$got_runs (want $want_runs) exit=$got_exit (want $want_exit) init_left=$got_init (want $want_init)"
    fail=1
  fi
}

run_case mirror-429      yes "Could not GET 'https://$host/maven2/a/b/1/b-1.jar'. Received status code 429 from server: Too Many Requests" 1  2 0 no
run_case mirror-500      yes "Could not HEAD 'https://$host/maven2/a/b/1/b-1.pom'. Received status code 500" 1                              2 0 no
run_case mirror-timeout  yes "Could not GET 'https://$host/maven2/a/b/1/b-1.jar'. Read timed out" 1                                         2 0 no
run_case jar-missing     yes "Could not find b-1.jar (a:b:1). Searched in: ciMirror $host" 1                                                2 0 no
run_case tampered        yes "Could not GET 'https:///maven2/z'. Received status code 429. Dependency verification failed for configuration ':c' (from $host, ciMirror): checksum differs" 1              1 1 yes
run_case test-failure    yes "FAILED: SearchTest.findsMove AssertionError: expected 3 but was 4" 1                                           1 1 yes
run_case healthy         yes "BUILD SUCCESSFUL" 0                                                                                            1 0 yes
run_case no-init         no  "Could not GET 'https://$host/maven2/a/b/1/b-1.jar'. Received status code 429" 1                                1 1 no
run_case other-host-429  yes "Could not GET 'https://other.example.test/x.jar'. Received status code 429" 1                                  1 1 yes
run_case exit-code-kept  yes "FAILED: compile error" 3                                                                                       1 3 yes

# A failing rerun returns its own code: the mirror failed, and Maven Central fails as well.
home=$work/rerun-fails/home; mkdir -p $home/init.d $work/rerun-fails
print "def mirrorUrl = 'https://$host/maven2'" > $home/init.d/ci-mirror.init.gradle
cat > $work/rerun-fails/stub.zsh <<EOF
#!/bin/zsh
print x >> $work/rerun-fails/count
if [[ -f $home/init.d/ci-mirror.init.gradle ]]; then print "Could not GET 'https://$host/maven2/x'. Received status code 503"; exit 1; fi
print 'FAILED: real test failure'; exit 7
EOF
chmod +x $work/rerun-fails/stub.zsh
$wrapper --gradle-home $home -- $work/rerun-fails/stub.zsh > $work/rerun-fails/out 2>&1
got=$?; runs=$(wc -l < $work/rerun-fails/count | tr -d ' ')
if [[ $got == 7 && $runs == 2 ]]; then print "PASS rerun-fails (runs=$runs exit=$got)"; else print "FAIL rerun-fails: runs=$runs exit=$got"; fail=1; fi

exit $fail
