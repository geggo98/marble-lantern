// CudaNet: the CUDA forward pass behind a plain C++20 interface (standard headers only, so that nvcc and g++ 12 both read it).
// Two implementations: cuda_net.cu (nvcc, the real device code) and cuda_net_stub.cpp (no CUDA; used on the Mac).
//
// The net knows nothing of the project's types: it takes the shape and the packed weights as spans and runs one chunk of a
// forward pass for a given bucket (a power of two at least the number of positions) and a given convolution kernel. The
// chunking, the bucket rule, the variant rule and the host priors belong to the adapter src/nn/cuda_backend.cpp.
// Errors are strings, never exceptions: an empty string means success. Not thread-safe.
#pragma once

#include <array>
#include <cstdint>
#include <memory>
#include <span>
#include <string>
#include <string_view>
#include <vector>

namespace sh::cuda {

// "cuda" in the build with device code, "stub" in the build without it.
std::string_view build_kind();

// Number of CUDA devices (0 in the stub build or without a driver) and the name of device 0 ("" if none).
int device_count();
std::string device_name();

enum class Variant : std::uint8_t { Naive, Tiled };
// Pageable: the caller's spans go to the driver directly (like the off-heap buffers of the JVM). Pinned: the net copies
// through page-locked staging buffers (cudaHostAlloc).
enum class HostMemory : std::uint8_t { Pageable, Pinned };

struct Shape {
    int channels = 0;
    int blocks = 0;
    int players = 0;
    int hidden = 0;
    int input_planes = 0;
};

struct ConvWeights {
    std::span<const float> weight;  // [out][7 * in]
    std::span<const float> bias;    // [out]
};

// Packed weights in the layout of Parameters (docs/network.md, "Tensor names"); the spans only have to live until create()
// returns, the net copies them to the device.
struct Weights {
    std::span<const int> gather;  // [121][7]
    ConvWeights conv_in;
    std::vector<std::array<ConvWeights, 2>> blocks;     // conv1, conv2 of each block
    std::span<const float> policy_weight, policy_bias;  // [2][C], [2]
    std::span<const float> dense1_weight, dense1_bias;  // [H][C], [H]
    std::span<const float> dense2_weight, dense2_bias;  // [P][H], [P]
};

struct Options {
    int max_bucket = 1024;  // the largest bucket a call may use
    HostMemory memory = HostMemory::Pageable;
    // One event between every two kernels, so that every kernel has its own time (kernels in CallTimes). The call is no
    // faster for it; the plain mode is the one that is compared.
    bool instrumented = false;
};

struct KernelTime {
    std::string name;  // "conv_in", "block0.conv1", ..., "policy_head", "value_head"
    double ms = 0;
};

// The milliseconds of one forward_raw call. copy_in and copy_out are CPU time around the copies between the caller's spans and the host staging buffers (pageable or pinned memory, as chosen);
// h2d, kernel and d2h are device time from cudaEvent pairs around the three stages on the stream.
struct CallTimes {
    double copy_in_ms = 0, h2d_ms = 0, kernel_ms = 0, d2h_ms = 0, copy_out_ms = 0;
    std::vector<KernelTime> kernels;  // instrumented mode only
};

// What the first call of a (bucket, variant) pair cost: buffer allocation, in milliseconds.
struct BucketSetup {
    int bucket = 0;
    Variant variant = Variant::Naive;
    double buffer_ms = 0;
};

// One chunk: n positions in a bucket of `bucket` slots (n <= bucket <= max_bucket).
struct Call {
    int n = 0;
    int bucket = 0;
    Variant variant = Variant::Naive;
    bool apply_tanh = false;  // tanh in the value head (plane sets without host priors); with host priors the adapter applies it
};

// The interface of one device net. cuda_net.cu implements it with CUDA; the tests implement it with an emulation on the CPU
// (tests/cuda_emu.hpp) to run the adapter without a device.
class CudaNet {
public:
    // Allocates the device, copies the weights. Returns nullptr and sets `error` if there is no device, the shape is out of
    // range (channels and hidden above 1024, sizes that do not fit the spans) or a CUDA call fails. The stub build always
    // fails with "no CUDA device in this build".
    static std::unique_ptr<CudaNet> create(const Shape& shape, const Weights& weights, const Options& options, std::string& error);
    virtual ~CudaNet() = default;
    CudaNet(const CudaNet&) = delete;
    CudaNet& operator=(const CudaNet&) = delete;
    CudaNet(CudaNet&&) = delete;
    CudaNet& operator=(CudaNet&&) = delete;

    virtual const Shape& shape() const = 0;
    virtual const Options& options() const = 0;
    // Milliseconds of context creation and of copying the weights in create() (module load happens with the context).
    virtual double init_ms() const = 0;
    virtual const std::vector<BucketSetup>& setup() const = 0;

    // input: n x [122][input_planes]; logits: n x 242; values: n x players. Slots n..bucket-1 of the bucket keep what they
    // held before (zeros at first); their outputs are not read. Returns "" or an error text; on an error the outputs are
    // not meaningful.
    virtual std::string forward_raw(std::span<const float> input, const Call& call, std::span<float> logits, std::span<float> values,
                                    CallTimes& times) = 0;

protected:
    CudaNet() = default;
};

}  // namespace sh::cuda
