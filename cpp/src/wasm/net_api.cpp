// The C API of the browser network backend (WebAssembly, built by cpp/CMakeLists.txt (target sh_net_flavour), run through scripts/web/build-wasm.zsh): the SIMD forward pass of
// src/nn with the classical priors, behind a flat C ABI (no embind, no exceptions, no threads). One global net; all calls
// are synchronous. The TypeScript side is web/src/engines/net/wasm-backend.ts (contract IC1).
//
// Flavours (one .wasm each, same source): the instruction set is chosen at build time.
//   scalar   no SIMD instructions              (NoFma variant of simd_net.cpp, generic 4-lane code)
//   simd     -msimd128                         (NoFma variant)
//   relaxed  -msimd128 -mrelaxed-simd          (Fma variant: f32x4.relaxed_madd)
// Buffers live inside the module: the caller asks for them with net_input_ptr(maxBatch), writes the encoded positions
// ([batch][122][planes] floats, the layout of the Java Encoder) there, calls net_evaluate(batch) and reads the outputs
// from net_policy_from_ptr() ([batch][121]), net_policy_to_ptr() ([batch][121]) and net_values_ptr() ([batch][value width],
// after the tanh). The pointers are valid until the next net_input_ptr, net_load or net_free; the heap may grow in between,
// so a JavaScript view of HEAPU8 must be taken again after every call.
//
// Error codes (0 = ok). net_error() has the text of the last error.
//   1 file       the bytes are not a readable safetensors file (truncated, bad header, bad layout)
//   2 format     the metadata does not name the format "sternhalma-nn" (wrong magic) or has no format key
//   3 config     metadata or tensors do not describe a supported net (plane set, sizes, shapes)
//   4 memory     the buffers could not be allocated
//   5 no-net     net_evaluate or net_input_ptr before a successful net_load
//   6 batch      batch < 1 or batch > the maxBatch of net_input_ptr
//   7 forward    the forward pass refused the input (a dirty padding row)
//   8 argument   a null pointer or an invalid maxBatch
#include <cstddef>
#include <cstdint>
#include <memory>
#include <span>
#include <string>
#include <utility>
#include <vector>

#include "config.hpp"
#include "parameters.hpp"
#include "safetensors.hpp"
#include "simd_net.hpp"

#ifdef __EMSCRIPTEN__
#include <emscripten.h>
#define NET_API extern "C" EMSCRIPTEN_KEEPALIVE
#else
#define NET_API extern "C"
#endif

#if defined(__wasm_relaxed_simd__)
#define NET_FLAVOUR 2
#elif defined(__wasm_simd128__)
#define NET_FLAVOUR 1
#else
#define NET_FLAVOUR 0
#endif

// The raw pointers of the C ABI are the one unsafe zone of this file.
#pragma clang unsafe_buffer_usage begin
namespace {

using namespace sh::nn;

constexpr int kErrFile = 1;
constexpr int kErrFormat = 2;
constexpr int kErrConfig = 3;
constexpr int kErrNoNet = 5;
constexpr int kErrBatch = 6;
constexpr int kErrForward = 7;
constexpr int kErrArgument = 8;

constexpr int kMaxBatchLimit = 4096;

std::unique_ptr<SimdNet> g_net;
NetworkConfig g_config;
int g_max_batch = 0;
std::vector<float> g_input;
std::vector<float> g_logits;  // [batch][242], cell * 2 + channel
std::vector<float> g_from;
std::vector<float> g_to;
std::vector<float> g_values;
std::string g_error;

int fail(int code, std::string message) {
    g_error = std::move(message);
    return code;
}

void release() {
    g_net.reset();
    g_max_batch = 0;
    for (auto* v : {&g_input, &g_logits, &g_from, &g_to, &g_values}) std::vector<float>().swap(*v);
}

}  // namespace

NET_API const char* net_error() { return g_error.c_str(); }

// 0 = the flavour was built without SIMD, 1 = simd128, 2 = simd128 with relaxed-simd (fused multiply-add).
NET_API int net_flavour() { return NET_FLAVOUR; }

