// Batch buckets and the convolution variant rule of TornadoNetwork (Java: bucket, variantFor, the chunk loop of forward).
// A call with n positions uses the plan of the smallest power of two at least n, capped at max_batch; a batch above
// max_batch is split into chunks of max_batch. The device backends build one pipeline set and buffer set per bucket.
#pragma once

#include <expected>
#include <vector>

#include "config.hpp"

namespace sh::nn {

inline constexpr int kDefaultMaxBatch = 1024;  // TornadoNetwork.DEFAULT_MAX_BATCH
inline constexpr int kNaiveUpTo = 128;         // TornadoNetwork.NAIVE_UP_TO: largest bucket * channels that uses the naive kernel

enum class ConvVariant : std::uint8_t { Naive, Tiled, Simdgroup };

// TornadoNetwork.bucket: error if n or max_batch is below 1.
std::expected<int, Error> bucket_for(int n, int max_batch);

// TornadoNetwork.slot: index of a bucket in the plan table, log2 for powers of two (1 -> 0, 2 -> 1, 4 -> 2, ...), the last
// slot for a capped bucket that is no power of two. bucket must be at least 1.
int bucket_slot(int bucket);

// TornadoNetwork.variantFor: Naive while bucket * channels <= 128, else Simdgroup where available (Metal) or Tiled (CUDA).
ConvVariant variant_for(int bucket, int channels, bool simdgroup_available);

struct Chunk {
    int first = 0;  // index of the first position
    int n = 0;      // positions in this chunk
    int bucket = 0;
};

// The chunks of a batch: [first, first + n) of at most max_batch positions each, with the bucket of each. Empty for batch < 1.
std::vector<Chunk> chunks_of(int batch, int max_batch);

}  // namespace sh::nn
