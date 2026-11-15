// Objective-C++ part of the Metal backend, compiled with -fobjc-arc (WP11a: naive convolution, heads, buckets, copy mode).
// Unsafe zones of this file (docs/cpp-comparison-plan.md): [buffer contents] is wrapped at once into a std::span (as_floats),
// and Objective-C objects are handled by ARC; the public header metal_net.hpp contains no Objective-C type.
#include "metal_net.hpp"

#import <Foundation/Foundation.h>
#import <Metal/Metal.h>

#include <algorithm>
#include <array>
#include <climits>
#include <cstdint>
#include <map>
#include <string>
#include <vector>

#include "gather.hpp"
#include "priors.hpp"

namespace {
constexpr std::string_view kKernelSource =
#include "kernels_msl.inc"
    ;
}  // namespace

namespace sh::metal {
namespace {

using nn::Error;

std::unexpected<Error> fail(std::string message) { return std::unexpected(Error{std::move(message)}); }

std::string text_of(NSString* s) { return s == nil ? std::string() : std::string([s UTF8String]); }

// The kernel argument structs mirror ConvArgs and HeadArgs of kernels.metal.
struct ConvArgs {
    int batch, in_channels, out_channels, residual, relu;
};
struct HeadArgs {
    int batch, channels, hidden, players, apply_tanh, out_offset;
};
static_assert(sizeof(ConvArgs) == 5 * sizeof(int) && sizeof(HeadArgs) == 6 * sizeof(int));

constexpr NSUInteger kValueGroup = 256;  // OpsKernels.VALUE_GROUP

// ---- the one place that touches [buffer contents] ----
#pragma clang unsafe_buffer_usage begin
std::span<float> as_floats(id<MTLBuffer> buffer) {
    return std::span<float>(static_cast<float*>([buffer contents]), [buffer length] / sizeof(float));
}
#pragma clang unsafe_buffer_usage end

// ---- device, queue, library (compiled once per process) and pipelines ----

struct Ctx {
    id<MTLDevice> device = nil;
    id<MTLCommandQueue> queue = nil;
    id<MTLLibrary> library = nil;
    std::map<std::string, id<MTLComputePipelineState>> pipelines;
};

struct ContextState {
    Ctx ctx;
    std::string error;
};

ContextState make_context() {
    ContextState s;
    s.ctx.device = MTLCreateSystemDefaultDevice();
    if (s.ctx.device == nil) {
        s.error = "no Metal device";
        return s;
    }
    s.ctx.queue = [s.ctx.device newCommandQueue];
    MTLCompileOptions* options = [[MTLCompileOptions alloc] init];
    // No fast math, like tornado.metal.fastmath=False. fastMathEnabled defaults to YES, so it has to be set.
    if (@available(macOS 15.0, *)) {
        options.mathMode = MTLMathModeSafe;
    } else {
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wdeprecated-declarations"
        options.fastMathEnabled = NO;
#pragma clang diagnostic pop
    }
    NSString* source = [[NSString alloc] initWithBytes:kKernelSource.data() length:kKernelSource.size() encoding:NSUTF8StringEncoding];
    NSError* error = nil;
    s.ctx.library = [s.ctx.device newLibraryWithSource:source options:options error:&error];
    if (s.ctx.library == nil) s.error = "Metal library compilation failed: " + text_of([error localizedDescription]);
    return s;
}

std::expected<Ctx*, Error> context() {
    static ContextState state = make_context();
    if (!state.error.empty()) return fail(state.error);
    return &state.ctx;
}

std::expected<id<MTLComputePipelineState>, Error> pipeline(Ctx& ctx, const std::string& name) {
    if (const auto it = ctx.pipelines.find(name); it != ctx.pipelines.end()) return it->second;
    id<MTLFunction> function = [ctx.library newFunctionWithName:[NSString stringWithUTF8String:name.c_str()]];
    if (function == nil) return fail("Metal function not found: " + name);
    NSError* error = nil;
    id<MTLComputePipelineState> state = [ctx.device newComputePipelineStateWithFunction:function error:&error];
    if (state == nil) return fail("Metal pipeline " + name + ": " + text_of([error localizedDescription]));
    if (name == "value_head" && [state maxTotalThreadsPerThreadgroup] < kValueGroup) return fail("value_head needs thread groups of 256");
    ctx.pipelines[name] = state;
    return state;
}

// ---- buffers ----

id<MTLBuffer> shared_buffer(Ctx& ctx, std::size_t floats) {
    return [ctx.device newBufferWithLength:std::max<std::size_t>(floats, 1) * sizeof(float) options:MTLResourceStorageModeShared];
}
id<MTLBuffer> shared_copy(Ctx& ctx, std::span<const float> data) {
    return [ctx.device newBufferWithBytes:data.data() length:std::max<std::size_t>(data.size_bytes(), sizeof(float)) options:MTLResourceStorageModeShared];
}
id<MTLBuffer> private_buffer(Ctx& ctx, std::size_t bytes) {
    return [ctx.device newBufferWithLength:std::max<std::size_t>(bytes, sizeof(float)) options:MTLResourceStorageModePrivate];
}

// ---- encoding ----

void encode_conv(id<MTLComputeCommandEncoder> enc, id<MTLComputePipelineState> state, id<MTLBuffer> in, id<MTLBuffer> gather, id<MTLBuffer> weight,
                 id<MTLBuffer> bias, id<MTLBuffer> res, id<MTLBuffer> out, const ConvArgs& a) {
    [enc setComputePipelineState:state];
    [enc setBuffer:in offset:0 atIndex:0];
    [enc setBuffer:gather offset:0 atIndex:1];
    [enc setBuffer:weight offset:0 atIndex:2];
    [enc setBuffer:bias offset:0 atIndex:3];
    [enc setBuffer:res offset:0 atIndex:4];
    [enc setBuffer:out offset:0 atIndex:5];
    [enc setBytes:&a length:sizeof(ConvArgs) atIndex:6];
    const auto tx = std::min<NSUInteger>(32, static_cast<NSUInteger>(a.out_channels));
    const auto ty = std::max<NSUInteger>(1, std::min<NSUInteger>(8, [state maxTotalThreadsPerThreadgroup] / tx));
    [enc dispatchThreads:MTLSizeMake(static_cast<NSUInteger>(a.out_channels), static_cast<NSUInteger>(a.batch) * nn::kRows, 1)
        threadsPerThreadgroup:MTLSizeMake(tx, ty, 1)];
}

void encode_policy(id<MTLComputeCommandEncoder> enc, id<MTLComputePipelineState> state, id<MTLBuffer> trunk, id<MTLBuffer> weight, id<MTLBuffer> bias,
                   id<MTLBuffer> out, const HeadArgs& a) {
    [enc setComputePipelineState:state];
    [enc setBuffer:trunk offset:0 atIndex:0];
    [enc setBuffer:weight offset:0 atIndex:1];
    [enc setBuffer:bias offset:0 atIndex:2];
    [enc setBuffer:out offset:0 atIndex:3];
    [enc setBytes:&a length:sizeof(HeadArgs) atIndex:4];
    [enc dispatchThreads:MTLSizeMake(nn::kPolicySize, static_cast<NSUInteger>(a.batch), 1) threadsPerThreadgroup:MTLSizeMake(64, 1, 1)];
}

void encode_value(id<MTLComputeCommandEncoder> enc, id<MTLComputePipelineState> state, id<MTLBuffer> trunk, id<MTLBuffer> w1, id<MTLBuffer> b1,
                  id<MTLBuffer> w2, id<MTLBuffer> b2, id<MTLBuffer> out, const HeadArgs& a) {
    [enc setComputePipelineState:state];
    [enc setBuffer:trunk offset:0 atIndex:0];
    [enc setBuffer:w1 offset:0 atIndex:1];
    [enc setBuffer:b1 offset:0 atIndex:2];
    [enc setBuffer:w2 offset:0 atIndex:3];
    [enc setBuffer:b2 offset:0 atIndex:4];
    [enc setBuffer:out offset:0 atIndex:5];
    [enc setBytes:&a length:sizeof(HeadArgs) atIndex:6];
    [enc dispatchThreadgroups:MTLSizeMake(static_cast<NSUInteger>(a.batch), 1, 1) threadsPerThreadgroup:MTLSizeMake(kValueGroup, 1, 1)];
}

std::expected<void, Error> finish(id<MTLCommandBuffer> cb) {
    [cb commit];
    [cb waitUntilCompleted];
    if ([cb status] != MTLCommandBufferStatusCompleted) return fail("Metal command buffer failed: " + text_of([[cb error] localizedDescription]));
    return {};
}

std::expected<void, Error> need(const char* name, std::size_t have, std::size_t want) {
    if (have < want) return fail(std::string(name) + " needs " + std::to_string(want) + " floats, has " + std::to_string(have));
    return {};
}

void read_back(id<MTLBuffer> buffer, std::span<float> out) {
    const std::span<float> data = as_floats(buffer);
    std::copy_n(data.begin(), out.size(), out.begin());
}

void write_to(id<MTLBuffer> buffer, std::span<const float> in) {
    const std::span<float> data = as_floats(buffer);
    std::copy_n(in.begin(), in.size(), data.begin());
}

struct Plan {
    int batch = 0;
    id<MTLBuffer> in_stage = nil;   // shared: the host writes the positions here
    id<MTLBuffer> out_stage = nil;  // shared: the host reads the head buffer here
    id<MTLBuffer> input = nil;      // private
    id<MTLBuffer> x = nil, t = nil, u = nil;
    id<MTLBuffer> out = nil;
};

struct ConvWeights {
    id<MTLBuffer> weight = nil, bias = nil;
};

}  // namespace

// ---- MetalNet ----

struct MetalNet::Impl {
    Ctx* ctx = nullptr;
    nn::NetworkConfig config;
    Options options;
    bool priors = false;
    float beta = 0, k = 0;
    id<MTLBuffer> gather = nil;
    id<MTLBuffer> no_residual = nil;  // read by the convolutions without residual instead of a residual; never read
    ConvWeights conv_in;
    std::vector<std::array<ConvWeights, 2>> blocks;
    id<MTLBuffer> policy_w = nil, policy_b = nil, dense1_w = nil, dense1_b = nil, dense2_w = nil, dense2_b = nil;
    id<MTLComputePipelineState> conv = nil, policy = nil, value = nil;
    std::vector<std::unique_ptr<Plan>> plans;  // index bucket_slot(bucket)
    int plans_built = 0;

