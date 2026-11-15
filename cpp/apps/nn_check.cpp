// Checks a network implementation against the Java reference outputs of the golden files (WP9).
//   nn_check [--golden DIR] --impl reference|simd|simd-nofma|metal|cuda [--preset small|large|gen11|all] [--positions N] [--batch N] [--variant auto|naive|tiled|simdgroup] [--memory copy|shared]
// --impl metal runs the Metal backend (macOS); --variant and --memory choose its convolution kernel (auto = the bucket rule, the
// default) and memory mode (copy, the default, or shared). --impl cuda runs the CUDA backend (only in a build with device code,
// scripts/build-cuda.sh; the stub build answers exit 64): --variant auto|naive|tiled, --memory pageable (default) or pinned.
// Compares with nn-ref-<preset> (the scalar Java `Network`) under the bound 1e-4 + 1e-3 |ref| (plus the extra term of
// MANIFEST.tsv, if any) and prints, per preset, the largest error as a fraction of the bound for logits and values.
// For --impl reference the C++ code must also reproduce the Java reference exactly: logits bit-identical, values within
// 1 ulp; the other implementations only have to meet the bound. --batch N sets the number of positions per forward call
// (default 64). The line ends with digest=<FNV-1a 64 of the output bits>. Exit codes: 0 pass, 1 bound broken, 2 usage or unreadable golden data, 64 implementation not in this build.
#include <bit>
#include <chrono>
#include <cstdint>
#include <cstdlib>
#include <iomanip>
#include <iostream>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

#include "args.hpp"
#include "cuda_backend.hpp"
#include "golden_io.hpp"
#include "reference_net.hpp"
#include "simd_net.hpp"

// The Metal backend exists where clang builds Objective-C++ for Apple (CMake); GCC on the Mac (the Linux-compatible stub build) has none.
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

// FNV-1a 64 over the bit patterns of logits and values: equal digests mean bit-identical outputs. It lets two builds of the same
// implementation (before and after a change of the code, or lanes 8 against lanes 16) be compared without storing the outputs.
std::uint64_t digest_of(const std::vector<float>& logits, const std::vector<float>& values) {
    std::uint64_t h = 14695981039346656037ULL;
    for (const std::vector<float>* v : {&logits, &values}) {
        for (const float f : *v) {
            const auto bits = std::bit_cast<std::uint32_t>(f);
            for (int shift = 0; shift < 32; shift += 8) h = (h ^ ((bits >> shift) & 0xFFU)) * 1099511628211ULL;
        }
    }
    return h;
}

struct Options {
    std::string golden;
    std::string impl;
    std::string preset = "all";
    int positions = 0;  // 0 = all
    int batch = 64;     // positions per forward call
    std::string variant = "auto";
    std::string memory;  // empty = the default of the backend: copy (Metal), pageable (CUDA)
};

std::string_view value_of(const std::vector<std::string_view>& args, std::size_t& i) {
    if (i + 1 >= args.size()) {
        std::cerr << "nn_check: " << args[i] << " needs a value\n";
        std::exit(2);
    }
    return args[++i];
}

Options parse(const std::vector<std::string_view>& args) {
    Options o;
    for (std::size_t i = 1; i < args.size(); ++i) {
        const std::string_view a = args[i];
        if (a == "--golden") {
            o.golden = std::string(value_of(args, i));
        } else if (a == "--impl") {
            o.impl = std::string(value_of(args, i));
        } else if (a == "--preset") {
            o.preset = std::string(value_of(args, i));
        } else if (a == "--positions") {
            o.positions = std::atoi(std::string(value_of(args, i)).c_str());
        } else if (a == "--variant") {
            o.variant = std::string(value_of(args, i));
        } else if (a == "--memory") {
            o.memory = std::string(value_of(args, i));
        } else if (a == "--batch") {
            o.batch = std::atoi(std::string(value_of(args, i)).c_str());
            if (o.batch < 1) {
                std::cerr << "nn_check: --batch needs a positive number\n";
                std::exit(2);
            }
        } else {
            std::cerr << "nn_check: unknown option " << a << '\n';
            std::exit(2);
        }
    }
    if (o.impl.empty()) {
        std::cerr << "usage: nn_check [--golden DIR] --impl reference|simd|simd-nofma|metal|cuda [--preset small|large|gen11|all] [--positions N] [--batch N] [--variant auto|naive|tiled|simdgroup] [--memory copy|shared|pageable|pinned]\n";
        std::exit(2);
    }
    return o;
}

}  // namespace

