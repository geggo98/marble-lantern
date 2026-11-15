// Little-endian reads from a byte span. The WP0 spike (cpp/README.md) showed that std::copy_n into a std::array followed by
// std::bit_cast passes -Wunsafe-buffer-usage, so no unsafe zone is needed here. Every read is bounds-checked and returns
// std::nullopt when the span is too short.
#pragma once

#include <algorithm>
#include <array>
#include <bit>
#include <cstddef>
#include <cstdint>
#include <optional>
#include <span>
#include <type_traits>

namespace sh {

static_assert(std::endian::native == std::endian::little, "the file formats are little-endian; this code reads them as they are");

template <class T>
    requires std::is_trivially_copyable_v<T>
std::optional<T> read_le(std::span<const std::byte> bytes, std::size_t offset) {
    if (offset > bytes.size() || bytes.size() - offset < sizeof(T)) return std::nullopt;
    std::array<std::byte, sizeof(T)> raw{};
    std::copy_n(bytes.begin() + static_cast<std::ptrdiff_t>(offset), sizeof(T), raw.begin());
    return std::bit_cast<T>(raw);
}

}  // namespace sh
