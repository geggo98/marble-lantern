// Checks a network implementation against the Java reference outputs of the golden files (WP9).
//   nn_check [--golden DIR] --impl reference|simd|simd-nofma|metal|cuda [--preset small|large|gen11|all] [--positions N] [--batch N]
// Compares with nn-ref-<preset> (the scalar Java `Network`) under the bound 1e-4 + 1e-3 |ref| (plus the extra term of
// MANIFEST.tsv, if any) and prints, per preset, the largest error as a fraction of the bound for logits and values.
// For --impl reference the C++ code must also reproduce the Java reference exactly: logits bit-identical, values within
// 1 ulp; the other implementations only have to meet the bound. --batch N sets the number of positions per forward call
// (default 64). Exit codes: 0 pass, 1 bound broken, 2 usage or unreadable golden data, 64 implementation not in this build.
#include <chrono>
#include <cstdlib>
#include <iomanip>
#include <iostream>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

#include "args.hpp"
#include "golden_io.hpp"
#include "reference_net.hpp"
#include "simd_net.hpp"

namespace {

using namespace sh::nn;

struct Options {
    std::string golden;
    std::string impl;
    std::string preset = "all";
    int positions = 0;  // 0 = all
    int batch = 64;     // positions per forward call
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
        std::cerr << "usage: nn_check [--golden DIR] --impl reference|simd|simd-nofma|metal|cuda [--preset small|large|gen11|all] [--positions N] [--batch N]\n";
        std::exit(2);
    }
    return o;
}

}  // namespace

int main(int argc, char** argv) {
    const auto args = sh::args_from_main(argc, argv);
    const Options opt = parse(args);
    if (opt.impl != "reference" && opt.impl != "simd" && opt.impl != "simd-nofma") {
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
        if (!exact) simd.emplace(*weights, opt.impl == "simd-nofma" ? SimdVariant::NoFma : SimdVariant::Fma);
        for (int first = 0; first < n; first += opt.batch) {
            const int m = std::min(opt.batch, n - first);
            const auto in = std::span<const float>(data->inputs).subspan(static_cast<std::size_t>(first) * static_cast<std::size_t>(cfg.input_size()),
                                                                        static_cast<std::size_t>(m) * static_cast<std::size_t>(cfg.input_size()));
            const auto lo = std::span<float>(logits).subspan(static_cast<std::size_t>(first) * kPolicySize, static_cast<std::size_t>(m) * kPolicySize);
            const auto va = std::span<float>(values).subspan(static_cast<std::size_t>(first) * static_cast<std::size_t>(cfg.players),
                                                             static_cast<std::size_t>(m) * static_cast<std::size_t>(cfg.players));
            if (auto r = exact ? net.forward(in, m, lo, va) : simd->forward(in, m, lo, va); !r) {
                std::cerr << "nn_check: " << name << ": forward failed: " << r.error().message << '\n';
                return 1;
            }
        }
        const double seconds = std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count();
        const double extra = extra_rel(*figures, "vector", *preset);
        const Comparison c = compare_outputs(logits, values, data->logits, data->values, 0, n, cfg.players, extra);
        const bool bound_ok = c.fraction() <= 1.0;
        const bool exact_ok = !exact || (c.logits_bit_identical && c.values_max_ulps <= 1.0);
        std::cout << std::fixed << std::setprecision(4) << "preset=" << name << " impl=" << opt.impl << (exact ? "" : " lanes=" + std::to_string(SimdNet::lanes())) << " batch=" << opt.batch << " positions=" << n
                  << " logits_max_err_frac=" << c.logits_fraction << " values_max_err_frac=" << c.values_fraction
                  << " logits_bit_identical=" << (c.logits_bit_identical ? "yes" : "NO") << std::setprecision(0)
                  << " values_max_ulps=" << c.values_max_ulps << std::setprecision(4) << " extra_rel=" << extra
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
