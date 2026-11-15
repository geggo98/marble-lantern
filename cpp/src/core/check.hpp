// Project-owned test harness: test registration, CHECK macros, skip, exit code. No framework needed on the AWS build.
// Usage in a test executable:
//   #include "check.hpp"
//   SH_TEST(adds) { CHECK_EQ(1 + 1, 2); }
//   int main() { return sh::test::run_all(); }
// Each test executable is one ctest test. A failed CHECK marks the running test as failed and continues; run_all()
// returns 1 if any test failed, else 0.
#pragma once

#include <cstddef>
#include <functional>
#include <iostream>
#include <sstream>
#include <string>
#include <string_view>
#include <vector>

namespace sh::test {

struct Case {
    std::string name;
    std::function<void()> body;
};

inline std::vector<Case>& registry() {
    static std::vector<Case> cases;
    return cases;
}

struct State {
    int checks = 0;
    int failures_in_test = 0;
    int skipped_tests = 0;
    bool skipped = false;
};

inline State& state() {
    static State s;
    return s;
}

struct Registrar {
    Registrar(std::string name, std::function<void()> body) { registry().push_back({std::move(name), std::move(body)}); }
};

template <class T>
std::string show(const T& v) {
    std::ostringstream os;
    if constexpr (requires { os << v; }) {
        os << v;
    } else {
        os << "<value>";
    }
    return os.str();
}

inline void fail(std::string_view file, int line, const std::string& message) {
    ++state().failures_in_test;
    std::cerr << file << ':' << line << ": CHECK failed: " << message << '\n';
}

inline void count_check() { ++state().checks; }

// Marks the running test as skipped (for example when a golden file is missing and not required).
inline void skip(const std::string& why) {
    state().skipped = true;
    std::cout << "SKIP: " << why << '\n';
}

inline int run_all() {
    int failed = 0;
    int passed = 0;
    for (const Case& c : registry()) {
        state().failures_in_test = 0;
        state().skipped = false;
        c.body();
        if (state().failures_in_test > 0) {
            ++failed;
            std::cout << "FAIL: " << c.name << '\n';
        } else if (state().skipped) {
            ++state().skipped_tests;
            std::cout << "SKIPPED (partly or fully): " << c.name << '\n';
        } else {
            ++passed;
            std::cout << "PASS: " << c.name << '\n';
        }
    }
    std::cout << passed << " passed, " << state().skipped_tests << " skipped, " << failed << " failed, "
              << state().checks << " checks\n";
    return failed == 0 ? 0 : 1;
}

}  // namespace sh::test

#define SH_TEST(name)                                                         \
    static void sh_test_##name();                                             \
    static const ::sh::test::Registrar sh_registrar_##name(#name, sh_test_##name); \
    static void sh_test_##name()

#define CHECK(cond)                                                              \
    do {                                                                         \
        ::sh::test::count_check();                                               \
        if (!(cond)) ::sh::test::fail(__FILE__, __LINE__, #cond);                \
    } while (false)

#define CHECK_EQ(a, b)                                                                              \
    do {                                                                                            \
        ::sh::test::count_check();                                                                  \
        const auto sh_a = (a);                                                                      \
        const auto sh_b = (b);                                                                      \
        if (!(sh_a == sh_b))                                                                        \
            ::sh::test::fail(__FILE__, __LINE__,                                                    \
                             std::string(#a " == " #b ": ") + ::sh::test::show(sh_a) + " vs " + ::sh::test::show(sh_b)); \
    } while (false)
