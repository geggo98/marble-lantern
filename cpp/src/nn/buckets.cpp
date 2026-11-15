#include "buckets.hpp"

#include <algorithm>
#include <bit>

namespace sh::nn {

std::expected<int, Error> bucket_for(int n, int max_batch) {
    if (n < 1 || max_batch < 1) {
        return std::unexpected(Error{"n and max_batch must be >= 1, got " + std::to_string(n) + ", " + std::to_string(max_batch)});
    }
    const auto m = static_cast<unsigned>(std::min(n, max_batch));
    const int power = static_cast<int>(std::bit_floor(m));
    const int up = power == n ? n : power << 1;
    return std::min(up, max_batch);
}

int bucket_slot(int bucket) {
    if (bucket < 2) return 0;
    return static_cast<int>(std::bit_width(static_cast<unsigned>(bucket - 1)));
}

ConvVariant variant_for(int bucket, int channels, bool simdgroup_available) {
    if (static_cast<long>(bucket) * channels <= kNaiveUpTo) return ConvVariant::Naive;
    return simdgroup_available ? ConvVariant::Simdgroup : ConvVariant::Tiled;
}

std::vector<Chunk> chunks_of(int batch, int max_batch) {
    std::vector<Chunk> out;
    if (batch < 1 || max_batch < 1) return out;
    for (int first = 0; first < batch; first += max_batch) {
        const int n = std::min(max_batch, batch - first);
        out.push_back({first, n, *bucket_for(n, max_batch)});
    }
    return out;
}

}  // namespace sh::nn
