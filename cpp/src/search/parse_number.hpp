// Strict number parsing for spec and golden text (the whole text is the number, an optional sign, no spaces). Written with a digit
// loop because std::from_chars needs raw-pointer arithmetic, which -Wunsafe-buffer-usage rejects.
#pragma once

#include <limits>
#include <optional>
#include <string_view>

namespace sh {

template <class T>
constexpr std::optional<T> parse_number(std::string_view text, int base = 10) {
    bool negative = false;
    if (!text.empty() && (text.front() == '-' || text.front() == '+')) {
        negative = text.front() == '-';
        text.remove_prefix(1);
    }
    if (text.empty() || (negative && !std::numeric_limits<T>::is_signed)) return std::nullopt;
    using Wide = unsigned long long;
    // the magnitude limit: one more for a negative signed number
    Wide limit = static_cast<Wide>(std::numeric_limits<T>::max());
    if (negative) limit += 1;
    Wide value = 0;
    for (const char c : text) {
        int digit = 0;
        if (c >= '0' && c <= '9') {
            digit = c - '0';
        } else if (c >= 'a' && c <= 'f') {
            digit = c - 'a' + 10;
        } else if (c >= 'A' && c <= 'F') {
            digit = c - 'A' + 10;
        } else {
            return std::nullopt;
        }
        if (digit >= base) return std::nullopt;
        const Wide b = static_cast<Wide>(base);
        if (value > (limit - static_cast<Wide>(digit)) / b) return std::nullopt;
        value = value * b + static_cast<Wide>(digit);
    }
    if (negative) return static_cast<T>(-static_cast<long long>(value - 1) - 1);
    return static_cast<T>(value);
}

}  // namespace sh
