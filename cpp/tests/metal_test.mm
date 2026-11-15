// Tests of the Metal backend (WP11a, WP11b). Stub created by WP0.
#include "check.hpp"
#include "metal_net.hpp"

SH_TEST(skeleton_links) {
    CHECK(!sh::metal::kernel_source().empty());
    if (!sh::metal::device_available()) sh::test::skip("no Metal device");
}

int main() { return sh::test::run_all(); }
