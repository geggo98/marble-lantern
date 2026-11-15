// CUDA correctness test (WP12): runs on the AWS instance against the real device, before anything else of the suite. It needs
// NO golden file: weights and inputs come from the SplittableRandom port with fixed seeds (tests/nn_random.hpp), the expected
// outputs from the C++ ReferenceNet (itself bit-identical to the Java reference, WP9), so that it can run before the Gradle
// build and the golden export.
//
//   cuda_test [--allow-no-device] [--batches 1,3,67,256]
//
// Without a device: in the stub build (no device code) the test reports "no CUDA device in this build" and exits 0, so that
// ctest on the Mac stays green; in a build WITH device code a missing device is a failure (exit 1) unless --allow-no-device is
// given. --batches restricts the batch sizes (the compute-sanitizer run uses 1,67).
#include <algorithm>
#include <bit>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <expected>
#include <iostream>
#include <limits>
#include <optional>
#include <span>
#include <sstream>
#include <string>
#include <string_view>
#include <vector>

#include "../apps/args.hpp"
#include "check.hpp"
#include "cuda_backend.hpp"
#include "cuda_net.hpp"
#include "golden_io.hpp"
#include "nn_random.hpp"
#include "reference_net.hpp"
#ifdef SH_CUDA_TEST_EMULATION
#include "cuda_emu.hpp"  // last: it defines the CUDA keywords as macros
#endif

