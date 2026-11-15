#!/usr/bin/env bash
# Launches ONE spot instance, runs scripts/cloud/bootstrap.sh on it, collects the results and removes everything again.
# Nothing is created unless you run "run" without --dry-run and confirm. See docs/cloud.md.
#
# usage:
#   aws-spot.sh preflight --instance-type T[,T2...] [--region R] [--max-price USD] [--backend cuda|none]
#       read-only: identity (the account id is never printed), spot quota of the instance family, spot price history,
#       the AMI parameter, and "aws ec2 run-instances --dry-run" (an EC2 permission check, creates nothing)
#   aws-spot.sh run --instance-type T --max-price USD --max-minutes N [--backend cuda|none] [--suite default|cpp]
#                   [--real-weights FILE] [--on-demand] [--dry-run] [--yes]
#       --dry-run prints every command that a real run would execute and runs none (it needs no credentials)
#   aws-spot.sh cleanup --run-id ID
#       deletes whatever is left of a run (instance, spot request, security group, key pair), found by its tags
#
# options:
#   --instance-type  an x86_64 type, for example g4dn.xlarge (NVIDIA T4) or c7i.xlarge (AVX-512); Arm types are refused
#   --max-price      the spot price cap in USD per hour (required, no default); at most 2.00
#   --max-minutes    hard runtime in minutes (required, no default), 15 to 240: the instance shuts itself down
#                    ("shutdown -h +N" from the user data) and terminates (instance-initiated shutdown = terminate)
#   --backend        cuda (default for g*/p* types) or none (CPU only: Vector API tests and benchmarks)
#   --budget-usd     refuse a run whose worst case max-price * max-minutes / 60 is above this (default 2.50)
#   --region         default $AWS_REGION; --ami to override the AMI (default: AWS public SSM parameter, see below)
#   --feed-seconds   length of each gpuFeed row in seconds, an integer 10 to 600 (default 60)
#   --suite          default (the earlier runs) or cpp (the C++ comparison, needs the cuda backend; bootstrap.sh --suite)
#   --real-weights   cpp suite only: a .safetensors file (at most 16 MB) that is uploaded with the source tarball; its SHA-256 is
#                    computed here, checked on the instance after the upload (a difference stops the run before anything is
#                    started) and written to <run dir>/real-weights.sha256. The file never goes into the repository
#   --on-demand      on-demand instead of spot; --max-price is then the assumed price per hour for the budget check
#   --yes            do not ask before spending
#
# configuration only through the environment or the repository's .env (names, no values here): AWS_PROFILE, AWS_REGION.
# The AMI: cuda = the AWS Deep Learning Base OSS Nvidia Driver GPU AMI (Ubuntu 22.04: NVIDIA driver and CUDA Toolkit
# preinstalled, no extra charge); none = the Canonical Ubuntu 22.04 AMI. Both are resolved from AWS public SSM
# parameters at run time.
#
# Resources of a run (all tagged Project=sternhalma-cloud, RunId=<id>): one spot request (one-time), one instance with
# its root volume, one security group (SSH from this machine's public address only) and one key pair, the last two
# created for the run and deleted at its end. No Session Manager, no IAM role, no bucket (public SSM parameters are read for the AMI id only). The script cleans up on every exit
# (also Ctrl-C, SIGHUP when the terminal closes, SIGTERM and errors) and ends with a verification that nothing is left.
# Results are fetched when the run is DONE, once more about 3 minutes before the instance's hard deadline (a hung step
# would otherwise lose every log) and once at the end; every transfer is time-bounded.
# The cost of every run that launched an instance is written to <run dir>/cost.tsv (one line, tab separated: suite, launched
# (ISO time, UTC), seconds, price_usd_per_h, estimate_usd, worst_case_usd); a first line with the worst case is written at the
# launch, so that a run that dies still counts. scripts/cloud/spot-retry.py sums these files for its cumulative cap.
# Exit codes: 0 ok, 1 failure or something left over, 2 usage, 3 pre-flight finding (quota, credentials, AMI),
# 4 no capacity or no quota, nothing was launched (InsufficientInstanceCapacity, MaxSpotInstanceCountExceeded,
# VcpuLimitExceeded, SpotMaxPriceTooLow from run-instances).
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
root=$(cd "$here/../.." && pwd)

usage() { sed -n '2,50p' "${BASH_SOURCE[0]}" >&2; exit 2; }

# --- configuration from .env (only the two names we use; the file is never executed) ------------------------------------
env_value() {
  [ -f "$root/.env" ] || return 0
  perl -ne 'if (/^\s*(?:export\s+)?'"$1"'\s*=\s*(.*?)\s*$/) { $v = $1; $v =~ s/^(["\x27])(.*)\1$/$2/; print $v; exit }' "$root/.env"
}
[ -n "${AWS_PROFILE:-}" ] || { v=$(env_value AWS_PROFILE); [ -z "$v" ] || export AWS_PROFILE=$v; }
[ -n "${AWS_REGION:-}" ] || { v=$(env_value AWS_REGION); [ -z "$v" ] || export AWS_REGION=$v; }

