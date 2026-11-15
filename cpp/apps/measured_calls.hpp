// Helpers of nn_bench for the passes that read a per-call log after every forward() (the CUDA kernel table and the Metal
// instrumented pass). A failed forward() must stop the pass: its log is empty or partial, and a median over it would look
// like a result.
#pragma once

#include <cstddef>
#include <string>
#include <vector>

namespace sh::bench {

// Calls `call` `warmup` times and then `calls` times; `sample` runs after every measured call. `call` returns an
// expected-like value (error().message). `sample` returns an error message, empty on success. Stops at the first failure and
// returns its message; an empty string means every call and sample succeeded.
template <class Call, class Sample>
std::string run_measured(int warmup, int calls, Call&& call, Sample&& sample) {
    for (int i = 0; i < warmup; ++i) {
        if (auto r = call(); !r) return "warm-up call " + std::to_string(i + 1) + " failed: " + r.error().message;
    }
    for (int i = 0; i < calls; ++i) {
        if (auto r = call(); !r) return "call " + std::to_string(i + 1) + " failed: " + r.error().message;
        if (std::string e = sample(); !e.empty()) return "call " + std::to_string(i + 1) + ": " + e;
    }
    return {};
}

struct KernelSeries {
    std::vector<std::string> names;
    std::vector<std::vector<double>> series;  // series[k]: the time of kernel names[k] in every measured call
    std::string error;                        // empty = complete
};

// run_measured plus the per-kernel series. `times()` returns the kernel log of the last call (elements with .name and .ms).
// Every call must report the same kernels as the first one, and there must be at least one.
template <class Call, class Times>
KernelSeries collect_kernel_series(int warmup, int calls, Call&& call, Times&& times) {
    KernelSeries out;
    out.error = run_measured(warmup, calls, call, [&]() -> std::string {
        const auto& kt = times();
        if (out.series.empty()) {
            for (const auto& k : kt) out.names.push_back(k.name);
            out.series.resize(out.names.size());
        } else if (kt.size() != out.names.size()) {
            return "kernel log has " + std::to_string(kt.size()) + " entries, expected " + std::to_string(out.names.size());
        }
        for (std::size_t k = 0; k < kt.size(); ++k) out.series[k].push_back(kt[k].ms);
        return {};
    });
    if (out.error.empty() && out.names.empty()) out.error = "no kernel times were recorded";
    return out;
}

}  // namespace sh::bench
