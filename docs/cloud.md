# Cloud tests: NVIDIA GPUs and x86 CPUs

**TL;DR:** The code can run on NVIDIA GPUs through the TornadoVM CUDA backend and on Intel/AMD CPUs through the Vector API, but so far only the Mac (Metal, ARM NEON) has run it.
Two scripts prepare short AWS spot tests: `scripts/cloud/bootstrap.sh` runs on the instance and `scripts/cloud/aws-spot.sh` launches one instance, collects the results and deletes everything again.
Nothing was launched: this document comes from the preparation on the Mac, with a dry run and a read-only pre-flight.
The pre-flight could not run against the account (the AWS profile named by `AWS_PROFILE` does not exist in the local AWS configuration), so every price below is an **estimate**, not a fetched value.
The expected cost of one GPU test and one CPU test of one hour each is below 1 EUR, far below the 5 EUR budget.

## What was verified, and what was not

Checked on 2026-11-14 against official sources. Values marked *computed* are SHA-256 sums that I computed over files fetched with `curl` into an empty directory (nothing downloaded was executed).

| Fact | Source | Status |
|---|---|---|
| Releases up to v8.1.0 (2026-11-06) exist; v7.3.1 (2026-10-15) is the pinned one | GitHub releases API of `beehive-lab/TornadoVM` (`https://api.github.com/repos/beehive-lab/TornadoVM/releases`) | verified |
| v7.3.1 has Linux amd64 archives for `cuda`, `opencl` and `full`, each as `jdk21` and `jdk22plus`, `.tar.gz` and `.zip` (no Linux aarch64; v8.x adds Linux aarch64 `cuda`) | same | verified |
| JDK 25 uses the `jdk22plus` archives: "One SDK for every JDK from 22 up" | `https://tornadovm.readthedocs.io/en/latest/installation.html`; `etc/tornado.jdk` in the archive says `tornado.jdk.floor=22` | verified for the SDK family; JDK 25 on Linux **not run** |
| URL pattern `https://github.com/beehive-lab/TornadoVM/releases/download/v7.3.1/tornadovm-7.3.1-jdk22plus-<flavour>-linux-amd64.tar.gz` | release assets | verified |
| `jdk22plus-cuda-linux-amd64.tar.gz`: 25,504,927 bytes, SHA-256 `2640f7fb6dc413e59cd493a4e0c9108e7b1d66252a2860b744eed091405a40da` | *computed*, equals the digest of the release metadata | verified |
| `jdk22plus-opencl-linux-amd64.tar.gz`: 23,058,056 bytes, SHA-256 `ee9be026e535ea3563af02e87fec0989c0602fa7cd2489afead0ee7894a07b81` | *computed*, equals the digest of the release metadata | verified |
| macOS opencl archive `d27cd906...de75` | verified by `scripts/setup-tornado.sh opencl` (download and check, 2026-11-14) | verified |
| The Linux `cuda` archive's backend is `cuda-backend` (`etc/tornado.backend`); there is **no PTX and no SPIR-V backend** in 7.3.1 (enum `TornadoVMBackendType`: `OPENCL`, `METAL`, `CUDA`, `JAVA`, `VIRTUAL`; CHANGELOG: PTX removed in favour of the CUDA backend, SPIR-V removed, both in 6.0.0) | the archive itself | verified |
| The CUDA backend compiles CUDA C with NVRTC and calls driver and NVRTC through `java.lang.foreign`; the driver must match or exceed the CUDA Toolkit version | `bin/tornado.py` and `CHANGELOG.rst` in the archive, `README.md` of the repository | verified as text; **not run** |
| Installer | SDKMAN: `sdk install tornadovm 7.3.1-jdk22plus-cuda` (documented); the repository's `scripts/setup-tornado.sh` is used instead (pinned, SHA-256 checked) | the SDKMAN route not tried |
| JDK: Azul Zulu 25.0.3 Linux x64, `https://cdn.azul.com/zulu/bin/zulu25.34.17-ca-jdk25.0.3-linux_x64.tar.gz`, 222 MB, SHA-256 `3a93235f4e5b313232647916219e91dc5cca0b9835c9b52a404ba58c43571697` | *computed*, equals `sha256_hash` of the Azul metadata API (`https://api.azul.com/metadata/v1/zulu/packages/`) | verified (the same release as the Mac's JDK) |
| Required CUDA Toolkit / driver version numbers | the documentation I could fetch states none | **not verified** |
| `tornado --devices` on Linux, CUDA kernel compilation, the `cuda` archive's extra modules (`tornado.cudf`, `cuvs`, `nccl`, `cutlass` are on the `--add-modules` list of the Linux argfile) | needs a Linux machine | **not verified**; the first thing the test shows |
| The Deep Learning AMI (name below) contains the NVIDIA driver and the CUDA Toolkit with `libnvrtc` | from memory of AWS documentation; the SSM parameter could not be read without credentials | **not verified**; `bootstrap.sh` checks `nvidia-smi -L` and `libnvrtc.so` and stops with exit 3 and a message if one is missing |
| EC2 prices, spot prices, quotas | need credentials | **not fetched**, see [Costs](#costs) |

TornadoVM 8.0.0 and 8.1.0 are out (Linux amd64 `jdk22plus` archives with other SHA-256 sums, listed in the release metadata); the project stays on 7.3.1, because all kernels were validated against it.

## Architecture

```
 your machine                                         AWS (one region, default VPC)
 ------------                                         ------------------------------
 aws-spot.sh run  --key pair + security group -->     key pair, security group (SSH from this machine only)
        |         --run-instances (spot, one-time) -> one spot instance, user data = hard timer + wait
        |         --scp bootstrap.sh, src.tar.gz  ->  /var/tmp/sh/
        |         --ssh touch UPLOADED            ->  user data starts bootstrap.sh
        |                                             JDK 25, TornadoVM SDK, build, device tests, Vector API tests and
        |                                             benchmarks, gpuFeed rows -> /var/tmp/sh/results/
        |<-------- scp results.tgz ----------------   shutdown -h +8 (grace), hard timer shutdown -h +N, then terminate
        +-- cleanup: terminate, delete security group and key pair, verify by tags, print the cost
```

**Fewest leftover resources.** The script creates a key pair and a security group and deletes both; the instance and its root volume end with the instance (instance-initiated shutdown behaviour *terminate*, volume `DeleteOnTermination` by default); the spot request is one-time and is cancelled explicitly. The alternative with SSM Session Manager needs an IAM role and instance profile, which are permanent objects and a permission that a new account may lack; an S3 bucket for the source and results would be one more permanent object. SSH with a per-run key pair and a security group that allows port 22 from this machine's address only has the shorter list. Every resource carries the tags `Project=sternhalma-cloud` and `RunId=<id>`, so `aws-spot.sh cleanup --run-id <id>` removes the remains of a run that died.

**Two timers.** The user data starts `shutdown -h +N` as its first line, before anything can fail (N = `--max-minutes`). `bootstrap.sh` repeats it with the time that is left, and its `trap EXIT` schedules `shutdown -h +8` whatever happened (the grace time for fetching the results). A shutdown from inside the instance terminates it. `aws-spot.sh` additionally terminates the instance itself in its cleanup.

**No secrets, no owner data in the repository.** Everything comes from the environment or the git-ignored `.env`: `AWS_PROFILE` and `AWS_REGION` (names in `.env.example`). The account id is never printed. The source tarball is `git archive HEAD` (committed files only, without `tablebase/tables` and `data`).

## Prerequisites

- AWS CLI with a profile of an account that may call `ec2`, `ssm:GetParameter`, `service-quotas:ListServiceQuotas` (read) and create/delete key pairs, security groups and spot instances. Set `AWS_PROFILE` and `AWS_REGION` in `.env`. The script never uses the CLI's default profile: every command except `run --dry-run` refuses to start without `AWS_PROFILE` (or `AWS_ACCESS_KEY_ID`) and refuses a profile that is not defined in the AWS config. The script tests (`scripts/cloud/test/run-all.sh`) need no AWS account; they use stubs.
- A **spot quota** for the instance family. New accounts often have 0 vCPU for *All G and VT Spot Instance Requests*; a `g4dn.xlarge` needs 4. The pre-flight reports this instead of failing late. A quota increase can take days; ask for it early.
- The repository checked out at the commit to test (the script archives `HEAD`).

## How to run

```sh
# 1. read-only: identity (no account id printed), quotas, spot prices of the last 3 hours, AMI parameter, "run-instances --dry-run"
scripts/cloud/aws-spot.sh preflight --instance-type g4dn.xlarge,g5.xlarge --max-price 0.35
scripts/cloud/aws-spot.sh preflight --instance-type c7i.xlarge,c7a.xlarge --backend none --max-price 0.15

# 2. print everything a run would do, create nothing, need no credentials
scripts/cloud/aws-spot.sh run --instance-type g4dn.xlarge --max-price 0.35 --max-minutes 75 --dry-run

# 3. the real runs (asks you to type the run id); results land in cloud-results/<run id>/ (git-ignored)
scripts/cloud/aws-spot.sh run --instance-type g4dn.xlarge --max-price 0.35 --max-minutes 75
scripts/cloud/aws-spot.sh run --instance-type c7i.xlarge  --max-price 0.15 --max-minutes 45 --backend none
```

`--max-price` and `--max-minutes` have no defaults. The script refuses a run whose worst case (`max-price x max-minutes / 60`) is above `--budget-usd` (default 2.50), a price cap above 2.00 USD/h, and Arm instance types. Exit codes: 0 ok, 1 failure or something left over, 2 usage, 3 pre-flight finding.

What `bootstrap.sh` runs and writes to `results/` (`summary.txt` lists every step as OK or FAIL; a failing step does not stop the others):

| Step | Output |
|---|---|
| machine facts | `system.txt`: CPU model, AVX flags, `nvidia-smi` |
| apt packages, JDK 25 (verified tarball), TornadoVM SDK (`setup-tornado.sh cuda`, SHA-256 checked), `tornado --devices` | `*.log`, `tornado-devices.txt` |
| build | `build.log` |
| Vector API tests (`VectorSpeciesTest`, `VectorNetworkTest`, `VectorSupportTest`) | `vector-tests.log`, `xml/` |
| Vector API benchmark, default species; on AVX-512 CPUs also with `-XX:UseAVX=2` and with `-XX:UseAVX=3 -XX:MaxVectorSize=64` | `vector-benchmark-*.log` |
| device tests (14 classes, `TORNADO_BACKEND=cuda`) | `device-tests-per-class.txt`, `xml-device/` |
| `gpuFeed` for SMALL and LARGE, batch 256, 60 s each, `--threads $(nproc)` | `gpufeed-small.log`, `gpufeed-large.log` |

The cost of one GPU run is dominated by the build (about 5 minutes for Gradle and the dependencies), the test run and the two 60-second feed rows; the 75-minute cap is a ceiling, not the expected duration (not measured; I expect 25 to 40 minutes).

### AMI choice

| Test | AMI | Why |
|---|---|---|
| NVIDIA | AWS *Deep Learning Base OSS Nvidia Driver GPU AMI (Ubuntu 22.04)*, public SSM parameter `/aws/service/deeplearning/ami/x86_64/base-oss-nvidia-driver-gpu-ubuntu-22.04/latest/ami-id` | Driver and CUDA Toolkit (NVRTC) come preinstalled and matched to each other; no charge beyond the instance. A plain Ubuntu AMI would need the driver installed (DKMS build, possibly a reboot) and the toolkit added: more minutes of paid time and more things that can fail. `bootstrap.sh --install-nvidia 1` has a best-effort driver installation for plain Ubuntu, **not validated**. |
| CPU | Canonical Ubuntu 22.04 server, SSM parameter `/aws/service/canonical/ubuntu/server/22.04/stable/current/amd64/hvm/ebs-gp2/ami-id` | The cheapest reliable path: nothing preinstalled is needed. |

Both parameters are resolved at run time and the pre-flight reads them. If AWS renamed one, pass `--ami <id>`. Amazon Linux is not supported by `bootstrap.sh` (apt only).

## Vector API on x86

What the code does is checked on ARM; what the JVM does on x86 is expected behaviour, to be measured by the CPU test.

- `VectorNetwork` takes its lane count from `VectorSupport.SPECIES`. Channels are padded to a multiple of two vectors (`stride`), so a channel count that is not a lane multiple needs no tail loop; the tail lanes hold zero weights. `VectorSpeciesTest` runs 64, 128, 256 and 512 bit in separate JVMs (a species is a constant of the JIT) on channel counts 5, 13, 33, 64 and 8 and hidden sizes 7 to 35, against the scalar reference: all four widths agree (largest error 0.0024 of the bound). On ARM the hardware maximum is 128 bit; wider species run through the Java fallback of the Vector API, which is correct and slow, so these tests exercise the generic lane handling, not wide hardware.
- Expected species: `SPECIES_PREFERRED` is 256 bit on an AVX2 CPU. On AVX-512 CPUs it is 512 bit when the JVM uses AVX-512 (`-XX:UseAVX=3`, the default on recent Intel server CPUs; some JVM versions prefer 256 bit on parts that lower their clock under 512-bit load). The benchmark header prints `UseAVX`, `MaxVectorSize`, the species and the preferred width of the JVM that ran.
- JVM flags: `--add-modules jdk.incubator.vector` (always); `-XX:UseAVX=2` caps at AVX2 (256 bit), `-XX:UseAVX=3 -XX:MaxVectorSize=64` asks for 512 bit; `-Dsternhalma.vector.bits=N` forces a species (tests).
- FMA: `FloatVector.fma` is a hardware instruction with FMA3. All AVX2 Intel and AMD cloud CPUs have it. Without it the JVM computes it in software, very slowly. The benchmark prints `UseFMA`.
- Register pressure: the convolution keeps 8 accumulators, 2 weight vectors and 4 broadcast values, 14 vector registers. AVX2 has 16, AVX-512 has 32, NEON 32, so the AVX2 build may spill; only a measurement shows whether it matters.
- Padding cost: the tile is two vectors, so 512-bit vectors pad to a multiple of 32 channels. SMALL (32) and LARGE (64) lose nothing; TINY (8) would pad to 32, which only matters for tests.
- Floating-point results differ between species in the last bits (different summation order); the tolerance of docs/network.md covers it.

Benchmark: `./gradlew --no-build-cache :nn:vectorBenchmark [-PvectorBits=256] [-PvectorSeconds=2] [-PvectorJvmArgs="-XX:UseAVX=2"]` prints GFLOP/s for SMALL and LARGE at batch 1 and 256, one thread. Measured on the Mac (Apple M3 Max, 128-bit, JDK 25, machine loaded by a training run, indicative): SMALL 30.6 / 29.9 GFLOP/s, LARGE 32.0 / 42.2 GFLOP/s at batch 1 / 256.

## Costs

**All prices are estimates from general knowledge of the EC2 price lists, not fetched**: the pre-flight needs credentials that were not available. `aws-spot.sh preflight` prints the real spot price history per zone; replace the table with its output before the first launch. Spot prices change by the hour. `EUR_PER_USD` (default 0.90 in the script) is an assumption for the printed conversion.

| Instance | What it tests | vCPU, memory, GPU | Spot (estimate, USD/h) | Cap (`--max-price`) | Run time | Worst case at cap |
|---|---|---|---|---|---|---|
| `g4dn.xlarge` | CUDA backend, NVIDIA T4 (16 GB), CPU Vector API on 4 vCPU | 4, 16 GiB, 1 x T4 | 0.20 to 0.30 | 0.35 | 75 min | 0.44 USD |
| `g5.xlarge` (alternative) | CUDA on an A10G (24 GB) | 4, 16 GiB, 1 x A10G | 0.40 to 0.60 | 0.65 | 60 min | 0.65 USD |
| `c7i.xlarge` | AVX-512 (Sapphire Rapids), Vector API | 4, 8 GiB | 0.06 to 0.10 | 0.15 | 45 min | 0.11 USD |
| `c7a.xlarge` (alternative) | AVX-512 on AMD (Zen 4) | 4, 8 GiB | 0.07 to 0.12 | 0.15 | 45 min | 0.11 USD |

Root volumes (gp3, about 100 GB for the Deep Learning AMI) add roughly 0.01 USD per hour. Data transfer is below 1 GB (the SDK, the JDK and the Gradle dependencies come in; results go out), which is free inbound and cents outbound.

**Expected total for the two recommended tests: under 1 EUR** (expected, 0.15 to 0.4 USD for the GPU hour and 0.05 to 0.1 for the CPU hour), and **at most 0.55 USD (about 0.5 EUR) at the caps**. The 5 EUR budget therefore leaves room for several repeats. A spot interruption ends a run early and loses its results (one-time request, terminate on interruption): start it again.

## Risks

- **No spot quota for G instances** (most likely): the pre-flight says so. Alternatives: a quota increase request; on-demand `g4dn.xlarge` (needs the on-demand *G and VT* quota, same default 0), which would change the script (`--instance-market-options` removed) and the cost estimate (about 0.6 USD/h): not implemented.
- **The CUDA backend has never run here.** Likely first failures: NVRTC or the driver not found, a kernel that CUDA C rejects (a `KernelContext` call or a Java construct that only Metal accepts), a `Math.max` or `tanh` difference. The device tests then fail with the message; `tornado-devices.txt` and `*.log` say why. A failing run is still a result.
- **The Deep Learning AMI**: name or contents may have changed; the pre-flight reads the parameter, `bootstrap.sh` checks for the driver and NVRTC.
- **Interruption** of a spot instance loses the run.
- **A runaway bill** is limited by the budget check, the two timers and the cleanup; a stuck `terminate` would show in the final verification, and `aws-spot.sh cleanup --run-id <id>` repeats it.
- **Source code on a rented machine**: the tarball holds the committed repository, no `.env`, no data.
- **Timings are indicative**: the Mac numbers in the other documents were taken on a loaded machine.

## Cleanup

`aws-spot.sh run` cleans up on every exit, also on Ctrl-C or an error, and ends with this check by tag (instance, volume, security group, key pair, open spot request):

```
nothing left: no instance, volume, security group, key pair or open spot request with RunId=<id>
```

If the script itself was killed, run `scripts/cloud/aws-spot.sh cleanup --run-id <id>`, or look in the EC2 console for the tag `Project=sternhalma-cloud`. The private key of a run lives in a temporary directory that is deleted at the end.

## Dry run

Output of `scripts/cloud/aws-spot.sh run --instance-type g4dn.xlarge --max-price 0.35 --max-minutes 75 --dry-run` (2026-11-14; no credentials, nothing created; paths shortened, the run id placeholder is `sh-<date>-<time>-<n>`):

```
run sh-<date>-<time>-<n>: g4dn.xlarge, backend cuda, max price 0.35 USD/h, hard runtime 75 min, worst case 0.44 USD
DRY RUN: nothing is executed or created
source tarball: git archive HEAD (committed files only, without tablebase/tables and data)
DRY-RUN: git -C $REPO archive --format=tar.gz -o $TMP/src.tar.gz HEAD -- . :\!tablebase/tables :\!data 
DRY-RUN (read-only): resolve_ami 
key pair and security group for this run
DRY-RUN: aws ec2 create-key-pair --key-name sternhalma-cloud-sh-<date>-<time>-<n> --key-type ed25519 --query KeyMaterial --output text --tag-specifications ResourceType=key-pair\,Tags=\[\{Key=Project\,Value=sternhalma-cloud\}\,\{Key=RunId\,Value=sh-<date>-<time>-<n>\}\] 
DRY-RUN (read-only): aws ec2 describe-vpcs --filters Name=is-default\,Values=true --query Vpcs\[0\].VpcId --output text 
DRY-RUN: aws ec2 create-security-group --group-name sternhalma-cloud-sh-<date>-<time>-<n> --description sternhalma\ cloud\ test\ sh-<date>-<time>-<n> --vpc-id \<default-vpc\> --tag-specifications ResourceType=security-group\,Tags=\[\{Key=Project\,Value=sternhalma-cloud\}\,\{Key=RunId\,Value=sh-<date>-<time>-<n>\}\] --query GroupId --output text 
DRY-RUN: aws ec2 authorize-security-group-ingress --group-id \<security-group-id\> --protocol tcp --port 22 --cidr \<public-address-of-this-machine\>/32 
--- user data (would be passed to the instance) ---
#!/bin/bash
T0=$(date +%s)
shutdown -h +75 "sternhalma hard timer"
mkdir -p /var/tmp/sh && chmod 777 /var/tmp/sh
for i in $(seq 1 240); do [ -f /var/tmp/sh/UPLOADED ] && break; sleep 5; done
if [ -f /var/tmp/sh/UPLOADED ]; then
  USED=$(( ($(date +%s) - T0) / 60 ))
  HARD=$(( 75 - USED ))
  [ "$HARD" -ge 5 ] || HARD=5
  bash /var/tmp/sh/bootstrap.sh --backend cuda --hard-minutes "$HARD" --workdir /var/tmp/sh --feed-seconds 60 > /var/tmp/sh/userdata-run.log 2>&1
else
  shutdown -h now
fi
---
launching one spot instance (one-time request, terminate on interruption and on shutdown)
DRY-RUN: aws ec2 run-instances --image-id \<ami-from-ssm:/aws/service/deeplearning/ami/x86_64/base-oss-nvidia-driver-gpu-ubuntu-22.04/latest/ami-id\> --instance-type g4dn.xlarge --count 1 --key-name sternhalma-cloud-sh-<date>-<time>-<n> --security-group-ids \<security-group-id\> --user-data file://$TMP/user-data.sh --instance-initiated-shutdown-behavior terminate --instance-market-options MarketType=spot\,SpotOptions=\{SpotInstanceType=one-time\,InstanceInterruptionBehavior=terminate\,MaxPrice=0.35\} --tag-specifications ResourceType=instance\,Tags=\[\{Key=Project\,Value=sternhalma-cloud\}\,\{Key=RunId\,Value=sh-<date>-<time>-<n>\}\] ResourceType=volume\,Tags=\[\{Key=Project\,Value=sternhalma-cloud\}\,\{Key=RunId\,Value=sh-<date>-<time>-<n>\}\] ResourceType=spot-instances-request\,Tags=\[\{Key=Project\,Value=sternhalma-cloud\}\,\{Key=RunId\,Value=sh-<date>-<time>-<n>\}\] --query Instances\[0\].InstanceId --output text 
DRY-RUN: aws ec2 wait instance-running --instance-ids \<instance-id\> 
DRY-RUN (read-only): aws ec2 describe-instances --instance-ids \<instance-id\> --query Reservations\[0\].Instances\[0\].PublicIpAddress --output text 
DRY-RUN (read-only): aws ec2 describe-instances --instance-ids \<instance-id\> --query Reservations\[0\].Instances\[0\].Placement.AvailabilityZone --output text 
waiting for ssh (up to 10 minutes)
DRY-RUN: poll 'ssh -i $TMP/key.pem -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o LogLevel=ERROR -o ConnectTimeout=10 -o ServerAliveInterval=30  ubuntu@<instance-public-address> true' every 10 s until it works
uploading bootstrap.sh and the source tarball
DRY-RUN: scp -i $TMP/key.pem -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o LogLevel=ERROR $REPO/scripts/cloud/bootstrap.sh ubuntu@\<instance-public-address\>:/var/tmp/sh/bootstrap.sh 
DRY-RUN: scp -i $TMP/key.pem -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o LogLevel=ERROR $TMP/src.tar.gz ubuntu@\<instance-public-address\>:/var/tmp/sh/src.tar.gz 
DRY-RUN: ssh -i $TMP/key.pem -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o LogLevel=ERROR -o ConnectTimeout=10 -o ServerAliveInterval=30 ubuntu@\<instance-public-address\> touch\ /var/tmp/sh/UPLOADED 
waiting for the run (at most 75 minutes; the instance ends itself then)
DRY-RUN: every 30 s: ssh ... 'test -f /var/tmp/sh/DONE; tail -n 2 /var/tmp/sh/results/bootstrap.log', until DONE or 75 minutes
collecting results into $REPO/cloud-results/sh-<date>-<time>-<n>
DRY-RUN: mkdir -p $REPO/cloud-results/sh-<date>-<time>-<n> 
DRY-RUN: scp -i $TMP/key.pem -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o LogLevel=ERROR ubuntu@\<instance-public-address\>:/var/tmp/sh/results.tgz $REPO/cloud-results/sh-<date>-<time>-<n>/results.tgz 
DRY-RUN: scp -i $TMP/key.pem -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o LogLevel=ERROR ubuntu@\<instance-public-address\>:/var/tmp/sh/userdata-run.log $REPO/cloud-results/sh-<date>-<time>-<n>/userdata-run.log 
DRY-RUN: ssh -i $TMP/key.pem -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o LogLevel=ERROR -o ConnectTimeout=10 -o ServerAliveInterval=30 ubuntu@\<instance-public-address\> sudo\ shutdown\ -h\ now 
DRY-RUN: cost = (end - launch) seconds / 3600 * latest describe-spot-price-history price for g4dn.xlarge in the zone; worst case 0.44 USD
DRY-RUN: cleanup: cancel spot request, terminate <instance-id> and wait, delete security group <security-group-id> (retry), delete key pair sternhalma-cloud-sh-<date>-<time>-<n>, then verify with describe-instances, describe-volumes, describe-security-groups, describe-key-pairs, describe-spot-instance-requests (tag RunId=sh-<date>-<time>-<n>)
```

The pre-flight against the real account was run on the same day and stopped at its first step:

```
pre-flight in eu-central-1 (read-only)
  FINDING: aws sts get-caller-identity failed (profile '<AWS_PROFILE>'): The config profile (<AWS_PROFILE>) could not be found
```

The profile named in the owner's `.env` is not defined in the local AWS configuration; I did not try any other profile, because they may belong to other accounts. Quotas and spot prices are therefore not known yet.
