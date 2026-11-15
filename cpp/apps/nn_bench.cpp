// Network benchmark (CPU, Metal, CUDA) with the schedule of the plan (docs/cpp-comparison-plan.md, WP13, "Network measurement").
//   nn_bench [--golden DIR] [--presets small,large[,gen11]] [--batches 1,16,64,256,512,1024]
//            [--impls reference,simd,simd-nofma,metal-copy,metal-shared,cuda-pageable,cuda-pinned]
//            [--variant auto|naive|tiled|simdgroup] [--anchors] [--cold] [--kernels] [--no-profiled]
//
// Prints the shared TSV schema of CrossBench (README, "The Java benchmark and its schedule"): comment lines `# ...`, one header
// line and one line per case. The schedule of a case is the one of the Java side: a warm-up of at least 2 s and 10 calls (a CPU
// case stops at 2 s), then 3 rounds of at least 1 s and at least one call; the input of call c is the golden positions
// c*n .. c*n+n-1 modulo 1,024; `evals_per_s` is the median round, `round_min` and `round_max` the other two; the last call of
// every case is checked against nn-ref-* with the bound of nn_check (plus the extra term of MANIFEST.tsv). A violation ends the
// program with exit code 2 and prints no line for that case. A CPU case whose one call would take more than 3 s at the rate of
// the smaller batches is skipped (`# skipped`).
//
// Phases: the CPU paths report NA. Metal (plain mode): copy_in and copy_out are the CPU copies, kernel_ms is commit-and-wait
// (blits, kernels and waiting in one command buffer, the counterpart of the "execute" figure of TornadoVM), h2d_ms and d2h_ms are
// NA; a comment line gives the GPU time of the command buffer. CUDA: h2d, kernel and d2h are cudaEvent times. After the main
// table a second pass with the instrumented Metal mode (one command buffer per kernel) prints `#instrumented` lines, the
// counterpart of the `#profiled` lines of the TornadoVM profiler; its wall time is not a result.
//
// --kernels: the convolution kernels alone (Metal: one table line per preset, batch and variant, GPU time of one command buffer;
// CUDA: the per-kernel cudaEvent times of the instrumented whole network). --cold: one forward pass of the first preset, impl and
// batch, then a `# cold` line. --anchors: SMALL and LARGE at batch 256 only (default impls simd and the Metal copy mode).
// Exit codes: 0 done, 2 verification failed or unreadable golden data, 64 usage, or an implementation that is not in this build.
#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <ctime>
#include <filesystem>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <limits>
#include <memory>
#include <numeric>
#include <optional>
#include <sstream>
#include <string>
#include <string_view>
#include <vector>

#if defined(__APPLE__)
#include <sys/sysctl.h>
#endif

#include "args.hpp"
#include "buckets.hpp"
#include "cuda_backend.hpp"
#include "golden_io.hpp"
#include "measured_calls.hpp"
#include "reference_net.hpp"
#include "simd_net.hpp"
#include "splittable_random.hpp"

#if defined(__APPLE__) && defined(__clang__)
#define SH_HAVE_METAL 1
#else
#define SH_HAVE_METAL 0
#endif
#if SH_HAVE_METAL
#include "metal_net.hpp"
#endif

