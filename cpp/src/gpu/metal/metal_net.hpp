// MetalNet: the Metal forward pass behind a plain C++ interface (pimpl, no Objective-C type; WP11a fills it).
// Stub created by WP0.
#pragma once

#include <string_view>

namespace sh::metal {

// True if a Metal device is available on this machine.
bool device_available();

// The MSL source of kernels.metal, embedded at build time (cmake/EmbedText.cmake).
std::string_view kernel_source();

}  // namespace sh::metal
