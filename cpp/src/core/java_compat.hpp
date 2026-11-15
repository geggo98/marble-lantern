// Java behaviour that C++ does not have by default (docs/cpp-comparison-plan.md, "Java compatibility rules").
// Each function states the Java expression it stands for. Tests: cpp/tests/java_compat_test.cpp.
#pragma once

#include <cmath>
#include <cstdint>
#include <limits>

namespace sh {

// Math.round(double): rounds half up (toward positive infinity), NaN gives 0, results saturate to the long range.
// Not std::round (half away from zero) and not floor(x + 0.5) (wrong for 0.49999999999999994).
inline std::int64_t java_round(double x) {
    if (std::isnan(x)) return 0;
    if (x >= 9223372036854775807.0) return std::numeric_limits<std::int64_t>::max();
    if (x <= -9223372036854775808.0) return std::numeric_limits<std::int64_t>::min();
    const double f = std::floor(x);
    const double diff = x - f;  // exact: f and x share the exponent range below 2^52, and x is integral above it
    const double r = diff >= 0.5 ? f + 1.0 : f;
    if (r >= 9223372036854775807.0) return std::numeric_limits<std::int64_t>::max();
    return static_cast<std::int64_t>(r);
}

// (long) d: truncates toward zero, NaN gives 0, saturates.
inline std::int64_t java_d2l(double d) {
    if (std::isnan(d)) return 0;
    if (d >= 9223372036854775807.0) return std::numeric_limits<std::int64_t>::max();
    if (d <= -9223372036854775808.0) return std::numeric_limits<std::int64_t>::min();
    return static_cast<std::int64_t>(d);
}

// (int) d: truncates toward zero, NaN gives 0, saturates. A plain static_cast of an out-of-range double is undefined in C++.
inline std::int32_t java_d2i(double d) {
    if (std::isnan(d)) return 0;
    if (d >= 2147483647.0) return std::numeric_limits<std::int32_t>::max();
    if (d <= -2147483648.0) return std::numeric_limits<std::int32_t>::min();
    return static_cast<std::int32_t>(d);
}

// Math.max(float, float): NaN if either is NaN; max(-0f, +0f) is +0f. std::max keeps -0 and ignores NaN.
inline float java_max(float a, float b) {
    if (std::isnan(a)) return a;
    if (std::isnan(b)) return b;
    if (a == 0.0f && b == 0.0f) return std::signbit(a) ? b : a;
    return a >= b ? a : b;
}

}  // namespace sh
