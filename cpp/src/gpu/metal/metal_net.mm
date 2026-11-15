// Objective-C++ part of the Metal backend, compiled with -fobjc-arc (WP11a: naive convolution, heads, buckets, copy mode; WP11b: tiled and simdgroup, shared mode, phase timing).
// Unsafe zones of this file (docs/cpp-comparison-plan.md): [buffer contents] is wrapped at once into a std::span (as_floats),
// and Objective-C objects are handled by ARC; the public header metal_net.hpp contains no Objective-C type.
#include "metal_net.hpp"

#import <Foundation/Foundation.h>
#import <Metal/Metal.h>

#include <algorithm>
#include <array>
#include <chrono>
#include <climits>
#include <cstddef>
#include <cstdint>
#include <limits>
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
std::size_t g_buffer_limit = std::numeric_limits<std::size_t>::max();
}
void set_buffer_limit_for_test(std::size_t bytes) { g_buffer_limit = bytes; }

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
constexpr NSUInteger kTileN = 32, kTileM = 64;  // KernelsHexConv.TILE_N, TILE_M
constexpr NSUInteger kGroupX = 8, kGroupY = 16;  // GROUP_X, GROUP_Y
constexpr NSUInteger kSgTile = 32, kSgGroupSize = 128;  // SG_TILE, SG_GROUP_SIZE

using Clock = std::chrono::steady_clock;
double ms_since(Clock::time_point t0) { return std::chrono::duration<double, std::milli>(Clock::now() - t0).count(); }

const char* conv_kernel_name(nn::ConvVariant v) {
    switch (v) {
        case nn::ConvVariant::Naive: return "conv_naive";
        case nn::ConvVariant::Tiled: return "conv_tiled";
        case nn::ConvVariant::Simdgroup: return "conv_simdgroup";
    }
    return "conv_naive";
}

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
    double library_ms = 0;
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
    const auto t0 = Clock::now();
    s.ctx.library = [s.ctx.device newLibraryWithSource:source options:options error:&error];
    s.library_ms = ms_since(t0);
    if (s.ctx.library == nil) s.error = "Metal library compilation failed: " + text_of([error localizedDescription]);
    return s;
}

ContextState& context_state() {
    static ContextState state = make_context();
    return state;
}

std::expected<Ctx*, Error> context() {
    ContextState& state = context_state();
    if (!state.error.empty()) return fail(state.error);
    return &state.ctx;
}

double library_ms() { return context_state().library_ms; }

std::expected<id<MTLComputePipelineState>, Error> create_pipeline(Ctx& ctx, const std::string& name) {
    id<MTLFunction> function = [ctx.library newFunctionWithName:[NSString stringWithUTF8String:name.c_str()]];
    if (function == nil) return fail("Metal function not found: " + name);
    NSError* error = nil;
    id<MTLComputePipelineState> state = [ctx.device newComputePipelineStateWithFunction:function error:&error];
    if (state == nil) return fail("Metal pipeline " + name + ": " + text_of([error localizedDescription]));
    if (name == "value_head" && [state maxTotalThreadsPerThreadgroup] < kValueGroup) return fail("value_head needs thread groups of 256");
    if (name == "conv_tiled" && [state maxTotalThreadsPerThreadgroup] < kGroupX * kGroupY) return fail("conv_tiled needs thread groups of 128");
    if (name == "conv_simdgroup" && [state maxTotalThreadsPerThreadgroup] < kSgGroupSize) return fail("conv_simdgroup needs thread groups of 128");
    return state;
}

// The pipelines of the single-kernel tests are made once per process; a MetalNet makes its own per bucket (timed).
std::expected<id<MTLComputePipelineState>, Error> pipeline(Ctx& ctx, const std::string& name) {
    if (const auto it = ctx.pipelines.find(name); it != ctx.pipelines.end()) return it->second;
    auto state = create_pipeline(ctx, name);
    if (!state) return state;
    ctx.pipelines[name] = *state;
    return state;
}

// ---- buffers ----

// A refused allocation (newBuffer... returns nil: memory pressure, a length above maxBufferLength) is recorded here, first one
// wins. Callers check ok() before they touch a buffer, so a nil buffer never reaches as_floats, a blit or a kernel.
struct Alloc {
    std::string error;
    bool ok() const { return error.empty(); }
};

