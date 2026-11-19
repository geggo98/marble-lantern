#!/bin/zsh
# Browser test of the production WebGPU backend (web/e2e/webgpu-backend.spec.ts) in Chromium.
# usage: webgpu-e2e.zsh [swiftshader|gpu] [--bench] [playwright arguments]
#   swiftshader (default)  software Vulkan, runs on machines without a GPU
#   gpu                    the real GPU of this machine
#   --bench                also runs the latency test (prints JSON; WEBGPU_BENCH_OUT=file writes it)
# Inputs: cpp/golden (`./gradlew --no-build-cache :search:goldenExport :nn:goldenExport`, plus web/bench/webgpu/RealRef.java for
# the real-* cases) and, for real-v2 and real-v3, STERNHALMA_DATA_DIR (default <repo>/data). Cases whose files are missing skip.
# Run inside `devenv shell` (PLAYWRIGHT_BROWSERS_PATH). Exit: Playwright's; 2 = prerequisite missing.
set -u
root=${0:A:h:h:h}
mode=swiftshader
if [[ ${1:-} == swiftshader || ${1:-} == gpu ]]; then mode=$1; shift; fi
if [[ ${1:-} == --bench ]]; then export WEBGPU_BENCH=1; shift; fi
[[ -d $root/web/node_modules/.bin ]] || { print -u2 "webgpu-e2e: web/node_modules is missing; run 'devenv tasks run web:install' first"; exit 2 }
[[ -n ${PLAYWRIGHT_BROWSERS_PATH:-} && -d $PLAYWRIGHT_BROWSERS_PATH ]] || { print -u2 "webgpu-e2e: PLAYWRIGHT_BROWSERS_PATH is unset; run inside 'devenv shell'"; exit 2 }
cd $root/web
export WEBGPU_E2E=$mode
exec npm exec --no -- playwright test e2e/webgpu-backend.spec.ts --project=chromium "$@"