namespace {

using namespace sh::nn;
using Clock = std::chrono::steady_clock;

constexpr double kNa = std::numeric_limits<double>::quiet_NaN();
constexpr double kWarmupSeconds = 2.0;
constexpr int kWarmupCalls = 10;
constexpr double kRoundSeconds = 1.0;
constexpr int kRounds = 3;
constexpr double kCpuCallLimitSeconds = 3.0;
constexpr int kInstrumentedCalls = 20;
constexpr int kPositions = 1024;
constexpr std::string_view kColumns =
    "lang\timpl\tdevice\tpreset\tweights\tbatch\tbucket\tlanes\tevals_per_s\tms_per_call_median\tms_p90\tround_min\tround_max"
    "\tcopy_in_ms\th2d_ms\tkernel_ms\td2h_ms\tcopy_out_ms\tpriors_ms\tfirst_call_ms\tload_1min\tverified";

double ms_since(Clock::time_point t0) { return std::chrono::duration<double, std::milli>(Clock::now() - t0).count(); }

std::string num(double v, int digits) {
    std::ostringstream s;
    s << std::fixed << std::setprecision(digits) << v;
    return s.str();
}
std::string na_or(double v, int digits) { return std::isnan(v) ? "NA" : num(v, digits); }

double load1() {
    std::array<double, 3> l{};
    return ::getloadavg(l.data(), 3) < 1 ? -1.0 : l[0];
}

double thread_cpu_seconds() {
    timespec ts{};
    ::clock_gettime(CLOCK_THREAD_CPUTIME_ID, &ts);
    return static_cast<double>(ts.tv_sec) + 1e-9 * static_cast<double>(ts.tv_nsec);
}

std::string cpu_model() {
    std::ifstream in("/proc/cpuinfo");
    for (std::string line; std::getline(in, line);) {
        if (line.starts_with("model name")) return line.substr(line.find(':') + 2);
    }
#if defined(__APPLE__)
    std::size_t len = 0;
    if (::sysctlbyname("machdep.cpu.brand_string", nullptr, &len, nullptr, 0) == 0 && len > 1) {
        std::string mac(len, '\0');
        if (::sysctlbyname("machdep.cpu.brand_string", mac.data(), &len, nullptr, 0) == 0) return std::string(mac.c_str());
    }
#endif
    return "unknown";
}

std::string hardening_mode() {
#if defined(_LIBCPP_VERSION)
#if defined(_LIBCPP_HARDENING_MODE)
    if (_LIBCPP_HARDENING_MODE == _LIBCPP_HARDENING_MODE_FAST) return "libc++ fast";
    if (_LIBCPP_HARDENING_MODE == _LIBCPP_HARDENING_MODE_EXTENSIVE) return "libc++ extensive";
    if (_LIBCPP_HARDENING_MODE == _LIBCPP_HARDENING_MODE_DEBUG) return "libc++ debug";
    return "libc++ none (unchecked)";
#else
    return "libc++ (mode unknown)";
#endif
#elif defined(_GLIBCXX_ASSERTIONS)
    return "libstdc++ _GLIBCXX_ASSERTIONS";
#else
    return "none (unchecked)";
#endif
}

std::string isa() {
    std::string s;
#if defined(__ARM_NEON)
    s += " NEON";
#endif
#if defined(__AVX512F__)
    s += " AVX512F";
#endif
#if defined(__AVX2__)
    s += " AVX2";
#endif
#if defined(__FMA__) || defined(__ARM_FEATURE_FMA)
    s += " FMA";
#endif
    return s.empty() ? " generic" : s;
}

// ---- command line ----

struct Options {
    std::string golden;
    std::vector<std::string> presets = {"small", "large"};
    std::vector<int> batches = {1, 16, 64, 256, 512, 1024};
    std::vector<std::string> impls;
    bool impls_given = false;
    std::string variant = "auto";
    bool anchors = false, cold = false, kernels = false, profiled = true;
};

std::vector<std::string> split_commas(std::string_view text) {
    std::vector<std::string> parts;
    std::size_t start = 0;
    while (start <= text.size()) {
        const std::size_t at = text.find(',', start);
        const std::size_t end = at == std::string_view::npos ? text.size() : at;
        if (end > start) parts.emplace_back(text.substr(start, end - start));
        if (at == std::string_view::npos) break;
        start = at + 1;
    }
    return parts;
}

[[noreturn]] void usage(std::string_view why) {
    if (!why.empty()) std::cerr << "nn_bench: " << why << '\n';
    std::cerr << "usage: nn_bench [--golden DIR] [--presets small,large,gen11] [--batches 1,16,64,256,512,1024]\n"
                 "                [--impls reference,simd,simd-nofma,metal-copy,metal-shared,cuda-pageable,cuda-pinned]\n"
                 "                [--variant auto|naive|tiled|simdgroup] [--anchors] [--cold] [--kernels] [--no-profiled]\n";
    std::exit(64);
}

Options parse(const std::vector<std::string_view>& args) {
    Options o;
    for (std::size_t i = 1; i < args.size(); ++i) {
        const std::string_view a = args[i];
        const auto value = [&]() -> std::string_view {
            if (i + 1 >= args.size()) usage(std::string(a) + " needs a value");
            return args[++i];
        };
        if (a == "--golden") {
            o.golden = std::string(value());
        } else if (a == "--presets") {
            o.presets = split_commas(value());
        } else if (a == "--batches") {
            o.batches.clear();
            for (const std::string& b : split_commas(value())) {
                const int n = std::atoi(b.c_str());
                if (n < 1 || n > kPositions) usage("batch " + b + " is not in 1.." + std::to_string(kPositions));
                o.batches.push_back(n);
            }
        } else if (a == "--impls") {
            o.impls = split_commas(value());
            o.impls_given = true;
        } else if (a == "--variant") {
            o.variant = std::string(value());
        } else if (a == "--anchors") {
            o.anchors = true;
        } else if (a == "--cold") {
            o.cold = true;
        } else if (a == "--kernels") {
            o.kernels = true;
        } else if (a == "--no-profiled") {
            o.profiled = false;
        } else if (a == "--help" || a == "-h") {
            usage("");
        } else {
            usage("unknown option " + std::string(a));
        }
    }
    return o;
}

// ---- implementations ----

struct Totals {  // totals since construction; NaN = not measured separately
    std::int64_t executions = 0;
    double copy_in = kNa, h2d = kNa, kernel = kNa, d2h = kNa, copy_out = kNa, priors = kNa, gpu = kNa;
};

class Runner {
public:
    virtual ~Runner() = default;
    virtual std::expected<void, Error> forward(std::span<const float> in, int batch, std::span<float> logits, std::span<float> values) = 0;
    virtual Totals totals() const { return {}; }
    virtual bool is_gpu() const { return false; }
    virtual std::string device() const { return "cpu"; }
    virtual std::vector<std::string> notes() const { return {}; }  // set-up facts, printed after the cases of one net
};

class ReferenceRunner final : public Runner {
public:
    explicit ReferenceRunner(const Parameters& p) : net_(p) {}
    std::expected<void, Error> forward(std::span<const float> in, int batch, std::span<float> lo, std::span<float> va) override { return net_.forward(in, batch, lo, va); }

private:
    ReferenceNet net_;
};

class SimdRunner final : public Runner {
public:
    SimdRunner(const Parameters& p, SimdVariant v) : net_(p, v) {}
    std::expected<void, Error> forward(std::span<const float> in, int batch, std::span<float> lo, std::span<float> va) override { return net_.forward(in, batch, lo, va); }

private:
    SimdNet net_;
};

std::optional<ConvVariant> parse_variant(const std::string& v, bool simdgroup_ok) {
    if (v == "naive") return ConvVariant::Naive;
    if (v == "tiled") return ConvVariant::Tiled;
    if (v == "simdgroup" && simdgroup_ok) return ConvVariant::Simdgroup;
    return std::nullopt;
}

#if SH_HAVE_METAL
class MetalRunner final : public Runner {
public:
    explicit MetalRunner(sh::metal::MetalNet net) : net_(std::move(net)) {}
    std::expected<void, Error> forward(std::span<const float> in, int batch, std::span<float> lo, std::span<float> va) override { return net_.forward(in, batch, lo, va); }
    bool is_gpu() const override { return true; }
    std::string device() const override { return "metal"; }
    Totals totals() const override {
        const sh::metal::PhaseTimes& t = net_.timings();
        Totals out;
        out.executions = t.calls;
        out.copy_in = t.copy_in_ms;
        out.kernel = t.commit_wait_ms;  // blits, kernels and waiting: the counterpart of the TornadoVM "execute" figure
        out.copy_out = t.copy_out_ms;
        out.priors = t.priors_ms;
        out.gpu = t.gpu_ms;
        return out;
    }
    std::vector<std::string> notes() const override {
        std::vector<std::string> n;
        n.push_back("metal library compile (newLibraryWithSource, once per process): " + num(sh::metal::library_compile_ms(), 3) + " ms");
        for (const sh::metal::BucketSetup& s : net_.setup()) {
            n.push_back("metal set-up of bucket " + std::to_string(s.bucket) + ": pipelines " + num(s.pipeline_ms, 3) + " ms, buffers " + num(s.buffer_ms, 3) + " ms");
        }
        return n;
    }

private:
    sh::metal::MetalNet net_;
};
#endif

class CudaRunner final : public Runner {
public:
    explicit CudaRunner(sh::CudaBackend net) : net_(std::move(net)) {}
    std::expected<void, Error> forward(std::span<const float> in, int batch, std::span<float> lo, std::span<float> va) override { return net_.forward(in, batch, lo, va); }
    bool is_gpu() const override { return true; }
    std::string device() const override { return "cuda"; }
    Totals totals() const override {
        const sh::CudaPhaseTimes& t = net_.timings();
        return Totals{t.calls, t.copy_in_ms, t.h2d_ms, t.kernel_ms, t.d2h_ms, t.copy_out_ms, t.priors_ms, kNa};
    }
    std::vector<std::string> notes() const override {
        std::vector<std::string> n;
        n.push_back("cuda context and weights: " + num(net_.init_ms(), 3) + " ms");
        for (const sh::cuda::BucketSetup& s : net_.setup()) n.push_back("cuda buffers of bucket " + std::to_string(s.bucket) + ": " + num(s.buffer_ms, 3) + " ms");
        return n;
    }

private:
    sh::CudaBackend net_;
};

bool have_metal() {
#if SH_HAVE_METAL
    return sh::metal::device_available();
#else
    return false;
#endif
}
bool have_cuda() { return sh::cuda::build_kind() == "cuda" && sh::cuda::device_count() > 0; }

bool known_impl(const std::string& i) {
    return i == "reference" || i == "simd" || i == "simd-nofma" || i == "metal-copy" || i == "metal-shared" || i == "cuda-pageable" || i == "cuda-pinned";
}
bool available(const std::string& i) {
    if (i.starts_with("metal")) return have_metal();
    if (i.starts_with("cuda")) return have_cuda();
    return true;
}

std::string label_of(const std::string& impl, const std::string& variant) {
    return (impl.starts_with("metal") || impl.starts_with("cuda")) && variant != "auto" ? impl + ":" + variant : impl;
}

std::expected<std::unique_ptr<Runner>, Error> make_runner(const std::string& impl, const Parameters& p, const std::string& variant) {
    if (impl == "reference") return std::unique_ptr<Runner>(std::make_unique<ReferenceRunner>(p));
    if (impl == "simd") return std::unique_ptr<Runner>(std::make_unique<SimdRunner>(p, SimdVariant::Fma));
    if (impl == "simd-nofma") return std::unique_ptr<Runner>(std::make_unique<SimdRunner>(p, SimdVariant::NoFma));
#if SH_HAVE_METAL
    if (impl.starts_with("metal")) {
        sh::metal::Options o;
        if (variant != "auto") {
            o.variant = parse_variant(variant, true);
            if (!o.variant) return std::unexpected(Error{"unknown variant " + variant});
        }
        o.memory = impl == "metal-shared" ? sh::metal::MemoryMode::Shared : sh::metal::MemoryMode::Copy;
        auto net = sh::metal::MetalNet::create(p, o);
        if (!net) return std::unexpected(net.error());
        return std::unique_ptr<Runner>(std::make_unique<MetalRunner>(std::move(*net)));
    }
#endif
    if (impl.starts_with("cuda")) {
        sh::CudaOptions o;
        if (variant != "auto") {
            o.variant = parse_variant(variant, false);
            if (!o.variant) return std::unexpected(Error{"unknown variant " + variant + " for cuda"});
        }
        o.memory = impl == "cuda-pinned" ? sh::cuda::HostMemory::Pinned : sh::cuda::HostMemory::Pageable;
        auto net = sh::CudaBackend::create(p, o);
        if (!net) return std::unexpected(net.error());
        return std::unique_ptr<Runner>(std::make_unique<CudaRunner>(std::move(*net)));
    }
    return std::unexpected(Error{"implementation " + impl + " is not in this build"});
}

// ---- one case ----

struct PresetData {
    std::string name;     // small | large | gen11
    std::string preset;   // column: small | large
    std::string weights;  // column: u5 | gen11
    Parameters params;
    GoldenData data;
    double extra_vector = 0, extra_tornado = 0;
};

struct CaseResult {
    std::string line;
    double first_ms = 0;
    double rate = 0;
};

double median_of(std::vector<double> v) {
    std::sort(v.begin(), v.end());
    return v[v.size() / 2];
}

CaseResult run_case(Runner& net, const std::string& label, const std::string& impl, const PresetData& pd, int batch, bool once, double bound_extra) {
    const NetworkConfig& cfg = pd.params.config();
    const std::size_t size = static_cast<std::size_t>(cfg.input_size());
    const int players = cfg.players;
    const int distinct = kPositions / std::gcd(kPositions, batch);
    std::vector<std::vector<float>> slices(static_cast<std::size_t>(distinct));
    for (int k = 0; k < distinct; ++k) {
        std::vector<float>& s = slices[static_cast<std::size_t>(k)];
        s.resize(static_cast<std::size_t>(batch) * size);
        for (int b = 0; b < batch; ++b) {
            const std::size_t from = static_cast<std::size_t>((static_cast<long long>(k) * batch + b) % kPositions) * size;
            std::copy_n(pd.data.inputs.begin() + static_cast<std::ptrdiff_t>(from), size, s.begin() + static_cast<std::ptrdiff_t>(static_cast<std::size_t>(b) * size));
        }
    }
    std::vector<float> logits(static_cast<std::size_t>(batch) * kPolicySize);
    std::vector<float> values(static_cast<std::size_t>(batch) * static_cast<std::size_t>(players));
    const bool gpu = net.is_gpu();
    const double load = load1();
    const auto call = [&](long long c) {
        auto r = net.forward(slices[static_cast<std::size_t>(c % distinct)], batch, logits, values);
        if (!r) {
            std::cerr << "nn_bench: " << label << " " << pd.name << " batch " << batch << ": forward failed: " << r.error().message << '\n';
            std::exit(2);
        }
    };

    long long n_calls = 0;
    const auto t0 = Clock::now();
    call(n_calls++);
    const double first_ms = ms_since(t0);

    const auto verify = [&](long long last_call) {
        const int last_slice = static_cast<int>(last_call % distinct);
        const int first_ref = static_cast<int>((static_cast<long long>(last_slice) * batch) % kPositions);
        const Comparison c = compare_outputs(logits, values, pd.data.logits, pd.data.values, first_ref, batch, players, bound_extra);
        const bool exact_ok = impl != "reference" || (c.logits_bit_identical && c.values_max_ulps <= 1.0);
        if (c.fraction() > 1.0 || !exact_ok) {
            std::cerr << "verification failed: " << label << " " << pd.preset << " " << pd.weights << " batch " << batch << ": error " << num(c.fraction(), 3)
                      << " of the bound" << (exact_ok ? "" : " (not identical to the reference)") << '\n';
            std::exit(2);
        }
    };

    const std::string bucket = gpu ? std::to_string(*bucket_for(batch, kDefaultMaxBatch)) : "NA";
    const std::string lanes = impl.starts_with("simd") ? std::to_string(kSimdLanes) : "NA";
    const auto head = [&](const std::string& rate, const std::string& ms50, const std::string& ms90, const std::string& mn, const std::string& mx,
                          const std::array<std::string, 6>& phases, double first, double ld) {
        std::string s = "cpp\t" + label + "\t" + net.device() + "\t" + pd.preset + "\t" + pd.weights + "\t" + std::to_string(batch) + "\t" + bucket + "\t" + lanes + "\t" + rate +
                        "\t" + ms50 + "\t" + ms90 + "\t" + mn + "\t" + mx;
        for (const std::string& p : phases) s += "\t" + p;
        return s + "\t" + num(first, 3) + "\t" + num(ld, 2) + "\tok";
    };
    const std::array<std::string, 6> none = {"NA", "NA", "NA", "NA", "NA", "NA"};
    if (once) {
        verify(n_calls - 1);
        return CaseResult{head("NA", "NA", "NA", "NA", "NA", none, first_ms, load), first_ms, 0};
    }

    const auto warm_start = Clock::now();
    while (true) {
        const bool time_up = ms_since(warm_start) >= kWarmupSeconds * 1000;
        if (time_up && (!gpu || n_calls >= kWarmupCalls)) break;
        call(n_calls++);
    }
    const Totals before = net.totals();
    const double cpu_before = thread_cpu_seconds();
    const auto measure_start = Clock::now();
    std::array<double, kRounds> rates{};
    std::vector<double> times;
    for (int r = 0; r < kRounds; ++r) {
        const auto begin = Clock::now();
        long long calls = 0;
        double elapsed_ms = 0;
        do {
            const auto a = Clock::now();
            call(n_calls++);
            const auto z = Clock::now();
            ++calls;
            times.push_back(std::chrono::duration<double, std::milli>(z - a).count());
            elapsed_ms = std::chrono::duration<double, std::milli>(z - begin).count();
        } while (elapsed_ms < kRoundSeconds * 1000);
        rates[static_cast<std::size_t>(r)] = static_cast<double>(calls) * batch / (elapsed_ms / 1000);
    }
    const double wall_s = ms_since(measure_start) / 1000;
    const double cpu_s = thread_cpu_seconds() - cpu_before;
    const Totals after = net.totals();
    verify(n_calls - 1);
    std::sort(times.begin(), times.end());
    std::array<double, kRounds> sorted_rates = rates;
    std::sort(sorted_rates.begin(), sorted_rates.end());
    const double ms50 = times[times.size() / 2];
    const double ms90 = times[std::min(times.size() - 1, static_cast<std::size_t>(std::ceil(0.9 * static_cast<double>(times.size()))) - 1)];
    std::array<std::string, 6> phases = none;
    const double execs = static_cast<double>(after.executions - before.executions);
    if (gpu && execs > 0) {
        const auto per_call = [&](double a, double b) { return na_or((a - b) / execs, 3); };
        phases = {per_call(after.copy_in, before.copy_in), per_call(after.h2d, before.h2d), per_call(after.kernel, before.kernel),
                  per_call(after.d2h, before.d2h),         per_call(after.copy_out, before.copy_out), per_call(after.priors, before.priors)};
        if (!std::isnan(after.gpu)) {
            std::cout << "# gpu: " << label << " " << pd.preset << " " << pd.weights << " batch " << batch << ": GPU time of the command buffer "
                      << num((after.gpu - before.gpu) / execs, 3) << " ms per call\n";
        }
    }
    std::cout << "# cpu: " << label << " " << pd.preset << " " << pd.weights << " batch " << batch << ": thread CPU time / wall time over the measured rounds "
              << num(cpu_s / wall_s, 3) << '\n';
    return CaseResult{head(num(sorted_rates[1], 1), num(ms50, 3), num(ms90, 3), num(sorted_rates[0], 1), num(sorted_rates[2], 1), phases, first_ms, load), first_ms,
                      sorted_rates[1]};
}

// The last call of a pass after the main table is checked against nn-ref like the main table is (the inputs are the first
// positions of the golden data, so the reference starts at position 0).
void verify_pass_outputs(const char* what, const PresetData& pd, int batch, const std::vector<float>& logits, const std::vector<float>& values) {
    const Comparison c = compare_outputs(logits, values, pd.data.logits, pd.data.values, 0, batch, pd.params.config().players, pd.extra_tornado);
    if (c.fraction() > 1.0) {
        std::cerr << "verification failed: " << what << " " << pd.preset << " " << pd.weights << " batch " << batch << ": error " << num(c.fraction(), 3) << " of the bound\n";
        std::exit(2);
    }
}

// ---- passes after the main table ----

#if SH_HAVE_METAL
// Instrumented Metal mode (one command buffer per kernel and blit): the counterpart of the TornadoVM profiler lines.
void instrumented_pass(const PresetData& pd, const std::vector<int>& batches) {
    sh::metal::Options o;
    o.instrumented = true;
    auto created = sh::metal::MetalNet::create(pd.params, o);
    if (!created) {
        std::cerr << "nn_bench: instrumented pass: " << created.error().message << '\n';
        return;
    }
    sh::metal::MetalNet net = std::move(*created);
    const NetworkConfig& cfg = pd.params.config();
    const std::size_t size = static_cast<std::size_t>(cfg.input_size());
    for (int batch : batches) {
        std::vector<float> in(pd.data.inputs.begin(), pd.data.inputs.begin() + static_cast<std::ptrdiff_t>(static_cast<std::size_t>(batch) * size));
        std::vector<float> logits(static_cast<std::size_t>(batch) * kPolicySize);
        std::vector<float> values(static_cast<std::size_t>(batch) * static_cast<std::size_t>(cfg.players));
        std::vector<double> wall, kernel, write, read;
        auto t0 = Clock::now();
        const std::string error = sh::bench::run_measured(
            kWarmupCalls, kInstrumentedCalls,
            [&] {
                t0 = Clock::now();
                return net.forward(in, batch, logits, values);
            },
            [&]() -> std::string {
                wall.push_back(ms_since(t0));
                double k = 0, w = 0, r = 0;
                for (const sh::metal::KernelTime& t : net.kernel_times()) {
                    if (t.name == "blit_in") {
                        w += t.gpu_ms;
                    } else if (t.name == "blit_out") {
                        r += t.gpu_ms;
                    } else {
                        k += t.gpu_ms;
                    }
                }
                kernel.push_back(k);
                write.push_back(w);
                read.push_back(r);
                return {};
            });
        if (!error.empty()) {
            std::cerr << "nn_bench: instrumented pass " << pd.preset << " " << pd.weights << " batch " << batch << ": " << error << '\n';
            std::exit(2);
        }
        verify_pass_outputs("instrumented pass", pd, batch, logits, values);
        std::cout << "#instrumented\t" << pd.preset << "\t" << pd.weights << "\t" << batch << "\t" << num(median_of(wall), 3) << "\t" << num(median_of(kernel), 3) << "\t"
                  << num(median_of(write), 3) << "\t" << num(median_of(read), 3) << '\n';
    }
}

// The convolution kernels alone on shared buffers: GPU time of one command buffer (kernel only) and wall time of the call
// (which includes creating the buffers and copying; it is not comparable with the Java wall time).
void kernel_table_metal(const std::vector<PresetData>& presets, const std::vector<int>& batches) {
    std::cout << "#kernels-columns\tlang\tdevice\tC\tbatch\tvariant\tkernel_ms_median\tgflops_kernel\twall_ms_median\tgflops_wall"
                 "\t(GPU time of one command buffer with one kernel; wall includes buffer creation and copies)\n";
    for (const PresetData& pd : presets) {
        const int c = pd.params.config().channels;
        for (int batch : batches) {
            sh::SplittableRandom rnd(1);
            const auto uniform = [&rnd](std::size_t n, double bound) {
                std::vector<float> v(n);
                for (float& x : v) x = static_cast<float>((static_cast<double>(rnd.next_long() >> 11) * 0x1.0p-53 * 2 - 1) * bound);
                return v;
            };
            const std::size_t rows = static_cast<std::size_t>(batch) * kRows;
            std::vector<float> in = uniform(rows * static_cast<std::size_t>(c), 1.0);
            for (int b = 0; b < batch; ++b) {  // padding row 0
                std::fill_n(in.begin() + static_cast<std::ptrdiff_t>((static_cast<std::size_t>(b) * kRows + kPadding) * static_cast<std::size_t>(c)), c, 0.0f);
            }
            const std::vector<float> weight = uniform(static_cast<std::size_t>(c) * kStencil * static_cast<std::size_t>(c), std::sqrt(6.0 / (7.0 * c)));
            const std::vector<float> bias = uniform(static_cast<std::size_t>(c), 0.1);
            const std::vector<float> residual(1, 0.0f);
            std::vector<float> out(rows * static_cast<std::size_t>(c));
            for (ConvVariant v : {ConvVariant::Naive, ConvVariant::Tiled, ConvVariant::Simdgroup}) {
                const char* name = v == ConvVariant::Naive ? "naive" : v == ConvVariant::Tiled ? "tiled" : "simdgroup";
                std::vector<double> kernel, wall;
                const auto start = Clock::now();
                for (int i = 0;; ++i) {
                    const auto t0 = Clock::now();
                    auto r = sh::metal::run_conv(v, in, weight, bias, residual, out, batch, c, c, false, true);
                    const double w = ms_since(t0);
                    if (!r) {
                        std::cerr << "nn_bench: kernel " << name << ": " << r.error().message << '\n';
                        std::exit(2);
                    }
                    if (i < 30) continue;  // warm-up
                    kernel.push_back(*r);
                    wall.push_back(w);
                    if (kernel.size() >= 20 && ms_since(start) >= 1000) break;
                }
                const double flops = 2.0 * static_cast<double>(batch) * kCells * c * kStencil * c;
                const double km = median_of(kernel), wm = median_of(wall);
                std::cout << "#kernels\tcpp\tmetal\t" << c << "\t" << batch << "\t" << name << "\t" << num(km, 3) << "\t" << num(flops / km / 1e6, 1) << "\t" << num(wm, 3) << "\t"
                          << num(flops / wm / 1e6, 1) << '\n';
            }
        }
    }
}
#endif

// CUDA: the per-kernel cudaEvent times of the instrumented whole network, median of 20 calls, every variant.
void kernel_table_cuda(const std::vector<PresetData>& presets, const std::vector<int>& batches) {
    std::cout << "#kernels-cuda-columns\tlang\tdevice\tpreset\tweights\tbatch\tvariant\tkernel\tms_median\n";
    for (const PresetData& pd : presets) {
        const NetworkConfig& cfg = pd.params.config();
        const std::size_t size = static_cast<std::size_t>(cfg.input_size());
        for (const std::string_view variant : {"naive", "tiled"}) {
            for (int batch : batches) {
                sh::CudaOptions o;
                o.variant = variant == "naive" ? ConvVariant::Naive : ConvVariant::Tiled;
                o.instrumented = true;
                auto net = sh::CudaBackend::create(pd.params, o);
                if (!net) {
                    std::cerr << "nn_bench: kernels: " << net.error().message << '\n';
                    std::exit(2);
                }
                std::vector<float> in(pd.data.inputs.begin(), pd.data.inputs.begin() + static_cast<std::ptrdiff_t>(static_cast<std::size_t>(batch) * size));
                std::vector<float> logits(static_cast<std::size_t>(batch) * kPolicySize);
                std::vector<float> values(static_cast<std::size_t>(batch) * static_cast<std::size_t>(cfg.players));
                const sh::bench::KernelSeries ks = sh::bench::collect_kernel_series(
                    30, kInstrumentedCalls, [&] { return net->forward(in, batch, logits, values); }, [&]() -> const auto& { return net->kernel_times(); });
                if (!ks.error.empty()) {
                    std::cerr << "nn_bench: kernels " << pd.preset << " " << pd.weights << " batch " << batch << " " << variant << ": " << ks.error << '\n';
                    std::exit(2);
                }
                verify_pass_outputs("kernels", pd, batch, logits, values);
                const std::vector<std::string>& names = ks.names;
                const std::vector<std::vector<double>>& series = ks.series;
                for (std::size_t k = 0; k < names.size(); ++k) {
                    std::cout << "#kernels-cuda\tcpp\tcuda\t" << pd.preset << "\t" << pd.weights << "\t" << batch << "\t" << variant << "\t" << names[k] << "\t"
                              << num(median_of(series[k]), 3) << '\n';
                }
            }
        }
    }
}

}  // namespace

