// Objective-C++ part of the Metal backend, compiled with -fobjc-arc. Stub created by WP0; WP11a fills it.
// Unsafe zones of this file (docs/cpp-comparison-plan.md): [buffer contents] wrapped at once into a std::span, Objective-C objects.
#include "metal_net.hpp"

#import <Metal/Metal.h>

namespace {
constexpr std::string_view kKernelSource =
#include "kernels_msl.inc"
    ;
}  // namespace

namespace sh::metal {

bool device_available() {
    @autoreleasepool {
        id<MTLDevice> device = MTLCreateSystemDefaultDevice();
        return device != nil;
    }
}

std::string_view kernel_source() { return kKernelSource; }

}  // namespace sh::metal
