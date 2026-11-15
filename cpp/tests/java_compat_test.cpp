// Tests of src/core: the Java compatibility functions, SplittableRandom, little-endian reads, SHA-256 and the golden reader.
// Expected values were produced by a Java program (java.util.SplittableRandom, Math.round, casts, Math.max).
// The Zobrist part reads zobrist.tsv from the Java exporter of WP1: data lines are "<index>\t<signed decimal key>" for the
// 366 keys in generation order (for p in 0..2: side key, then 121 piece keys); other lines are ignored here.
#include <array>
#include <bit>
#include <cmath>
#include <cstdint>
#include <filesystem>
#include <fstream>
#include <limits>
#include <string>

#include "check.hpp"
#include "golden.hpp"
#include "golden_test_support.hpp"
#include "java_compat.hpp"
#include "le_bytes.hpp"
#include "sha256.hpp"
#include "splittable_random.hpp"

using namespace sh;

SH_TEST(java_round) {
    CHECK_EQ(java_round(2.5), 3);
    CHECK_EQ(java_round(-2.5), -2);
    CHECK_EQ(java_round(-0.5), 0);
    CHECK_EQ(java_round(0.49999999999999994), 0);
    CHECK_EQ(java_round(std::nan("")), 0);
    CHECK_EQ(java_round(1e300), std::numeric_limits<std::int64_t>::max());
    CHECK_EQ(java_round(-1e300), std::numeric_limits<std::int64_t>::min());
    CHECK_EQ(java_round(std::numeric_limits<double>::infinity()), std::numeric_limits<std::int64_t>::max());
    CHECK_EQ(java_round(-std::numeric_limits<double>::infinity()), std::numeric_limits<std::int64_t>::min());
    CHECK_EQ(java_round(0.5), 1);
    CHECK_EQ(java_round(-1.5), -1);
    CHECK_EQ(java_round(4503599627370497.0), 4503599627370497);  // 2^52 + 1 is integral: unchanged
}

SH_TEST(java_casts) {
    CHECK_EQ(java_d2i(1e300), 2147483647);
    CHECK_EQ(java_d2i(-1e300), std::numeric_limits<std::int32_t>::min());
    CHECK_EQ(java_d2i(std::nan("")), 0);
    CHECK_EQ(java_d2i(-7.9), -7);
    CHECK_EQ(java_d2i(2147483647.5), 2147483647);
    CHECK_EQ(java_d2l(1e300), std::numeric_limits<std::int64_t>::max());
    CHECK_EQ(java_d2l(std::nan("")), 0);
    CHECK_EQ(java_d2l(-3.99), -3);
}

SH_TEST(java_max_float) {
    CHECK(!std::signbit(java_max(-0.0f, 0.0f)));
    CHECK(!std::signbit(java_max(0.0f, -0.0f)));
    CHECK(std::signbit(java_max(-0.0f, -0.0f)));
    CHECK(std::isnan(java_max(std::nanf(""), 1.0f)));
    CHECK(std::isnan(java_max(1.0f, std::nanf(""))));
    CHECK_EQ(java_max(1.0f, 2.0f), 2.0f);
    CHECK_EQ(java_max(-3.0f, -2.0f), -2.0f);
    // std::max keeps -0 here: the reason java_max exists
    CHECK(std::signbit(std::max(-0.0f, 0.0f)));
}

SH_TEST(splittable_random_known_answers) {
    SplittableRandom a(0x5E7A11A100000001ULL);
    CHECK_EQ(static_cast<std::int64_t>(a.next_long()), 216775284241193035LL);
    CHECK_EQ(static_cast<std::int64_t>(a.next_long()), -4454468946316862012LL);
    CHECK_EQ(static_cast<std::int64_t>(a.next_long()), 446968125664862222LL);
    SplittableRandom b(42);
    CHECK_EQ(static_cast<std::int64_t>(b.next_long()), -4767286540954276203LL);
    CHECK_EQ(static_cast<std::int64_t>(b.next_long()), 2949826092126892291LL);
    CHECK_EQ(b.next_int(), -1877322334);
    CHECK_EQ(b.next_int(), -1024560952);
}

SH_TEST(zobrist_keys_match_golden) {
    const auto lines = golden::lines_or_skip("zobrist.tsv");
    if (!lines) return;
    std::array<std::int64_t, 366> expected{};
    SplittableRandom rng(0x5E7A11A100000001ULL);
    for (auto& key : expected) key = static_cast<std::int64_t>(rng.next_long());
    int compared = 0;
    for (const std::string& line : *lines) {
        const std::size_t tab = line.find('\t');
        if (tab == std::string::npos || tab == 0 || line.find_first_not_of("0123456789") < tab) continue;
        const std::size_t index = std::stoul(line.substr(0, tab));
        if (index >= expected.size()) continue;
        CHECK_EQ(std::stoll(line.substr(tab + 1)), expected[index]);
        ++compared;
    }
    CHECK_EQ(compared, 366);
}

