// Command-line arguments as std::vector<std::string_view>. argv[i] is raw-pointer indexing, which -Wunsafe-buffer-usage rejects,
// so the conversion is the one marked unsafe zone of the apps (docs/cpp-comparison-plan.md, "Unsafe zones"). main touches nothing else.
#pragma once

#include <string_view>
#include <vector>

namespace sh {

#if defined(__clang__)
#pragma clang unsafe_buffer_usage begin
#endif
inline std::vector<std::string_view> args_from_main(int argc, char** argv) {
    std::vector<std::string_view> out;
    out.reserve(argc > 0 ? static_cast<std::size_t>(argc) : 0);
    for (int i = 0; i < argc; ++i) out.emplace_back(argv[i]);
    return out;
}
#if defined(__clang__)
#pragma clang unsafe_buffer_usage end
#endif

}  // namespace sh
