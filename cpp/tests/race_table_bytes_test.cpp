// Tests of RaceTable::load_bytes (the browser path: a table that is already in memory) and of RaceTable::default_path.
// Crafted tables only (region rows >= 4: 15 cells, C(15, 10) = 3003 states), so the test needs no Git LFS object.
#include <algorithm>
#include <chrono>
#include <cstdint>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <optional>
#include <span>
#include <string>
#include <vector>

#include "check.hpp"
#include "race_table.hpp"
#include "ranking.hpp"
#include "rows.hpp"
#include "sha256.hpp"

using namespace sh;

namespace {

constexpr int kCraftRow = 4;

void put_be(std::string& out, std::uint64_t value, int bytes) {
    for (int i = bytes - 1; i >= 0; --i) out.push_back(static_cast<char>((value >> (8 * i)) & 0xffU));
}

std::string hex_to_bytes(const std::string& hex) {
    std::string out;
    for (std::size_t i = 0; i + 1 < hex.size(); i += 2) out.push_back(static_cast<char>(std::stoi(hex.substr(i, 2), nullptr, 16)));
    return out;
}

std::string hash_of(const std::string& data) { return sha256_hex(std::as_bytes(std::span<const char>(data))); }

// A well-formed table file of the region rows >= 4; `body_sha` replaces the body checksum of the header if given.
std::string craft_file(const std::optional<std::string>& body_sha = std::nullopt, const std::optional<std::string>& body_override = std::nullopt) {
    const std::vector<int> cells = rows::region_cells(kCraftRow);
    const auto ranking = Ranking::create(cells, 10);
    std::string body;
    if (body_override) {
        body = *body_override;
    } else {
        for (std::int64_t i = 0; i < ranking->count(); ++i) body.push_back(static_cast<char>(i % 7 == 0 ? 255 : i % 200));
    }
    std::string out = "SHRT";
    put_be(out, 1, 4);
    put_be(out, 0, 4);
    put_be(out, static_cast<std::uint64_t>(kCraftRow), 4);
    put_be(out, 10, 4);
    put_be(out, cells.size(), 4);
    for (const int cell : cells) put_be(out, static_cast<std::uint32_t>(cell), 4);
    put_be(out, static_cast<std::uint64_t>(ranking->count()), 8);
    out += body_sha.value_or(hex_to_bytes(hash_of(body)));
    out += body;
    return out;
}

Region region_of(const std::string& content) { return Region{kCraftRow, "craft.bin", hash_of(content), false}; }

std::vector<char> as_vector(const std::string& s) { return std::vector<char>(s.begin(), s.end()); }

std::optional<LoadError::Kind> kind_of(const std::expected<RaceTable, LoadError>& r) {
    if (r) return std::nullopt;
    return r.error().kind;
}

}  // namespace

SH_TEST(load_bytes_accepts_a_good_table_and_reads_the_same_counts_as_load) {
    const std::string content = craft_file();
    const Region region = region_of(content);
    for (const bool verify : {true, false}) {
        auto table = RaceTable::load_bytes(as_vector(content), "craft.bin", region, verify);
        CHECK(table.has_value());
        if (!table) continue;
        CHECK_EQ(table->states(), 3003);
        CHECK_EQ(table->entry(0), RaceTable::kUnknown);  // rank 0: 0 % 7 == 0
        CHECK_EQ(table->entry(1), 1);
        CHECK_EQ(table->entry(8), 8);
    }
}

SH_TEST(load_bytes_default_verifies_the_file_checksum) {
    const std::string content = craft_file();
    Region wrong = region_of(content);
    wrong.sha256 = std::string(64, '0');
    CHECK(kind_of(RaceTable::load_bytes(as_vector(content), "craft.bin", wrong)) == LoadError::Kind::FileChecksum);
    CHECK(kind_of(RaceTable::load_bytes(as_vector(content), "craft.bin", wrong, true)) == LoadError::Kind::FileChecksum);
}

SH_TEST(load_bytes_verify_false_skips_exactly_the_two_checksums) {
    const std::string content = craft_file();
    Region wrong = region_of(content);
    wrong.sha256 = std::string(64, '0');
    CHECK(RaceTable::load_bytes(as_vector(content), "craft.bin", wrong, false).has_value());  // file checksum not checked

    // A body that does not match the checksum of the header: the whole-file constant is made to fit, so only the body check can fail.
    const std::string bad_body = craft_file(hex_to_bytes(std::string(64, '1')));
    const Region fits = region_of(bad_body);
    CHECK(kind_of(RaceTable::load_bytes(as_vector(bad_body), "craft.bin", fits, true)) == LoadError::Kind::BodyChecksum);
    CHECK(RaceTable::load_bytes(as_vector(bad_body), "craft.bin", fits, false).has_value());

    // The structure is checked in both modes.
    std::string truncated = content;
    truncated.pop_back();
    std::string trailing = content + 'x';
    std::string html = "<html>404</html>";
    for (const bool verify : {true, false}) {
        CHECK(kind_of(RaceTable::load_bytes(as_vector(truncated), "craft.bin", region_of(truncated), verify)) == LoadError::Kind::Truncated);
        CHECK(kind_of(RaceTable::load_bytes(as_vector(trailing), "craft.bin", region_of(trailing), verify)) == LoadError::Kind::TrailingBytes);
        CHECK(kind_of(RaceTable::load_bytes(as_vector(html), "craft.bin", region_of(html), verify)) == LoadError::Kind::BadMagic);
        CHECK(kind_of(RaceTable::load_bytes({}, "craft.bin", region_of(""), verify)) == LoadError::Kind::BadMagic);
    }
}

