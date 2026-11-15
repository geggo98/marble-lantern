// CudaNet: the CUDA forward pass behind a plain C++20 interface (standard headers only; WP12 fills it).
// Two implementations: cuda_net.cu (nvcc, the real device code) and cuda_net_stub.cpp (no CUDA; used on the Mac).
// Stub created by WP0.
#pragma once

#include <string_view>

namespace sh::cuda {

// "cuda" in the build with device code, "stub" in the build without it.
std::string_view build_kind();

}  // namespace sh::cuda
