#include "sha256.hpp"

#include <algorithm>
#include <array>
#include <bit>
#include <cstdint>
#include <vector>

#if defined(__APPLE__)
#include <CommonCrypto/CommonDigest.h>
#endif

namespace sh {
namespace {

constexpr std::array<std::uint32_t, 64> kRound = {
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2};

std::string to_hex(std::span<const std::uint8_t, 32> digest) {
    static constexpr std::string_view kDigits = "0123456789abcdef";
    std::string out;
    out.reserve(64);
    for (const std::uint8_t b : digest) {
        out.push_back(kDigits[b >> 4]);
        out.push_back(kDigits[b & 15]);
    }
    return out;
}

void compress(std::array<std::uint32_t, 8>& h, std::span<const std::byte, 64> block) {
    std::array<std::uint32_t, 64> w{};
    for (std::size_t i = 0; i < 16; ++i) {
        w[i] = (static_cast<std::uint32_t>(block[4 * i]) << 24) | (static_cast<std::uint32_t>(block[4 * i + 1]) << 16) |
               (static_cast<std::uint32_t>(block[4 * i + 2]) << 8) | static_cast<std::uint32_t>(block[4 * i + 3]);
    }
    for (std::size_t i = 16; i < 64; ++i) {
        const std::uint32_t s0 = std::rotr(w[i - 15], 7) ^ std::rotr(w[i - 15], 18) ^ (w[i - 15] >> 3);
        const std::uint32_t s1 = std::rotr(w[i - 2], 17) ^ std::rotr(w[i - 2], 19) ^ (w[i - 2] >> 10);
        w[i] = w[i - 16] + s0 + w[i - 7] + s1;
    }
    std::array<std::uint32_t, 8> v = h;
    for (std::size_t i = 0; i < 64; ++i) {
        const std::uint32_t s1 = std::rotr(v[4], 6) ^ std::rotr(v[4], 11) ^ std::rotr(v[4], 25);
        const std::uint32_t ch = (v[4] & v[5]) ^ (~v[4] & v[6]);
        const std::uint32_t t1 = v[7] + s1 + ch + kRound[i] + w[i];
        const std::uint32_t s0 = std::rotr(v[0], 2) ^ std::rotr(v[0], 13) ^ std::rotr(v[0], 22);
        const std::uint32_t maj = (v[0] & v[1]) ^ (v[0] & v[2]) ^ (v[1] & v[2]);
        const std::uint32_t t2 = s0 + maj;
        v[7] = v[6];
        v[6] = v[5];
        v[5] = v[4];
        v[4] = v[3] + t1;
        v[3] = v[2];
        v[2] = v[1];
        v[1] = v[0];
        v[0] = t1 + t2;
    }
    for (std::size_t i = 0; i < 8; ++i) h[i] += v[i];
}

}  // namespace

std::string sha256_hex_portable(std::span<const std::byte> data) {
    std::array<std::uint32_t, 8> h = {0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
                                      0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19};
    std::size_t pos = 0;
    while (data.size() - pos >= 64) {
        compress(h, data.subspan(pos).first<64>());
        pos += 64;
    }
    // Last one or two blocks: the rest, 0x80, zeros, the bit length as 64-bit big-endian.
    std::array<std::byte, 128> tail{};
    const std::size_t rest = data.size() - pos;
    std::copy_n(data.begin() + static_cast<std::ptrdiff_t>(pos), rest, tail.begin());
    tail[rest] = std::byte{0x80};
    const std::size_t tail_len = rest + 1 + 8 <= 64 ? 64 : 128;
    const std::uint64_t bits = static_cast<std::uint64_t>(data.size()) * 8U;
    for (std::size_t i = 0; i < 8; ++i) {
        tail[tail_len - 1 - i] = static_cast<std::byte>((bits >> (8 * i)) & 0xff);
    }
    compress(h, std::span<const std::byte>(tail).first<64>());
    if (tail_len == 128) compress(h, std::span<const std::byte>(tail).subspan<64, 64>());
    std::array<std::uint8_t, 32> digest{};
    for (std::size_t i = 0; i < 8; ++i) {
        for (std::size_t j = 0; j < 4; ++j) digest[4 * i + j] = static_cast<std::uint8_t>((h[i] >> (24 - 8 * j)) & 0xff);
    }
    return to_hex(digest);
}

std::string sha256_hex(std::span<const std::byte> data) {
#if defined(__APPLE__)
    std::array<std::uint8_t, 32> digest{};
    CC_SHA256_CTX ctx;
    CC_SHA256_Init(&ctx);
    constexpr std::size_t kChunk = std::size_t{1} << 28;  // CC_LONG is 32 bits wide
    while (!data.empty()) {
        const std::size_t n = std::min(kChunk, data.size());
        CC_SHA256_Update(&ctx, data.data(), static_cast<CC_LONG>(n));
        data = data.subspan(n);
    }
    CC_SHA256_Final(digest.data(), &ctx);
    return to_hex(digest);
#else
    return sha256_hex_portable(data);
#endif
}

}  // namespace sh
