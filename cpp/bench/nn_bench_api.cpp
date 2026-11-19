// Bench-only C API of the network forward pass, for the browser benchmark (scripts/bench-net-wasm.zsh). It is built by
// scripts/bench-net-wasm.zsh with Emscripten (target wasm: bench-driver.mjs calls these functions) and natively (the same
// file with a main(): the same schedule and the same JSON lines, the "native C++" row). It does not touch src/wasm/wasm_api.cpp.
//
// Implementations (impl argument of nb_forward): 0 = ReferenceNet (scalar, double accumulation), 1 = SimdNet fused variant
// (simd_net.cpp, -ffp-contract=fast: with -mrelaxed-simd it becomes f32x4.relaxed_madd, native NEON fmla), 2 = SimdNet without FMA.
// Not a shipped file: no hardening, no unsafe-buffer discipline (bench code; the checked code is src/nn).
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <expected>
#include <memory>
#include <optional>
#include <span>
#include <string>
#include <vector>

#include "parameters.hpp"
#include "reference_net.hpp"
#include "safetensors.hpp"
#include "simd_net.hpp"

#if defined(__EMSCRIPTEN__)
#include <emscripten/emscripten.h>
#define NB_EXPORT extern "C" EMSCRIPTEN_KEEPALIVE
#else
#define NB_EXPORT extern "C"
#endif

namespace {

using namespace sh::nn;

std::optional<Parameters> g_parameters;
std::unique_ptr<ReferenceNet> g_reference;
std::unique_ptr<SimdNet> g_fma;
std::unique_ptr<SimdNet> g_nofma;
std::string g_error;

}  // namespace

NB_EXPORT const char* nb_error() { return g_error.c_str(); }

// 0 = ok, 1 = error (nb_error). bytes: the whole safetensors file.
NB_EXPORT int nb_load(const std::uint8_t* bytes, std::size_t length) {
    g_reference.reset();
    g_fma.reset();
    g_nofma.reset();
    g_parameters.reset();
    auto contents = parse_safetensors(std::span<const std::byte>(reinterpret_cast<const std::byte*>(bytes), length));
    if (!contents) {
        g_error = contents.error().message;
        return 1;
    }
    auto parameters = Parameters::from_contents(std::move(*contents));
    if (!parameters) {
        g_error = parameters.error().message;
        return 1;
    }
    g_parameters = std::move(*parameters);
    g_reference = std::make_unique<ReferenceNet>(*g_parameters);
    g_fma = std::make_unique<SimdNet>(*g_parameters, SimdVariant::Fma);
    g_nofma = std::make_unique<SimdNet>(*g_parameters, SimdVariant::NoFma);
    return 0;
}

NB_EXPORT int nb_planes() { return g_parameters ? g_parameters->config().input_planes() : 0; }
NB_EXPORT int nb_players() { return g_parameters ? g_parameters->config().players : 0; }
NB_EXPORT int nb_lanes() { return SimdNet::lanes(); }

// 0 = ok, 1 = error (nb_error). input: batch x [122][planes]; logits: batch x 242; values: batch x players.
NB_EXPORT int nb_forward(int impl, const float* input, int batch, float* logits, float* values) {
    if (!g_parameters) {
        g_error = "no net loaded";
        return 1;
    }
    const auto& config = g_parameters->config();
    const std::span<const float> in(input, static_cast<std::size_t>(batch) * static_cast<std::size_t>(config.input_size()));
    const std::span<float> lo(logits, static_cast<std::size_t>(batch) * kPolicySize);
    const std::span<float> va(values, static_cast<std::size_t>(batch) * static_cast<std::size_t>(config.players));
    std::expected<void, Error> r;
    switch (impl) {
        case 0: r = g_reference->forward(in, batch, lo, va); break;
        case 1: r = g_fma->forward(in, batch, lo, va); break;
        case 2: r = g_nofma->forward(in, batch, lo, va); break;
        default: g_error = "unknown impl"; return 1;
    }
    if (!r) {
        g_error = r.error().message;
        return 1;
    }
    return 0;
}

#if !defined(__EMSCRIPTEN__)
// ---- native driver: the same schedule and output lines as bench-driver.mjs and JvmNetBench ----
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <fstream>
#include <iterator>
#include <sstream>

namespace {

constexpr double kWarmupSeconds = 1.0;
constexpr int kWarmupCalls = 10;
constexpr double kRoundSeconds = 0.8;
constexpr int kRounds = 5;

std::vector<std::uint8_t> read_file(const char* path) {
    std::ifstream in(path, std::ios::binary);
    return {std::istreambuf_iterator<char>(in), std::istreambuf_iterator<char>()};
}

double now() { return std::chrono::duration<double>(std::chrono::steady_clock::now().time_since_epoch()).count(); }

double fraction(double fast, double ref) { return std::abs(fast - ref) / (1e-4 + 1e-3 * std::abs(ref)); }

}  // namespace

