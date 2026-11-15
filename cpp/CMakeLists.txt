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
  src/search/alpha_beta.cpp src/search/alpha_beta_player.cpp src/search/fingerprint.cpp)
target_include_directories(sh_search PUBLIC src/search)
target_link_libraries(sh_search PUBLIC sh_engine sh_tablebase)

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
sh_test(tablebase_test sh_tablebase)
sh_test(eval_test sh_search)
sh_test(search_test sh_search)
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
if(APPLE)
  add_executable(metal_test tests/metal_test.mm)
  sh_target(metal_test)
  target_compile_options(metal_test PRIVATE -fobjc-arc)
  target_link_libraries(metal_test PRIVATE sh_metal sh_core)
  add_test(NAME metal_test COMMAND metal_test)
endif()