id<MTLBuffer> checked(Alloc& a, id<MTLBuffer> buffer, std::size_t bytes) {
    if (buffer == nil && a.ok()) a.error = "Metal buffer allocation of " + std::to_string(bytes) + " bytes failed";
    return buffer;
}
bool refused(std::size_t bytes) { return bytes > g_buffer_limit; }  // test seam, see set_buffer_limit_for_test

id<MTLBuffer> shared_buffer(Ctx& ctx, Alloc& a, std::size_t floats) {
    const std::size_t bytes = std::max<std::size_t>(floats, 1) * sizeof(float);
    if (refused(bytes)) return checked(a, nil, bytes);
    return checked(a, [ctx.device newBufferWithLength:bytes options:MTLResourceStorageModeShared], bytes);
}
id<MTLBuffer> shared_bytes(Ctx& ctx, Alloc& a, const void* data, std::size_t size) {
    const std::size_t bytes = std::max<std::size_t>(size, sizeof(float));
    if (refused(bytes)) return checked(a, nil, bytes);
    return checked(a, [ctx.device newBufferWithBytes:data length:bytes options:MTLResourceStorageModeShared], bytes);
}
id<MTLBuffer> shared_copy(Ctx& ctx, Alloc& a, std::span<const float> data) { return shared_bytes(ctx, a, data.data(), data.size_bytes()); }
id<MTLBuffer> private_buffer(Ctx& ctx, Alloc& a, std::size_t bytes_wanted) {
    const std::size_t bytes = std::max<std::size_t>(bytes_wanted, sizeof(float));
    if (refused(bytes)) return checked(a, nil, bytes);
    return checked(a, [ctx.device newBufferWithLength:bytes options:MTLResourceStorageModePrivate], bytes);
}

// ---- encoding ----

