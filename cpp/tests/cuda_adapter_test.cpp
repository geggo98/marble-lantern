// Tests of the CUDA adapter with the stub backend (WP12). Stub created by WP0.
#include "check.hpp"
#include "cuda_backend.hpp"
#include "cuda_net.hpp"

SH_TEST(skeleton_links) {
    CHECK_EQ(sh::cuda_backend_name(), "cuda-stub");
    CHECK_EQ(sh::cuda::build_kind(), "stub");
}

int main() { return sh::test::run_all(); }
