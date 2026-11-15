// Tests of the CUDA adapter and of the CUDA kernels without a GPU (WP12). Four parts:
//   1. the stub build: no device, create() fails with the stub's error, option errors come first;
//   2. the argument packing of cuda_backend.cpp (Parameters -> cuda::Weights) against the layout of Parameters;
//   3. the REAL kernels of src/gpu/cuda/kernels.cuh, run on the CPU by tests/cuda_emu.hpp, against the reference: every
//      convolution shape with both kernels, both heads, the launch geometry;
//   4. the adapter with an emulated device net: the whole network against ReferenceNet, bucket and variant rule, chunks,
//      padding refusal, host priors, timing totals.
// What this cannot prove: that nvcc compiles the file, that the device behaves like the emulation (warps, memory ordering,
// fused multiply-add), anything about the CUDA runtime. That is the CUDA gate of the AWS run (cuda_test.cpp).
#include <algorithm>
#include <bit>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <functional>
#include <iostream>
#include <limits>
#include <optional>
#include <string>
#include <utility>
#include <vector>

#include "check.hpp"
#include "config.hpp"
#include "cuda_backend.hpp"
#include "cuda_net.hpp"
#include "gather.hpp"
#include "golden_io.hpp"
#include "parameters.hpp"
#include "reference_net.hpp"
#include "nn_random.hpp"
#include "splittable_random.hpp"
#include "cuda_emu.hpp"  // last: it defines the CUDA keywords as macros