SH_TEST(load_bytes_recognises_a_git_lfs_pointer) {
    const std::string pointer = "version https://git-lfs.github.com/spec/v1\noid sha256:" + std::string(64, 'a') + "\nsize 123\n";
    for (const bool verify : {true, false}) {
        CHECK(kind_of(RaceTable::load_bytes(as_vector(pointer), "craft.bin", region_of(pointer), verify)) == LoadError::Kind::LfsPointer);
    }
}

namespace {

void write_file(const std::filesystem::path& file, const std::string& content) {
    std::filesystem::create_directories(file.parent_path());
    std::ofstream out(file, std::ios::binary | std::ios::trunc);
    out << content;
}

const std::string kPointer = "version https://git-lfs.github.com/spec/v1\noid sha256:" + std::string(64, 'b') + "\nsize 1\n";

// Runs `body(root, env_dir)` in a temporary repository root (it holds settings.gradle.kts, the current directory is moved into it)
// with STERNHALMA_TABLE_DIR set to <root>/envdir; restores the current directory and the environment afterwards.
template <typename Body>
void in_temporary_root(Body body) {
    namespace fs = std::filesystem;
    const fs::path base = fs::temp_directory_path() / ("sh-race-path-test-" + std::to_string(std::chrono::steady_clock::now().time_since_epoch().count()));
    fs::create_directories(base);
    const fs::path root = fs::canonical(base);
    write_file(root / "settings.gradle.kts", "");
    const fs::path env_dir = root / "envdir";
    fs::create_directories(env_dir);
    const char* previous = std::getenv("STERNHALMA_TABLE_DIR");
    const std::optional<std::string> saved = previous ? std::optional<std::string>(previous) : std::nullopt;
    const fs::path old_cwd = fs::current_path();
    setenv("STERNHALMA_TABLE_DIR", env_dir.c_str(), 1);
    fs::current_path(root);
    body(root, env_dir);
    fs::current_path(old_cwd);
    if (saved) setenv("STERNHALMA_TABLE_DIR", saved->c_str(), 1); else unsetenv("STERNHALMA_TABLE_DIR");
    std::error_code ec;
    fs::remove_all(base, ec);
}

std::string canon(const std::filesystem::path& p) {
    std::error_code ec;
    return std::filesystem::weakly_canonical(p, ec).string();
}

}  // namespace

SH_TEST(default_path_takes_the_directory_of_the_environment_if_it_holds_a_real_file) {
    in_temporary_root([](const std::filesystem::path& root, const std::filesystem::path& env_dir) {
        write_file(env_dir / "rows2.bin", craft_file());  // content does not matter here, only that it is no pointer
        write_file(root / "tablebase" / "tables" / "rows2.bin", craft_file());  // a real file further down the order must not win
        CHECK_EQ(canon(RaceTable::default_path(kRows2)), canon(env_dir / "rows2.bin"));
    });
}

SH_TEST(default_path_skips_a_pointer_in_the_environment_directory_and_takes_the_cache) {
    in_temporary_root([](const std::filesystem::path& root, const std::filesystem::path& env_dir) {
        write_file(env_dir / "rows2.bin", kPointer);
        write_file(root / "tablebase" / "tables" / "rows2.bin", kPointer);
        write_file(root / "tablebase" / "build" / "tables" / "rows2.bin", craft_file());
        CHECK_EQ(canon(RaceTable::default_path(kRows2)), canon(root / "tablebase" / "build" / "tables" / "rows2.bin"));
    });
}

SH_TEST(default_path_prefers_tablebase_tables_over_the_cache) {
    in_temporary_root([](const std::filesystem::path& root, const std::filesystem::path& env_dir) {
        write_file(env_dir / "rows2.bin", kPointer);
        write_file(root / "tablebase" / "tables" / "rows2.bin", craft_file());
        write_file(root / "tablebase" / "build" / "tables" / "rows2.bin", craft_file());
        CHECK_EQ(canon(RaceTable::default_path(kRows2)), canon(root / "tablebase" / "tables" / "rows2.bin"));
    });
}

SH_TEST(default_path_falls_back_to_the_environment_directory_if_no_candidate_is_a_real_file) {
    in_temporary_root([](const std::filesystem::path& root, const std::filesystem::path& env_dir) {
        write_file(env_dir / "rows2.bin", kPointer);
        write_file(root / "tablebase" / "tables" / "rows2.bin", kPointer);  // the cache candidate is missing
        CHECK_EQ(canon(RaceTable::default_path(kRows2)), canon(env_dir / "rows2.bin"));
    });
}

int main() { return sh::test::run_all(); }