int main(int argc, char** argv) {
    const auto args = sh::args_from_main(argc, argv);
    const Options opt = parse(args);
    bool have_metal = false;
#if SH_HAVE_METAL
    have_metal = true;
#endif
    const bool have_cuda = sh::cuda::build_kind() == "cuda";
    if (opt.impl != "reference" && opt.impl != "simd" && opt.impl != "simd-nofma" && !(opt.impl == "metal" && have_metal) &&
        !(opt.impl == "cuda" && have_cuda)) {
        std::cerr << "nn_check: implementation '" << opt.impl << "' is not available in this build\n";
        return 64;
    }
    const std::filesystem::path dir = opt.golden.empty() ? sh::golden::directory() : std::filesystem::path(opt.golden);
    std::vector<std::string> names;
    if (opt.preset == "all") {
        names = {"small", "large", "gen11"};
    } else {
        names = {opt.preset};
    }
    auto figures = load_figures(dir);
    if (!figures) {
        std::cerr << "nn_check: " << figures.error().message << '\n';
        return 2;
    }
    int status = 0;
    int checked = 0;
    for (const std::string& name : names) {
        const auto preset = preset_by_name(name);
        if (!preset) {
            std::cerr << "nn_check: unknown preset " << name << " (small, large, gen11 or all)\n";
            return 2;
        }
        auto data = load_golden_data(dir, *preset);
        if (!data) {
            if (opt.preset == "all" && data.error().kind == sh::golden::ErrorKind::Missing) {
                std::cout << "preset=" << name << " skipped: " << data.error().message << '\n';
                continue;
            }
            std::cerr << "nn_check: " << name << ": " << data.error().message << '\n';
            return 2;
        }
        auto weights = load_golden_weights(dir, *preset);
        if (!weights) {
            if (opt.preset == "all" && weights.error().kind == sh::golden::ErrorKind::Missing) {
                std::cout << "preset=" << name << " skipped: " << weights.error().message << '\n';
                continue;
            }
            std::cerr << "nn_check: " << name << ": " << weights.error().message << '\n';
            return 2;
        }
        const NetworkConfig& cfg = weights->config();
        const int n = opt.positions > 0 ? std::min(opt.positions, data->positions) : data->positions;
        std::vector<float> logits(static_cast<std::size_t>(n) * kPolicySize);
        std::vector<float> values(static_cast<std::size_t>(n) * static_cast<std::size_t>(cfg.players));
        const auto t0 = std::chrono::steady_clock::now();
        const ReferenceNet net(*weights);
        const bool exact = opt.impl == "reference";
        std::optional<SimdNet> simd;
        if (opt.impl == "simd" || opt.impl == "simd-nofma") simd.emplace(*weights, opt.impl == "simd-nofma" ? SimdVariant::NoFma : SimdVariant::Fma);
#if SH_HAVE_METAL
        std::optional<sh::metal::MetalNet> gpu;
        if (opt.impl == "metal") {
            sh::metal::Options options;
            if (opt.variant == "naive") {
                options.variant = ConvVariant::Naive;
            } else if (opt.variant == "tiled") {
                options.variant = ConvVariant::Tiled;
            } else if (opt.variant == "simdgroup") {
                options.variant = ConvVariant::Simdgroup;
            } else if (opt.variant != "auto") {
                std::cerr << "nn_check: unknown variant '" << opt.variant << "' (auto, naive, tiled or simdgroup)\n";
                return 2;
            }
            if (opt.memory.empty() || opt.memory == "copy") {
                options.memory = sh::metal::MemoryMode::Copy;
            } else if (opt.memory == "shared") {
                options.memory = sh::metal::MemoryMode::Shared;
            } else {
                std::cerr << "nn_check: unknown memory mode '" << opt.memory << "' (copy or shared)\n";
                return 2;
            }
            auto created = sh::metal::MetalNet::create(*weights, options);
            if (!created) {
                std::cerr << "nn_check: " << name << ": " << created.error().message << '\n';
                return 2;
            }
            gpu.emplace(std::move(*created));
        }
#endif
        std::optional<sh::CudaBackend> cuda_net;
        if (opt.impl == "cuda") {
            sh::CudaOptions options;
            if (opt.variant == "naive") {
                options.variant = ConvVariant::Naive;
            } else if (opt.variant == "tiled") {
                options.variant = ConvVariant::Tiled;
            } else if (opt.variant != "auto") {
                std::cerr << "nn_check: unknown variant '" << opt.variant << "' for cuda (auto, naive or tiled)\n";
                return 2;
            }
            if (opt.memory.empty() || opt.memory == "pageable") {
                options.memory = sh::cuda::HostMemory::Pageable;
            } else if (opt.memory == "pinned") {
                options.memory = sh::cuda::HostMemory::Pinned;
            } else {
                std::cerr << "nn_check: unknown memory mode '" << opt.memory << "' for cuda (pageable or pinned)\n";
                return 2;
            }
            auto created = sh::CudaBackend::create(*weights, options);
            if (!created) {
                std::cerr << "nn_check: " << name << ": " << created.error().message << '\n';
                return 2;
            }
            cuda_net.emplace(std::move(*created));
        }
        for (int first = 0; first < n; first += opt.batch) {
            const int m = std::min(opt.batch, n - first);
            const auto in = std::span<const float>(data->inputs).subspan(static_cast<std::size_t>(first) * static_cast<std::size_t>(cfg.input_size()),
                                                                        static_cast<std::size_t>(m) * static_cast<std::size_t>(cfg.input_size()));
            const auto lo = std::span<float>(logits).subspan(static_cast<std::size_t>(first) * kPolicySize, static_cast<std::size_t>(m) * kPolicySize);
            const auto va = std::span<float>(values).subspan(static_cast<std::size_t>(first) * static_cast<std::size_t>(cfg.players),
                                                             static_cast<std::size_t>(m) * static_cast<std::size_t>(cfg.players));
            const auto forward = [&]() {
#if SH_HAVE_METAL
                if (gpu) return gpu->forward(in, m, lo, va);
#endif
                if (cuda_net) return cuda_net->forward(in, m, lo, va);
                return exact ? net.forward(in, m, lo, va) : simd->forward(in, m, lo, va);
            };
            if (auto r = forward(); !r) {
                std::cerr << "nn_check: " << name << ": forward failed: " << r.error().message << '\n';
                return 1;
            }
        }
        const double seconds = std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count();
        const double extra = extra_rel(*figures, opt.impl == "metal" || opt.impl == "cuda" ? "tornado" : "vector", *preset);
        const Comparison c = compare_outputs(logits, values, data->logits, data->values, 0, n, cfg.players, extra);
        const bool bound_ok = c.fraction() <= 1.0;
        const bool exact_ok = !exact || (c.logits_bit_identical && c.values_max_ulps <= 1.0);
        std::cout << std::fixed << std::setprecision(4) << "preset=" << name << " impl=" << opt.impl << (simd ? " lanes=" + std::to_string(SimdNet::lanes()) : "") << (opt.impl == "metal" ? " variant=" + opt.variant + " memory=" + (opt.memory.empty() ? "copy" : opt.memory) : "")
                  << (opt.impl == "cuda" ? " variant=" + opt.variant + " memory=" + (opt.memory.empty() ? "pageable" : opt.memory) : "") << " batch=" << opt.batch << " positions=" << n
                  << " logits_max_err_frac=" << c.logits_fraction << " values_max_err_frac=" << c.values_fraction
                  << " logits_bit_identical=" << (c.logits_bit_identical ? "yes" : "NO") << std::setprecision(0)
                  << " values_max_ulps=" << c.values_max_ulps << std::setprecision(4) << " extra_rel=" << extra
                  << " digest=" << std::hex << std::setfill('0') << std::setw(16) << digest_of(logits, values) << std::dec << std::setfill(' ')
                  << " seconds=" << seconds << " -> " << (bound_ok && exact_ok ? "ok" : "FAIL") << '\n';
        ++checked;
        if (!bound_ok || !exact_ok) status = 1;
    }
    if (checked == 0) {
        std::cerr << "nn_check: nothing was checked\n";
        return 2;
    }
    return status;
}