int main(int argc, char** argv) {
    const auto process_start = Clock::now();
    const auto args = sh::args_from_main(argc, argv);
    Options opt = parse(args);
    if (opt.anchors) {
        opt.presets = {"small", "large"};
        opt.batches = {256};
    }
    if (!opt.impls_given) {
        opt.impls = opt.anchors ? std::vector<std::string>{"simd"} : std::vector<std::string>{"reference", "simd", "simd-nofma"};
        if (have_metal()) {
            opt.impls.push_back("metal-copy");
            if (!opt.anchors) opt.impls.push_back("metal-shared");
        }
        if (have_cuda()) {
            opt.impls.push_back("cuda-pageable");
            if (!opt.anchors) opt.impls.push_back("cuda-pinned");
        }
    }
    for (const std::string& i : opt.impls) {
        if (!known_impl(i)) usage("unknown implementation " + i);
        if (!available(i)) {
            std::cerr << "nn_bench: implementation '" << i << "' is not available in this build or on this machine\n";
            return 64;
        }
    }
    const std::filesystem::path dir = opt.golden.empty() ? sh::golden::directory() : std::filesystem::path(opt.golden);
    auto figures = load_figures(dir);
    if (!figures) {
        std::cerr << "nn_bench: " << figures.error().message << '\n';
        return 2;
    }

    std::string device_name = "none";
#if SH_HAVE_METAL
    if (sh::metal::device_available()) device_name = sh::metal::device_name() + " (Metal)";
#endif
    if (have_cuda()) device_name = sh::cuda::device_name() + " (CUDA)";
#if defined(__clang__)
    const std::string compiler = std::string("clang ") + __clang_version__;
#else
    const std::string compiler = std::string("gcc ") + __VERSION__;
#endif
#if defined(__OPTIMIZE__)
    const char* optimised = "optimised";
#else
    const char* optimised = "NOT optimised";
#endif
    std::cout << "# nn_bench (C++ side of the cross-language network benchmark)\n";
    std::cout << "# cpu " << cpu_model() << "\n";
    std::cout << "# compiler " << compiler << ", " << optimised << ", " << sizeof(void*) * 8 << " bit," << isa() << ", hardening " << hardening_mode() << "\n";
    std::cout << "# simd lanes " << kSimdLanes << " (SH_SIMD_LANES), vector extension types; one host thread\n";
    std::cout << "# device " << device_name << "\n";
    std::cout << "# phases: cpu paths NA; metal: kernel_ms is commit-and-wait (blits, kernels, waiting), h2d_ms and d2h_ms are NA; cuda: cudaEvent times\n";
    std::cout << "# load_1min before " << num(load1(), 2) << "\n";
    std::cout << "# golden manifest checked; presets";
    for (const std::string& p : opt.presets) std::cout << " " << p;
    std::cout << ", batches";
    for (int b : opt.batches) std::cout << " " << b;
    std::cout << ", impls";
    for (const std::string& i : opt.impls) std::cout << " " << i;
    std::cout << ", variant " << opt.variant << (opt.anchors ? ", anchors only" : "") << (opt.cold ? ", cold" : "") << (opt.kernels ? ", kernels" : "") << "\n";
    if (!opt.kernels) std::cout << kColumns << "\n";
    std::cout.flush();

    std::vector<PresetData> presets;
    for (const std::string& name : opt.presets) {
        const auto preset = preset_by_name(name);
        if (!preset) usage("unknown preset " + name + " (small, large or gen11)");
        auto data = load_golden_data(dir, *preset);
        auto weights = load_golden_weights(dir, *preset);
        if (!data || !weights) {
            const sh::golden::Error& e = !data ? data.error() : weights.error();
            if (e.kind == sh::golden::ErrorKind::Missing && name == "gen11") {
                std::cout << "# skipped: preset gen11 (" << e.message << ")\n";
                continue;
            }
            std::cerr << "nn_bench: " << name << ": " << e.message << '\n';
            return 2;
        }
        presets.push_back(PresetData{name, name == "small" ? "small" : "large", name == "gen11" ? "gen11" : "u5", std::move(*weights), std::move(*data),
                                     extra_rel(*figures, "vector", *preset), extra_rel(*figures, "tornado", *preset)});
    }

    if (opt.kernels) {
#if SH_HAVE_METAL
        if (have_metal()) kernel_table_metal(presets, opt.batches);
#endif
        if (have_cuda()) kernel_table_cuda(presets, opt.batches);
        std::cout << "# load_1min after " << num(load1(), 2) << "\n";
        return 0;
    }

    for (const PresetData& pd : presets) {
        for (const std::string& impl : opt.impls) {
            const std::string label = label_of(impl, opt.variant);
            const auto t_construct = Clock::now();
            auto runner = make_runner(impl, pd.params, opt.variant);
            if (!runner) {
                std::cerr << "nn_bench: " << label << " " << pd.name << ": " << runner.error().message << '\n';
                return 2;
            }
            const double construct_ms = ms_since(t_construct);
            const double extra = impl.starts_with("metal") || impl.starts_with("cuda") ? pd.extra_tornado : pd.extra_vector;
            double rate_so_far = 0;
            for (int batch : opt.batches) {
                const bool cpu = !(*runner)->is_gpu();
                if (cpu && rate_so_far > 0 && batch / rate_so_far > kCpuCallLimitSeconds) {
                    std::cout << "# skipped: " << label << " " << pd.preset << " " << pd.weights << " batch " << batch << " (one call would take about " << num(batch / rate_so_far, 1)
                              << " s)\n";
                    continue;
                }
                const CaseResult r = run_case(**runner, label, impl, pd, batch, opt.cold, extra);
                rate_so_far = r.rate;
                std::cout << r.line << "\n";
                std::cout.flush();
                if (opt.cold) {
                    std::cout << "# cold: construct " << num(construct_ms, 1) << " ms, first call " << num(r.first_ms, 1) << " ms, process uptime (from main) "
                              << num(ms_since(process_start), 1) << " ms\n";
                    for (const std::string& n : (*runner)->notes()) std::cout << "# cold: " << n << "\n";
                    return 0;
                }
            }
            for (const std::string& n : (*runner)->notes()) std::cout << "# " << label << " " << pd.preset << " " << pd.weights << ": " << n << "\n";
        }
    }
#if SH_HAVE_METAL
    if (opt.profiled && !opt.anchors && have_metal() && std::find(opt.impls.begin(), opt.impls.end(), "metal-copy") != opt.impls.end()) {
        std::cout << "#instrumented-columns\tpreset\tweights\tbatch\twall_ms_median\tkernel_ms_median\tdevice_write_ms_median\tdevice_read_ms_median"
                     "\t(Metal instrumented mode, one command buffer per kernel, GPU clock; slows the call)\n";
        for (const PresetData& pd : presets) instrumented_pass(pd, opt.batches);
    }
#endif
    std::cout << "# load_1min after " << num(load1(), 2) << "\n";
    return 0;
}