# --- arguments -------------------------------------------------------------------------------------------------------
[ $# -ge 1 ] || usage
CMD=$1; shift
INSTANCE_TYPES=""; MAX_PRICE=""; MAX_MIN=""; BACKEND=""; BUDGET_USD=2.50; REGION=${AWS_REGION:-}; AMI=""
DRY_RUN=0; YES=0; RUN_ID_ARG=""; FEED_SECONDS=60; ON_DEMAND=0; SUITE=default; REAL_WEIGHTS=""
while [ $# -gt 0 ]; do
  case $1 in
    --instance-type) INSTANCE_TYPES=${2:-}; shift 2 ;;
    --max-price) MAX_PRICE=${2:-}; shift 2 ;;
    --max-minutes) MAX_MIN=${2:-}; shift 2 ;;
    --on-demand) ON_DEMAND=1; shift ;;   # on-demand instead of spot: --max-price is then the assumed price per hour for the budget check
    --backend) BACKEND=${2:-}; shift 2 ;;
    --budget-usd) BUDGET_USD=${2:-}; shift 2 ;;
    --region) REGION=${2:-}; shift 2 ;;
    --ami) AMI=${2:-}; shift 2 ;;
    --run-id) RUN_ID_ARG=${2:-}; shift 2 ;;
    --feed-seconds) FEED_SECONDS=${2:-}; shift 2 ;;
    --suite) SUITE=${2:-}; shift 2 ;;
    --real-weights) REAL_WEIGHTS=${2:-}; shift 2 ;;
    --dry-run) DRY_RUN=1; shift ;;
    --yes) YES=1; shift ;;
    -h|--help) usage ;;
    *) echo "unknown option: $1" >&2; usage ;;
  esac
done
case $CMD in preflight|run|cleanup) ;; *) usage ;; esac
[ -n "$REGION" ] || { echo "no region: set AWS_REGION (in .env or the environment) or pass --region" >&2; exit 2; }
# Every value that reaches an aws call, the user data (runs as root on the instance), ssh or scp is validated here, by an
# anchored pattern (\A..\z: a trailing newline does not pass). Nothing unvalidated is ever pasted into the user data.
matches() { perl -e 'exit($ARGV[0] =~ /\A$ARGV[1]\z/ ? 0 : 1)' "$1" "$2"; }
matches "$REGION" '[a-z]{2}(-[a-z]+)+-\d' || { echo "error: --region / AWS_REGION is not a region name like eu-central-1" >&2; exit 2; }
matches "$FEED_SECONDS" '\d{1,3}' && [ "$FEED_SECONDS" -ge 10 ] && [ "$FEED_SECONDS" -le 600 ] \
  || { echo "error: --feed-seconds must be an integer from 10 to 600" >&2; exit 2; }
FEED_SECONDS=$((10#$FEED_SECONDS))   # no leading zeros (octal in arithmetic)
matches "$SUITE" 'default|cpp' || { echo "error: --suite must be default or cpp" >&2; exit 2; }
[ -z "$REAL_WEIGHTS" ] || [ "$SUITE" = cpp ] || { echo "error: --real-weights needs --suite cpp" >&2; exit 2; }
[ -z "$AMI" ] || matches "$AMI" 'ami-[0-9a-f]{8,17}' || { echo "error: --ami must look like ami-0123456789abcdef0" >&2; exit 2; }
[ -z "$RUN_ID_ARG" ] || matches "$RUN_ID_ARG" 'sh-\d{8}-\d{6}-\d{1,5}' || { echo "error: --run-id must look like sh-YYYYMMDD-HHMMSS-N" >&2; exit 2; }
[ -z "$INSTANCE_TYPES" ] || matches "$INSTANCE_TYPES" '[a-z0-9]+\.[a-z0-9]+(,[a-z0-9]+\.[a-z0-9]+)*' || { echo "error: --instance-type must look like g4dn.xlarge (comma separated for preflight)" >&2; exit 2; }
export AWS_DEFAULT_REGION=$REGION AWS_PAGER=""
# Never fall back to the CLI's default profile: it may belong to another account. Only "run --dry-run" makes no aws call at
# all; preflight and cleanup (also with --dry-run) do, so they need the guard too.
if ! { [ "$CMD" = run ] && [ "$DRY_RUN" = 1 ]; }; then
  if [ -z "${AWS_PROFILE:-}" ] && [ -z "${AWS_ACCESS_KEY_ID:-}" ]; then
    echo "no AWS_PROFILE (and no AWS_ACCESS_KEY_ID): set AWS_PROFILE in .env or the environment" >&2; exit 2
  fi
  if [ -n "${AWS_PROFILE:-}" ]; then
    # reads the local AWS config only (no network call); an undefined profile would otherwise fall back to the defaults
    # "configure list --profile" works in AWS CLI v1 and v2 (v1 has no "configure list-profiles"): it fails for an unknown profile
    if ! aws configure list --profile "$AWS_PROFILE" > /dev/null 2>&1; then
      echo "error: the profile named by AWS_PROFILE is not defined in the AWS config (see: aws configure list); fix AWS_PROFILE in .env or the environment" >&2; exit 2
    fi
  fi
fi

TAG_PROJECT=sternhalma-cloud
SSM_AMI_CUDA=/aws/service/deeplearning/ami/x86_64/base-oss-nvidia-driver-gpu-ubuntu-22.04/latest/ami-id
SSM_AMI_NONE=/aws/service/canonical/ubuntu/server/22.04/stable/current/amd64/hvm/ebs-gp2/ami-id
SSH_USER=ubuntu
REMOTE=/var/tmp/sh

is_number() { perl -e 'exit($ARGV[0] =~ /\A\d+(\.\d+)?\z/ ? 0 : 1)' "$1"; }
fail() { echo "error: $*" >&2; exit "${2:-2}"; }

first_type=${INSTANCE_TYPES%%,*}
[ "$CMD" = cleanup ] || [ -n "$first_type" ] || fail "--instance-type is required"
check_type() {
  case $1 in
    *.*) ;;
    *) fail "instance type '$1' is not of the form family.size" ;;
  esac
  family=${1%%.*}
  # Arm (Graviton) families carry a 'g' after the generation digit: c7g, m6gd, g5g. The GPU family g4dn / g5 / g6 does not.
  suffix=$(perl -e '($f) = $ARGV[0] =~ /^[a-z]+\d+(.*)$/; print $f // ""' "$family")
  case $suffix in *g*) fail "'$1' is an Arm type; the NVIDIA and AVX tests need x86_64" ;; esac
}
IFS=, read -r -a TYPE_LIST <<< "$INSTANCE_TYPES"
for t in ${TYPE_LIST[@]+"${TYPE_LIST[@]}"}; do check_type "$t"; done
if [ -z "$BACKEND" ] && [ -n "$first_type" ]; then
  case $first_type in g*|p*) BACKEND=cuda ;; *) BACKEND=none ;; esac