void encode_conv(nn::ConvVariant variant, id<MTLComputeCommandEncoder> enc, id<MTLComputePipelineState> state, id<MTLBuffer> in, id<MTLBuffer> gather, id<MTLBuffer> weight,
                 id<MTLBuffer> bias, id<MTLBuffer> res, id<MTLBuffer> out, const ConvArgs& a) {
    [enc setComputePipelineState:state];
    [enc setBuffer:in offset:0 atIndex:0];
    [enc setBuffer:gather offset:0 atIndex:1];
    [enc setBuffer:weight offset:0 atIndex:2];
    [enc setBuffer:bias offset:0 atIndex:3];
    [enc setBuffer:res offset:0 atIndex:4];
    [enc setBuffer:out offset:0 atIndex:5];
    [enc setBytes:&a length:sizeof(ConvArgs) atIndex:6];
    const auto out_channels = static_cast<NSUInteger>(a.out_channels);
    const auto rows = static_cast<NSUInteger>(a.batch) * nn::kRows;
    if (variant == nn::ConvVariant::Naive) {
        const auto tx = std::min<NSUInteger>(32, out_channels);
        const auto ty = std::max<NSUInteger>(1, std::min<NSUInteger>(8, [state maxTotalThreadsPerThreadgroup] / tx));
        [enc dispatchThreads:MTLSizeMake(out_channels, rows, 1) threadsPerThreadgroup:MTLSizeMake(tx, ty, 1)];
    } else if (variant == nn::ConvVariant::Tiled) {  // KernelsHexConv.tiledGrid: groups of 8 x 16
        [enc dispatchThreadgroups:MTLSizeMake((out_channels + kTileN - 1) / kTileN, (rows + kTileM - 1) / kTileM, 1) threadsPerThreadgroup:MTLSizeMake(kGroupX, kGroupY, 1)];
    } else {  // KernelsHexConv.simdgroupGrid: one group of 128 per 32 x 32 tile
        const NSUInteger groups_n = (out_channels + kSgTile - 1) / kSgTile;
        const NSUInteger groups_m = (rows + kSgTile - 1) / kSgTile;
        [enc dispatchThreadgroups:MTLSizeMake(groups_n * groups_m, 1, 1) threadsPerThreadgroup:MTLSizeMake(kSgGroupSize, 1, 1)];
    }
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

// GPU time of a completed command buffer in milliseconds.
double gpu_ms_of(id<MTLCommandBuffer> cb) { return ([cb GPUEndTime] - [cb GPUStartTime]) * 1000.0; }

// Encodes the steps of one forward chunk. Plain mode: one command buffer, one compute encoder for all kernels, blits before and
// after. Instrumented mode: one command buffer per kernel and per blit, committed and waited for at once and timed by the GPU
// clock into `log` (summed by name).
class Run {
public:
    Run(Ctx& ctx, bool instrumented, std::vector<KernelTime>& log) : ctx_(ctx), instrumented_(instrumented), log_(log) {}

    id<MTLComputeCommandEncoder> kernel(const std::string& label) {
        if (instrumented_) {
            cb_ = [ctx_.queue commandBuffer];
            enc_ = [cb_ computeCommandEncoder];
            label_ = label;
        } else if (enc_ == nil) {
            if (cb_ == nil) cb_ = [ctx_.queue commandBuffer];
            enc_ = [cb_ computeCommandEncoder];
        }
        return enc_;
    }
    std::expected<void, Error> end_kernel() {
        if (!instrumented_) return {};
        [enc_ endEncoding];
        enc_ = nil;
        return flush(label_);
    }
    id<MTLBlitCommandEncoder> blit() {
        close_compute();
        if (instrumented_ || cb_ == nil) cb_ = [ctx_.queue commandBuffer];
        blit_ = [cb_ blitCommandEncoder];
        return blit_;
    }
    std::expected<void, Error> end_blit(const std::string& label) {
        [blit_ endEncoding];
        blit_ = nil;
        return instrumented_ ? flush(label) : std::expected<void, Error>();
    }
    std::expected<void, Error> finish_all() {
        close_compute();
        return cb_ == nil ? std::expected<void, Error>() : flush("");
    }
    double gpu_ms() const { return gpu_ms_; }

private:
    void close_compute() {
        if (enc_ != nil) [enc_ endEncoding];
        enc_ = nil;
    }
    std::expected<void, Error> flush(const std::string& label) {
        id<MTLCommandBuffer> cb = cb_;
        cb_ = nil;
        if (auto r = finish(cb); !r) return r;
        const double ms = gpu_ms_of(cb);
        gpu_ms_ += ms;
        if (instrumented_) {
            const auto it = std::find_if(log_.begin(), log_.end(), [&](const KernelTime& k) { return k.name == label; });
            if (it != log_.end()) {
                it->gpu_ms += ms;
            } else {
                log_.push_back(KernelTime{label, ms});
            }
        }
        return {};
    }

    Ctx& ctx_;
    bool instrumented_;
    std::vector<KernelTime>& log_;
    id<MTLCommandBuffer> cb_ = nil;
    id<MTLComputeCommandEncoder> enc_ = nil;
    id<MTLBlitCommandEncoder> blit_ = nil;
    std::string label_;
    double gpu_ms_ = 0;
};

std::expected<void, Error> need(const char* name, std::size_t have, std::size_t want) {
    if (have < want) return fail(std::string(name) + " needs " + std::to_string(want) + " floats, has " + std::to_string(have));
    return {};
}

std::expected<void, Error> read_back(id<MTLBuffer> buffer, std::span<float> out) {
    const std::span<float> data = as_floats(buffer);
    if (out.size() > data.size()) return fail("Metal buffer holds " + std::to_string(data.size()) + " floats, " + std::to_string(out.size()) + " are read");
    std::copy_n(data.begin(), out.size(), out.begin());
    return {};
}

std::expected<void, Error> write_to(id<MTLBuffer> buffer, std::span<const float> in) {
    const std::span<float> data = as_floats(buffer);
    if (in.size() > data.size()) return fail("Metal buffer holds " + std::to_string(data.size()) + " floats, " + std::to_string(in.size()) + " are written");
    std::copy_n(in.begin(), in.size(), data.begin());
    return {};
}

struct Plan {
    int batch = 0;
    nn::ConvVariant variant = nn::ConvVariant::Naive;
    id<MTLComputePipelineState> conv = nil, policy = nil, value = nil;
    id<MTLBuffer> in_stage = nil;   // copy mode: shared, the host writes the positions here
    id<MTLBuffer> out_stage = nil;  // copy mode: shared, the host reads the head buffer here
    id<MTLBuffer> input = nil;      // copy mode: private; shared mode: shared, the host writes the positions here
    id<MTLBuffer> x = nil, t = nil, u = nil;  // private
    id<MTLBuffer> out = nil;        // copy mode: private; shared mode: shared, the host reads the heads here
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
    std::vector<std::unique_ptr<Plan>> plans;  // index bucket_slot(bucket)
    int plans_built = 0;
    PhaseTimes times;
    std::vector<KernelTime> kernel_log;
    std::vector<BucketSetup> setup;

    std::expected<Plan*, Error> plan(int bucket) {
        const auto slot = static_cast<std::size_t>(nn::bucket_slot(bucket));
        if (plans[slot] == nullptr) {
            auto made = make_plan(bucket);
            if (!made) return std::unexpected(made.error());
            plans[slot] = std::move(*made);
        }
        return plans[slot].get();
    }

    std::expected<std::unique_ptr<Plan>, Error> make_plan(int bucket) {
        auto p = std::make_unique<Plan>();
        p->batch = bucket;
        p->variant = options.variant.value_or(nn::variant_for(bucket, config.channels, true));
        const auto t_pipelines = Clock::now();
        for (auto [field, name] : {std::pair{&p->conv, conv_kernel_name(p->variant)}, std::pair{&p->policy, "policy_head"}, std::pair{&p->value, "value_head"}}) {
            auto state = create_pipeline(*ctx, name);
            if (!state) return std::unexpected(state.error());
            *field = *state;
        }
        const double pipeline_ms = ms_since(t_pipelines);
        const auto t_buffers = Clock::now();
        const bool shared = options.memory == MemoryMode::Shared;
        const auto b = static_cast<std::size_t>(bucket);
        const std::size_t input_floats = b * static_cast<std::size_t>(config.input_size());
        const std::size_t activation = b * static_cast<std::size_t>(nn::kRows) * static_cast<std::size_t>(config.channels);
        const std::size_t out_floats = b * static_cast<std::size_t>(nn::kPolicySize + config.players);
        Alloc alloc;
        p->x = private_buffer(*ctx, alloc, activation * sizeof(float));
        p->t = private_buffer(*ctx, alloc, activation * sizeof(float));
        p->u = private_buffer(*ctx, alloc, activation * sizeof(float));
        if (shared) {
            // The two buffers of the host-visible mode: no staging, no blits. Slots of a bucket that a call does not fill keep
            // what was there before (zeros at first); their outputs are not read.
            p->input = shared_buffer(*ctx, alloc, input_floats);
            p->out = shared_buffer(*ctx, alloc, out_floats);
            if (!alloc.ok()) return fail(alloc.error);
            std::ranges::fill(as_floats(p->input), 0.0f);
            std::ranges::fill(as_floats(p->out), 0.0f);
        } else {
            p->in_stage = shared_buffer(*ctx, alloc, input_floats);
            p->out_stage = shared_buffer(*ctx, alloc, out_floats);
            p->input = private_buffer(*ctx, alloc, input_floats * sizeof(float));
            p->out = private_buffer(*ctx, alloc, out_floats * sizeof(float));
            if (!alloc.ok()) return fail(alloc.error);
            // The slots of a bucket that a call does not fill keep zeros (their outputs are not read).
            id<MTLCommandBuffer> cb = [ctx->queue commandBuffer];
            id<MTLBlitCommandEncoder> blit = [cb blitCommandEncoder];
            [blit fillBuffer:p->input range:NSMakeRange(0, [p->input length]) value:0];
            [blit endEncoding];
            if (auto r = finish(cb); !r) return std::unexpected(r.error());
        }
        setup.push_back(BucketSetup{bucket, p->variant, pipeline_ms, ms_since(t_buffers)});
        ++plans_built;
        return p;
    }

    std::expected<void, Error> run_chunk(Plan& p, std::span<const float> input, int n, std::span<float> logits, std::span<float> values) {
        const int c = config.channels;
        const int players = config.players;
        const bool shared = options.memory == MemoryMode::Shared;
        const std::size_t in_floats = static_cast<std::size_t>(n) * static_cast<std::size_t>(config.input_size());
        auto t = Clock::now();
        if (auto r = write_to(shared ? p.input : p.in_stage, input.first(in_floats)); !r) return r;
        times.copy_in_ms += ms_since(t);

        t = Clock::now();
        Run run(*ctx, options.instrumented, kernel_log);
        const int batch = p.batch;
        const nn::ConvVariant v = p.variant;
        if (!shared) {
            id<MTLBlitCommandEncoder> up = run.blit();
            [up copyFromBuffer:p.in_stage sourceOffset:0 toBuffer:p.input destinationOffset:0 size:in_floats * sizeof(float)];
            if (auto r = run.end_blit("blit_in"); !r) return r;
        }
        const auto conv_step = [&](const std::string& label, id<MTLBuffer> in, const ConvWeights& w, id<MTLBuffer> res, id<MTLBuffer> out, const ConvArgs& a) {
            encode_conv(v, run.kernel(label), p.conv, in, gather, w.weight, w.bias, res, out, a);
            return run.end_kernel();
        };
        if (auto r = conv_step("conv_in", p.input, conv_in, no_residual, p.x, {batch, config.input_planes(), c, 0, 1}); !r) return r;
        id<MTLBuffer> x = p.x;
        id<MTLBuffer> tt = p.t;
        id<MTLBuffer> u = p.u;
        for (std::size_t i = 0; i < blocks.size(); ++i) {  // x is the block input; conv1 writes t, conv2 reads t and adds x into u; then u is the next x
            const std::string name = "block" + std::to_string(i);
            if (auto r = conv_step(name + ".conv1", x, blocks[i][0], no_residual, tt, {batch, c, c, 0, 1}); !r) return r;
            if (auto r = conv_step(name + ".conv2", tt, blocks[i][1], x, u, {batch, c, c, 1, 1}); !r) return r;
            std::swap(x, u);
        }
        const HeadArgs heads{batch, c, config.value_hidden, players, priors ? 0 : 1, batch * nn::kPolicySize};
        encode_policy(run.kernel("policy_head"), p.policy, x, policy_w, policy_b, p.out, heads);
        if (auto r = run.end_kernel(); !r) return r;
        encode_value(run.kernel("value_head"), p.value, x, dense1_w, dense1_b, dense2_w, dense2_b, p.out, heads);
        if (auto r = run.end_kernel(); !r) return r;
        const std::size_t out_floats = static_cast<std::size_t>(batch) * static_cast<std::size_t>(nn::kPolicySize + players);
        if (!shared) {
            id<MTLBlitCommandEncoder> down = run.blit();
            [down copyFromBuffer:p.out sourceOffset:0 toBuffer:p.out_stage destinationOffset:0 size:out_floats * sizeof(float)];
            if (auto r = run.end_blit("blit_out"); !r) return r;
        }
        if (auto r = run.finish_all(); !r) return r;
        times.commit_wait_ms += ms_since(t);
        times.gpu_ms += run.gpu_ms();

        t = Clock::now();
        const std::span<float> stage = as_floats(shared ? p.out : p.out_stage);
        const auto nl = static_cast<std::size_t>(n) * static_cast<std::size_t>(nn::kPolicySize);
        const auto nv = static_cast<std::size_t>(n) * static_cast<std::size_t>(players);
        std::copy_n(stage.begin(), nl, logits.begin());
        std::copy_n(stage.begin() + static_cast<std::ptrdiff_t>(static_cast<std::size_t>(batch) * static_cast<std::size_t>(nn::kPolicySize)), nv, values.begin());
        times.copy_out_ms += ms_since(t);
        ++times.calls;
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
        kernel_log.clear();
        for (const nn::Chunk& chunk : nn::chunks_of(batch, options.max_batch)) {
            const auto first = static_cast<std::size_t>(chunk.first);
            const auto n = static_cast<std::size_t>(chunk.n);
            const auto in = input.subspan(first * in_size, n * in_size);
            const auto lo = logits.subspan(first * static_cast<std::size_t>(nn::kPolicySize), n * static_cast<std::size_t>(nn::kPolicySize));
            const auto va = values.subspan(first * players, n * players);
            auto pl = plan(chunk.bucket);
            if (!pl) return std::unexpected(pl.error());
            if (auto r = run_chunk(**pl, in, chunk.n, lo, va); !r) return r;
            if (priors) {
                const auto t = Clock::now();
                nn::apply_host_priors(beta, k, in, config.input_planes(), config.players, chunk.n, lo, va);
                times.priors_ms += ms_since(t);
            }
        }
        return {};
    }
};

std::expected<MetalNet, Error> MetalNet::create(const nn::Parameters& parameters, const Options& options) {
    @autoreleasepool {
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
        // Parameters and the gather table: copied once into private buffers, through shared staging buffers and one blit pass.
        std::vector<id<MTLBuffer>> staging;
        id<MTLCommandBuffer> cb = [(*ctx)->queue commandBuffer];
        id<MTLBlitCommandEncoder> blit = [cb blitCommandEncoder];
        Alloc alloc;
        const auto upload = [&](std::span<const float> data) {
            id<MTLBuffer> stage = shared_copy(**ctx, alloc, data);
            id<MTLBuffer> device = private_buffer(**ctx, alloc, data.size_bytes());
            if (alloc.ok()) {
                [blit copyFromBuffer:stage sourceOffset:0 toBuffer:device destinationOffset:0 size:data.size_bytes()];
                staging.push_back(stage);
            }
            return device;
        };
        const auto conv_weights = [&](const std::string& prefix) { return ConvWeights{upload(parameters.data(prefix + ".weight")), upload(parameters.data(prefix + ".bias"))}; };
        {
            const nn::GatherTable& table = nn::gather_table();
            id<MTLBuffer> stage = shared_bytes(**ctx, alloc, table.data(), sizeof(table));
            impl->gather = private_buffer(**ctx, alloc, sizeof(table));
            if (alloc.ok()) {
                [blit copyFromBuffer:stage sourceOffset:0 toBuffer:impl->gather destinationOffset:0 size:sizeof(table)];
                staging.push_back(stage);
            }
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
        if (!alloc.ok()) return fail(alloc.error);
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
const PhaseTimes& MetalNet::timings() const { return impl_->times; }
void MetalNet::reset_timings() { impl_->times = PhaseTimes{}; }
const std::vector<KernelTime>& MetalNet::kernel_times() const { return impl_->kernel_log; }
const std::vector<BucketSetup>& MetalNet::setup() const { return impl_->setup; }

std::expected<void, Error> MetalNet::forward(std::span<const float> input, int batch, std::span<float> logits, std::span<float> values) {
    @autoreleasepool {
        return impl_->forward(input, batch, logits, values);
    }
}

// ---- single kernels for the tests ----

std::expected<void, Error> run_conv_naive(std::span<const float> in, std::span<const float> weight, std::span<const float> bias, std::span<const float> residual,
                                          std::span<float> out, int batch, int in_channels, int out_channels, bool use_residual, bool relu) {
    auto r = run_conv(nn::ConvVariant::Naive, in, weight, bias, residual, out, batch, in_channels, out_channels, use_residual, relu);
    if (!r) return std::unexpected(r.error());
    return {};
}

std::expected<double, Error> run_conv(nn::ConvVariant variant, std::span<const float> in, std::span<const float> weight, std::span<const float> bias, std::span<const float> residual,
                                      std::span<float> out, int batch, int in_channels, int out_channels, bool use_residual, bool relu) {
    @autoreleasepool {
        if (batch < 1 || in_channels < 1 || out_channels < 1) return fail("run_conv: sizes must be >= 1");
        const auto rows = static_cast<std::size_t>(batch) * nn::kRows;
        const auto cin = static_cast<std::size_t>(in_channels);
        const auto cout = static_cast<std::size_t>(out_channels);
        if (auto r = need("in", in.size(), rows * cin); !r) return std::unexpected(r.error());
        if (auto r = need("weight", weight.size(), cout * nn::kStencil * cin); !r) return std::unexpected(r.error());
        if (auto r = need("bias", bias.size(), cout); !r) return std::unexpected(r.error());
        if (auto r = need("out", out.size(), rows * cout); !r) return std::unexpected(r.error());
        if (use_residual) {
            if (auto r = need("residual", residual.size(), rows * cout); !r) return std::unexpected(r.error());
        }
        auto ctx = context();
        if (!ctx) return std::unexpected(ctx.error());
        auto state = pipeline(**ctx, conv_kernel_name(variant));
        if (!state) return std::unexpected(state.error());
        const nn::GatherTable& table = nn::gather_table();
        Alloc alloc;
        id<MTLBuffer> gather = shared_bytes(**ctx, alloc, table.data(), sizeof(table));
        id<MTLBuffer> out_buffer = shared_copy(**ctx, alloc, out.first(rows * cout));
        id<MTLBuffer> in_buffer = shared_copy(**ctx, alloc, in.first(rows * cin));
        id<MTLBuffer> weight_buffer = shared_copy(**ctx, alloc, weight.first(cout * nn::kStencil * cin));
        id<MTLBuffer> bias_buffer = shared_copy(**ctx, alloc, bias.first(cout));
        id<MTLBuffer> residual_buffer = use_residual ? shared_copy(**ctx, alloc, residual.first(rows * cout)) : shared_buffer(**ctx, alloc, 1);
        if (!alloc.ok()) return fail(alloc.error);
        id<MTLCommandBuffer> cb = [(*ctx)->queue commandBuffer];
        id<MTLComputeCommandEncoder> enc = [cb computeCommandEncoder];
        encode_conv(variant, enc, *state, in_buffer, gather, weight_buffer, bias_buffer, residual_buffer, out_buffer,
                    {batch, in_channels, out_channels, use_residual ? 1 : 0, relu ? 1 : 0});
        [enc endEncoding];
        if (auto r = finish(cb); !r) return std::unexpected(r.error());
        if (auto r = read_back(out_buffer, out.first(rows * cout)); !r) return std::unexpected(r.error());
        return gpu_ms_of(cb);
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
        Alloc alloc;
        id<MTLBuffer> out = shared_buffer(**ctx, alloc, logit_floats + b * p);
        id<MTLBuffer> trunk_buffer = shared_copy(**ctx, alloc, trunk.first(b * nn::kRows * c));
        id<MTLBuffer> policy_w = shared_copy(**ctx, alloc, w.policy_weight.first(2 * c));
        id<MTLBuffer> policy_b = shared_copy(**ctx, alloc, w.policy_bias.first(2));
        id<MTLBuffer> dense1_w = shared_copy(**ctx, alloc, w.dense1_weight.first(h * c));
        id<MTLBuffer> dense1_b = shared_copy(**ctx, alloc, w.dense1_bias.first(h));
        id<MTLBuffer> dense2_w = shared_copy(**ctx, alloc, w.dense2_weight.first(p * h));
        id<MTLBuffer> dense2_b = shared_copy(**ctx, alloc, w.dense2_bias.first(p));
        if (!alloc.ok()) return fail(alloc.error);
        if (auto r = write_to(out, logits.first(logit_floats)); !r) return r;
        const std::span<float> tail = as_floats(out).subspan(logit_floats);
        std::copy_n(values.begin(), b * p, tail.begin());
        const HeadArgs a{batch, channels, hidden, players, apply_tanh ? 1 : 0, static_cast<int>(logit_floats)};
        id<MTLCommandBuffer> cb = [(*ctx)->queue commandBuffer];
        id<MTLComputeCommandEncoder> enc = [cb computeCommandEncoder];
        encode_policy(enc, *policy, trunk_buffer, policy_w, policy_b, out, a);
        encode_value(enc, *value, trunk_buffer, dense1_w, dense1_b, dense2_w, dense2_b, out, a);
        [enc endEncoding];
        if (auto r = finish(cb); !r) return r;
        const std::span<float> data = as_floats(out);
        std::copy_n(data.begin(), logit_floats, logits.begin());
        std::copy_n(data.begin() + static_cast<std::ptrdiff_t>(logit_floats), b * p, values.begin());
        return {};
    }
}

double library_compile_ms() {
    @autoreleasepool {
        auto ctx = context();
        return ctx ? library_ms() : 0.0;
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
