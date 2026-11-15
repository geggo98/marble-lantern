// Implements cuda_net.hpp without CUDA: the Mac build runs everything except the device code (WP12 adds CudaNet::create,
// which returns the error "no CUDA device in this build").
#include "cuda_net.hpp"

namespace sh::cuda {

std::string_view build_kind() { return "stub"; }

}  // namespace sh::cuda