    Plan& plan(int bucket) {
        const auto slot = static_cast<std::size_t>(nn::bucket_slot(bucket));
        if (plans[slot] == nullptr) plans[slot] = make_plan(bucket);
        return *plans[slot];
    }

    std::unique_ptr<Plan> make_plan(int bucket) {
        auto p = std::make_unique<Plan>();
        p->batch = bucket;
        const auto b = static_cast<std::size_t>(bucket);
        const std::size_t input_floats = b * static_cast<std::size_t>(config.input_size());
        const std::size_t activation = b * static_cast<std::size_t>(nn::kRows) * static_cast<std::size_t>(config.channels);
        const std::size_t out_floats = b * static_cast<std::size_t>(nn::kPolicySize + config.players);
        p->in_stage = shared_buffer(*ctx, input_floats);
        p->out_stage = shared_buffer(*ctx, out_floats);
        p->input = private_buffer(*ctx, input_floats * sizeof(float));
        p->x = private_buffer(*ctx, activation * sizeof(float));
        p->t = private_buffer(*ctx, activation * sizeof(float));
        p->u = private_buffer(*ctx, activation * sizeof(float));
        p->out = private_buffer(*ctx, out_floats * sizeof(float));
        // The slots of a bucket that a call does not fill keep zeros (their outputs are not read).
        id<MTLCommandBuffer> cb = [ctx->queue commandBuffer];
        id<MTLBlitCommandEncoder> blit = [cb blitCommandEncoder];
        [blit fillBuffer:p->input range:NSMakeRange(0, [p->input length]) value:0];
        [blit endEncoding];
        (void)finish(cb);
        ++plans_built;
        return p;
    }