namespace {

using namespace sh;
using namespace sh::testdata;

#ifdef SH_CUDA_TEST_EMULATION
// Test of the test: the same program against the emulated device of cuda_emu.hpp (the real kernels on the CPU), compiled by
// scripts/build-gcc12-stub.sh. It shows that the expectations below are right before the paid run; what depends on a real
// device (timings, events, the device name) is skipped. Small batches only: the emulation runs one thread per CUDA thread.
constexpr bool kEmulated = true;
std::vector<int> g_batches = {1, 3};
std::expected<CudaBackend, nn::Error> make_backend(const nn::Parameters& params, const CudaOptions& options) {
    return CudaBackend::create(params, options, [](const cuda::Shape& s, const cuda::Weights& w, const cuda::Options& o, std::string& error) {
        return sh::emu::EmuNet::create(s, w, o, error);
    });
}
#else
constexpr bool kEmulated = false;
std::vector<int> g_batches = {1, 3, 67, 256};
std::expected<CudaBackend, nn::Error> make_backend(const nn::Parameters& params, const CudaOptions& options) { return CudaBackend::create(params, options); }
#endif

struct Preset {
    const char* name;
    nn::NetworkConfig config;
};

std::vector<Preset> presets() {
    return {
        {"tiny-classic1", nn::tiny_config(2, nn::PlaneSet::Classic1)},
        {"tiny-plain", nn::tiny_config(3, nn::PlaneSet::Plain)},  // no host priors: the device applies the tanh
        {"small-classic1", nn::small_config(2, nn::PlaneSet::Classic1)},
        {"large-classic1", nn::large_config(2, nn::PlaneSet::Classic1)},
        {"odd-33", *nn::make_config(33, 2, 2, 32, nn::PlaneSet::Classic1)},  // channels that are no multiple of a tile
    };
}

const char* variant_name(std::optional<nn::ConvVariant> v) {
    if (!v) return "auto";
    return *v == nn::ConvVariant::Tiled ? "tiled" : "naive";
}
const char* memory_name(cuda::HostMemory m) { return m == cuda::HostMemory::Pinned ? "pinned" : "pageable"; }

int max_batch_of_run() { return *std::max_element(g_batches.begin(), g_batches.end()); }

// The reference outputs for the first `n` positions of make_inputs(config, n, seed + 1).
struct Reference {
    std::vector<float> input, logits, values;
};
Reference reference_for(const nn::Parameters& params, int n, std::uint64_t seed) {
    const nn::NetworkConfig& cfg = params.config();
    Reference r;
    r.input = make_inputs(cfg, n, seed + 1);
    r.logits.resize(static_cast<std::size_t>(n) * nn::kPolicySize);
    r.values.resize(static_cast<std::size_t>(n) * static_cast<std::size_t>(cfg.players));
    const nn::ReferenceNet net(params);
    auto ok = net.forward(r.input, n, r.logits, r.values);
    if (!ok) {
        std::cerr << "reference: " << ok.error().message << '\n';
        std::abort();
    }
    return r;
}

// Runs a backend on the first `batch` positions of the reference input and compares; returns the largest error as a fraction of
// the bound, or infinity if the backend failed.
double run_and_compare(CudaBackend& backend, const nn::Parameters& params, const Reference& ref, int batch) {
    const nn::NetworkConfig& cfg = params.config();
    const auto players = static_cast<std::size_t>(cfg.players);
    std::vector<float> logits(static_cast<std::size_t>(batch) * nn::kPolicySize, std::nanf(""));
    std::vector<float> values(static_cast<std::size_t>(batch) * players, std::nanf(""));
    auto r = backend.forward(std::span<const float>(ref.input).first(static_cast<std::size_t>(batch) * static_cast<std::size_t>(cfg.input_size())), batch, logits, values);
    if (!r) {
        std::cerr << "  forward failed: " << r.error().message << '\n';
        return std::numeric_limits<double>::infinity();
    }
    const nn::Comparison c = nn::compare_outputs(logits, values, ref.logits, ref.values, 0, batch, cfg.players, 0);
    return c.fraction();
}

SH_TEST(network_matches_the_reference) {
    const int n = max_batch_of_run();
    std::uint64_t seed = 1000;
    for (const Preset& preset : presets()) {
        const nn::Parameters params = make_params(preset.config, ++seed);
        const Reference ref = reference_for(params, n, seed);
        for (const std::optional<nn::ConvVariant> variant : {std::optional<nn::ConvVariant>(), std::optional<nn::ConvVariant>(nn::ConvVariant::Naive),
                                                              std::optional<nn::ConvVariant>(nn::ConvVariant::Tiled)}) {
            for (const cuda::HostMemory memory : {cuda::HostMemory::Pageable, cuda::HostMemory::Pinned}) {
                CudaOptions options;
                options.variant = variant;
                options.memory = memory;
                auto backend = make_backend(params, options);
                CHECK(backend.has_value());
                if (!backend) {
                    std::cerr << preset.name << ": " << backend.error().message << '\n';
                    continue;
                }
                for (int batch : g_batches) {
                    const double worst = run_and_compare(*backend, params, ref, batch);
                    std::cout << "  " << preset.name << " variant=" << variant_name(variant) << " memory=" << memory_name(memory) << " batch=" << batch
                              << " largest error " << worst << " of the bound\n";
                    CHECK(worst <= 1.0);
                }
            }
        }
    }
}

SH_TEST(a_position_in_every_slot_and_bucket) {
    // The property that lockstep self-play relies on: one position gives the same bits alone and inside other batches. The Metal
    // port has it on the Apple GPU; for CUDA nothing guarantees it (the compiler may contract differently per kernel), so the
    // test REPORTS the number of differing positions and only requires the results to stay within the bound. Forced variants:
    // the rule would switch the kernel between buckets, and the two kernels sum in a different order.
    const nn::NetworkConfig cfg = nn::small_config(2, nn::PlaneSet::Classic1);
    const nn::Parameters params = make_params(cfg, 77);
    const Reference ref = reference_for(params, 5, 77);
    const auto size = static_cast<std::size_t>(cfg.input_size());
    for (const nn::ConvVariant variant : {nn::ConvVariant::Naive, nn::ConvVariant::Tiled}) {
        CudaOptions options;
        options.variant = variant;
        auto backend = make_backend(params, options);
        CHECK(backend.has_value());
        if (!backend) continue;
        std::vector<float> logits(5 * nn::kPolicySize), values(5 * 2);
        CHECK(backend->forward(ref.input, 5, logits, values).has_value());  // bucket 8
        int differing = 0;
        for (int pos = 0; pos < 5; ++pos) {
            std::vector<float> one_logits(nn::kPolicySize), one_values(2);  // bucket 1
            CHECK(backend->forward(std::span<const float>(ref.input).subspan(static_cast<std::size_t>(pos) * size, size), 1, one_logits, one_values).has_value());
            const nn::Comparison c = nn::compare_outputs(one_logits, one_values, std::span<const float>(ref.logits).subspan(static_cast<std::size_t>(pos) * nn::kPolicySize, nn::kPolicySize),
                                                         std::span<const float>(ref.values).subspan(static_cast<std::size_t>(pos) * 2, 2), 0, 1, 2, 0);
            CHECK(c.fraction() <= 1.0);
            for (std::size_t i = 0; i < one_logits.size(); ++i) {
                if (std::bit_cast<std::uint32_t>(one_logits[i]) != std::bit_cast<std::uint32_t>(logits[static_cast<std::size_t>(pos) * nn::kPolicySize + i])) {
                    ++differing;
                    break;
                }
            }
        }
        std::cout << "  variant=" << (variant == nn::ConvVariant::Tiled ? "tiled" : "naive") << ": " << differing
                  << " of 5 positions differ in some bit between bucket 1 and bucket 8 (information, not a failure)\n";
    }
}

SH_TEST(nan_reaches_the_outputs_and_stays_in_its_position) {
    // a NaN in one position must give NaN logits there (ReLU keeps NaN, DeviceMath.relu) and leave the others alone
    const nn::NetworkConfig cfg = nn::tiny_config(2, nn::PlaneSet::Classic1);
    const nn::Parameters params = make_params(cfg, 91);
    std::vector<float> input = make_inputs(cfg, 3, 92);
    const auto size = static_cast<std::size_t>(cfg.input_size());
    input[size + 5 * static_cast<std::size_t>(cfg.input_planes())] = std::nanf("");  // position 1, cell 5, plane 0
    for (const nn::ConvVariant variant : {nn::ConvVariant::Naive, nn::ConvVariant::Tiled}) {
        CudaOptions options;
        options.variant = variant;
        auto backend = make_backend(params, options);
        CHECK(backend.has_value());
        if (!backend) continue;
        std::vector<float> logits(3 * nn::kPolicySize), values(3 * 2);
        CHECK(backend->forward(input, 3, logits, values).has_value());
        const auto nans = [&](int pos) {
            int count = 0;
            for (int i = 0; i < nn::kPolicySize; ++i) count += std::isnan(logits[static_cast<std::size_t>(pos * nn::kPolicySize + i)]) ? 1 : 0;
            return count;
        };
        CHECK(nans(1) > 0);
        CHECK_EQ(nans(0), 0);
        CHECK_EQ(nans(2), 0);
    }
}

SH_TEST(the_value_head_saturates_exactly) {
    // plane set without host priors, dense2 biases of 44.3, 100 and -1000, +inf: the values are exactly +-1 (DeviceMath.tanh)
    const nn::NetworkConfig cfg = nn::tiny_config(2, nn::PlaneSet::Plain);
    const std::vector<float> zero_input(static_cast<std::size_t>(cfg.input_size()), 0.0f);
    const std::vector<std::pair<std::vector<float>, std::vector<float>>> cases = {
        {{44.3f, 100.0f}, {1.0f, 1.0f}}, {{-1000.0f, std::numeric_limits<float>::infinity()}, {-1.0f, 1.0f}}};
    for (const auto& [biases, want] : cases) {
        std::vector<nn::Tensor> tensors = random_tensors(cfg, 93);
        for (nn::Tensor& t : tensors) {
            if (t.name == "head_value.dense2.bias") t.data = biases;
            if (t.name == "head_value.dense2.weight") std::fill(t.data.begin(), t.data.end(), 0.0f);  // the bias alone decides
        }
        auto params = nn::Parameters::from_tensors(cfg, std::move(tensors));
        CHECK(params.has_value());
        if (!params) continue;
        auto backend = make_backend(*params, CudaOptions{});
        CHECK(backend.has_value());
        if (!backend) continue;
        std::vector<float> logits(nn::kPolicySize), values(2);
        CHECK(backend->forward(zero_input, 1, logits, values).has_value());
        CHECK_EQ(values[0], want[0]);
        CHECK_EQ(values[1], want[1]);
    }
}

SH_TEST(timings_and_kernel_times_are_recorded) {
    if (kEmulated) {
        sh::test::skip("timings need a device");
        return;
    }
    const nn::NetworkConfig cfg = nn::small_config(2, nn::PlaneSet::Classic1);
    const nn::Parameters params = make_params(cfg, 94);
    const std::vector<float> input = make_inputs(cfg, 64, 95);
    for (const cuda::HostMemory memory : {cuda::HostMemory::Pageable, cuda::HostMemory::Pinned}) {
        CudaOptions options;
        options.memory = memory;
        options.instrumented = true;
        auto backend = make_backend(params, options);
        CHECK(backend.has_value());
        if (!backend) continue;
        std::vector<float> logits(64 * nn::kPolicySize), values(64 * 2);
        CHECK(backend->forward(input, 64, logits, values).has_value());  // warm-up: module load, buffers
        backend->reset_timings();
        for (int i = 0; i < 3; ++i) CHECK(backend->forward(input, 64, logits, values).has_value());
        const CudaPhaseTimes& t = backend->timings();
        CHECK_EQ(t.calls, 3);
        CHECK(t.kernel_ms > 0 && t.h2d_ms > 0 && t.d2h_ms > 0);
        CHECK(t.copy_in_ms >= 0 && t.copy_out_ms >= 0 && t.priors_ms >= 0);
        CHECK_EQ(backend->plans_built(), 1);
        CHECK(backend->init_ms() > 0);
        // the last call lists every kernel once: conv_in, 2 per block, 2 heads; each takes time, and together they are the kernel stage
        const auto& kernels = backend->kernel_times();
        CHECK_EQ(kernels.size(), static_cast<std::size_t>(1 + 2 * cfg.blocks + 2));
        double sum = 0;
        for (const cuda::KernelTime& k : kernels) {
            CHECK(k.ms >= 0);
            sum += k.ms;
        }
        // kernel_ms is the total of three calls, the list is the last one: the sum of the list is not above the total
        CHECK(sum > 0);
        CHECK(sum <= t.kernel_ms * 1.01);
    }
}

SH_TEST(device_is_reported) {
    if (kEmulated) {
        sh::test::skip("no device in the emulation");
        return;
    }
    CHECK(cuda::device_count() >= 1);
    CHECK(!cuda::device_name().empty());
    std::cout << "  device: " << cuda::device_name() << '\n';
}

bool parse_batches(std::string_view text) {
    std::vector<int> out;
    std::istringstream in{std::string(text)};
    for (std::string part; std::getline(in, part, ',');) {
        const int v = std::atoi(part.c_str());
        if (v < 1) return false;
        out.push_back(v);
    }
    if (out.empty()) return false;
    g_batches = out;
    return true;
}

}  // namespace

int main(int argc, char** argv) {
    const auto args = sh::args_from_main(argc, argv);
    bool allow_no_device = false;
    for (std::size_t i = 1; i < args.size(); ++i) {
        if (args[i] == "--allow-no-device") {
            allow_no_device = true;
        } else if (args[i] == "--batches" && i + 1 < args.size() && parse_batches(args[i + 1])) {
            ++i;
        } else {
            std::cerr << "usage: cuda_test [--allow-no-device] [--batches 1,3,67,256]\n";
            return 2;
        }
    }
    if (!kEmulated && cuda::device_count() < 1) {
        const bool stub = cuda::build_kind() == "stub";
        std::cout << "no CUDA device" << (stub ? " in this build (stub): nothing to test here" : " found") << '\n';
        if (stub || allow_no_device) return 0;
        std::cerr << "cuda_test: this build has device code but there is no device\n";
        return 1;
    }
    return sh::test::run_all();
}
