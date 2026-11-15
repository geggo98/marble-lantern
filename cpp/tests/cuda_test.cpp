// CUDA correctness test (WP12): runs on the AWS instance, against the real device. With the stub backend it only reports.
// Stub created by WP0.
#include "check.hpp"
#include "cuda_net.hpp"

SH_TEST(skeleton_links) {
    if (sh::cuda::build_kind() != "cuda") {
        sh::test::skip("no CUDA device code in this build");
        return;
    }
    CHECK(true);
}

int main() { return sh::test::run_all(); }