    std::expected<void, Error> run_chunk(Plan& p, std::span<const float> input, int n, std::span<float> logits, std::span<float> values) {
        const int c = config.channels;
        const int players = config.players;
        const std::size_t in_floats = static_cast<std::size_t>(n) * static_cast<std::size_t>(config.input_size());
        write_to(p.in_stage, input.first(in_floats));
        id<MTLCommandBuffer> cb = [ctx->queue commandBuffer];
        id<MTLBlitCommandEncoder> up = [cb blitCommandEncoder];
        [up copyFromBuffer:p.in_stage sourceOffset:0 toBuffer:p.input destinationOffset:0 size:in_floats * sizeof(float)];
        [up endEncoding];
        id<MTLComputeCommandEncoder> enc = [cb computeCommandEncoder];
        const int batch = p.batch;
        encode_conv(enc, conv, p.input, gather, conv_in.weight, conv_in.bias, no_residual, p.x, {batch, config.input_planes(), c, 0, 1});
        id<MTLBuffer> x = p.x;
        id<MTLBuffer> t = p.t;
        id<MTLBuffer> u = p.u;
        for (const auto& block : blocks) {  // x is the block input; conv1 writes t, conv2 reads t and adds x into u; then u is the next x
            encode_conv(enc, conv, x, gather, block[0].weight, block[0].bias, no_residual, t, {batch, c, c, 0, 1});
            encode_conv(enc, conv, t, gather, block[1].weight, block[1].bias, x, u, {batch, c, c, 1, 1});
            std::swap(x, u);
        }
        const HeadArgs heads{batch, c, config.value_hidden, players, priors ? 0 : 1, batch * nn::kPolicySize};
        encode_policy(enc, policy, x, policy_w, policy_b, p.out, heads);
        encode_value(enc, value, x, dense1_w, dense1_b, dense2_w, dense2_b, p.out, heads);
        [enc endEncoding];
        const std::size_t out_floats = static_cast<std::size_t>(batch) * static_cast<std::size_t>(nn::kPolicySize + players);
        id<MTLBlitCommandEncoder> down = [cb blitCommandEncoder];
        [down copyFromBuffer:p.out sourceOffset:0 toBuffer:p.out_stage destinationOffset:0 size:out_floats * sizeof(float)];
        [down endEncoding];
        if (auto r = finish(cb); !r) return r;
        const std::span<float> stage = as_floats(p.out_stage);
        const auto nl = static_cast<std::size_t>(n) * static_cast<std::size_t>(nn::kPolicySize);
        const auto nv = static_cast<std::size_t>(n) * static_cast<std::size_t>(players);
        std::copy_n(stage.begin(), nl, logits.begin());
        std::copy_n(stage.begin() + static_cast<std::ptrdiff_t>(static_cast<std::size_t>(batch) * static_cast<std::size_t>(nn::kPolicySize)), nv, values.begin());
        return {};
    }

