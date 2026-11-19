cmake_minimum_required(VERSION 3.28)
project(sternhalma_cpp LANGUAGES CXX)

# C++ port of the classical player and of the network forward pass (docs/cpp-comparison-plan.md).
# Every library, app and test target of the plan exists from WP0 on; later work packages fill files and add no target.

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_EXPORT_COMPILE_COMMANDS ON)
set(CMAKE_RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}")

if(APPLE)
  enable_language(OBJCXX)
  set(CMAKE_OBJCXX_STANDARD 23)
  set(CMAKE_OBJCXX_STANDARD_REQUIRED ON)
  set(CMAKE_OBJCXX_EXTENSIONS OFF)
endif()

list(APPEND CMAKE_MODULE_PATH "${CMAKE_CURRENT_SOURCE_DIR}/cmake")
include(Warnings)
include(Hardening)
include(Sanitizers)

# Timing builds use the CPU they run on, as the JVM's JIT does: -mcpu=native on Apple silicon, -march=native elsewhere.
option(SH_NATIVE "Compile for the host CPU" OFF)
set(sh_arch_flags "")
if(SH_NATIVE)
  if(APPLE AND CMAKE_SYSTEM_PROCESSOR STREQUAL "arm64")
    set(sh_arch_flags -mcpu=native)
  else()
    set(sh_arch_flags -march=native)
  endif()
endif()

# clang-tidy (preset "tidy"): give it the SDK of the compiler on macOS, so that it can parse the system headers.
if(CMAKE_CXX_CLANG_TIDY AND APPLE)
  # The wrapper of nixpkgs clang-tools adds the Nix libc++ and fails on the Apple SDK; clang-tidy-unwrapped (same package) does not.
  set(sh_sysroot "${CMAKE_OSX_SYSROOT}")
  if(NOT sh_sysroot)
    execute_process(COMMAND xcrun --show-sdk-path OUTPUT_VARIABLE sh_sysroot OUTPUT_STRIP_TRAILING_WHITESPACE)
  endif()
  list(APPEND CMAKE_CXX_CLANG_TIDY "--extra-arg=-isysroot${sh_sysroot}")
endif()

# Applies warnings, hardening, sanitizers and the architecture flags to a target.
function(sh_target name)
  target_link_libraries(${name} PRIVATE sh_warnings sh_hardening sh_sanitizers)
  if(sh_arch_flags)
    target_compile_options(${name} PRIVATE ${sh_arch_flags})
  endif()
endfunction()

function(sh_library name)
  add_library(${name} STATIC ${ARGN})
  sh_target(${name})
endfunction()

enable_testing()
function(sh_test name)
  add_executable(${name} tests/${name}.cpp)
  sh_target(${name})
  target_link_libraries(${name} PRIVATE ${ARGN})
  add_test(NAME ${name} COMMAND ${name})
endfunction()

function(sh_app name source)
  add_executable(${name} ${source})
  sh_target(${name})
  target_include_directories(${name} PRIVATE apps)
  target_link_libraries(${name} PRIVATE ${ARGN})
endfunction()

# ---- libraries ----

sh_library(sh_core src/core/golden.cpp src/core/json_min.cpp src/core/sha256.cpp)
target_include_directories(sh_core PUBLIC src/core)
target_compile_definitions(sh_core PRIVATE SH_GOLDEN_DIR="${CMAKE_CURRENT_SOURCE_DIR}/golden")

sh_library(sh_engine
  src/engine/geometry.cpp src/engine/layout.cpp src/engine/rules.cpp
  src/engine/outcome.cpp src/engine/move.cpp src/engine/position.cpp)
target_include_directories(sh_engine PUBLIC src/engine)
target_link_libraries(sh_engine PUBLIC sh_core)

sh_library(sh_tablebase src/tablebase/rows.cpp src/tablebase/ranking.cpp src/tablebase/race_table.cpp)
target_include_directories(sh_tablebase PUBLIC src/tablebase)
target_link_libraries(sh_tablebase PUBLIC sh_engine)

sh_library(sh_search
  src/search/scores.cpp src/search/options.cpp src/search/player_spec.cpp
  src/search/linear_weights.cpp src/search/linear_eval.cpp src/search/incremental_linear.cpp
  src/search/jump_graph.cpp src/search/race_eval.cpp src/search/race_adjudication.cpp
  src/search/solitaire.cpp src/search/race_move.cpp
  src/search/alpha_beta.cpp src/search/alpha_beta_player.cpp src/search/fingerprint.cpp
  src/search/three_player_search.cpp src/search/three_player_player.cpp)
