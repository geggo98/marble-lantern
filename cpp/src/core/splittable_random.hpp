// java.util.SplittableRandom(seed), the sequence methods nextLong() and nextInt(), ported exactly (all arithmetic unsigned).
// The Zobrist keys of Position come from seed 0x5E7A11A100000001, in the order: for each player p in 0..2, one side key,
// then 121 piece keys.
#pragma once

#include <cstdint>

namespace sh {

class SplittableRandom {
public:
    explicit SplittableRandom(std::uint64_t seed) : seed_(seed) {}

    std::uint64_t next_long() { return mix64(next_seed()); }

    // nextInt(): the upper 32 bits of a second mixing function.
    std::int32_t next_int() { return static_cast<std::int32_t>(static_cast<std::uint32_t>(mix32(next_seed()))); }

private:
    static constexpr std::uint64_t kGoldenGamma = 0x9e3779b97f4a7c15ULL;

    std::uint64_t next_seed() {
        seed_ += kGoldenGamma;
        return seed_;
    }
    static std::uint64_t mix64(std::uint64_t z) {
        z = (z ^ (z >> 30)) * 0xbf58476d1ce4e5b9ULL;
        z = (z ^ (z >> 27)) * 0x94d049bb133111ebULL;
        return z ^ (z >> 31);
    }
    static std::uint64_t mix32(std::uint64_t z) {
        z = (z ^ (z >> 33)) * 0x62a9d9ed799705f5ULL;
        return ((z ^ (z >> 28)) * 0xcb24d0a5c88c35b3ULL) >> 32;
    }

    std::uint64_t seed_;
};

}  // namespace sh