fi
case ${BACKEND:-none} in cuda|none) ;; *) fail "--backend must be cuda or none" ;; esac
[ "$SUITE" = default ] || [ "${BACKEND:-none}" = cuda ] || fail "--suite cpp needs the cuda backend"

# --- command runners --------------------------------------------------------------------------------------------------
quote() { printf '%q ' "$@"; }
# ro: a read-only call (never changes anything in the account); in --dry-run it is printed, not run
ro() {
  if [ "$DRY_RUN" = 1 ]; then echo "DRY-RUN (read-only): $(quote "$@")" >&2; return 0; fi
  "$@"
}
# mut: a call that creates, changes or deletes something; in --dry-run it is only printed
mut() {
  if [ "$DRY_RUN" = 1 ]; then echo "DRY-RUN: $(quote "$@")" >&2; return 0; fi
  "$@"
}
log() { printf '[%s] %s\n' "$(date -u +%H:%M:%S)" "$*" >&2; }

# --- pre-flight (read-only) ---------------------------------------------------------------------------------------------
quota_pattern() {  # the family of an instance type -> a regex over the names of the Service Quotas
  case $1 in
    g*|vt*) echo 'G and VT' ;;
    p*) echo 'P4|P5|P ' ;;
    *) echo 'Standard' ;;
  esac
}
quota_report() {  # type price -> prints quota findings; returns 1 if the spot quota is below the vCPUs of the type
  local type=$1 vcpus pattern rows status=0 value name
  vcpus=$(aws ec2 describe-instance-types --instance-types "$type" --query 'InstanceTypes[0].VCpuInfo.DefaultVCpus' --output text 2>/dev/null || echo "?")
  pattern=$(quota_pattern "${type%%.*}")
  rows=$(aws service-quotas list-service-quotas --service-code ec2 --max-items 500 \
    --query "Quotas[?contains(QuotaName, 'Spot')].[QuotaName,Value]" --output text 2>/dev/null | perl -ne "print if /$pattern/" || true)
  if [ -z "$rows" ]; then
    echo "  quota: could not read the Service Quotas for $type (no permission or no match); check the console" >&2
    return 0
  fi
  while IFS=$'\t' read -r name value; do
    echo "  quota: $name = $value (the type needs $vcpus vCPU)"
    if [ "$vcpus" != "?" ] && perl -e 'exit(($ARGV[0] + 0) < ($ARGV[1] + 0) ? 0 : 1)' "$value" "$vcpus"; then
      echo "  FINDING: the spot quota ($value vCPU) is below the $vcpus vCPU of $type. New accounts often have 0 for G and VT: request an increase in Service Quotas (can take days), or choose another type." >&2
      status=1
    fi
  done <<< "$rows"
  return $status
}
price_report() {  # type -> spot price history of the last 3 hours per availability zone
  local type=$1 start
  start=$(perl -MPOSIX=strftime -e 'print strftime("%Y-%m-%dT%H:%M:%SZ", gmtime(time - 3*3600))')
  aws ec2 describe-spot-price-history --instance-types "$type" --product-descriptions "Linux/UNIX" --start-time "$start" \
    --query 'SpotPriceHistory[].[AvailabilityZone,SpotPrice]' --output text 2>/dev/null |
    perl -ane '$z=$F[0]; $p=$F[1]; push @{$a{$z}}, $p; END { for $z (sort keys %a) { @p=sort {$a<=>$b} @{$a{$z}}; printf "  spot price %s: min %.4f, max %.4f USD/h over %d changes (last 3 h)\n", $z, $p[0], $p[-1], scalar @p } print "  spot price: no history returned (type not offered in this region?)\n" unless %a }'
}
ami_param() { [ "${BACKEND:-none}" = cuda ] && echo "$SSM_AMI_CUDA" || echo "$SSM_AMI_NONE"; }
# The AMI: the --ami option, else the public SSM parameter; if that cannot be read and the backend is cuda, the newest Deep Learning
# AMI by name (the id is printed by the caller, never committed).
resolve_ami() {
  local id
  if [ -n "$AMI" ]; then echo "$AMI"; return; fi
  if id=$(aws ssm get-parameter --name "$(ami_param)" --query 'Parameter.Value' --output text 2> /dev/null) && [ -n "$id" ] && [ "$id" != None ]; then
    echo "$id"; return
  fi
  [ "${BACKEND:-none}" = cuda ] || return 1
  id=$(aws ec2 describe-images --owners amazon --filters "Name=name,Values=Deep Learning Base OSS Nvidia Driver GPU AMI (Ubuntu 22.04)*" "Name=state,Values=available" \
    --query 'sort_by(Images,&CreationDate)[-1].ImageId' --output text 2> /dev/null) || return 1
  [ -n "$id" ] && [ "$id" != None ] || return 1
  echo "$id"
}

