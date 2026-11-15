// Minimal JSON reader for the safetensors header (docs/cpp-comparison-plan.md, WP9). It reads exactly what the header needs:
// objects, arrays, strings with escapes and non-negative integers, and rejects everything else (true, false, null, fractions,
// exponents, negative numbers, leading zeros, integers above 2^63 - 1, unpaired surrogate escapes, control characters in
// strings, nesting deeper than kMaxDepth, content after the top-level value). The input must be valid UTF-8; a string that
// is not is rejected before parsing. Object members keep their order and duplicates; the caller decides what a duplicate means.
#pragma once

#include <cstdint>
#include <expected>
#include <string>
#include <string_view>
#include <utility>
#include <variant>
#include <vector>

namespace sh::json {

struct Error {
    std::string message;  // includes the character position
};

struct Value;
using Object = std::vector<std::pair<std::string, Value>>;
using Array = std::vector<Value>;

struct Value {
    std::variant<std::string, std::uint64_t, Array, Object> v;
    bool is_string() const { return std::holds_alternative<std::string>(v); }
    bool is_uint() const { return std::holds_alternative<std::uint64_t>(v); }
    bool is_array() const { return std::holds_alternative<Array>(v); }
    bool is_object() const { return std::holds_alternative<Object>(v); }
};

inline constexpr int kMaxDepth = 16;

// True if the text is well-formed UTF-8 (no overlong forms, no surrogates, nothing above U+10FFFF).
bool is_valid_utf8(std::string_view text);

// Parses one JSON value that covers the whole text (surrounding JSON whitespace is allowed).
std::expected<Value, Error> parse(std::string_view text);

}  // namespace sh::json
