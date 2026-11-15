// Tests of apps/measured_calls.hpp: a failed forward() must stop the per-kernel passes of nn_bench with its message.
#include <expected>
#include <string>
#include <vector>

#include "check.hpp"
#include "config.hpp"
#include "measured_calls.hpp"

namespace {

using sh::bench::collect_kernel_series;
using sh::bench::run_measured;

struct K {
    std::string name;
    double ms;
};
using Result = std::expected<void, sh::nn::Error>;

SH_TEST(all_calls_succeed) {
    int n = 0, samples = 0;
    const std::string e = run_measured(2, 3, [&]() -> Result { ++n; return {}; }, [&]() -> std::string { ++samples; return {}; });
    CHECK_EQ(e, std::string());
    CHECK_EQ(n, 5);
    CHECK_EQ(samples, 3);
}

SH_TEST(warmup_failure_is_reported) {
    int n = 0;
    const std::string e = run_measured(3, 3, [&]() -> Result { if (++n == 2) return std::unexpected(sh::nn::Error{"boom"}); return {}; },
                                       []() -> std::string { return {}; });
    CHECK(e.find("warm-up call 2") != std::string::npos);
    CHECK(e.find("boom") != std::string::npos);
}

SH_TEST(measured_failure_stops_the_series) {
    int n = 0;
    const std::vector<K> log{{"a", 1.0}, {"b", 2.0}};
    const auto r = collect_kernel_series(1, 5, [&]() -> Result { if (++n == 3) return std::unexpected(sh::nn::Error{"device lost"}); return {}; },
                                         [&]() -> const std::vector<K>& { return log; });
    CHECK(r.error.find("call 2") != std::string::npos);
    CHECK(r.error.find("device lost") != std::string::npos);
}

SH_TEST(every_call_fails_gives_an_error_not_an_empty_table) {
    const std::vector<K> empty;
    const auto r = collect_kernel_series(0, 3, []() -> Result { return std::unexpected(sh::nn::Error{"x"}); },
                                         [&]() -> const std::vector<K>& { return empty; });
    CHECK(!r.error.empty());
    CHECK(r.names.empty());
}

SH_TEST(empty_log_without_failure_is_an_error) {
    const std::vector<K> empty;
    const auto r = collect_kernel_series(0, 3, []() -> Result { return {}; }, [&]() -> const std::vector<K>& { return empty; });
    CHECK(r.error.find("no kernel times") != std::string::npos);
}

SH_TEST(shrinking_log_is_an_error) {
    std::vector<K> log{{"a", 1.0}, {"b", 2.0}};
    int n = 0;
    const auto r = collect_kernel_series(0, 4, []() -> Result { return {}; }, [&]() -> const std::vector<K>& {
        if (++n == 3) log.pop_back();
        return log;
    });
    CHECK(r.error.find("kernel log has 1 entries, expected 2") != std::string::npos);
}

SH_TEST(complete_series) {
    const std::vector<K> log{{"a", 1.5}, {"b", 2.5}};
    const auto r = collect_kernel_series(1, 4, []() -> Result { return {}; }, [&]() -> const std::vector<K>& { return log; });
    CHECK_EQ(r.error, std::string());
    CHECK_EQ(r.names.size(), std::size_t{2});
    CHECK_EQ(r.series[1].size(), std::size_t{4});
}

}  // namespace

int main() { return sh::test::run_all(); }