preflight() {
  local rc=0 type ami out
  log "pre-flight in $REGION (read-only)"
  if aws sts get-caller-identity --query 'Arn' --output text > /dev/null 2>&1; then
    echo "  credentials: OK (sts get-caller-identity succeeded; account id not shown)"
  else
    echo "  FINDING: aws sts get-caller-identity failed (profile '${AWS_PROFILE:-<unset>}'): $(aws sts get-caller-identity 2>&1 | tail -1)" >&2
    return 3
  fi
  if ami=$(resolve_ami 2> /dev/null); then
    echo "  AMI: $(ami_param) -> $ami"
  else
    echo "  FINDING: could not read the AMI parameter $(ami_param); pass --ami" >&2
    rc=3; ami=""
  fi
  for type in "${TYPE_LIST[@]}"; do
    echo "type $type:"
    price_report "$type" || true
    quota_report "$type" || rc=3
    if [ -n "$ami" ]; then
      out=$(aws ec2 run-instances --dry-run --image-id "$ami" --instance-type "$type" --count 1 \
        --instance-market-options 'MarketType=spot,SpotOptions={SpotInstanceType=one-time,InstanceInterruptionBehavior=terminate,MaxPrice='"${MAX_PRICE:-1.00}"'}' 2>&1 || true)
      case $out in
        *DryRunOperation*) echo "  run-instances --dry-run: OK (permission and parameters accepted; nothing was created)" ;;
        *) echo "  FINDING: run-instances --dry-run: $(echo "$out" | tail -1)" >&2; rc=3 ;;
      esac
    fi
  done
  return $rc
}

# --- cleanup ----------------------------------------------------------------------------------------------------------------
RUN_ID=""; KEY_DIR=""; INSTANCE_ID=""; SG_ID=""; KEY_NAME=""; LAUNCHED_AT=""; AZ=""
cleanup_run() {  # RUN_ID -> deletes everything tagged with it; idempotent
  local id=$1 ids sir
  log "cleanup of $id"
  ids=$(aws ec2 describe-instances --filters "Name=tag:RunId,Values=$id" "Name=instance-state-name,Values=pending,running,stopping,stopped,shutting-down" \
    --query 'Reservations[].Instances[].InstanceId' --output text 2>/dev/null || true)
  sir=$(aws ec2 describe-spot-instance-requests --filters "Name=tag:RunId,Values=$id" "Name=state,Values=open,active,disabled" \
    --query 'SpotInstanceRequests[].SpotInstanceRequestId' --output text 2>/dev/null || true)
  # shellcheck disable=SC2086  # the lists are space-separated IDs
  [ -z "$sir" ] || mut aws ec2 cancel-spot-instance-requests --spot-instance-request-ids $sir > /dev/null
  # shellcheck disable=SC2086
  if [ -n "$ids" ]; then
    mut aws ec2 terminate-instances --instance-ids $ids > /dev/null
    mut aws ec2 wait instance-terminated --instance-ids $ids || true
  fi
  SG_ID=$(aws ec2 describe-security-groups --filters "Name=tag:RunId,Values=$id" --query 'SecurityGroups[].GroupId' --output text 2>/dev/null || true)
  for sg in $SG_ID; do
    for _ in 1 2 3 4 5 6 7 8; do
      mut aws ec2 delete-security-group --group-id "$sg" 2> /dev/null && break
      sleep 10
    done
  done
  KEY_NAME=$(aws ec2 describe-key-pairs --filters "Name=tag:RunId,Values=$id" --query 'KeyPairs[].KeyName' --output text 2>/dev/null || true)
  for k in $KEY_NAME; do mut aws ec2 delete-key-pair --key-name "$k" > /dev/null; done
  [ -z "$KEY_DIR" ] || rm -rf "$KEY_DIR"
}
verify_once() {  # RUN_ID -> lists leftovers, returns 1 if there are any
  local id=$1 left=0 out
  log "verification: nothing may be left of $id"
  out=$(aws ec2 describe-instances --filters "Name=tag:RunId,Values=$id" "Name=instance-state-name,Values=pending,running,stopping,stopped,shutting-down" --query 'Reservations[].Instances[].[InstanceId,State.Name]' --output text)
  [ -z "$out" ] || { echo "  LEFT: instance $out" >&2; left=1; }
  out=$(aws ec2 describe-volumes --filters "Name=tag:RunId,Values=$id" --query 'Volumes[].[VolumeId,State]' --output text)
  [ -z "$out" ] || { echo "  LEFT: volume $out" >&2; left=1; }
  out=$(aws ec2 describe-security-groups --filters "Name=tag:RunId,Values=$id" --query 'SecurityGroups[].GroupId' --output text)
  [ -z "$out" ] || { echo "  LEFT: security group $out" >&2; left=1; }
  out=$(aws ec2 describe-key-pairs --filters "Name=tag:RunId,Values=$id" --query 'KeyPairs[].KeyName' --output text)
  [ -z "$out" ] || { echo "  LEFT: key pair $out" >&2; left=1; }
  out=$(aws ec2 describe-spot-instance-requests --filters "Name=tag:RunId,Values=$id" "Name=state,Values=open,active,disabled" --query 'SpotInstanceRequests[].SpotInstanceRequestId' --output text)
  [ -z "$out" ] || { echo "  LEFT: spot request $out" >&2; left=1; }
  [ "$left" = 0 ] && echo "  nothing left: no instance, volume, security group, key pair or open spot request with RunId=$id" >&2
  return $left
}
# The state of a just cancelled spot request or a just terminated instance settles a few seconds later: retry before reporting a leftover.
verify_clean() {
  local id=$1 try=1 max=${VERIFY_TRIES:-6}
  while ! verify_once "$id"; do
    [ "$try" -lt "$max" ] || return 1
    log "something still shows up (the state may still be settling); checking again in ${VERIFY_SLEEP:-10} s ($try/$max)"
    sleep "${VERIFY_SLEEP:-10}"; try=$((try + 1))
  done
}