    std::expected<void, Error> forward(std::span<const float> input, int batch, std::span<float> logits, std::span<float> values) {
        if (batch < 1) return fail("batch must be >= 1, got " + std::to_string(batch));
        const auto b = static_cast<std::size_t>(batch);
        const auto in_size = static_cast<std::size_t>(config.input_size());
        const auto planes = static_cast<std::size_t>(config.input_planes());
        const auto players = static_cast<std::size_t>(config.players);
        if (auto r = need("input", input.size(), b * in_size); !r) return r;
        if (auto r = need("logits", logits.size(), b * static_cast<std::size_t>(nn::kPolicySize)); !r) return r;
        if (auto r = need("values", values.size(), b * players); !r) return r;
        // Every position is checked before anything runs, as the reference refuses a dirty padding row too.
        for (std::size_t pos = 0; pos < b; ++pos) {
            const std::span<const float> pad = input.subspan(pos * in_size + static_cast<std::size_t>(nn::kPadding) * planes, planes);
            for (std::size_t plane = 0; plane < planes; ++plane) {
                if (pad[plane] != 0) return fail("padding row of input " + std::to_string(pos) + " is not zero in plane " + std::to_string(plane));
            }
        }
        for (const nn::Chunk& chunk : nn::chunks_of(batch, options.max_batch)) {
            const auto first = static_cast<std::size_t>(chunk.first);
            const auto n = static_cast<std::size_t>(chunk.n);
            const auto in = input.subspan(first * in_size, n * in_size);
            const auto lo = logits.subspan(first * static_cast<std::size_t>(nn::kPolicySize), n * static_cast<std::size_t>(nn::kPolicySize));
            const auto va = values.subspan(first * players, n * players);
            if (auto r = run_chunk(plan(chunk.bucket), in, chunk.n, lo, va); !r) return r;
            if (priors) nn::apply_host_priors(beta, k, in, config.input_planes(), config.players, chunk.n, lo, va);
        }
        return {};
    }
};

std::expected<MetalNet, Error> MetalNet::create(const nn::Parameters& parameters, const Options& options) {
    @autoreleasepool {
        if (options.variant != nn::ConvVariant::Naive) return fail("the Metal backend has only the naive convolution so far (WP11b adds tiled and simdgroup)");
        if (options.memory != MemoryMode::Copy) return fail("the Metal backend has only the copy memory mode so far (WP11b adds shared)");
        if (options.max_batch < 1) return fail("max_batch must be >= 1, got " + std::to_string(options.max_batch));
        const nn::NetworkConfig& cfg = parameters.config();
        if (static_cast<long long>(options.max_batch) * nn::kRows * cfg.channels > INT_MAX) {
            return fail("max_batch " + std::to_string(options.max_batch) + " is too large for " + std::to_string(cfg.channels) + " channels");
        }
        auto ctx = context();
        if (!ctx) return std::unexpected(ctx.error());
        auto impl = std::make_unique<Impl>();
        impl->ctx = *ctx;
        impl->config = cfg;
        impl->options = options;
        impl->priors = nn::has_residual_priors(cfg.planes);
        if (impl->priors) {
            impl->beta = parameters.data(nn::kPriorBeta)[0];
            impl->k = parameters.data(nn::kPriorK)[0];
        }
        for (auto [field, name] : {std::pair{&impl->conv, "conv_naive"}, std::pair{&impl->policy, "policy_head"}, std::pair{&impl->value, "value_head"}}) {
            auto p = pipeline(**ctx, name);
            if (!p) return std::unexpected(p.error());
            *field = *p;
        }
        // Parameters and the gather table: copied once into private buffers, through shared staging buffers and one blit pass.
        std::vector<id<MTLBuffer>> staging;
        id<MTLCommandBuffer> cb = [(*ctx)->queue commandBuffer];
        id<MTLBlitCommandEncoder> blit = [cb blitCommandEncoder];
        const auto upload = [&](std::span<const float> data) {
            id<MTLBuffer> stage = shared_copy(**ctx, data);
            id<MTLBuffer> device = private_buffer(**ctx, data.size_bytes());
            [blit copyFromBuffer:stage sourceOffset:0 toBuffer:device destinationOffset:0 size:data.size_bytes()];
            staging.push_back(stage);
            return device;
        };
        const auto conv_weights = [&](const std::string& prefix) { return ConvWeights{upload(parameters.data(prefix + ".weight")), upload(parameters.data(prefix + ".bias"))}; };
        {
            const nn::GatherTable& table = nn::gather_table();
            id<MTLBuffer> stage = [(*ctx)->device newBufferWithBytes:table.data() length:sizeof(table) options:MTLResourceStorageModeShared];
            impl->gather = private_buffer(**ctx, sizeof(table));
            [blit copyFromBuffer:stage sourceOffset:0 toBuffer:impl->gather destinationOffset:0 size:sizeof(table)];
            staging.push_back(stage);
        }
        const std::array<float, 1> zero{0.0f};
        impl->no_residual = upload(zero);
        impl->conv_in = conv_weights("conv_in");
        for (int i = 0; i < cfg.blocks; ++i) {
            const std::string block = "block" + std::to_string(i);
            impl->blocks.push_back({conv_weights(block + ".conv1"), conv_weights(block + ".conv2")});
        }
        impl->policy_w = upload(parameters.data("head_policy.weight"));
        impl->policy_b = upload(parameters.data("head_policy.bias"));
        impl->dense1_w = upload(parameters.data("head_value.dense1.weight"));
        impl->dense1_b = upload(parameters.data("head_value.dense1.bias"));
        impl->dense2_w = upload(parameters.data("head_value.dense2.weight"));
        impl->dense2_b = upload(parameters.data("head_value.dense2.bias"));
        [blit endEncoding];
        if (auto r = finish(cb); !r) return std::unexpected(r.error());
        impl->plans.resize(static_cast<std::size_t>(nn::bucket_slot(options.max_batch)) + 1);
        return MetalNet(std::move(impl));
    }
}

MetalNet::MetalNet(std::unique_ptr<Impl> impl) : impl_(std::move(impl)) {}
MetalNet::MetalNet(MetalNet&&) noexcept = default;
MetalNet& MetalNet::operator=(MetalNet&&) noexcept = default;
MetalNet::~MetalNet() = default;

const nn::NetworkConfig& MetalNet::config() const { return impl_->config; }
const Options& MetalNet::options() const { return impl_->options; }
int MetalNet::plans_built() const { return impl_->plans_built; }

std::expected<void, Error> MetalNet::forward(std::span<const float> input, int batch, std::span<float> logits, std::span<float> values) {
    @autoreleasepool {
        return impl_->forward(input, batch, logits, values);
    }
}

// ---- single kernels for the tests ----

std::expected<void, Error> run_conv_naive(std::span<const float> in, std::span<const float> weight, std::span<const float> bias, std::span<const float> residual,
                                          std::span<float> out, int batch, int in_channels, int out_channels, bool use_residual, bool relu) {
    @autoreleasepool {
        if (batch < 1 || in_channels < 1 || out_channels < 1) return fail("run_conv_naive: sizes must be >= 1");
        const auto rows = static_cast<std::size_t>(batch) * nn::kRows;
        const auto cin = static_cast<std::size_t>(in_channels);
        const auto cout = static_cast<std::size_t>(out_channels);
        if (auto r = need("in", in.size(), rows * cin); !r) return r;
        if (auto r = need("weight", weight.size(), cout * nn::kStencil * cin); !r) return r;
        if (auto r = need("bias", bias.size(), cout); !r) return r;
        if (auto r = need("out", out.size(), rows * cout); !r) return r;
        if (use_residual) {
            if (auto r = need("residual", residual.size(), rows * cout); !r) return r;
        }
        auto ctx = context();
        if (!ctx) return std::unexpected(ctx.error());
        auto state = pipeline(**ctx, "conv_naive");
        if (!state) return std::unexpected(state.error());
        const nn::GatherTable& table = nn::gather_table();
        id<MTLBuffer> gather = [(*ctx)->device newBufferWithBytes:table.data() length:sizeof(table) options:MTLResourceStorageModeShared];
        id<MTLBuffer> out_buffer = shared_copy(**ctx, out.first(rows * cout));
        id<MTLCommandBuffer> cb = [(*ctx)->queue commandBuffer];
        id<MTLComputeCommandEncoder> enc = [cb computeCommandEncoder];
        encode_conv(enc, *state, shared_copy(**ctx, in.first(rows * cin)), gather, shared_copy(**ctx, weight.first(cout * nn::kStencil * cin)),
                    shared_copy(**ctx, bias.first(cout)), use_residual ? shared_copy(**ctx, residual.first(rows * cout)) : shared_buffer(**ctx, 1), out_buffer,
                    {batch, in_channels, out_channels, use_residual ? 1 : 0, relu ? 1 : 0});
        [enc endEncoding];
        if (auto r = finish(cb); !r) return r;
        read_back(out_buffer, out.first(rows * cout));
        return {};
    }
}

std::expected<void, Error> run_heads(std::span<const float> trunk, const HeadWeights& w, int batch, int channels, int hidden, int players, bool apply_tanh,
                                     std::span<float> logits, std::span<float> values) {
    @autoreleasepool {
        if (batch < 1 || channels < 1 || hidden < 1 || players < 1) return fail("run_heads: sizes must be >= 1");
        if (channels > 1024 || hidden > 1024) return fail("run_heads: channels and hidden are at most 1024");
        const auto b = static_cast<std::size_t>(batch);
        const auto c = static_cast<std::size_t>(channels);
        const auto h = static_cast<std::size_t>(hidden);
        const auto p = static_cast<std::size_t>(players);
        if (auto r = need("trunk", trunk.size(), b * nn::kRows * c); !r) return r;
        if (auto r = need("policy_weight", w.policy_weight.size(), 2 * c); !r) return r;
        if (auto r = need("policy_bias", w.policy_bias.size(), 2); !r) return r;
        if (auto r = need("dense1_weight", w.dense1_weight.size(), h * c); !r) return r;
        if (auto r = need("dense1_bias", w.dense1_bias.size(), h); !r) return r;
        if (auto r = need("dense2_weight", w.dense2_weight.size(), p * h); !r) return r;
        if (auto r = need("dense2_bias", w.dense2_bias.size(), p); !r) return r;
        if (auto r = need("logits", logits.size(), b * nn::kPolicySize); !r) return r;
        if (auto r = need("values", values.size(), b * p); !r) return r;
        auto ctx = context();
        if (!ctx) return std::unexpected(ctx.error());
        auto policy = pipeline(**ctx, "policy_head");
        auto value = pipeline(**ctx, "value_head");
        if (!policy) return std::unexpected(policy.error());
        if (!value) return std::unexpected(value.error());
        // One output buffer as in TornadoNetwork: the logits [batch][121][2], then the values [batch][players].
        const std::size_t logit_floats = b * nn::kPolicySize;
        id<MTLBuffer> out = shared_buffer(**ctx, logit_floats + b * p);
        write_to(out, logits.first(logit_floats));
        const std::span<float> tail = as_floats(out).subspan(logit_floats);
        std::copy_n(values.begin(), b * p, tail.begin());
        id<MTLBuffer> trunk_buffer = shared_copy(**ctx, trunk.first(b * nn::kRows * c));
        const HeadArgs a{batch, channels, hidden, players, apply_tanh ? 1 : 0, static_cast<int>(logit_floats)};
        id<MTLCommandBuffer> cb = [(*ctx)->queue commandBuffer];
        id<MTLComputeCommandEncoder> enc = [cb computeCommandEncoder];
        encode_policy(enc, *policy, trunk_buffer, shared_copy(**ctx, w.policy_weight.first(2 * c)), shared_copy(**ctx, w.policy_bias.first(2)), out, a);
        encode_value(enc, *value, trunk_buffer, shared_copy(**ctx, w.dense1_weight.first(h * c)), shared_copy(**ctx, w.dense1_bias.first(h)),
                     shared_copy(**ctx, w.dense2_weight.first(p * h)), shared_copy(**ctx, w.dense2_bias.first(p)), out, a);
        [enc endEncoding];
        if (auto r = finish(cb); !r) return r;
        const std::span<float> data = as_floats(out);
        std::copy_n(data.begin(), logit_floats, logits.begin());
        std::copy_n(data.begin() + static_cast<std::ptrdiff_t>(logit_floats), b * p, values.begin());
        return {};
    }
}

bool device_available() {
    @autoreleasepool {
        id<MTLDevice> device = MTLCreateSystemDefaultDevice();
        return device != nil;
    }
}

std::string device_name() {
    @autoreleasepool {
        id<MTLDevice> device = MTLCreateSystemDefaultDevice();
        return device == nil ? std::string() : text_of([device name]);
    }
}

std::string_view kernel_source() { return kKernelSource; }

}  // namespace sh::metal
