// Minimal JSON reader for the safetensors header and the golden files of the network track. Stub of WP0: WP9 fills it.
#pragma once

#include <string_view>

namespace sh {

// Name of this module; the stub keeps the library non-empty until the real parser lands.
std::string_view json_min_module();

}  // namespace sh