if [ "$CMD" = preflight ]; then
  preflight; exit $?
fi
if [ "$CMD" = cleanup ]; then
  [ -n "$RUN_ID_ARG" ] || fail "--run-id is required"
  cleanup_run "$RUN_ID_ARG"; verify_clean "$RUN_ID_ARG"; exit $?
fi

# --- run ----------------------------------------------------------------------------------------------------------------------
[ "${#TYPE_LIST[@]}" = 1 ] || fail "run takes exactly one --instance-type"
[ -n "$MAX_PRICE" ] || fail "--max-price is required (USD per hour, no default)"
[ -n "$MAX_MIN" ] || fail "--max-minutes is required (no default)"
is_number "$MAX_PRICE" || fail "--max-price must be a number"
is_number "$BUDGET_USD" || fail "--budget-usd must be a number"
matches "$MAX_MIN" '\d{1,4}' || fail "--max-minutes must be an integer"
MAX_MIN=$((10#$MAX_MIN))   # no leading zeros (octal in the user data's arithmetic)
[ "$MAX_MIN" -ge 15 ] && [ "$MAX_MIN" -le 240 ] || fail "--max-minutes must be between 15 and 240"
perl -e 'exit($ARGV[0] <= 2.0 ? 0 : 1)' "$MAX_PRICE" || fail "--max-price above 2.00 USD/h is refused"
WORST=$(perl -e 'printf "%.2f", $ARGV[0] * $ARGV[1] / 60' "$MAX_PRICE" "$MAX_MIN")
perl -e 'exit($ARGV[0] <= $ARGV[1] ? 0 : 1)' "$WORST" "$BUDGET_USD" || fail "worst case $WORST USD (max-price x max-minutes / 60) is above the budget $BUDGET_USD USD"
EUR_PER_USD=${EUR_PER_USD:-0.90}   # an assumption for the estimate only; set it to the current rate

# the real network weights (cpp suite, optional): a regular .safetensors file of at most 16 MiB. Its SHA-256 is computed here before
# the upload; the name of the file is not recorded anywhere, only the hash.
REAL_SHA=""
if [ -n "$REAL_WEIGHTS" ]; then
  [ -f "$REAL_WEIGHTS" ] && [ ! -L "$REAL_WEIGHTS" ] || fail "--real-weights: not a regular file"
  case $REAL_WEIGHTS in *.safetensors) ;; *) fail "--real-weights: the file name must end in .safetensors" ;; esac
  [ "$(perl -e 'print -s $ARGV[0]' "$REAL_WEIGHTS")" -le 16777216 ] || fail "--real-weights: more than 16 MiB"
  [ -s "$REAL_WEIGHTS" ] || fail "--real-weights: the file is empty"
  REAL_SHA=$(perl -MDigest::SHA -e 'print Digest::SHA->new(256)->addfile($ARGV[0])->hexdigest' "$REAL_WEIGHTS")
  matches "$REAL_SHA" '[0-9a-f]{64}' || fail "--real-weights: could not compute the SHA-256"
fi

# cost of the run: <run dir>/cost.tsv, one line: suite, launched (ISO, UTC), seconds, price_usd_per_h, estimate_usd, worst_case_usd.
# Written at the launch (worst case, so that a run that dies still counts) and again at the end (elapsed time times the price).
COST_FINAL=0; COST_PRICE=""; COST_PRICE_READ=0
cost_price() {   # the hourly price: on-demand = the assumed price of --max-price, spot = the latest price of the zone
  [ "$COST_PRICE_READ" = 1 ] && return 0
  COST_PRICE_READ=1
  if [ "$ON_DEMAND" = 1 ]; then COST_PRICE=$MAX_PRICE
  elif [ -n "$AZ" ]; then
    COST_PRICE=$(aws ec2 describe-spot-price-history --instance-types "$TYPE" --availability-zone "$AZ" --product-descriptions "Linux/UNIX" --max-items 1 \
      --query 'SpotPriceHistory[0].SpotPrice' --output text 2> /dev/null || echo "")
  fi
  [ "$COST_PRICE" != None ] || COST_PRICE=""
}
write_cost_file() {   # provisional|final
  local phase=$1 secs=0 price=unknown est="$WORST" iso tmp
  [ -n "$LAUNCHED_AT" ] && [ "$DRY_RUN" = 0 ] || return 0
  if [ "$phase" = final ]; then
    secs=$(( $(date +%s) - LAUNCHED_AT )); [ "$secs" -ge 0 ] || secs=0
    cost_price
    if [ -n "$COST_PRICE" ]; then
      price=$COST_PRICE
      est=$(perl -e '$e = $ARGV[0] / 3600 * $ARGV[1]; $e = $ARGV[2] if $e > $ARGV[2]; printf "%.4f", $e' "$secs" "$price" "$WORST")
    fi
    COST_FINAL=1
  fi
  iso=$(perl -MPOSIX=strftime -e 'print strftime("%Y-%m-%dT%H:%M:%SZ", gmtime($ARGV[0]))' "$LAUNCHED_AT")
  mkdir -p "$OUT_DIR"
  tmp=$OUT_DIR/.cost.tsv.$$
  printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$SUITE" "$iso" "$secs" "$price" "$est" "$WORST" > "$tmp" && mv "$tmp" "$OUT_DIR/cost.tsv"
}