target_include_directories(sh_search PUBLIC src/search)
target_link_libraries(sh_search PUBLIC sh_engine sh_tablebase)

# ---- WebAssembly (preset "wasm", configured with emcmake): the classical engine as engine-cpp.{js,wasm} ----
# Only the engine libraries above are built; the network code, the apps and the tests need a host and stay out of this build.
if(EMSCRIPTEN)
  # SH_WASM_ES6 ON: engine-cpp.js is an ES module (export default). OFF: a classic script that defines createEngineCpp.
  option(SH_WASM_ES6 "Emit engine-cpp.js as an ES module" ON)
  add_executable(engine-cpp src/wasm/wasm_api.cpp)
  sh_target(engine-cpp)
  target_link_libraries(engine-cpp PRIVATE sh_search)
  set(sh_wasm_exports
    _shw_error _shw_alloc _shw_free _shw_load_rows2 _shw_player_new _shw_player_clear _shw_game_new _shw_jump_rule _shw_side_to_move _shw_ply
    _shw_owner _shw_cell_count _shw_cell_q _shw_cell_r _shw_legal_moves _shw_play _shw_unmake _shw_outcome _shw_choose
    _shw_last_score _shw_last_depth _shw_last_nodes _shw_has_result _shw_total_nodes _shw_race_moves _shw_rows1_requests)
  list(JOIN sh_wasm_exports "," sh_wasm_exports_csv)
  # No threads and no SharedArrayBuffer (GitHub Pages cannot send COOP/COEP); no file system (the table comes as bytes).
  target_link_options(engine-cpp PRIVATE
    --no-entry -sMODULARIZE=1 -sEXPORT_NAME=createEngineCpp -sENVIRONMENT=web,worker,node
    -sALLOW_MEMORY_GROWTH=1 -sFILESYSTEM=0 -sEXPORTED_RUNTIME_METHODS=HEAPU8,HEAP32,UTF8ToString
    "-sEXPORTED_FUNCTIONS=_malloc,_free,${sh_wasm_exports_csv}")
  if(SH_WASM_ES6)
    target_link_options(engine-cpp PRIVATE -sEXPORT_ES6=1)
  endif()
  # Emscripten's own warning when nixpkgs binaryen is one release ahead of the one it expects must not stop the build.
  target_link_options(engine-cpp PRIVATE -Wno-version-check)
  return()
endif()

# The C ABI of the browser engine, built natively as well so that wasm_api_test can call it without Emscripten.
sh_library(sh_wasm_api src/wasm/wasm_api.cpp)
target_link_libraries(sh_wasm_api PUBLIC sh_search)

sh_library(sh_nn
  src/nn/config.cpp src/nn/safetensors.cpp src/nn/parameters.cpp src/nn/gather.cpp
  src/nn/reference_net.cpp src/nn/simd_net.cpp src/nn/simd_net_nofma.cpp src/nn/priors.cpp src/nn/golden_io.cpp src/nn/buckets.cpp)
target_include_directories(sh_nn PUBLIC src/nn)
target_link_libraries(sh_nn PUBLIC sh_core)
# The one named exception to -ffp-contract=off: the FMA variant of the SIMD forward pass (WP10).
set_source_files_properties(src/nn/simd_net.cpp PROPERTIES COMPILE_OPTIONS "-ffp-contract=fast")

# The CUDA host code on the Mac: the adapter plus the stub of CudaNet. The real device code (cuda_net.cu) is built by
# scripts/build-cuda.sh on the instance, never by CMake.
sh_library(sh_cuda_stub src/nn/cuda_backend.cpp src/gpu/cuda/cuda_net_stub.cpp)
target_include_directories(sh_cuda_stub PUBLIC src/nn src/gpu/cuda)
target_link_libraries(sh_cuda_stub PUBLIC sh_nn)