// bytes: an F32 safetensors file of a net with the plane set plain, classic1, classic1-planes or classic1v.
NET_API int net_load(const std::uint8_t* bytes, std::size_t length) {
    release();
    if (bytes == nullptr) return fail(kErrArgument, "net_load: null pointer");
    auto contents = parse_safetensors(std::span<const std::byte>(reinterpret_cast<const std::byte*>(bytes), length));
    if (!contents) return fail(kErrFile, contents.error().message);
    const auto format = contents->metadata.find("format");
    if (format == contents->metadata.end() || format->second != kFormat) {
        return fail(kErrFormat, "unknown format: " + (format == contents->metadata.end() ? std::string("(none)") : format->second));
    }
    auto parameters = Parameters::from_contents(std::move(*contents));
    if (!parameters) return fail(kErrConfig, parameters.error().message);
    g_config = parameters->config();
    g_net = std::make_unique<SimdNet>(*parameters, NET_FLAVOUR == 2 ? SimdVariant::Fma : SimdVariant::NoFma);
    g_error.clear();
    return 0;
}

NET_API int net_planes() { return g_net ? g_config.input_planes() : 0; }
// Width of the value head: 2, or 3 for classic1v (that net serves two and three players: use the first n values).
NET_API int net_value_width() { return g_net ? g_config.players : 0; }

// Allocates the buffers for batches up to maxBatch (1 to 4096) and returns the address of the input buffer
// (maxBatch * 122 * planes floats, zeroed), or 0 after an error (net_error). A second call with another maxBatch replaces them.
NET_API float* net_input_ptr(int maxBatch) {
    if (!g_net) {
        fail(kErrNoNet, "no net loaded");
        return nullptr;
    }
    if (maxBatch < 1 || maxBatch > kMaxBatchLimit) {
        fail(kErrArgument, "maxBatch must be in [1, " + std::to_string(kMaxBatchLimit) + "], got " + std::to_string(maxBatch));
        return nullptr;
    }
    const auto n = static_cast<std::size_t>(maxBatch);
    g_input.assign(n * static_cast<std::size_t>(g_config.input_size()), 0.0F);
    g_logits.assign(n * kPolicySize, 0.0F);
    g_from.assign(n * kCells, 0.0F);
    g_to.assign(n * kCells, 0.0F);
    g_values.assign(n * static_cast<std::size_t>(g_config.players), 0.0F);
    g_max_batch = maxBatch;
    return g_input.data();
}

NET_API float* net_policy_from_ptr() { return g_from.data(); }
NET_API float* net_policy_to_ptr() { return g_to.data(); }
NET_API float* net_values_ptr() { return g_values.data(); }
NET_API int net_max_batch() { return g_max_batch; }

// Evaluates the first `batch` positions of the input buffer. 0 = ok, else an error code (net_error).
NET_API int net_evaluate(int batch) {
    if (!g_net || g_max_batch == 0) return fail(kErrNoNet, "no net loaded or net_input_ptr not called");
    if (batch < 1 || batch > g_max_batch) {
        return fail(kErrBatch, "batch must be in [1, " + std::to_string(g_max_batch) + "], got " + std::to_string(batch));
    }
    const auto n = static_cast<std::size_t>(batch);
    const auto players = static_cast<std::size_t>(g_config.players);
    const std::span<const float> in(g_input.data(), n * static_cast<std::size_t>(g_config.input_size()));
    auto result = g_net->forward(in, batch, std::span<float>(g_logits.data(), n * kPolicySize), std::span<float>(g_values.data(), n * players));
    if (!result) return fail(kErrForward, result.error().message);
    for (std::size_t b = 0; b < n; ++b) {
        const float* row = g_logits.data() + b * kPolicySize;
        float* from = g_from.data() + b * kCells;
        float* to = g_to.data() + b * kCells;
        for (std::size_t c = 0; c < static_cast<std::size_t>(kCells); ++c) {
            from[c] = row[2 * c];
            to[c] = row[2 * c + 1];
        }
    }
    return 0;
}

NET_API void net_free() {
    release();
    g_error.clear();
}
#pragma clang unsafe_buffer_usage end
