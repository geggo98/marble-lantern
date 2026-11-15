// Implements cuda_net.hpp without CUDA: the Mac build runs everything except the device code. CudaNet::create returns the
// error "no CUDA device in this build"; there is no implementation of the interface in this build.
#include "cuda_net.hpp"

namespace sh::cuda {

std::string_view build_kind() { return "stub"; }
int device_count() { return 0; }
std::string device_name() { return {}; }

std::unique_ptr<CudaNet> CudaNet::create(const Shape&, const Weights&, const Options&, std::string& error) {
    error = "no CUDA device in this build";
    return nullptr;
}

}  // namespace sh::cuda