namespace {

using namespace sh;
namespace k = sh::cuda::kernels;

using namespace sh::testdata;

// |got - ref| as a fraction of the bound 1e-4 + 1e-3 |ref| (docs/network.md, "Numeric conventions"); infinity for a NaN.
double fraction(float got, double ref) {
    if (std::isnan(got) || std::isnan(ref)) return std::numeric_limits<double>::infinity();
    return std::abs(static_cast<double>(got) - ref) / (1e-4 + 1e-3 * std::abs(ref));
}

double max_fraction(const std::vector<float>& got, const std::vector<double>& ref) {
    CHECK_EQ(got.size(), ref.size());
    double worst = 0;
    for (std::size_t i = 0; i < got.size() && i < ref.size(); ++i) worst = std::max(worst, fraction(got[i], ref[i]));
    return worst;
}

// ---- 1. the stub build ----

SH_TEST(stub_build) {
    CHECK_EQ(cuda_backend_name(), cuda::build_kind() == "cuda" ? "cuda" : "cuda-stub");
    if (cuda::build_kind() != "stub") {
        sh::test::skip("this build has device code");
        return;
    }
    CHECK_EQ(cuda_backend_name(), "cuda-stub");
    CHECK_EQ(cuda::device_count(), 0);
    CHECK_EQ(cuda::device_name(), "");
    const nn::Parameters p = make_params(nn::tiny_config(2, nn::PlaneSet::Classic1), 1);
    auto created = CudaBackend::create(p);
    CHECK(!created.has_value());
    if (!created) CHECK_EQ(created.error().message, "no CUDA device in this build");
    // The adapter's own checks come before the device is asked.
    CudaOptions simdgroup;
    simdgroup.variant = nn::ConvVariant::Simdgroup;
    auto refused = CudaBackend::create(p, simdgroup);
    CHECK(!refused.has_value());
    if (!refused) CHECK(refused.error().message.find("simdgroup") != std::string::npos);
    CudaOptions no_batch;
    no_batch.max_batch = 0;
    CHECK(!CudaBackend::create(p, no_batch).has_value());
    std::string error;
    const PackedWeights packed = pack_weights(p);
    CHECK(cuda::CudaNet::create(packed.shape, packed.weights, cuda::Options{}, error) == nullptr);
    CHECK_EQ(error, "no CUDA device in this build");
}

// ---- 2. the argument packing ----

void check_packing(const nn::NetworkConfig& cfg, std::uint64_t seed) {
    const nn::Parameters p = make_params(cfg, seed);
    const PackedWeights packed = pack_weights(p);
    const cuda::Shape& s = packed.shape;
    const cuda::Weights& w = packed.weights;
    CHECK_EQ(s.channels, cfg.channels);
    CHECK_EQ(s.blocks, cfg.blocks);
    CHECK_EQ(s.players, cfg.players);
    CHECK_EQ(s.hidden, cfg.value_hidden);
    CHECK_EQ(s.input_planes, cfg.input_planes());
    const auto c = static_cast<std::size_t>(cfg.channels);
    const auto planes = static_cast<std::size_t>(cfg.input_planes());
    const auto h = static_cast<std::size_t>(cfg.value_hidden);
    const auto players = static_cast<std::size_t>(cfg.players);
    // every span is the very tensor of the Parameters (same address), with the size of the layout
    const auto same = [&](std::span<const float> got, const char* name, std::size_t size) {
        const std::span<const float> want = p.data(name);
        CHECK(got.data() == want.data());
        CHECK_EQ(got.size(), want.size());
        CHECK_EQ(got.size(), size);
    };
    CHECK_EQ(w.gather.size(), static_cast<std::size_t>(nn::kCells * nn::kStencil));
    CHECK(w.gather.data() == nn::gather_table().data());
    same(w.conv_in.weight, "conv_in.weight", c * 7 * planes);
    same(w.conv_in.bias, "conv_in.bias", c);
    CHECK_EQ(w.blocks.size(), static_cast<std::size_t>(cfg.blocks));
    for (std::size_t i = 0; i < w.blocks.size(); ++i) {
        const std::string block = "block" + std::to_string(i);
        same(w.blocks[i][0].weight, (block + ".conv1.weight").c_str(), c * 7 * c);
        same(w.blocks[i][0].bias, (block + ".conv1.bias").c_str(), c);
        same(w.blocks[i][1].weight, (block + ".conv2.weight").c_str(), c * 7 * c);
        same(w.blocks[i][1].bias, (block + ".conv2.bias").c_str(), c);
    }
    same(w.policy_weight, "head_policy.weight", 2 * c);
    same(w.policy_bias, "head_policy.bias", 2);
    same(w.dense1_weight, "head_value.dense1.weight", h * c);
    same(w.dense1_bias, "head_value.dense1.bias", h);
    same(w.dense2_weight, "head_value.dense2.weight", players * h);
    same(w.dense2_bias, "head_value.dense2.bias", players);
    // the tensors differ from one another, so a swapped pair would not go unseen above
    if (cfg.blocks > 0) CHECK(w.blocks[0][0].weight.data() != w.blocks[0][1].weight.data());
}

SH_TEST(packing_matches_the_parameter_layout) {
    check_packing(nn::tiny_config(2, nn::PlaneSet::Classic1), 11);
    check_packing(nn::tiny_config(3, nn::PlaneSet::Plain), 12);
    check_packing(nn::small_config(2, nn::PlaneSet::Classic1), 13);
    check_packing(nn::large_config(2, nn::PlaneSet::Classic1), 14);
    auto zero_blocks = nn::make_config(5, 0, 2, 32, nn::PlaneSet::Classic1);
    CHECK(zero_blocks.has_value());
    if (zero_blocks) check_packing(*zero_blocks, 15);
}

// ---- 3. the kernels ----

SH_TEST(launch_geometry) {
    using namespace sh::cuda::kernels;
    // tiled: blocks of 8 x 16, one block per 64 rows x 32 channels
    CHECK_EQ(tiled_dims(64, 1).grid_x, 2);
    CHECK_EQ(tiled_dims(64, 1).grid_y, 2);  // 122 rows
    CHECK_EQ(tiled_dims(33, 3).grid_x, 2);
    CHECK_EQ(tiled_dims(33, 3).grid_y, 6);  // 366 rows
    CHECK_EQ(tiled_dims(8, 1).block_x, 8);
    CHECK_EQ(tiled_dims(8, 1).block_y, 16);
    // naive: blocks of up to 32 channels x 8 rows
    CHECK_EQ(naive_dims(100, 3).block_x, 32);
    CHECK_EQ(naive_dims(100, 3).block_y, 8);
    CHECK_EQ(naive_dims(100, 3).grid_x, 4);
    CHECK_EQ(naive_dims(100, 3).grid_y, 46);  // 366 rows
    CHECK_EQ(naive_dims(5, 1).block_x, 5);
    CHECK_EQ(naive_dims(5, 1).grid_x, 1);
    CHECK_EQ(policy_dims(7).grid_x, 4);  // 242 logits in blocks of 64
    CHECK_EQ(policy_dims(7).grid_y, 7);
    CHECK_EQ(value_dims(7).grid_x, 7);
    CHECK_EQ(value_dims(7).block_x, 256);
    // every output element has a thread, for sizes around the tile edges, and no block is larger than 1024 threads
    for (int cout : {1, 2, 5, 31, 32, 33, 63, 64, 65, 100, 1024}) {
        for (int batch : {1, 2, 3, 64, 67, 1024}) {
            const LaunchDims n = naive_dims(cout, batch);
            CHECK(n.grid_x * n.block_x >= cout);
            CHECK(n.grid_y * n.block_y >= batch * kRows);
            CHECK(n.block_x * n.block_y <= 1024);
            const LaunchDims t = tiled_dims(cout, batch);
            CHECK(t.grid_x * kTileN >= cout);
            CHECK(t.grid_y * kTileM >= batch * kRows);
            CHECK_EQ(t.block_x * t.block_y, kGroupSize);
        }
    }
}

// One convolution kernel over `batch` positions against the double-precision reference (hex_conv, then residual and ReLU).
void check_conv(k::ConvKind kind, int cin, int cout, int batch, bool residual, bool relu, std::uint64_t seed) {
    SplittableRandom rng(seed);
    const auto rows = static_cast<std::size_t>(batch) * nn::kRows;
    const auto in_ch = static_cast<std::size_t>(cin);
    const auto out_ch = static_cast<std::size_t>(cout);
    std::vector<float> in = uniform(rows * in_ch, 1.0f, rng);
    for (int b = 0; b < batch; ++b) {  // the padding row of every position is zero
        for (std::size_t ch = 0; ch < in_ch; ++ch) in[(static_cast<std::size_t>(b) * nn::kRows + nn::kPadding) * in_ch + ch] = 0.0f;
    }
    const std::vector<float> weight = uniform(out_ch * 7 * in_ch, 0.5f, rng);
    const std::vector<float> bias = uniform(out_ch, 0.5f, rng);
    const std::vector<float> res = residual ? uniform(rows * out_ch, 1.0f, rng) : std::vector<float>(1, 0.0f);
    const std::vector<int> gather(nn::gather_table().begin(), nn::gather_table().end());
    std::vector<float> out(rows * out_ch, std::nanf(""));  // an element that the kernel does not write stays NaN

    sh::emu::EmuLauncher launcher;
    launcher.gather = gather.data();
    launcher.conv(kind, in.data(), k::ConvLayer{weight.data(), bias.data()}, res.data(), out.data(), k::ConvArgs{batch, cin, cout, residual ? 1 : 0, relu ? 1 : 0}, "conv");

    std::vector<double> want(rows * out_ch);
    std::vector<float> position_out(static_cast<std::size_t>(nn::kRows) * out_ch);
    for (int b = 0; b < batch; ++b) {
        const std::size_t first = static_cast<std::size_t>(b) * nn::kRows;
        auto r = nn::hex_conv(std::span<const float>(in).subspan(first * in_ch, nn::kRows * in_ch), cin, weight, bias, cout, position_out);
        CHECK(r.has_value());
        for (std::size_t row = 0; row < static_cast<std::size_t>(nn::kRows); ++row) {
            for (std::size_t o = 0; o < out_ch; ++o) {
                double v = static_cast<double>(position_out[row * out_ch + o]);
                if (row != static_cast<std::size_t>(nn::kPadding)) {  // the padding row is written as 0 whatever the flags say
                    if (residual) v += static_cast<double>(res[(first + row) * out_ch + o]);
                    if (relu && v < 0) v = 0;
                }
                want[(first + row) * out_ch + o] = v;
            }
        }
    }
    const double worst = max_fraction(out, want);
    if (!(worst <= 1.0)) {
        std::cerr << "conv kind=" << (kind == k::ConvKind::Naive ? "naive" : "tiled") << " cin=" << cin << " cout=" << cout << " batch=" << batch
                  << " residual=" << residual << " relu=" << relu << ": largest error " << worst << " of the bound\n";
    }
    CHECK(worst <= 1.0);
}

SH_TEST(conv_kernels_against_the_reference) {
    // shapes of TINY, SMALL and LARGE (conv_in 13 planes, blocks C -> C) and sizes that are no multiple of a tile
    const std::vector<std::pair<int, int>> shapes = {{13, 8}, {8, 8}, {13, 32}, {32, 32}, {13, 64}, {64, 64}, {1, 1}, {5, 70}, {64, 31}, {9, 33}};
    for (const k::ConvKind kind : {k::ConvKind::Naive, k::ConvKind::Tiled}) {
        std::uint64_t seed = 100;
        for (const auto& [cin, cout] : shapes) {
            for (int batch : {1, 3}) check_conv(kind, cin, cout, batch, false, true, ++seed);
        }
        // every combination of the two flags on two shapes
        for (bool residual : {false, true}) {
            for (bool relu : {false, true}) {
                check_conv(kind, 9, 33, 2, residual, relu, ++seed);
                check_conv(kind, 32, 32, 1, residual, relu, ++seed);
            }
        }
        check_conv(kind, 5, 7, 67, true, true, ++seed);  // a batch that is no power of two
    }
}

SH_TEST(relu_keeps_nan) {
    // the NaN of one input element reaches every output that reads it; ReLU must not turn it into 0 (DeviceMath.relu)
    for (const k::ConvKind kind : {k::ConvKind::Naive, k::ConvKind::Tiled}) {
        const int cin = 3, cout = 4;
        SplittableRandom rng(77);
        std::vector<float> in = uniform(static_cast<std::size_t>(nn::kRows * cin), 1.0f, rng);
        for (int ch = 0; ch < cin; ++ch) in[static_cast<std::size_t>(nn::kPadding * cin + ch)] = 0.0f;
        in[5 * cin] = std::nanf("");  // cell 5, channel 0
        const std::vector<float> weight = uniform(static_cast<std::size_t>(cout * 7 * cin), 0.5f, rng);
        const std::vector<float> bias = uniform(cout, 0.5f, rng);
        const std::vector<float> res(1, 0.0f);
        const std::vector<int> gather(nn::gather_table().begin(), nn::gather_table().end());
        std::vector<float> out(static_cast<std::size_t>(nn::kRows * cout), 0.0f);
        sh::emu::EmuLauncher launcher;
        launcher.gather = gather.data();
        launcher.conv(kind, in.data(), k::ConvLayer{weight.data(), bias.data()}, res.data(), out.data(), k::ConvArgs{1, cin, cout, 0, 1}, "conv");
        int nan_cells = 0;
        for (int cell = 0; cell < nn::kCells; ++cell) {
            if (std::isnan(out[static_cast<std::size_t>(cell * cout)])) ++nan_cells;
        }
        CHECK(nan_cells >= 1);  // cell 5 itself and its neighbours
        CHECK(std::isnan(out[static_cast<std::size_t>(5 * cout)]));
    }
}

// Both heads on a trunk against a double-precision computation of the same formulas.
void check_heads(int channels, int hidden, int players, bool apply_tanh, int batch, std::uint64_t seed) {
    SplittableRandom rng(seed);
    const auto c = static_cast<std::size_t>(channels);
    const auto h = static_cast<std::size_t>(hidden);
    const auto p = static_cast<std::size_t>(players);
    const auto b = static_cast<std::size_t>(batch);
    std::vector<float> trunk = uniform(b * nn::kRows * c, 1.0f, rng);
    for (std::size_t pos = 0; pos < b; ++pos) {  // the padding row is not read: NaN there shows it if it is
        for (std::size_t ch = 0; ch < c; ++ch) trunk[(pos * nn::kRows + nn::kPadding) * c + ch] = std::nanf("");
    }
    const std::vector<float> pw = uniform(2 * c, 0.5f, rng), pb = uniform(2, 0.5f, rng);
    const std::vector<float> w1 = uniform(h * c, 0.5f, rng), b1 = uniform(h, 0.5f, rng);
    const std::vector<float> w2 = uniform(p * h, 0.5f, rng), b2 = uniform(p, 0.5f, rng);
    const std::size_t logit_floats = b * nn::kPolicySize;
    std::vector<float> out(logit_floats + b * p, std::nanf(""));
    const k::HeadArgs args{batch, channels, hidden, players, apply_tanh ? 1 : 0, static_cast<int>(logit_floats)};

    sh::emu::EmuLauncher launcher;
    launcher.policy(trunk.data(), pw.data(), pb.data(), out.data(), args, "policy_head");
    launcher.value(trunk.data(), w1.data(), b1.data(), w2.data(), b2.data(), out.data(), args, "value_head");

    std::vector<double> want_logits(logit_floats), want_values(b * p);
    for (std::size_t pos = 0; pos < b; ++pos) {
        for (std::size_t cell = 0; cell < static_cast<std::size_t>(nn::kCells); ++cell) {
            for (std::size_t kk = 0; kk < 2; ++kk) {
                double sum = static_cast<double>(pb[kk]);
                for (std::size_t ch = 0; ch < c; ++ch) sum += static_cast<double>(pw[kk * c + ch]) * static_cast<double>(trunk[(pos * nn::kRows + cell) * c + ch]);
                want_logits[pos * nn::kPolicySize + cell * 2 + kk] = sum;
            }
        }
        std::vector<double> pooled(c, 0.0);
        for (std::size_t ch = 0; ch < c; ++ch) {
            for (std::size_t cell = 0; cell < static_cast<std::size_t>(nn::kCells); ++cell) pooled[ch] += static_cast<double>(trunk[(pos * nn::kRows + cell) * c + ch]);
            pooled[ch] /= nn::kCells;
        }
        std::vector<double> act(h);
        for (std::size_t j = 0; j < h; ++j) {
            double sum = static_cast<double>(b1[j]);
            for (std::size_t ch = 0; ch < c; ++ch) sum += static_cast<double>(w1[j * c + ch]) * pooled[ch];
            act[j] = sum < 0 ? 0 : sum;
        }
        for (std::size_t q = 0; q < p; ++q) {
            double sum = static_cast<double>(b2[q]);
            for (std::size_t j = 0; j < h; ++j) sum += static_cast<double>(w2[q * h + j]) * act[j];
            want_values[pos * p + q] = apply_tanh ? std::tanh(sum) : sum;
        }
    }
    const std::vector<float> got_logits(out.begin(), out.begin() + static_cast<std::ptrdiff_t>(logit_floats));
    const std::vector<float> got_values(out.begin() + static_cast<std::ptrdiff_t>(logit_floats), out.end());
    const double wl = max_fraction(got_logits, want_logits);
    const double wv = max_fraction(got_values, want_values);
    if (!(wl <= 1.0 && wv <= 1.0)) {
        std::cerr << "heads C=" << channels << " H=" << hidden << " P=" << players << " tanh=" << apply_tanh << " batch=" << batch << ": logits " << wl
                  << " values " << wv << " of the bound\n";
    }
    CHECK(wl <= 1.0);
    CHECK(wv <= 1.0);
}

SH_TEST(head_kernels_against_the_reference) {
    std::uint64_t seed = 500;
    // C = 32, 64, 100, 300 as in the Metal tests: C < 256 shares a channel between 256 / C threads, C >= 256 does not
    for (int channels : {1, 8, 32, 64, 100, 300}) {
        check_heads(channels, 32, 2, false, 1, ++seed);
        check_heads(channels, 8, 3, true, 3, ++seed);
    }
    check_heads(64, 1024, 2, true, 2, ++seed);   // a hidden layer of the largest size
    check_heads(1024, 32, 2, true, 1, ++seed);   // the widest trunk
}

SH_TEST(value_head_saturates_exactly) {
    // dense2 biases of 44.3 and +-1000: tanhf(44.3) is exactly 1 only through the saturation of DeviceMath.tanh (|x| > 8.5)
    const int channels = 4, hidden = 2, players = 2;
    const std::vector<float> trunk(static_cast<std::size_t>(nn::kRows * channels), 0.25f);
    const std::vector<float> pw(8, 0.0f), pb(2, 0.0f), w1(8, 0.0f), b1(2, 0.0f), w2(4, 0.0f);
    const float inf = std::numeric_limits<float>::infinity();
    const std::vector<std::pair<std::vector<float>, std::vector<float>>> cases = {{{44.3f, 100.0f}, {1.0f, 1.0f}}, {{-1000.0f, inf}, {-1.0f, 1.0f}}};
    for (const auto& [biases, want] : cases) {
        std::vector<float> out(static_cast<std::size_t>(nn::kPolicySize + players), std::nanf(""));
        const k::HeadArgs args{1, channels, hidden, players, 1, nn::kPolicySize};
        sh::emu::EmuLauncher launcher;
        launcher.policy(trunk.data(), pw.data(), pb.data(), out.data(), args, "policy_head");
        launcher.value(trunk.data(), w1.data(), b1.data(), w2.data(), biases.data(), out.data(), args, "value_head");
        CHECK_EQ(out[nn::kPolicySize], want[0]);      // exactly, not within a tolerance
        CHECK_EQ(out[nn::kPolicySize + 1], want[1]);
    }
}

// ---- 4. the adapter with an emulated device net ----

struct Run {
    std::vector<float> logits, values, ref_logits, ref_values;
    nn::Comparison comparison;
};

// Runs the adapter on `batch` positions with the emulated net and the reference on the same input; `observe` sees the net.
Run run_backend(const nn::NetworkConfig& cfg, int batch, const CudaOptions& options, std::uint64_t seed, sh::emu::EmuNet*& net,
                std::optional<CudaBackend>& backend_out) {
    const nn::Parameters params = make_params(cfg, seed);
    const std::vector<float> input = make_inputs(cfg, batch, seed + 1000);
    Run run;
    run.ref_logits.resize(static_cast<std::size_t>(batch) * nn::kPolicySize);
    run.ref_values.resize(static_cast<std::size_t>(batch) * static_cast<std::size_t>(cfg.players));
    const nn::ReferenceNet reference(params);
    auto r = reference.forward(input, batch, run.ref_logits, run.ref_values);
    CHECK(r.has_value());
    auto backend = CudaBackend::create(params, options, [&net](const cuda::Shape& s, const cuda::Weights& w, const cuda::Options& o, std::string& error) {
        return sh::emu::EmuNet::create(s, w, o, error, &net);
    });
    CHECK(backend.has_value());
    if (!backend) return run;
    run.logits.assign(run.ref_logits.size(), std::nanf(""));
    run.values.assign(run.ref_values.size(), std::nanf(""));
    auto f = backend->forward(input, batch, run.logits, run.values);
    CHECK(f.has_value());
    if (!f) std::cerr << "forward: " << f.error().message << '\n';
    run.comparison = nn::compare_outputs(run.logits, run.values, run.ref_logits, run.ref_values, 0, batch, cfg.players, 0);
    backend_out.emplace(std::move(*backend));
    return run;
}

void expect_close(const Run& run, const std::string& what) {
    if (!(run.comparison.fraction() <= 1.0)) std::cerr << what << ": largest error " << run.comparison.fraction() << " of the bound\n";
    CHECK(run.comparison.fraction() <= 1.0);
}

SH_TEST(whole_network_tiny_every_variant) {
    const nn::NetworkConfig cfg = nn::tiny_config(2, nn::PlaneSet::Classic1);
    for (const std::optional<nn::ConvVariant> variant : {std::optional<nn::ConvVariant>(), std::optional<nn::ConvVariant>(nn::ConvVariant::Naive),
                                                          std::optional<nn::ConvVariant>(nn::ConvVariant::Tiled)}) {
        CudaOptions options;
        options.variant = variant;
        sh::emu::EmuNet* net = nullptr;
        std::optional<CudaBackend> backend;
        const Run run = run_backend(cfg, 5, options, 21, net, backend);
        expect_close(run, "tiny");
        CHECK(net != nullptr);
        if (net == nullptr) continue;
        CHECK_EQ(net->calls.size(), static_cast<std::size_t>(1));
        if (net->calls.empty()) continue;
        const cuda::Call& call = net->calls[0];
        CHECK_EQ(call.n, 5);
        CHECK_EQ(call.bucket, 8);
        CHECK(!call.apply_tanh);  // classic1: the host priors come before the tanh, so the device does not apply it
        // the rule: naive while bucket * C <= 128 (8 * 8 = 64), else tiled; a forced variant wins
        const cuda::Variant want = variant ? (*variant == nn::ConvVariant::Tiled ? cuda::Variant::Tiled : cuda::Variant::Naive) : cuda::Variant::Naive;
        CHECK(call.variant == want);
        // the launch order of TornadoNetwork: conv_in, per block conv1 and conv2, policy head, value head
        CHECK_EQ(net->launches.size(), static_cast<std::size_t>(1));
        const std::vector<std::string> order = {"conv_in", "block0.conv1", "block0.conv2", "policy_head", "value_head"};
        if (!net->launches.empty()) CHECK(net->launches[0] == order);
    }
}

SH_TEST(whole_network_plain_applies_tanh_on_the_device) {
    const nn::NetworkConfig cfg = nn::tiny_config(3, nn::PlaneSet::Plain);
    CudaOptions options;
    options.variant = nn::ConvVariant::Tiled;
    sh::emu::EmuNet* net = nullptr;
    std::optional<CudaBackend> backend;
    const Run run = run_backend(cfg, 2, options, 22, net, backend);
    expect_close(run, "plain");
    CHECK(net != nullptr);
    if (net != nullptr && !net->calls.empty()) CHECK(net->calls[0].apply_tanh);
    for (float v : run.values) CHECK(std::abs(v) <= 1.0f);
}

SH_TEST(whole_network_small_uses_tiled_above_the_naive_limit) {
    const nn::NetworkConfig cfg = nn::small_config(2, nn::PlaneSet::Classic1);
    sh::emu::EmuNet* net = nullptr;
    std::optional<CudaBackend> backend;
    const Run run = run_backend(cfg, 5, CudaOptions{}, 23, net, backend);  // bucket 8, 8 * 32 = 256 > 128
    expect_close(run, "small");
    CHECK(net != nullptr);
    if (net != nullptr && !net->calls.empty()) {
        CHECK_EQ(net->calls[0].bucket, 8);
        CHECK(net->calls[0].variant == cuda::Variant::Tiled);
    }
    CHECK(backend.has_value());
    if (backend) CHECK_EQ(backend->plans_built(), 1);
}

SH_TEST(whole_network_large_at_the_naive_limit) {
    const nn::NetworkConfig cfg = nn::large_config(2, nn::PlaneSet::Classic1);
    sh::emu::EmuNet* net = nullptr;
    std::optional<CudaBackend> backend;
    const Run run = run_backend(cfg, 2, CudaOptions{}, 24, net, backend);  // bucket 2, 2 * 64 = 128: naive
    expect_close(run, "large");
    CHECK(net != nullptr);
    if (net != nullptr && !net->calls.empty()) CHECK(net->calls[0].variant == cuda::Variant::Naive);
}

SH_TEST(batches_above_max_batch_are_split) {
    const nn::NetworkConfig cfg = nn::tiny_config(2, nn::PlaneSet::Classic1);
    // max_batch 4: 7 positions are chunks of 4 and 3; the 3 use the bucket 4, the bucket never exceeds max_batch
    {
        CudaOptions options;
        options.max_batch = 4;
        sh::emu::EmuNet* net = nullptr;
        std::optional<CudaBackend> backend;
        const Run run = run_backend(cfg, 7, options, 25, net, backend);
        expect_close(run, "split 4+3");
        CHECK(net != nullptr);
        if (net != nullptr) {
            CHECK_EQ(net->calls.size(), static_cast<std::size_t>(2));
            if (net->calls.size() == 2) {
                CHECK_EQ(net->calls[0].n, 4);
                CHECK_EQ(net->calls[0].bucket, 4);
                CHECK_EQ(net->calls[1].n, 3);
                CHECK_EQ(net->calls[1].bucket, 4);
            }
        }
        CHECK(backend.has_value());
        if (backend) {
            CHECK_EQ(backend->timings().calls, 2);
            CHECK_EQ(backend->plans_built(), 1);  // one bucket, used twice
        }
    }
    // max_batch 3 is no power of two: the bucket is capped at 3
    {
        CudaOptions options;
        options.max_batch = 3;
        sh::emu::EmuNet* net = nullptr;
        std::optional<CudaBackend> backend;
        const Run run = run_backend(cfg, 7, options, 26, net, backend);
        expect_close(run, "split 3+3+1");
        CHECK(net != nullptr);
        if (net != nullptr && net->calls.size() == 3) {
            CHECK_EQ(net->calls[0].bucket, 3);
            CHECK_EQ(net->calls[1].bucket, 3);
            CHECK_EQ(net->calls[2].n, 1);
            CHECK_EQ(net->calls[2].bucket, 1);
        } else {
            CHECK(false);
        }
    }
}

SH_TEST(a_position_gives_the_same_bits_in_every_slot_and_bucket) {
    // the property lockstep self-play relies on (as in the Metal tests): one position, alone and inside other batches
    const nn::NetworkConfig cfg = nn::tiny_config(2, nn::PlaneSet::Classic1);
    const nn::Parameters params = make_params(cfg, 27);
    const std::vector<float> input = make_inputs(cfg, 5, 28);
    sh::emu::EmuNet* net = nullptr;
    auto backend = CudaBackend::create(params, {}, [&net](const cuda::Shape& s, const cuda::Weights& w, const cuda::Options& o, std::string& e) {
        return sh::emu::EmuNet::create(s, w, o, e, &net);
    });
    CHECK(backend.has_value());
    if (!backend) return;
    const auto size = static_cast<std::size_t>(cfg.input_size());
    std::vector<float> logits(5 * nn::kPolicySize), values(5 * 2);
    CHECK(backend->forward(input, 5, logits, values).has_value());
    for (int pos = 0; pos < 5; ++pos) {
        std::vector<float> one_logits(nn::kPolicySize), one_values(2);
        const auto one = std::span<const float>(input).subspan(static_cast<std::size_t>(pos) * size, size);
        CHECK(backend->forward(one, 1, one_logits, one_values).has_value());
        for (std::size_t i = 0; i < one_logits.size(); ++i) {
            CHECK(std::bit_cast<std::uint32_t>(one_logits[i]) == std::bit_cast<std::uint32_t>(logits[static_cast<std::size_t>(pos) * nn::kPolicySize + i]));
        }
        for (std::size_t i = 0; i < 2; ++i) {
            CHECK(std::bit_cast<std::uint32_t>(one_values[i]) == std::bit_cast<std::uint32_t>(values[static_cast<std::size_t>(pos) * 2 + i]));
        }
    }
}

SH_TEST(forward_refuses_bad_arguments) {
    const nn::NetworkConfig cfg = nn::tiny_config(2, nn::PlaneSet::Classic1);
    const nn::Parameters params = make_params(cfg, 29);
    auto backend = CudaBackend::create(params, {}, [](const cuda::Shape& s, const cuda::Weights& w, const cuda::Options& o, std::string& e) {
        return sh::emu::EmuNet::create(s, w, o, e);
    });
    CHECK(backend.has_value());
    if (!backend) return;
    std::vector<float> input = make_inputs(cfg, 2, 30);
    std::vector<float> logits(2 * nn::kPolicySize), values(2 * 2);
    CHECK(backend->forward(input, 2, logits, values).has_value());
    CHECK(!backend->forward(input, 0, logits, values).has_value());                                   // batch < 1
    CHECK(!backend->forward(std::span<const float>(input).first(input.size() - 1), 2, logits, values).has_value());  // input too short
    CHECK(!backend->forward(input, 2, std::span<float>(logits).first(logits.size() - 1), values).has_value());
    CHECK(!backend->forward(input, 2, logits, std::span<float>(values).first(values.size() - 1)).has_value());
    // a dirty padding row in the second position refuses the whole call and leaves the outputs alone
    const auto planes = static_cast<std::size_t>(cfg.input_planes());
    input[static_cast<std::size_t>(cfg.input_size()) + static_cast<std::size_t>(nn::kPadding) * planes + 6] = 0.5f;
    std::fill(logits.begin(), logits.end(), 7.0f);
    auto dirty = backend->forward(input, 2, logits, values);
    CHECK(!dirty.has_value());
    if (!dirty) CHECK(dirty.error().message.find("padding row") != std::string::npos);
    CHECK_EQ(logits[0], 7.0f);
}

SH_TEST(timings_and_kernel_times_add_up) {
    const nn::NetworkConfig cfg = nn::tiny_config(2, nn::PlaneSet::Classic1);
    CudaOptions options;
    options.max_batch = 2;
    options.instrumented = true;
    sh::emu::EmuNet* net = nullptr;
    std::optional<CudaBackend> backend;
    const Run run = run_backend(cfg, 5, options, 31, net, backend);  // chunks of 2, 2 and 1
    expect_close(run, "instrumented");
    CHECK(backend.has_value());
    if (!backend) return;
    CHECK_EQ(backend->timings().calls, 3);
    CHECK(backend->timings().priors_ms >= 0);
    // the kernels of the last forward() are listed once each, summed over the chunks
    const std::vector<std::string> order = {"conv_in", "block0.conv1", "block0.conv2", "policy_head", "value_head"};
    CHECK_EQ(backend->kernel_times().size(), order.size());
    for (std::size_t i = 0; i < order.size() && i < backend->kernel_times().size(); ++i) CHECK_EQ(backend->kernel_times()[i].name, order[i]);
    backend->reset_timings();
    CHECK_EQ(backend->timings().calls, 0);
}

SH_TEST(create_refuses_what_the_device_layer_refuses) {
    const nn::Parameters params = make_params(nn::tiny_config(2, nn::PlaneSet::Classic1), 32);
    auto failing = CudaBackend::create(params, {}, [](const cuda::Shape&, const cuda::Weights&, const cuda::Options&, std::string& e) -> std::unique_ptr<cuda::CudaNet> {
        e = "out of memory";
        return nullptr;
    });
    CHECK(!failing.has_value());
    if (!failing) CHECK_EQ(failing.error().message, "out of memory");
    // a factory that fails without a message still gives a message
    auto silent = CudaBackend::create(params, {}, [](const cuda::Shape&, const cuda::Weights&, const cuda::Options&, std::string&) -> std::unique_ptr<cuda::CudaNet> {
        return nullptr;
    });
    CHECK(!silent.has_value());
    if (!silent) CHECK(!silent.error().message.empty());
}

}  // namespace

int main() { return sh::test::run_all(); }
