// Glue between the golden reader and the test harness: skip when a golden file is missing, fail when it is required or corrupt.
#pragma once

#include <optional>
#include <string>
#include <string_view>
#include <vector>

#include "check.hpp"
#include "golden.hpp"

namespace sh::golden {

inline std::optional<std::vector<std::string>> lines_or_skip(std::string_view name) {
    auto result = load_lines(name);
    if (result) return std::move(*result);
    const std::string message = std::string(name) + ": " + result.error().message;
    if (result.error().kind == ErrorKind::Missing && !required()) {
        ::sh::test::skip(message);
    } else {
        ::sh::test::count_check();
        ::sh::test::fail("golden", 0, message);
    }
    return std::nullopt;
}

}  // namespace sh::golden