RUN_ID=sh-$(date -u +%Y%m%d-%H%M%S)-$RANDOM
KEY_DIR=$(mktemp -d)
[ "$DRY_RUN" = 0 ] || trap 'rm -rf "$KEY_DIR"' EXIT
OUT_DIR=${RESULTS_DIR:-$root/cloud-results}/$RUN_ID
TYPE=${TYPE_LIST[0]}
log "run $RUN_ID: suite $SUITE, $TYPE, backend ${BACKEND:-none}, max price $MAX_PRICE USD/h, hard runtime $MAX_MIN min, worst case $WORST USD"
[ "$DRY_RUN" = 1 ] && log "DRY RUN: nothing is executed or created"

if [ "$DRY_RUN" = 0 ]; then
  preflight || fail "pre-flight found a problem (above); nothing was created" 3
  if [ "$YES" != 1 ]; then
    printf 'This will start one %s spot instance for at most %s minutes (worst case %s USD). Type the run id %s to go on: ' "$TYPE" "$MAX_MIN" "$WORST" "$RUN_ID" >&2
    read -r answer
    [ "$answer" = "$RUN_ID" ] || fail "not confirmed" 1
  fi
  # the cleanup runs on every way out; a second signal must not interrupt it half way
  # A signal can arrive inside a command with "2> /dev/null"; the trap then runs with that redirection: fds 3 and 4 hold the real ones.
  exec 3>&2 4>&1
  trap 'rc=$?; trap "" INT TERM HUP; exec 2>&3 1>&4; trap - EXIT; [ "$COST_FINAL" = 1 ] || write_cost_file final || true; cleanup_run "$RUN_ID" || true; verify_clean "$RUN_ID" || rc=1; exit $rc' EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM
  trap 'exit 129' HUP   # the terminal was closed
fi

# the source: the committed tree of HEAD without the LFS tables and the data directory
SRC_TAR=$KEY_DIR/src.tar.gz
log "source tarball: git archive HEAD (committed files only, without tablebase/tables and data)"
mut git -C "$root" archive --format=tar.gz -o "$SRC_TAR" HEAD -- . ':!tablebase/tables' ':!data'

AMI_ID=$(ro resolve_ami || true)
[ "$DRY_RUN" = 1 ] && AMI_ID="<ami-from-ssm:$(ami_param)>"
[ -n "$AMI_ID" ] || fail "no AMI" 3
[ "$DRY_RUN" = 1 ] || log "AMI: $AMI_ID"

KEY_NAME=$TAG_PROJECT-$RUN_ID
MY_IP=""
if [ "$DRY_RUN" = 1 ]; then
  MY_IP="<public-address-of-this-machine>"