SH_TEST(le_bytes) {
    const std::array<std::byte, 8> raw = {std::byte{1}, std::byte{2}, std::byte{3}, std::byte{4},
                                          std::byte{5}, std::byte{6}, std::byte{7}, std::byte{0x88}};
    CHECK_EQ(read_le<std::uint32_t>(raw, 0).value_or(0), 0x04030201U);
    CHECK_EQ(read_le<std::uint64_t>(raw, 0).value_or(0), 0x8807060504030201ULL);
    CHECK_EQ(read_le<std::uint16_t>(raw, 6).value_or(0), 0x8807U);
    CHECK_EQ(read_le<std::int32_t>(raw, 4).value_or(0), static_cast<std::int32_t>(0x88070605U));
    CHECK(!read_le<std::uint32_t>(raw, 5).has_value());
    CHECK(!read_le<std::uint8_t>(raw, 9).has_value());
    CHECK(read_le<std::uint8_t>(raw, 7).has_value());
    const std::array<std::byte, 4> one = {std::byte{0}, std::byte{0}, std::byte{0x80}, std::byte{0x3f}};
    CHECK_EQ(read_le<float>(one, 0).value_or(0.0f), 1.0f);
}

namespace {
std::string hash_of(const std::string& s, bool portable) {
    const auto bytes = std::as_bytes(std::span<const char>(s));
    return portable ? sha256_hex_portable(bytes) : sha256_hex(bytes);
}
}  // namespace

SH_TEST(sha256) {
    for (const bool portable : {false, true}) {
        CHECK_EQ(hash_of("", portable), "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        CHECK_EQ(hash_of("abc", portable), "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        CHECK_EQ(hash_of("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq", portable),
                 "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1");
        // 55, 56 and 64 bytes: the padding boundaries (one or two blocks in the tail)
        CHECK_EQ(hash_of(std::string(55, 'a'), portable), "9f4390f8d30c2dd92ec9f095b65e2b9ae9b0a925a5258e241c9f1e910f734318");
        CHECK_EQ(hash_of(std::string(56, 'a'), portable), "b35439a4ac6f0948b6d6f9e3c6af0f5f590ce20f1bde7090ef7970686ec6738a");
        CHECK_EQ(hash_of(std::string(64, 'a'), portable), "ffe054fe7ae0cb6dc65c3af9b61d5209f439851db43d0ba5997337df154668eb");
        CHECK_EQ(hash_of(std::string(1000000, 'a'), portable), "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0");
    }
}

SH_TEST(golden_reader) {
    namespace fs = std::filesystem;
    const fs::path dir = fs::temp_directory_path() / "sh_golden_reader_test";
    fs::remove_all(dir);
    fs::create_directories(dir);
    {
        std::ofstream(dir / "a.tsv") << "x\ty\nz\n";
        std::ofstream(dir / "MANIFEST.tsv")
            << "file\tlines\tsha256\tcommit\n"
            << "a.tsv\t2\t" << sha256_hex(std::as_bytes(std::span<const char>(std::string_view("x\ty\nz\n")))) << "\tabc\n"
            << "b.tsv\t1\t0000\tabc\n";
        std::ofstream(dir / "b.tsv") << "tampered\n";
    }
    auto ok = golden::load_lines(dir, "a.tsv");
    CHECK(ok.has_value());
    if (ok) {
        CHECK_EQ(ok->size(), std::size_t{2});
        CHECK_EQ((*ok)[0], "x\ty");
    }
    auto tampered = golden::load_lines(dir, "b.tsv");
    CHECK(!tampered.has_value());
    if (!tampered) CHECK(tampered.error().kind == golden::ErrorKind::Corrupt);
    auto unlisted = golden::load_lines(dir, "c.tsv");
    CHECK(!unlisted.has_value());
    if (!unlisted) CHECK(unlisted.error().kind == golden::ErrorKind::Missing);
    auto no_dir = golden::load_lines(dir / "nowhere", "a.tsv");
    CHECK(!no_dir.has_value());
    if (!no_dir) CHECK(no_dir.error().kind == golden::ErrorKind::Missing);
    fs::remove_all(dir);
}

int main() { return sh::test::run_all(); }