if(APPLE)
  sh_library(sh_metal src/gpu/metal/metal_net.mm)
  target_include_directories(sh_metal PUBLIC src/gpu/metal)
  target_include_directories(sh_metal PRIVATE "${CMAKE_CURRENT_BINARY_DIR}/metal")
  target_compile_options(sh_metal PRIVATE $<$<COMPILE_LANGUAGE:OBJCXX>:-fobjc-arc>)
  target_link_libraries(sh_metal PUBLIC sh_core sh_nn "-framework Metal" "-framework Foundation")
  add_custom_command(
    OUTPUT "${CMAKE_CURRENT_BINARY_DIR}/metal/kernels_msl.inc"
    COMMAND ${CMAKE_COMMAND} -E make_directory "${CMAKE_CURRENT_BINARY_DIR}/metal"
    COMMAND ${CMAKE_COMMAND} -DINPUT=${CMAKE_CURRENT_SOURCE_DIR}/src/gpu/metal/kernels.metal
            -DOUTPUT=${CMAKE_CURRENT_BINARY_DIR}/metal/kernels_msl.inc -P ${CMAKE_CURRENT_SOURCE_DIR}/cmake/EmbedText.cmake
    DEPENDS src/gpu/metal/kernels.metal cmake/EmbedText.cmake
    VERBATIM)
  add_custom_target(sh_metal_kernels DEPENDS "${CMAKE_CURRENT_BINARY_DIR}/metal/kernels_msl.inc")
  add_dependencies(sh_metal sh_metal_kernels)
endif()

# ---- apps ----

sh_app(fingerprint apps/fingerprint_main.cpp sh_search)
sh_app(search_bench apps/search_bench.cpp sh_search)
sh_app(nn_check apps/nn_check.cpp sh_cuda_stub)
sh_app(nn_bench apps/nn_bench.cpp sh_cuda_stub)
if(APPLE)
  target_link_libraries(nn_check PRIVATE sh_metal)
  target_link_libraries(nn_bench PRIVATE sh_metal)
endif()

# ---- tests (one executable per module, each one ctest test) ----

sh_test(java_compat_test sh_core)
sh_test(engine_test sh_engine)
sh_test(long_jump_test sh_engine)
# The Java golden file of the long hop is read in place, so that the two sides cannot drift apart.
target_compile_definitions(long_jump_test PRIVATE SH_LONG_JUMP_GOLDEN="${CMAKE_CURRENT_SOURCE_DIR}/../engine/src/test/resources/long-jump-golden.tsv")
sh_test(three_player_test sh_engine)
target_compile_definitions(three_player_test PRIVATE SH_THREE_PLAYER_GOLDEN="${CMAKE_CURRENT_SOURCE_DIR}/../engine/src/test/resources/three-player-golden.tsv")
sh_test(three_player_search_test sh_search)
target_compile_definitions(three_player_search_test PRIVATE SH_THREE_PLAYER_CHOOSE_GOLDEN="${CMAKE_CURRENT_SOURCE_DIR}/../search/src/test/resources/three-player-choose-golden.tsv")
sh_test(tablebase_test sh_tablebase)
sh_test(race_table_bytes_test sh_search)
sh_test(eval_test sh_search)
sh_test(search_test sh_search)
sh_test(wasm_api_test sh_wasm_api)
sh_test(nn_test sh_nn)
target_include_directories(nn_test PRIVATE tests)
# WP10: the object code proves the two variants of the SIMD forward pass (fused instructions in one, none in the other).
add_test(NAME simd_object_code
  COMMAND ${CMAKE_CURRENT_SOURCE_DIR}/scripts/check-fma.sh
          ${CMAKE_BINARY_DIR}/CMakeFiles/sh_nn.dir/src/nn/simd_net.cpp.o
          ${CMAKE_BINARY_DIR}/CMakeFiles/sh_nn.dir/src/nn/simd_net_nofma.cpp.o)
set_tests_properties(simd_object_code PROPERTIES SKIP_RETURN_CODE 77)
sh_test(cuda_adapter_test sh_cuda_stub)
sh_test(cuda_test sh_cuda_stub)
add_test(NAME nn_bench_kernels_exit COMMAND ${CMAKE_CURRENT_SOURCE_DIR}/scripts/check-nn-bench-kernels.sh $<TARGET_FILE:nn_bench>)
set_tests_properties(nn_bench_kernels_exit PROPERTIES SKIP_RETURN_CODE 77)
sh_test(bench_series_test sh_nn)
target_include_directories(bench_series_test PRIVATE apps)
if(APPLE)
  add_executable(metal_test tests/metal_test.mm)
  sh_target(metal_test)
  target_compile_options(metal_test PRIVATE -fobjc-arc)
  target_link_libraries(metal_test PRIVATE sh_metal sh_core)
  add_test(NAME metal_test COMMAND metal_test)
  # a run without a device must fail, not pass with every test skipped
  add_test(NAME metal_test_no_device COMMAND metal_test --pretend-no-device)
  set_tests_properties(metal_test_no_device PROPERTIES WILL_FAIL TRUE)
  add_test(NAME metal_test_no_device_allowed COMMAND metal_test --pretend-no-device --allow-no-device)
endif()