else
  MY_IP=$(curl -fsS --max-time 10 https://checkip.amazonaws.com | tr -d '[:space:]')
  [ -n "$MY_IP" ] || fail "could not find this machine's public address" 1
fi
TAGS="{Key=Project,Value=$TAG_PROJECT},{Key=RunId,Value=$RUN_ID}"

KEY_FILE=$KEY_DIR/key.pem
log "key pair and security group for this run"
mut aws ec2 create-key-pair --key-name "$KEY_NAME" --key-type ed25519 --query KeyMaterial --output text \
  --tag-specifications "ResourceType=key-pair,Tags=[$TAGS]" > "$([ "$DRY_RUN" = 1 ] && echo /dev/null || echo "$KEY_FILE")"
[ "$DRY_RUN" = 1 ] || chmod 600 "$KEY_FILE"
VPC_ID=$(ro aws ec2 describe-vpcs --filters Name=is-default,Values=true --query 'Vpcs[0].VpcId' --output text || true)
[ "$DRY_RUN" = 1 ] && VPC_ID="<default-vpc>"
[ -n "$VPC_ID" ] && [ "$VPC_ID" != None ] || fail "no default VPC in $REGION" 3
SG_ID=$(mut aws ec2 create-security-group --group-name "$KEY_NAME" --description "sternhalma cloud test $RUN_ID" --vpc-id "$VPC_ID" \
  --tag-specifications "ResourceType=security-group,Tags=[$TAGS]" --query GroupId --output text || true)
[ "$DRY_RUN" = 1 ] && SG_ID="<security-group-id>"
mut aws ec2 authorize-security-group-ingress --group-id "$SG_ID" --protocol tcp --port 22 --cidr "$MY_IP/32" > /dev/null

# the user data: the hard timer first, then wait for the upload, then bootstrap.sh
UD=$KEY_DIR/user-data.sh
cat > "$UD" <<EOF
#!/bin/bash
T0=\$(date +%s)
shutdown -h +$MAX_MIN "sternhalma hard timer"
mkdir -p $REMOTE && chmod 777 $REMOTE
for i in \$(seq 1 240); do [ -f $REMOTE/UPLOADED ] && break; sleep 5; done
if [ -f $REMOTE/UPLOADED ]; then
  USED=\$(( (\$(date +%s) - T0) / 60 ))
  HARD=\$(( $MAX_MIN - USED ))
  [ "\$HARD" -ge 5 ] || HARD=5
  bash $REMOTE/bootstrap.sh --backend ${BACKEND:-none} --hard-minutes "\$HARD" --workdir $REMOTE --feed-seconds $FEED_SECONDS --suite $SUITE${REAL_SHA:+ --real-weights-sha256 $REAL_SHA} > $REMOTE/userdata-run.log 2>&1
else
  shutdown -h now
fi
EOF
[ "$DRY_RUN" = 1 ] && { echo "--- user data (would be passed to the instance) ---" >&2; cat "$UD" >&2; echo "---" >&2; }

if [ "$ON_DEMAND" = 1 ]; then
  log "launching one ON-DEMAND instance (terminate on shutdown; the price cap is only the assumed hourly price for the budget check)"
  MARKET=(); TAGSPEC=("ResourceType=instance,Tags=[$TAGS]" "ResourceType=volume,Tags=[$TAGS]")
else
  log "launching one spot instance (one-time request, terminate on interruption and on shutdown)"
  MARKET=(--instance-market-options "MarketType=spot,SpotOptions={SpotInstanceType=one-time,InstanceInterruptionBehavior=terminate,MaxPrice=$MAX_PRICE}")
  TAGSPEC=("ResourceType=instance,Tags=[$TAGS]" "ResourceType=volume,Tags=[$TAGS]" "ResourceType=spot-instances-request,Tags=[$TAGS]")
fi
RUN_ARGS=(aws ec2 run-instances --image-id "$AMI_ID" --instance-type "$TYPE" --count 1 --key-name "$KEY_NAME"
  --security-group-ids "$SG_ID" --user-data "file://$UD" --instance-initiated-shutdown-behavior terminate
  "${MARKET[@]}" --tag-specifications "${TAGSPEC[@]}" --query 'Instances[0].InstanceId' --output text)
if [ "$DRY_RUN" = 1 ]; then
  mut "${RUN_ARGS[@]}"
  INSTANCE_ID="<instance-id>"
else
  # stderr goes to a file first: no capacity and no quota are told from other failures by their error code (exit 4)
  INSTANCE_ID=$("${RUN_ARGS[@]}" 2> "$KEY_DIR/run-instances.err" || true)
  if [ -z "$INSTANCE_ID" ]; then
    cat "$KEY_DIR/run-instances.err" >&2
    if grep -q -E 'InsufficientInstanceCapacity|MaxSpotInstanceCountExceeded|VcpuLimitExceeded|SpotMaxPriceTooLow' "$KEY_DIR/run-instances.err"; then
      fail "no capacity or no quota: nothing was launched (exit 4)" 4
    fi
  fi
fi
[ -n "$INSTANCE_ID" ] || fail "run-instances returned no instance" 1
LAUNCHED_AT=$(date +%s)
write_cost_file provisional
[ "$DRY_RUN" = 1 ] || echo "$SUITE" > "$OUT_DIR/suite.txt"
mut aws ec2 wait instance-running --instance-ids "$INSTANCE_ID"
IP=$(ro aws ec2 describe-instances --instance-ids "$INSTANCE_ID" --query 'Reservations[0].Instances[0].PublicIpAddress' --output text || true)
AZ=$(ro aws ec2 describe-instances --instance-ids "$INSTANCE_ID" --query 'Reservations[0].Instances[0].Placement.AvailabilityZone' --output text || true)
[ "$DRY_RUN" = 1 ] && { IP="<instance-public-address>"; AZ="<az>"; }

SSH=(ssh -i "$KEY_FILE" -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o LogLevel=ERROR -o ConnectTimeout=10 -o ServerAliveInterval=30)
SCP=(scp -i "$KEY_FILE" -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o LogLevel=ERROR -o ConnectTimeout=10 -o ServerAliveInterval=10 -o ServerAliveCountMax=3)
# bounded SECONDS COMMAND...: kills the command after SECONDS (perl's alarm survives exec; macOS has no timeout)
bounded() { local s=$1; shift; perl -e 'alarm shift; exec @ARGV or exit 127' "$s" "$@"; }
# collect LABEL: fetches the results/ directory of the instance into $OUT_DIR/LABEL (bounded; failures are only reported); a no-op in --dry-run
collect() {
  local label=$1
  log "collecting results/ ($label)"
  mut mkdir -p "$OUT_DIR/$label"
  mut bounded 120 "${SCP[@]}" -r "$SSH_USER@$IP:$REMOTE/results" "$OUT_DIR/$label/" 2> /dev/null || echo "  (could not fetch results/ at '$label': the instance may be gone)" >&2
}
log "waiting for ssh (up to 10 minutes)"
if [ "$DRY_RUN" = 1 ]; then
  echo "DRY-RUN: poll '$(quote "${SSH[@]}") $SSH_USER@$IP true' every 10 s until it works" >&2
else
  ok=0
  for _ in $(seq 1 60); do "${SSH[@]}" "$SSH_USER@$IP" true 2> /dev/null && { ok=1; break; }; sleep 10; done
  [ "$ok" = 1 ] || fail "ssh did not come up" 1
fi
log "uploading bootstrap.sh and the source tarball"
# ssh answers before cloud-init has run the user data on some images (GPU deep learning AMI): create the target directory here as well (idempotent)
mut "${SSH[@]}" "$SSH_USER@$IP" "sudo mkdir -p $REMOTE && sudo chmod 777 $REMOTE"
mut "${SCP[@]}" "$here/bootstrap.sh" "$SSH_USER@$IP:$REMOTE/bootstrap.sh"
mut "${SCP[@]}" "$SRC_TAR" "$SSH_USER@$IP:$REMOTE/src.tar.gz"
if [ -n "$REAL_WEIGHTS" ]; then
  log "uploading the real weights (sha256 $REAL_SHA); they stay on the instance and are not copied back"
  mut "${SCP[@]}" "$REAL_WEIGHTS" "$SSH_USER@$IP:$REMOTE/real-weights.safetensors"
  if [ "$DRY_RUN" = 1 ]; then
    echo "DRY-RUN: ssh ... sha256sum $REMOTE/real-weights.safetensors must print $REAL_SHA, else the run stops and everything is cleaned up" >&2
  else
    REMOTE_SHA=$(bounded 60 "${SSH[@]}" "$SSH_USER@$IP" "sha256sum $REMOTE/real-weights.safetensors" 2> /dev/null | cut -d' ' -f1 | tr -d '[:space:]' || true)
    mkdir -p "$OUT_DIR"
    printf 'sha256 before the upload (this machine): %s\nsha256 after the upload (the instance):  %s\n' "$REAL_SHA" "${REMOTE_SHA:-unreadable}" > "$OUT_DIR/real-weights.sha256"
    [ "$REMOTE_SHA" = "$REAL_SHA" ] || fail "the SHA-256 of the real weights on the instance differs from the one computed here (or could not be read): the run is stopped" 1
    log "the SHA-256 of the uploaded weights equals the local one"
  fi
fi
mut "${SSH[@]}" "$SSH_USER@$IP" "touch $REMOTE/UPLOADED"

log "waiting for the run (at most $MAX_MIN minutes; the instance ends itself then)"
if [ "$DRY_RUN" = 1 ]; then
  echo "DRY-RUN: every 30 s: ssh ... 'test -f $REMOTE/DONE; tail -n 2 $REMOTE/results/bootstrap.log', until DONE or $MAX_MIN minutes" >&2
else
  # The instance's hard timer started at its boot, after LAUNCHED_AT: this deadline is the earliest it can have.
  deadline=$(( LAUNCHED_AT + MAX_MIN * 60 ))
  last_chance=0
  while [ "$(date +%s)" -lt "$deadline" ]; do
    if bounded 60 "${SSH[@]}" "$SSH_USER@$IP" "test -f $REMOTE/DONE" 2> /dev/null; then break; fi
    if [ "$last_chance" = 0 ] && [ "$(date +%s)" -ge $(( deadline - 180 )) ]; then
      last_chance=1   # a step that hangs ends with the hard timer: take what the logs hold now
      collect last-chance
    fi
    bounded 60 "${SSH[@]}" "$SSH_USER@$IP" "tail -n 1 $REMOTE/results/bootstrap.log" 2> /dev/null | cut -c1-160 >&2 || true
    sleep 30
  done
fi
log "collecting results into $OUT_DIR"
mut mkdir -p "$OUT_DIR"
mut bounded 120 "${SCP[@]}" "$SSH_USER@$IP:$REMOTE/results.tgz" "$OUT_DIR/results.tgz" || echo "no results.tgz (the run did not finish?)" >&2
mut bounded 60 "${SCP[@]}" "$SSH_USER@$IP:$REMOTE/userdata-run.log" "$OUT_DIR/userdata-run.log" || true
collect final
if [ "$DRY_RUN" = 0 ] && [ -f "$OUT_DIR/results.tgz" ]; then
  tar -xzf "$OUT_DIR/results.tgz" -C "$OUT_DIR" 2> /dev/null && cat "$OUT_DIR/results/summary.txt" >&2 || true
fi
mut bounded 60 "${SSH[@]}" "$SSH_USER@$IP" "sudo shutdown -h now" || true

# cost: elapsed time times the spot price of the zone (the price changes slowly; per-second billing)
ENDED_AT=$(date +%s)
if [ "$DRY_RUN" = 1 ]; then
  : # (on-demand: the cost line below uses the assumed hourly price)
  echo "DRY-RUN: cost = (end - launch) seconds / 3600 * latest describe-spot-price-history price for $TYPE in the zone; worst case $WORST USD" >&2
else
  cost_price; PRICE=$COST_PRICE
  if [ -n "$PRICE" ] && [ "$PRICE" != None ]; then
    perl -e 'printf STDERR "cost estimate: %d s at %s USD/h = %.3f USD (about %.3f EUR at an assumed %s EUR/USD); the 8 GB to 100 GB root volume adds a few cents\n", $ARGV[0], $ARGV[1], $ARGV[0]/3600*$ARGV[1], $ARGV[0]/3600*$ARGV[1]*$ARGV[2], $ARGV[2]' \
      "$(( ENDED_AT - LAUNCHED_AT ))" "$PRICE" "$EUR_PER_USD"
  else
    echo "cost estimate: no spot price returned; at most $WORST USD" >&2
  fi
  write_cost_file final
fi

if [ "$DRY_RUN" = 1 ]; then
  echo "DRY-RUN: cleanup: cancel spot request, terminate $INSTANCE_ID and wait, delete security group $SG_ID (retry), delete key pair $KEY_NAME, then verify with describe-instances, describe-volumes, describe-security-groups, describe-key-pairs, describe-spot-instance-requests (tag RunId=$RUN_ID)" >&2
  exit 0
fi
exit 0
