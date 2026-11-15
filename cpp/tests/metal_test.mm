// Tests of the Metal backend (WP11a, WP11b): the three convolution kernels and the heads against the scalar reference, a hand-computed
// case, the whole network (every variant, both memory modes, the automatic variant rule) against the Java golden outputs and the cases
// of nn-cases.safetensors, buckets, slots, timing and errors.
// Every output buffer starts NaN-filled, so an element that a kernel skips stays NaN and fails.
#include <algorithm>
#include <array>
#include <bit>
#include <cmath>
#include <cstdint>
#include <limits>
#include <optional>
#include <string>
#include <vector>

#include "check.hpp"
#include "gather.hpp"
#include "golden.hpp"
#include "golden_io.hpp"
#include "metal_net.hpp"
#include "parameters.hpp"
#include "reference_net.hpp"
#include "splittable_random.hpp"

using namespace sh;
using namespace sh::nn;

namespace {

const float kNaN = std::numeric_limits<float>::quiet_NaN();

bool have_device() {
    if (metal::device_available()) return true;
    test::skip("no Metal device");
    return false;
}

// The bound of docs/network.md, "Numeric conventions".
bool within(float got, float ref) { return !std::isnan(got) && !std::isnan(ref) && std::abs(got - ref) <= 1e-4f + 1e-3f * std::abs(ref); }

struct Rng {
    SplittableRandom r;
    explicit Rng(std::uint64_t seed) : r(seed) {}
    // uniform in [lo, hi)
    float next(float lo, float hi) { return lo + (hi - lo) * static_cast<float>(static_cast<double>(r.next_long() >> 40) / static_cast<double>(1 << 24)); }
};

std::vector<float> random_vector(Rng& rng, std::size_t n, float lo, float hi) {
    std::vector<float> v(n);
    for (float& x : v) x = rng.next(lo, hi);
    return v;
}

// [batch][122][channels] with the padding row of every position set to `pad`.
void set_padding_rows(std::vector<float>& a, int batch, int channels, float pad) {
    for (int b = 0; b < batch; ++b) {
        for (int ch = 0; ch < channels; ++ch) a[(static_cast<std::size_t>(b) * kRows + kPadding) * static_cast<std::size_t>(channels) + static_cast<std::size_t>(ch)] = pad;
    }
}

// ---- convolution ----

const std::array<ConvVariant, 3> kVariants = {ConvVariant::Naive, ConvVariant::Tiled, ConvVariant::Simdgroup};

const char* name_of(ConvVariant v) { return v == ConvVariant::Naive ? "naive" : v == ConvVariant::Tiled ? "tiled" : "simdgroup"; }

void check_conv_shape(ConvVariant variant, int cin, int cout, int batch, bool with_residual_and_relu, std::uint64_t seed, std::string label) {
    label = std::string(name_of(variant)) + " " + label;
    Rng rng(seed);
    const auto cin_n = static_cast<std::size_t>(cin);
    const auto cout_n = static_cast<std::size_t>(cout);
    const float bound = std::sqrt(6.0f / (7.0f * static_cast<float>(cout)));
    std::vector<float> in = random_vector(rng, static_cast<std::size_t>(batch) * kRows * cin_n, -1.0f, 1.0f);
    set_padding_rows(in, batch, cin, 0.0f);
    const std::vector<float> weight = random_vector(rng, cout_n * kStencil * cin_n, -bound, bound);
    const std::vector<float> bias = random_vector(rng, cout_n, -0.5f, 0.5f);
    std::vector<float> res = random_vector(rng, static_cast<std::size_t>(batch) * kRows * cout_n, -1.0f, 1.0f);
    set_padding_rows(res, batch, cout, 7.0f);  // garbage in the padding row: the kernel must write 0 there
    std::vector<float> out(static_cast<std::size_t>(batch) * kRows * cout_n, kNaN);
    auto r = metal::run_conv(variant, in, weight, bias, res, out, batch, cin, cout, with_residual_and_relu, with_residual_and_relu);
    CHECK(r.has_value());
    if (!r) {
        std::cerr << "  " << r.error().message << '\n';
        return;
    }
    int bad = 0;
    for (int b = 0; b < batch; ++b) {
        const auto base_in = static_cast<std::size_t>(b) * kRows * cin_n;
        const auto base_out = static_cast<std::size_t>(b) * kRows * cout_n;
        std::vector<float> ref(static_cast<std::size_t>(kRows) * cout_n);
        CHECK(hex_conv(std::span<const float>(in).subspan(base_in, static_cast<std::size_t>(kRows) * cin_n), cin, weight, bias, cout, ref).has_value());
        for (std::size_t row = 0; row < static_cast<std::size_t>(kRows); ++row) {
            for (std::size_t o = 0; o < cout_n; ++o) {
                float expected = ref[row * cout_n + o];
                if (with_residual_and_relu && row != static_cast<std::size_t>(kPadding)) {
                    expected += res[base_out + row * cout_n + o];
                    expected = expected < 0.0f ? 0.0f : expected;
                }
                const float got = out[base_out + row * cout_n + o];
                if (row == static_cast<std::size_t>(kPadding) ? got != 0.0f : !within(got, expected)) ++bad;
            }
        }
    }
    if (bad != 0) std::cerr << "  " << label << ": " << bad << " elements outside the bound\n";
    CHECK_EQ(bad, 0);
}

SH_TEST(conv_every_variant_every_layer_shape_of_tiny_small_large) {
    if (!have_device()) return;
    std::uint64_t seed = 100;
    for (const ConvVariant variant : kVariants) {
        for (const NetworkConfig& cfg : {tiny_config(2, PlaneSet::Classic1), small_config(2, PlaneSet::Classic1), large_config(2, PlaneSet::Classic1)}) {
            // conv_in: planes -> C; every block convolution: C -> C
            for (const int cin : {cfg.input_planes(), cfg.channels}) {
                for (const int batch : {1, 3, 67}) {
                    for (const bool fused : {false, true}) {
                        check_conv_shape(variant, cin, cfg.channels, batch, fused, ++seed,
                                         "C=" + std::to_string(cfg.channels) + " cin=" + std::to_string(cin) + " batch=" + std::to_string(batch) + (fused ? " residual+relu" : " plain"));
                    }
                }
            }
        }
    }
}

SH_TEST(conv_every_variant_odd_channel_counts) {
    if (!have_device()) return;
    // sizes that are not multiples of a tile (32 channels, 32 columns, 64 rows) or of a thread group: edge threads and masked tile
    // elements must not write or read outside
    std::uint64_t seed = 7;
    for (const ConvVariant variant : kVariants) {
        check_conv_shape(variant, 1, 1, 2, false, ++seed, "1->1");
        check_conv_shape(variant, 3, 5, 3, true, ++seed, "3->5");
        check_conv_shape(variant, 33, 17, 2, true, ++seed, "33->17");
        check_conv_shape(variant, 5, 70, 1, true, ++seed, "5->70");
        check_conv_shape(variant, 64, 31, 5, false, ++seed, "64->31");
        check_conv_shape(variant, 9, 33, 4, true, ++seed, "9->33");
    }
}

void check_hand_computed(ConvVariant variant);

// KernelsHexConvTest, hand-computed case: one channel, weight k + 1 for stencil entry k, bias 0.5, input c + 1 (+ 1000 per position).
// Integers, so every float sum is exact and the results must be equal, not just close.
SH_TEST(conv_every_variant_hand_computed) {
    if (!have_device()) return;
    for (const ConvVariant variant : kVariants) check_hand_computed(variant);
    // a corner cell with the neighbours written out by hand: cell 0 has the neighbours 1 (direction 0, +q) and the padding row elsewhere
    CHECK_EQ(*gather(0, 0), 0);
}

void check_hand_computed(ConvVariant variant) {
    constexpr int kBatch = 3;
    std::vector<float> in(static_cast<std::size_t>(kBatch) * kRows, 0.0f);
    for (int b = 0; b < kBatch; ++b) {
        for (int c = 0; c < kCells; ++c) in[static_cast<std::size_t>(b * kRows + c)] = static_cast<float>(c + 1 + 1000 * b);
    }
    std::vector<float> weight(kStencil);
    for (int k = 0; k < kStencil; ++k) weight[static_cast<std::size_t>(k)] = static_cast<float>(k + 1);
    const std::vector<float> res(static_cast<std::size_t>(kBatch) * kRows, 3.0f);
    for (const float bias_value : {0.5f, -4000.5f}) {
        const bool fused = bias_value < 0;  // the negative bias makes some sums negative, so ReLU matters
        std::vector<float> out(static_cast<std::size_t>(kBatch) * kRows, kNaN);
        const std::vector<float> bias = {bias_value};
        CHECK(metal::run_conv(variant, in, weight, bias, res, out, kBatch, 1, 1, fused, fused).has_value());
        int negative = 0;
        for (int b = 0; b < kBatch; ++b) {
            for (int c = 0; c < kCells; ++c) {
                float expected = bias_value;  // all values are exact in float; the order of the sum does not matter
                float sum = 0;
                for (int k = 0; k < kStencil; ++k) sum += static_cast<float>(k + 1) * in[static_cast<std::size_t>(b * kRows + *gather(c, k))];
                expected = sum + bias_value;
                if (fused) {
                    expected += 3.0f;
                    if (expected < 0.0f) {
                        expected = 0.0f;
                        ++negative;
                    }
                }
                CHECK_EQ(out[static_cast<std::size_t>(b * kRows + c)], expected);
            }
            CHECK_EQ(out[static_cast<std::size_t>(b * kRows + kPadding)], 0.0f);
        }
        if (fused) CHECK(negative > 0);
    }
}

// DeviceMath.relu keeps NaN: a NaN in one cell of the input gives NaN exactly where the scalar reference has NaN, in every kernel,
// with ReLU and residual switched on (a ReLU written as v > 0 ? v : 0 would turn NaN into 0).
SH_TEST(conv_every_variant_keeps_nan) {
    if (!have_device()) return;
    constexpr int kCin = 5, kCout = 6, kBatch = 2;
    for (const ConvVariant variant : kVariants) {
        Rng rng(4242);
        std::vector<float> in = random_vector(rng, static_cast<std::size_t>(kBatch) * kRows * kCin, -1.0f, 1.0f);
        set_padding_rows(in, kBatch, kCin, 0.0f);
        in[(0 * static_cast<std::size_t>(kRows) + 5) * kCin + 2] = kNaN;
        const std::vector<float> weight = random_vector(rng, static_cast<std::size_t>(kCout) * kStencil * kCin, 0.1f, 1.0f);  // no zero weight: NaN never hides
        const std::vector<float> bias = random_vector(rng, kCout, -0.5f, 0.5f);
        const std::vector<float> res(static_cast<std::size_t>(kBatch) * kRows * kCout, 0.25f);
        std::vector<float> out(static_cast<std::size_t>(kBatch) * kRows * kCout, kNaN);
        CHECK(metal::run_conv(variant, in, weight, bias, res, out, kBatch, kCin, kCout, true, true).has_value());
        int nan_cells = 0;
        for (int b = 0; b < kBatch; ++b) {
            std::vector<float> ref(static_cast<std::size_t>(kRows) * kCout);
            CHECK(hex_conv(std::span<const float>(in).subspan(static_cast<std::size_t>(b) * kRows * kCin, static_cast<std::size_t>(kRows) * kCin), kCin, weight, bias, kCout, ref).has_value());
            for (std::size_t row = 0; row < static_cast<std::size_t>(kRows); ++row) {
                for (std::size_t o = 0; o < static_cast<std::size_t>(kCout); ++o) {
                    const float got = out[(static_cast<std::size_t>(b) * kRows + row) * kCout + o];
                    if (row == static_cast<std::size_t>(kPadding)) {
                        CHECK_EQ(got, 0.0f);
                    } else {
                        CHECK_EQ(std::isnan(got), std::isnan(ref[row * kCout + o]));
                        if (std::isnan(got)) ++nan_cells;
                    }
                }
            }
        }
        CHECK(nan_cells >= kCout);  // at least the cell itself
    }
}

// ---- heads ----

struct HeadCase {
    std::vector<float> trunk, pw, pb, w1, b1, w2, b2;
};

HeadCase make_head_case(Rng& rng, int batch, int channels, int hidden, int players) {
    const auto c = static_cast<std::size_t>(channels);
    const auto h = static_cast<std::size_t>(hidden);
    const auto p = static_cast<std::size_t>(players);
    HeadCase k;
    k.trunk = random_vector(rng, static_cast<std::size_t>(batch) * kRows * c, 0.0f, 1.0f);
    set_padding_rows(k.trunk, batch, channels, kNaN);  // a head that reads the padding row fails
    const float s = 0.8f / std::sqrt(static_cast<float>(channels));
    k.pw = random_vector(rng, 2 * c, -s, s);
    k.pb = random_vector(rng, 2, -0.5f, 0.5f);
    k.w1 = random_vector(rng, h * c, -s, s);
    k.b1 = random_vector(rng, h, -0.5f, 0.5f);
    const float s2 = 1.0f / std::sqrt(static_cast<float>(hidden));
    k.w2 = random_vector(rng, p * h, -s2, s2);
    k.b2 = random_vector(rng, p, -0.5f, 0.5f);
    return k;
}

void check_heads(int batch, int channels, int hidden, int players, bool apply_tanh, std::uint64_t seed) {
    Rng rng(seed);
    const HeadCase k = make_head_case(rng, batch, channels, hidden, players);
    const auto c = static_cast<std::size_t>(channels);
    const auto b_n = static_cast<std::size_t>(batch);
    const auto p = static_cast<std::size_t>(players);
    std::vector<float> logits(b_n * kPolicySize, kNaN);
    std::vector<float> values(b_n * p, kNaN);
    const metal::HeadWeights w{k.pw, k.pb, k.w1, k.b1, k.w2, k.b2};
    auto r = metal::run_heads(k.trunk, w, batch, channels, hidden, players, apply_tanh, logits, values);
    CHECK(r.has_value());
    if (!r) {
        std::cerr << "  " << r.error().message << '\n';
        return;
    }
    int bad = 0;
    int unsaturated = 0;
    for (std::size_t b = 0; b < b_n; ++b) {
        const std::span<const float> trunk = std::span<const float>(k.trunk).subspan(b * kRows * c, static_cast<std::size_t>(kCells) * c);
        std::vector<float> policy(static_cast<std::size_t>(kPolicySize));
        CHECK(pointwise(trunk, kCells, channels, k.pw, k.pb, kPolicyChannels, policy).has_value());
        for (std::size_t j = 0; j < policy.size(); ++j) bad += within(logits[b * kPolicySize + j], policy[j]) ? 0 : 1;
        std::vector<float> pooled(c);
        for (std::size_t ch = 0; ch < c; ++ch) {
            double sum = 0;
            for (std::size_t cell = 0; cell < static_cast<std::size_t>(kCells); ++cell) sum += static_cast<double>(trunk[cell * c + ch]);
            pooled[ch] = static_cast<float>(sum / kCells);
        }
        std::vector<float> hidden_act(static_cast<std::size_t>(hidden));
        CHECK(pointwise(pooled, 1, channels, k.w1, k.b1, hidden, hidden_act).has_value());
        for (float& v : hidden_act) v = v < 0.0f ? 0.0f : v;
        std::vector<float> value(p);
        CHECK(pointwise(hidden_act, 1, hidden, k.w2, k.b2, players, value).has_value());
        for (std::size_t o = 0; o < p; ++o) {
            const float expected = apply_tanh ? static_cast<float>(std::tanh(static_cast<double>(value[o]))) : value[o];
            bad += within(values[b * p + o], expected) ? 0 : 1;
            if (apply_tanh && std::abs(expected) < 0.9f) ++unsaturated;
        }
    }
    if (bad != 0) std::cerr << "  heads C=" << channels << " H=" << hidden << " P=" << players << " batch=" << batch << ": " << bad << " elements outside the bound\n";
    CHECK_EQ(bad, 0);
    if (apply_tanh) CHECK(unsaturated * 4 >= static_cast<int>(b_n * p));  // tanh is really tested, not only its saturation
}

SH_TEST(heads_against_the_reference_operations) {
    if (!have_device()) return;
    std::uint64_t seed = 500;
    // C = 32 and 64 divide 256; 100 does not; 300 is above 256 (one work item per channel path)
    for (const int channels : {32, 64, 100, 300}) {
        for (const int batch : {1, 3, 67}) {
            check_heads(batch, channels, kValueHidden, 2, true, ++seed);
            check_heads(batch, channels, kValueHidden, 2, false, ++seed);
        }
    }
    check_heads(3, 100, 300, 3, true, ++seed);  // hidden above 256, three players
    check_heads(2, 1, 1, 2, true, ++seed);
    check_heads(2, 8, 1024, 2, true, ++seed);
    // barriers under load (OpsHeadsTest): writers and readers of threadgroup memory in different SIMD groups, many groups
    check_heads(1024, 1, 1024, 2, true, ++seed);
    check_heads(1024, 2, 512, 2, true, ++seed);
    check_heads(1024, 8, 1024, 2, true, ++seed);
    check_heads(1024, 8, 1024, 8, true, ++seed);
    check_heads(64, 1024, 1024, 2, true, ++seed);
}

// OpsHeadsTest, saturation: dense2 biases 44.3, 100, -1000 and +inf must give exactly +-1 on the device (DeviceMath.tanh), and a NaN in
// a cell row must give NaN values and NaN logits for that cell, leaving the other positions alone.
SH_TEST(value_head_saturates_exactly_and_keeps_nan) {
    if (!have_device()) return;
    for (const int channels : {32, 300}) {
        const auto c = static_cast<std::size_t>(channels);
        Rng rng(900 + static_cast<std::uint64_t>(channels));
        HeadCase k = make_head_case(rng, 2, channels, 3, 4);
        k.w2.assign(4 * 3, 0.0f);
        k.b2 = {44.3f, 100.0f, -1000.0f, std::numeric_limits<float>::infinity()};
        k.trunk[5 * c + 3] = kNaN;  // cell 5 of position 0, channel 3
        std::vector<float> logits(2 * kPolicySize, kNaN);
        std::vector<float> values(2 * 4, kNaN);
        const metal::HeadWeights w{k.pw, k.pb, k.w1, k.b1, k.w2, k.b2};
        CHECK(metal::run_heads(k.trunk, w, 2, channels, 3, 4, true, logits, values).has_value());
        for (std::size_t o = 0; o < 4; ++o) CHECK(std::isnan(values[o]));  // position 0: the pooled channel 3 is NaN, so the value is NaN
        const std::array<float, 4> expected = {1.0f, 1.0f, -1.0f, 1.0f};
        for (std::size_t o = 0; o < 4; ++o) CHECK_EQ(values[4 + o], expected[o]);  // position 1 saturates exactly
        for (std::size_t j = 0; j < static_cast<std::size_t>(kPolicySize); ++j) {
            const bool nan_expected = j == 10 || j == 11;  // cell 5, both logits
            CHECK_EQ(std::isnan(logits[j]), nan_expected);
            CHECK(!std::isnan(logits[kPolicySize + j]));
        }
    }
}

SH_TEST(value_head_pools_cell_120_and_divides_by_121) {
    if (!have_device()) return;
    for (const int channels : {1, 300}) {
        // trunk = 0 except 121 in cell 120 of channel 0 (and 299): the mean is exactly 1 in that channel
        const auto c = static_cast<std::size_t>(channels);
        std::vector<float> trunk(kRows * c, 0.0f);
        trunk[120 * c + 0] = 121.0f;
        if (channels > 1) trunk[120 * c + c - 1] = 121.0f;
        const std::vector<float> pw(2 * c, 0.0f);
        const std::vector<float> pb = {0.0f, 0.0f};
        std::vector<float> w1(c, 0.0f);   // hidden = 1: h = pooled[0] + pooled[C-1]
        w1[0] = 1.0f;
        if (channels > 1) w1[c - 1] = 1.0f;
        const std::vector<float> b1 = {0.0f};
        const std::vector<float> w2 = {1.0f, 2.0f};
        const std::vector<float> b2 = {0.25f, 0.5f};
        std::vector<float> logits(kPolicySize, kNaN);
        std::vector<float> values(2, kNaN);
        const metal::HeadWeights w{pw, pb, w1, b1, w2, b2};
        CHECK(metal::run_heads(trunk, w, 1, channels, 1, 2, false, logits, values).has_value());
        const float hidden = channels > 1 ? 2.0f : 1.0f;
        CHECK_EQ(values[0], hidden * 1.0f + 0.25f);
        CHECK_EQ(values[1], hidden * 2.0f + 0.5f);
    }
}

// ---- the whole network ----

std::optional<std::pair<GoldenData, Parameters>> load_preset(const std::string& name, Preset& preset_out) {
    const auto preset = preset_by_name(name);
    preset_out = *preset;
    auto data = load_golden_data(golden::directory(), *preset);
    auto weights = load_golden_weights(golden::directory(), *preset);
    for (const golden::Error* e : {data ? nullptr : &data.error(), weights ? nullptr : &weights.error()}) {
        if (e == nullptr) continue;
        test::count_check();
        if (e->kind == golden::ErrorKind::Missing && (name == "gen11" || !golden::required())) {
            test::skip(name + ": " + e->message);
        } else {
            test::fail("golden", 0, name + ": " + e->message);
        }
        return std::nullopt;
    }
    return std::pair{std::move(*data), std::move(*weights)};
}

struct Mode {
    std::optional<ConvVariant> variant;  // empty = the bucket rule
    metal::MemoryMode memory;
    std::string label() const {
        return std::string(variant ? name_of(*variant) : "auto") + (memory == metal::MemoryMode::Copy ? "/copy" : "/shared");
    }
};

std::vector<Mode> all_modes() {
    std::vector<Mode> modes;
    for (const metal::MemoryMode memory : {metal::MemoryMode::Copy, metal::MemoryMode::Shared}) {
        modes.push_back(Mode{std::nullopt, memory});
        for (const ConvVariant v : kVariants) modes.push_back(Mode{v, memory});
    }
    return modes;
}

bool same_bits(std::span<const float> a, std::span<const float> b) {
    if (a.size() != b.size()) return false;
    for (std::size_t i = 0; i < a.size(); ++i) {
        if (std::bit_cast<std::uint32_t>(a[i]) != std::bit_cast<std::uint32_t>(b[i])) return false;
    }
    return true;
}

void check_network_against_java(const std::string& name) {
    if (!have_device()) return;
    Preset preset;
    auto loaded = load_preset(name, preset);
    if (!loaded) return;
    const GoldenData& data = loaded->first;
    const Parameters& weights = loaded->second;
    const NetworkConfig& cfg = weights.config();
    auto figures = load_figures(golden::directory());
    CHECK(figures.has_value());
    if (!figures) return;
    const double extra = extra_rel(*figures, "tornado", preset);
    const auto in_size = static_cast<std::size_t>(cfg.input_size());
    const auto players = static_cast<std::size_t>(cfg.players);
    constexpr int kPositions = 67;
    const std::span<const float> input = std::span<const float>(data.inputs).first(kPositions * in_size);
    for (const Mode& mode : all_modes()) {
        // one call of 67 positions (bucket 128), then chunks of 16 (bucket 16 and a last chunk of 3)
        for (const int max_batch : {1024, 16}) {
            metal::Options options;
            options.max_batch = max_batch;
            options.variant = mode.variant;
            options.memory = mode.memory;
            auto net = metal::MetalNet::create(weights, options);
            CHECK(net.has_value());
            if (!net) {
                std::cerr << "  " << net.error().message << '\n';
                return;
            }
            std::vector<float> logits(kPositions * static_cast<std::size_t>(kPolicySize), kNaN);
            std::vector<float> values(kPositions * players, kNaN);
            auto r = net->forward(input, kPositions, logits, values);
            CHECK(r.has_value());
            if (!r) {
                std::cerr << "  " << r.error().message << '\n';
                return;
            }
            const Comparison c = compare_outputs(logits, values, data.logits, data.values, 0, kPositions, cfg.players, extra);
            std::cout << "  " << name << " " << mode.label() << " max_batch=" << max_batch << ": largest error " << c.fraction() << " of the bound (logits " << c.logits_fraction << ", values " << c.values_fraction << ")\n";
            CHECK(c.fraction() <= 1.0);
        }
        // the slots of a bucket do not interact: three positions in one call (bucket 4) give the bits of three single calls (bucket 1)
        // and of the same positions in different slots of a bucket of 8
        metal::Options options;
        options.variant = mode.variant;
        options.memory = mode.memory;
        auto net = metal::MetalNet::create(weights, options);
        CHECK(net.has_value());
        if (!net) return;
        std::vector<float> l3(3 * static_cast<std::size_t>(kPolicySize), kNaN);
        std::vector<float> v3(3 * players, kNaN);
        CHECK(net->forward(input.first(3 * in_size), 3, l3, v3).has_value());
        std::vector<float> l5(5 * static_cast<std::size_t>(kPolicySize), kNaN);
        std::vector<float> v5(5 * players, kNaN);
        // positions 2, 0, 1 and two others, so that position 0 sits in slot 1, position 1 in slot 2, position 2 in slot 0
        std::vector<float> shuffled;
        for (const std::size_t pos : {2, 0, 1, 3, 4}) shuffled.insert(shuffled.end(), input.begin() + static_cast<std::ptrdiff_t>(pos * in_size), input.begin() + static_cast<std::ptrdiff_t>((pos + 1) * in_size));
        CHECK(net->forward(shuffled, 5, l5, v5).has_value());
        for (std::size_t pos = 0; pos < 3; ++pos) {
            std::vector<float> l1(static_cast<std::size_t>(kPolicySize), kNaN);
            std::vector<float> v1(players, kNaN);
            CHECK(net->forward(input.subspan(pos * in_size, in_size), 1, l1, v1).has_value());
            const auto at = [&](const std::vector<float>& all, std::size_t slot, std::size_t width) { return std::span<const float>(all).subspan(slot * width, width); };
            const std::size_t slot5 = pos == 2 ? 0 : pos + 1;
            const bool same = same_bits(l1, at(l3, pos, kPolicySize)) && same_bits(v1, at(v3, pos, players)) && same_bits(at(l5, slot5, kPolicySize), at(l3, pos, kPolicySize)) &&
                              same_bits(at(v5, slot5, players), at(v3, pos, players));
            if (!same) std::cerr << "  " << name << " " << mode.label() << ": position " << pos << " differs between slots or buckets\n";
            CHECK(same);
        }
        CHECK_EQ(net->plans_built(), 3);  // buckets 4, 1 and 8
    }
}

SH_TEST(network_small_against_java) { check_network_against_java("small"); }
SH_TEST(network_large_against_java) { check_network_against_java("large"); }
SH_TEST(network_gen11_against_java) { check_network_against_java("gen11"); }

SH_TEST(nn_cases_on_the_gpu) {
    if (!have_device()) return;
    auto cases = load_golden_contents(golden::directory(), "nn-cases.safetensors");
    if (!cases) {
        test::count_check();
        if (cases.error().kind == golden::ErrorKind::Missing && !golden::required()) {
            test::skip("nn-cases: " + cases.error().message);
        } else {
            test::fail("golden", 0, "nn-cases: " + cases.error().message);
        }
        return;
    }
    const NetworkConfig tiny = tiny_config(2, PlaneSet::Classic1);
    const auto find = [&](const std::string& name) -> const std::vector<float>& {
        for (const Tensor& t : cases->tensors) {
            if (t.name == name) return t.data;
        }
        test::count_check();
        test::fail("golden", 0, "nn-cases lacks " + name);
        static const std::vector<float> none;
        return none;
    };
    std::vector<Tensor> base;
    for (const Tensor& t : cases->tensors) {
        if (t.name.starts_with("base.") && t.name != "base.logits" && t.name != "base.values") base.push_back(Tensor{t.name.substr(5), t.shape, t.data});
    }
    const auto run = [&](const std::vector<Tensor>& tensors, const std::vector<float>& input, std::vector<float>& logits, std::vector<float>& values) {
        auto params = Parameters::from_tensors(tiny, tensors);
        CHECK(params.has_value());
        if (!params) return std::expected<void, Error>(std::unexpected(Error{"parameters"}));
        auto net = metal::MetalNet::create(*params);
        CHECK(net.has_value());
        if (!net) return std::expected<void, Error>(std::unexpected(net.error()));
        logits.assign(kPolicySize, kNaN);
        values.assign(2, kNaN);
        return net->forward(input, 1, logits, values);
    };
    const std::vector<float>& input = find("input");
    std::vector<float> logits, values;
    // the base network
    CHECK(run(base, input, logits, values).has_value());
    const Comparison cmp = compare_outputs(logits, values, find("base.logits"), find("base.values"), 0, 1, 2, 0.0);
    CHECK(cmp.fraction() <= 1.0);
    // a dirty padding row is refused
    CHECK(!run(base, find("dirty_input"), logits, values).has_value());
    // NaN in conv_in.weight: NaN exactly where Java has NaN
    {
        std::vector<Tensor> t = base;
        for (Tensor& x : t) {
            if (x.name == "conv_in.weight") x.data = find("nan.conv_in.weight");
        }
        CHECK(run(t, input, logits, values).has_value());
        const std::vector<float>& el = find("nan.logits");
        const std::vector<float>& ev = find("nan.values");
        int nan_logits = 0;
        for (std::size_t i = 0; i < logits.size(); ++i) {
            CHECK_EQ(std::isnan(logits[i]), std::isnan(el[i]));
            if (std::isnan(logits[i])) ++nan_logits; else CHECK(within(logits[i], el[i]));
        }
        CHECK(nan_logits > 0);
        CHECK(std::isnan(values[0]) && std::isnan(values[1]));
        CHECK(std::isnan(ev[0]) && std::isnan(ev[1]));
    }
    // saturation: dense2 biases 44.3, 100 and -1000, +inf give exactly {1, 1} and {-1, 1}
    for (const std::string tag : {"sat_a", "sat_b"}) {
        std::vector<Tensor> t = base;
        for (Tensor& x : t) {
            if (x.name == "head_value.dense2.bias") x.data = find(tag + ".head_value.dense2.bias");
        }
        CHECK(run(t, input, logits, values).has_value());
        CHECK_EQ(values[0], tag == "sat_a" ? 1.0f : -1.0f);
        CHECK_EQ(values[1], 1.0f);
    }
}

// ---- buckets, errors ----

SH_TEST(buckets_plans_and_errors) {
    if (!have_device()) return;
    Preset preset;
    auto loaded = load_preset("small", preset);
    if (!loaded) return;
    const Parameters& weights = loaded->second;
    const auto in_size = static_cast<std::size_t>(weights.config().input_size());
    auto net = metal::MetalNet::create(weights);
    CHECK(net.has_value());
    if (!net) return;
    CHECK_EQ(net->plans_built(), 0);  // built on first use
    std::vector<float> logits(5 * static_cast<std::size_t>(kPolicySize));
    std::vector<float> values(5 * 2);
    const std::span<const float> input = std::span<const float>(loaded->first.inputs).first(5 * in_size);
    CHECK(net->forward(input.first(in_size), 1, logits, values).has_value());
    CHECK_EQ(net->plans_built(), 1);
    CHECK(net->forward(input.first(3 * in_size), 3, logits, values).has_value());  // bucket 4
    CHECK_EQ(net->plans_built(), 2);
    CHECK(net->forward(input.first(4 * in_size), 4, logits, values).has_value());  // bucket 4 again
    CHECK_EQ(net->plans_built(), 2);
    CHECK(net->forward(input, 5, logits, values).has_value());  // bucket 8
    CHECK_EQ(net->plans_built(), 3);
    // refusals
    CHECK(!net->forward(input, 0, logits, values).has_value());
    CHECK(!net->forward(input.first(in_size), 2, logits, values).has_value());                                     // input too short
    CHECK(!net->forward(input, 5, std::span<float>(logits).first(10), values).has_value());                         // logits too short
    CHECK(!net->forward(input, 5, logits, std::span<float>(values).first(3)).has_value());                          // values too short
    std::vector<float> dirty(input.begin(), input.begin() + static_cast<std::ptrdiff_t>(in_size));
    dirty[static_cast<std::size_t>(kPadding * weights.config().input_planes())] = 0.5f;
    CHECK(!net->forward(dirty, 1, logits, values).has_value());
    dirty[static_cast<std::size_t>(kPadding * weights.config().input_planes())] = kNaN;
    CHECK(!net->forward(dirty, 1, logits, values).has_value());
    metal::Options zero;
    zero.max_batch = 0;
    CHECK(!metal::MetalNet::create(weights, zero).has_value());
}

// ---- timing, instrumented mode, set-up record, variant rule ----

SH_TEST(timing_setup_and_variant_rule) {
    if (!have_device()) return;
    Preset preset;
    auto loaded = load_preset("small", preset);
    if (!loaded) return;
    const Parameters& weights = loaded->second;
    const NetworkConfig& cfg = weights.config();
    const auto in_size = static_cast<std::size_t>(cfg.input_size());
    const auto players = static_cast<std::size_t>(cfg.players);
    const std::span<const float> input = std::span<const float>(loaded->first.inputs).first(40 * in_size);
    CHECK(metal::library_compile_ms() > 0.0);

    // the rule: naive while bucket * C <= 128, else simdgroup; a forced variant wins
    {
        auto net = metal::MetalNet::create(weights);
        CHECK(net.has_value());
        if (!net) return;
        std::vector<float> logits(40 * static_cast<std::size_t>(kPolicySize));
        std::vector<float> values(40 * players);
        for (const int n : {1, 2, 4, 8, 16, 40}) CHECK(net->forward(input.first(static_cast<std::size_t>(n) * in_size), n, logits, values).has_value());
        CHECK_EQ(net->setup().size(), static_cast<std::size_t>(6));
        for (const metal::BucketSetup& b : net->setup()) {
            CHECK(b.variant == variant_for(b.bucket, cfg.channels, true));
            CHECK((b.variant == ConvVariant::Naive) == (static_cast<long long>(b.bucket) * cfg.channels <= 128));
            CHECK(b.pipeline_ms > 0.0);
            CHECK(b.buffer_ms >= 0.0);
        }
        CHECK(net->setup().front().variant == ConvVariant::Naive || cfg.channels > 128);
        CHECK(net->setup().back().variant == ConvVariant::Simdgroup);  // bucket 64
        const metal::PhaseTimes t = net->timings();
        CHECK_EQ(t.calls, 6);
        CHECK(t.copy_in_ms > 0.0 && t.commit_wait_ms > 0.0 && t.gpu_ms > 0.0 && t.copy_out_ms > 0.0 && t.priors_ms > 0.0);
        CHECK(t.gpu_ms <= t.commit_wait_ms * 1.5);  // the GPU time cannot much exceed the CPU time that waited for it
        CHECK(net->kernel_times().empty());         // plain mode keeps no per-kernel record
        net->reset_timings();
        CHECK_EQ(net->timings().calls, 0);
        CHECK_EQ(net->timings().commit_wait_ms, 0.0);
    }
    for (const ConvVariant forced : kVariants) {
        metal::Options options;
        options.variant = forced;
        auto net = metal::MetalNet::create(weights, options);
        CHECK(net.has_value());
        if (!net) return;
        std::vector<float> logits(2 * static_cast<std::size_t>(kPolicySize));
        std::vector<float> values(2 * players);
        CHECK(net->forward(input.first(2 * in_size), 2, logits, values).has_value());
        CHECK(net->setup().front().variant == forced);
    }

    // instrumented mode: one command buffer per kernel, the same bits, a record of every kernel
    for (const metal::MemoryMode memory : {metal::MemoryMode::Copy, metal::MemoryMode::Shared}) {
        metal::Options plain;
        plain.memory = memory;
        metal::Options instrumented = plain;
        instrumented.instrumented = true;
        auto a = metal::MetalNet::create(weights, plain);
        auto b = metal::MetalNet::create(weights, instrumented);
        CHECK(a.has_value() && b.has_value());
        if (!a || !b) return;
        std::vector<float> la(40 * static_cast<std::size_t>(kPolicySize), kNaN), lb = la;
        std::vector<float> va(40 * players, kNaN), vb = va;
        CHECK(a->forward(input, 40, la, va).has_value());
        CHECK(b->forward(input, 40, lb, vb).has_value());
        CHECK(same_bits(la, lb) && same_bits(va, vb));
        const std::vector<metal::KernelTime>& k = b->kernel_times();
        const std::size_t expected = static_cast<std::size_t>(1 + 2 * cfg.blocks + 2) + (memory == metal::MemoryMode::Copy ? 2 : 0);
        CHECK_EQ(k.size(), expected);
        double sum = 0;
        for (const metal::KernelTime& t : k) {
            CHECK(t.gpu_ms >= 0.0);
            sum += t.gpu_ms;
        }
        CHECK(sum > 0.0);
        const auto has = [&](const std::string& n) { return std::ranges::any_of(k, [&](const metal::KernelTime& t) { return t.name == n; }); };
        CHECK(has("conv_in") && has("block0.conv1") && has("block" + std::to_string(cfg.blocks - 1) + ".conv2") && has("policy_head") && has("value_head"));
        CHECK_EQ(has("blit_in"), memory == metal::MemoryMode::Copy);
        CHECK_EQ(has("blit_out"), memory == metal::MemoryMode::Copy);
        CHECK(a->kernel_times().empty());
    }
}

// How far the three kernels differ from each other on a whole network: reported, not required (the order of the sums inside the
// SIMD-group matrix unit is not documented); each of them is within the bound of the Java reference in the network tests.
SH_TEST(variants_on_the_whole_network_report) {
    if (!have_device()) return;
    Preset preset;
    auto loaded = load_preset("small", preset);
    if (!loaded) return;
    const Parameters& weights = loaded->second;
    const auto in_size = static_cast<std::size_t>(weights.config().input_size());
    const auto players = static_cast<std::size_t>(weights.config().players);
    constexpr std::size_t kN = 64;
    const std::span<const float> input = std::span<const float>(loaded->first.inputs).first(kN * in_size);
    std::vector<std::vector<float>> logits;
    for (const ConvVariant v : kVariants) {
        metal::Options options;
        options.variant = v;
        auto net = metal::MetalNet::create(weights, options);
        CHECK(net.has_value());
        if (!net) return;
        std::vector<float> l(kN * static_cast<std::size_t>(kPolicySize), kNaN);
        std::vector<float> va(kN * players, kNaN);
        CHECK(net->forward(input, static_cast<int>(kN), l, va).has_value());
        logits.push_back(std::move(l));
    }
    for (std::size_t other = 1; other < logits.size(); ++other) {
        std::size_t differing = 0;
        for (std::size_t i = 0; i < logits[0].size(); ++i) differing += std::bit_cast<std::uint32_t>(logits[0][i]) != std::bit_cast<std::uint32_t>(logits[other][i]) ? 1 : 0;
        std::cout << "  logits that differ in bits, naive vs " << name_of(kVariants[other]) << ": " << differing << " of " << logits[0].size() << '\n';
    }
}

SH_TEST(skeleton_links) {
    CHECK(!metal::kernel_source().empty());
    if (!have_device()) return;
    CHECK(!metal::device_name().empty());
}

}  // namespace

int main() { return sh::test::run_all(); }