int main(int argc, char** argv) {
    if (argc < 3) {
        std::fprintf(stderr, "usage: nn_bench_native <net.safetensors> <reference.safetensors> [batches e.g. 1,8,32,128]\n");
        return 64;
    }
    const auto net = read_file(argv[1]);
    if (nb_load(net.data(), net.size()) != 0) {
        std::fprintf(stderr, "load: %s\n", nb_error());
        return 1;
    }
    const auto ref_bytes = read_file(argv[2]);
    auto ref = parse_safetensors(std::span<const std::byte>(reinterpret_cast<const std::byte*>(ref_bytes.data()), ref_bytes.size()));
    if (!ref) {
        std::fprintf(stderr, "reference: %s\n", ref.error().message.c_str());
        return 1;
    }
    const std::vector<float>* inputs = nullptr;
    const std::vector<float>* ref_logits = nullptr;
    const std::vector<float>* ref_values = nullptr;
    for (const auto& t : ref->tensors) {
        if (t.name == "inputs") inputs = &t.data;
        if (t.name == "logits") ref_logits = &t.data;
        if (t.name == "values") ref_values = &t.data;
    }
    const int planes = nb_planes();
    const int players = nb_players();
    const std::size_t size = static_cast<std::size_t>(kRows) * static_cast<std::size_t>(planes);
    const std::size_t positions = inputs->size() / size;
    std::vector<int> batches;
    {
        std::stringstream s(argc > 3 ? argv[3] : "1,8,32,128");
        for (std::string item; std::getline(s, item, ',');) batches.push_back(std::stoi(item));
    }
    batches.push_back(32);  // the self-ratio case: batch 32 again at the end
    const char* names[] = {"native-reference", "native-simd-fma", "native-simd-nofma"};
    for (int impl = 0; impl < 3; ++impl) {
        for (std::size_t bi = 0; bi < batches.size(); ++bi) {
            const int batch = batches[bi];
            const bool again = bi + 1 == batches.size();
            std::vector<float> in(static_cast<std::size_t>(batch) * size);
            for (int b = 0; b < batch; ++b) {
                std::copy_n(inputs->begin() + static_cast<std::ptrdiff_t>((static_cast<std::size_t>(b) % positions) * size), size,
                            in.begin() + static_cast<std::ptrdiff_t>(static_cast<std::size_t>(b) * size));
            }
            std::vector<float> lo(static_cast<std::size_t>(batch) * kPolicySize);
            std::vector<float> va(static_cast<std::size_t>(batch) * static_cast<std::size_t>(players));
            if (nb_forward(impl, in.data(), batch, lo.data(), va.data()) != 0) {
                std::fprintf(stderr, "forward: %s\n", nb_error());
                return 1;
            }
            double err = 0;
            for (int b = 0; b < batch; ++b) {
                const std::size_t p = static_cast<std::size_t>(b) % positions;
                for (int i = 0; i < kPolicySize; ++i)
                    err = std::max(err, fraction(lo[static_cast<std::size_t>(b) * kPolicySize + static_cast<std::size_t>(i)],
                                                 (*ref_logits)[p * kPolicySize + static_cast<std::size_t>(i)]));
                for (int i = 0; i < players; ++i)
                    err = std::max(err, fraction(va[static_cast<std::size_t>(b * players + i)], (*ref_values)[p * static_cast<std::size_t>(players) + static_cast<std::size_t>(i)]));
            }
            const double w0 = now();
            for (int calls = 0; calls < kWarmupCalls || now() - w0 < kWarmupSeconds; ++calls)
                nb_forward(impl, in.data(), batch, lo.data(), va.data());
            std::vector<double> ms;
            for (int r = 0; r < kRounds; ++r) {
                const double t0 = now();
                int calls = 0;
                do {
                    nb_forward(impl, in.data(), batch, lo.data(), va.data());
                    ++calls;
                } while (now() - t0 < kRoundSeconds);
                ms.push_back((now() - t0) * 1000.0 / calls);
            }
            std::vector<double> sorted = ms;
            std::sort(sorted.begin(), sorted.end());
            std::ostringstream rounds;
            for (std::size_t i = 0; i < ms.size(); ++i) {
                char buf[32];
                std::snprintf(buf, sizeof buf, "%.4f", batch * 1000.0 / ms[i]);
                rounds << (i ? "," : "") << buf;
            }
            std::printf(
                "{\"impl\":\"%s%s\",\"runtime\":\"native\",\"batch\":%d,\"pos_per_s\":%.1f,\"ms_per_batch\":%.4f,\"min_pos_per_s\":%.1f,"
                "\"max_pos_per_s\":%.1f,\"rounds_pos_per_s\":[%s],\"err_fraction\":%.4f}\n",
                names[impl], again ? "-again" : "", batch, batch * 1000.0 / sorted[2], sorted[2], batch * 1000.0 / sorted.back(),
                batch * 1000.0 / sorted.front(), rounds.str().c_str(), err);
            std::fflush(stdout);
        }
    }
    return 0;
}
#endif
