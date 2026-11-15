// C++23 adapter between Parameters and the CudaNet of src/gpu/cuda (WP12). Stub created by WP0.
#pragma once

#include <string_view>

namespace sh {

// Name of the backend this build contains: "cuda-stub" (no device code) in the stub build.
std::string_view cuda_backend_name();

}  // namespace sh
