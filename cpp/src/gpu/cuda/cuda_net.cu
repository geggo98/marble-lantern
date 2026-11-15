// Device code and the thin host layer of the CUDA backend (nvcc -std=c++20 -ccbin g++-12; built by build-cuda.sh, never by
// CMake). Includes only cuda_net.hpp, kernels.cuh and standard and CUDA headers. Stub created by WP0; WP12 fills it.
#include "cuda_net.hpp"

namespace sh::cuda {

std::string_view build_kind() { return "cuda"; }

}  // namespace sh::cuda
