// MSL kernels of the Metal backend, compiled at run time from this source (newLibraryWithSource:options:error:).
// Stub created by WP0; WP11a and WP11b fill it.
#include <metal_stdlib>
using namespace metal;

kernel void noop(device float* out [[buffer(0)]], uint gid [[thread_position_in_grid]]) {
    out[gid] = 0.0f;
}
