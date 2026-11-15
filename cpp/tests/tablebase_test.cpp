// Tests of src/tablebase (WP3): rows, ranking, the race table reader. Golden file (written by the Java exporter of WP1):
// race-counts.tsv (the rows >= 1 count of both sides for 334 positions). The real tables rows1.bin and rows2.bin come from
// STERNHALMA_TABLE_DIR or tablebase/tables/ (Git LFS); a missing or pointer-only table skips its tests, or fails them if
// STERNHALMA_GOLDEN_REQUIRED=1. The crafted-file tests always run.
#include <algorithm>
#include <array>
#include <chrono>
#include <cstdint>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <numeric>
#include <optional>
#include <span>
#include <string>
#include <string_view>
#include <vector>

#include "check.hpp"
#include "geometry.hpp"
#include "golden.hpp"
#include "golden_test_support.hpp"
#include "layout.hpp"
#include "position.hpp"
#include "race_table.hpp"
#include "ranking.hpp"
#include "rows.hpp"
#include "rules.hpp"
#include "sha256.hpp"

using namespace sh;

namespace {

std::vector<std::string_view> split(std::string_view line, char sep) {
    std::vector<std::string_view> out;
    std::size_t start = 0;
    while (true) {
        const std::size_t at = line.find(sep, start);
        if (at == std::string_view::npos) {
            out.push_back(line.substr(start));
            return out;
        }
        out.push_back(line.substr(start, at - start));
        start = at + 1;
    }
}

std::int64_t to_i64(std::string_view s) { return std::stoll(std::string(s)); }

std::vector<int> int_list(std::string_view s) {
    std::vector<int> out;
    if (s == "-" || s.empty()) return out;
    for (const std::string_view part : split(s, ' ')) out.push_back(static_cast<int>(to_i64(part)));
    return out;
}

// ---- crafted table files (region rows >= 4: 15 cells, C(15, 10) = 3003 states) ----

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

struct Craft {
    std::string magic = "SHRT";
    int version = 1;
    int leave_goal = 0;
    int min_row = kCraftRow;
    int pieces = 10;
    std::optional<std::vector<int>> cells;  // default: the region cells
    std::int64_t states = -1;               // default: the region's state count
    std::string body;                       // default: i % 200, every 7th entry 255
    std::optional<std::string> body_sha;    // raw 32 bytes; default: the SHA-256 of the body
};

std::string craft_file(Craft c) {
    const std::vector<int> region = rows::region_cells(kCraftRow);
    const std::vector<int> cells = c.cells.value_or(region);
    const auto ranking = Ranking::create(region, 10);
    if (c.states < 0) c.states = ranking->count();
    if (c.body.empty()) {
        for (std::int64_t i = 0; i < ranking->count(); ++i) c.body.push_back(static_cast<char>(i % 7 == 0 ? 255 : i % 200));
    }
    std::string out = c.magic;
    put_be(out, static_cast<std::uint32_t>(c.version), 4);
    put_be(out, static_cast<std::uint32_t>(c.leave_goal), 4);
    put_be(out, static_cast<std::uint32_t>(c.min_row), 4);
    put_be(out, static_cast<std::uint32_t>(c.pieces), 4);
    put_be(out, static_cast<std::uint32_t>(cells.size()), 4);
    for (const int cell : cells) put_be(out, static_cast<std::uint32_t>(cell), 4);
    put_be(out, static_cast<std::uint64_t>(c.states), 8);
    out += c.body_sha.value_or(hex_to_bytes(hash_of(c.body)));
    out += c.body;
    return out;
}

struct TempDir {
    std::filesystem::path path;
    TempDir() {
        path = std::filesystem::temp_directory_path() / ("sh-tablebase-test-" + std::to_string(std::chrono::steady_clock::now().time_since_epoch().count()));
        std::filesystem::create_directories(path);
    }
    ~TempDir() {
        std::error_code ec;
        std::filesystem::remove_all(path, ec);
    }
    TempDir(const TempDir&) = delete;
    TempDir& operator=(const TempDir&) = delete;
    std::filesystem::path write(const std::string& name, const std::string& content) const {
        const auto file = path / name;
        std::ofstream out(file, std::ios::binary);
        out.write(content.data(), static_cast<std::streamsize>(content.size()));
        return file;
    }
};

Region craft_region(const std::string& file_content) { return Region{kCraftRow, "craft.bin", hash_of(file_content), false}; }

// Loads a crafted file against a region whose constant is the SHA-256 of the file itself (so that only the other checks can fail).
std::expected<RaceTable, LoadError> load_crafted(const std::string& content) {
    const TempDir dir;
    return RaceTable::load(dir.write("craft.bin", content), craft_region(content));
}

std::optional<LoadError::Kind> kind_of(const std::expected<RaceTable, LoadError>& r) {
    if (r) return std::nullopt;
    return r.error().kind;
}

// The real table of a region, or std::nullopt after the test was marked skipped or failed.
std::optional<RaceTable> real_table(const Region& region) {
    auto table = RaceTable::load_default(region);
    if (table) return std::move(*table);
    const std::string message = region.file_name + ": " + table.error().message;
    const bool absent = table.error().kind == LoadError::Kind::Missing || table.error().kind == LoadError::Kind::LfsPointer;
    if (absent && !golden::required()) {
        test::skip(message);
    } else {
        test::count_check();
        test::fail(__FILE__, __LINE__, message);
    }
    return std::nullopt;
}

}  // namespace

// ---- rows ----

SH_TEST(rows_regions) {
    CHECK_EQ(rows::kGoalRow, 5);
    CHECK_EQ(rows::kLastRow, 8);
    CHECK_EQ(static_cast<int>(rows::region_cells(2).size()), 28);
    CHECK_EQ(static_cast<int>(rows::region_cells(1).size()), 36);
    CHECK_EQ(static_cast<int>(rows::region_cells(kCraftRow).size()), 15);
    for (const int min_row : {1, 2, kCraftRow}) {
        const std::vector<int> cells = rows::region_cells(min_row);
        CHECK(std::ranges::is_sorted(cells));
        for (const int c : cells) {
            CHECK(rows::allowed(0, c));
            CHECK(geometry::r(c) >= min_row);
        }
    }
    // the goal corner of player 0 is the corner with r > 4, rows 5 to 8
    for (const int c : geometry::corner_cells(corner::kRPos)) {
        CHECK(rows::row(0, c) >= rows::kGoalRow && rows::row(0, c) <= rows::kLastRow);
        CHECK_EQ(rows::row(1, c), -rows::row(0, c));
    }
}

SH_TEST(rows_to_player0) {
    // player 1 stands in the corner r > 4 at the start; in player 0's orientation that is the corner r < -4
    const Position initial = Position::initial(RuleSet::defaults());
    std::array<int, kPieces> mapped{};
    CHECK(rows::to_player0(1, initial.pieces(1), mapped));
    CHECK(std::ranges::is_sorted(mapped));
    for (const int c : mapped) CHECK_EQ(geometry::corner_of(c), corner::kRNeg);
    for (const int c : initial.pieces(1)) CHECK_EQ(rows::to_player0(1, c), geometry::player_flip(c));
    std::array<int, kPieces> own{};
    CHECK(rows::to_player0(0, initial.pieces(0), own));
    CHECK(std::ranges::equal(own, initial.pieces(0)));
    std::array<int, 3> wrong_size{};
    CHECK(!rows::to_player0(0, initial.pieces(0), wrong_size));
}

// ---- ranking ----

SH_TEST(ranking_counts_of_the_regions) {
    const auto rows2 = kRows2.ranking();
    const auto rows1 = kRows1.ranking();
    CHECK(rows2.has_value() && rows1.has_value());
    CHECK_EQ(rows2->count(), std::int64_t{13123110});
    CHECK_EQ(rows1->count(), std::int64_t{254186856});
}

SH_TEST(ranking_is_a_bijection_on_a_small_region) {
    const std::vector<int> cells = rows::region_cells(kCraftRow);
    const auto ranking = Ranking::create(cells, 10);
    CHECK(ranking.has_value());
    CHECK_EQ(ranking->count(), std::int64_t{3003});
    std::vector<bool> seen(3003, false);
    for (std::int64_t r = 0; r < ranking->count(); ++r) {
        const auto config = ranking->unrank(r);
        CHECK(config.has_value());
        if (!config) continue;
        CHECK(std::ranges::is_sorted(*config));
        CHECK(ranking->covers(*config));
        CHECK_EQ(ranking->rank(*config).value_or(-1), r);
        // the order of the cells does not matter
        std::vector<int> reversed(config->rbegin(), config->rend());
        CHECK_EQ(ranking->rank(reversed).value_or(-1), r);
        seen[static_cast<std::size_t>(ranking->rank(*config).value_or(0))] = true;
    }
    CHECK(std::ranges::all_of(seen, [](bool b) { return b; }));
}

SH_TEST(ranking_samples_of_the_large_regions_round_trip) {
    for (const Region* region : {&kRows1, &kRows2}) {
        const auto ranking = region->ranking();
        CHECK(ranking.has_value());
        const std::int64_t stride = ranking->count() / 9973 + 1;
        int checked = 0;
        for (std::int64_t r = 0; r < ranking->count(); r += stride) {
            const auto config = ranking->unrank(r);
            CHECK(config.has_value());
            CHECK_EQ(ranking->rank(*config).value_or(-1), r);
            ++checked;
        }
        CHECK(checked > 1000);
        // the first and the last rank
        CHECK_EQ(ranking->rank(*ranking->unrank(0)).value_or(-1), std::int64_t{0});
        CHECK_EQ(ranking->rank(*ranking->unrank(ranking->count() - 1)).value_or(-1), ranking->count() - 1);
        CHECK(!ranking->unrank(-1).has_value());
        CHECK(!ranking->unrank(ranking->count()).has_value());
    }
}

SH_TEST(ranking_covers_and_rejects) {
    const std::vector<int> cells = rows::region_cells(kCraftRow);
    const auto ranking = Ranking::create(cells, 10);
    std::vector<int> config(cells.begin(), cells.begin() + 10);
    CHECK(ranking->covers(config));
    std::vector<int> nine(config.begin(), config.begin() + 9);
    CHECK(!ranking->covers(nine));
    CHECK(!ranking->rank(nine).has_value());
    std::vector<int> outside = config;
    outside[3] = geometry::id(0, -8);  // a cell of the start corner, not in the region
    CHECK(!ranking->covers(outside));
    CHECK(!ranking->rank(outside).has_value());
    std::vector<int> negative = config;
    negative[0] = -1;
    CHECK(!ranking->covers(negative));
    std::vector<int> big = config;
    big[0] = kCells;
    CHECK(!ranking->covers(big));
    // a duplicate cell is "covered" (as in Java) but has no rank
    std::vector<int> duplicate = config;
    duplicate[9] = duplicate[0];
    CHECK(ranking->covers(duplicate));
    CHECK(!ranking->rank(duplicate).has_value());
    CHECK_EQ(ranking->index_of(cells[4]), 4);
    CHECK_EQ(ranking->index_of(geometry::id(0, -8)), -1);
    CHECK_EQ(ranking->index_of(-5), -1);
    // construction errors
    CHECK(!Ranking::create(std::vector<int>{3, 3, 4}, 2).has_value());
    CHECK(!Ranking::create(std::vector<int>{4, 3}, 2).has_value());
    CHECK(!Ranking::create(std::vector<int>{0, 1, 500}, 2).has_value());
    CHECK(!Ranking::create(cells, 0).has_value());
    CHECK(!Ranking::create(cells, 11).has_value());
}

// ---- the reader on crafted files ----

SH_TEST(crafted_file_loads_and_counts) {
    const std::string content = craft_file({});
    auto table = load_crafted(content);
    CHECK(table.has_value());
    if (!table) return;
    CHECK_EQ(table->states(), std::int64_t{3003});
    CHECK_EQ(table->min_row(), kCraftRow);
    for (std::int64_t r = 0; r < table->states(); ++r) {
        const int expected = r % 7 == 0 ? 255 : static_cast<int>(r % 200);
        CHECK_EQ(table->entry(r), expected);
        const std::vector<int> config = table->ranking().unrank(r).value();
        CHECK_EQ(table->covers(config), r % 7 != 0);
        CHECK_EQ(table->count(config), r % 7 == 0 ? -1 : expected);
    }
    std::vector<int> outside = rows::region_cells(kCraftRow);
    outside.resize(10);
    outside[0] = geometry::id(0, -8);
    CHECK_EQ(table->count(outside), -1);
    CHECK(!table->covers(outside));
}

SH_TEST(crafted_rejections) {
    using Kind = LoadError::Kind;
    const std::string good = craft_file({});

    // a Git LFS pointer
    const std::string pointer = "version https://git-lfs.github.com/spec/v1\noid sha256:" + std::string(64, 'a') + "\nsize 12345\n";
    const auto pointed = load_crafted(pointer);
    CHECK(kind_of(pointed) == Kind::LfsPointer);
    CHECK(!pointed && pointed.error().message.find("git lfs pull") != std::string::npos);

    CHECK(kind_of(load_crafted(craft_file({.magic = "SHRX"}))) == Kind::BadMagic);
    CHECK(kind_of(load_crafted("SH")) == Kind::BadMagic);
    CHECK(kind_of(load_crafted("")) == Kind::BadMagic);
    CHECK(kind_of(load_crafted(craft_file({.version = 2}))) == Kind::BadVersion);
    CHECK(kind_of(load_crafted(craft_file({.leave_goal = 2}))) == Kind::ImplausibleHeader);
    CHECK(kind_of(load_crafted(craft_file({.pieces = 0}))) == Kind::ImplausibleHeader);
    CHECK(kind_of(load_crafted(craft_file({.states = 0}))) == Kind::ImplausibleHeader);
    CHECK(kind_of(load_crafted(craft_file({.states = std::int64_t{1} << 40}))) == Kind::ImplausibleHeader);
    CHECK(kind_of(load_crafted(good.substr(0, 30))) == Kind::Truncated);                         // inside the header
    CHECK(kind_of(load_crafted(good.substr(0, good.size() - 1))) == Kind::Truncated);           // one body byte short
    CHECK(kind_of(load_crafted(good.substr(0, good.size() / 2))) == Kind::Truncated);           // half
    CHECK(kind_of(load_crafted(good + "x")) == Kind::TrailingBytes);

    // the SHA-256 of the whole file differs from the constant of the region (the file is intact)
    {
        const TempDir dir;
        const auto file = dir.write("craft.bin", good);
        Region region = craft_region(good);
        region.sha256 = std::string(64, '0');
        const auto result = RaceTable::load(file, region);
        CHECK(kind_of(result) == Kind::FileChecksum);
        // a flipped body byte also fails the whole-file SHA-256 first
        std::string flipped = good;
        flipped.back() = static_cast<char>(flipped.back() ^ 1);
        const auto file2 = dir.write("flipped.bin", flipped);
        CHECK(kind_of(RaceTable::load(file2, craft_region(good))) == Kind::FileChecksum);
    }
    // a body that does not match the body checksum of the header, although the constant of the region matches the file
    {
        Craft c;
        c.body_sha = std::string(32, '\x01');
        CHECK(kind_of(load_crafted(craft_file(c))) == Kind::BodyChecksum);
    }
    // another region: another minimum row, another cell list, leave-goal allowed, 9 pieces, another state count
    CHECK(kind_of(load_crafted(craft_file({.min_row = kCraftRow + 1}))) == Kind::OtherRegion);
    CHECK(kind_of(load_crafted(craft_file({.leave_goal = 1}))) == Kind::OtherRegion);
    CHECK(kind_of(load_crafted(craft_file({.pieces = 9}))) == Kind::OtherRegion);
    {
        std::vector<int> cells = rows::region_cells(kCraftRow);
        cells[0] += 1;
        CHECK(kind_of(load_crafted(craft_file({.cells = cells}))) == Kind::OtherRegion);
    }
    // a missing file
    {
        const TempDir dir;
        CHECK(kind_of(RaceTable::load(dir.path / "nothing.bin", craft_region(good))) == Kind::Missing);
        CHECK(kind_of(RaceTable::load(dir.path, craft_region(good))) == Kind::Missing);  // a directory
    }
}

SH_TEST(sha256_constants_are_the_recorded_ones) {
    CHECK_EQ(kRows1.sha256, std::string("85850d6f47f526daf45ac5cd3a0dad8841244cb50d0d8d15a5b6a54f09d1bfd1"));
    CHECK_EQ(kRows2.sha256, std::string("d62edf38bbccb229ca055da3054a11e6daffae817d2293dbfa523ad8bb3678e3"));
}

// ---- the real tables ----

SH_TEST(rows2_loads_and_is_consistent) {
    const auto start = std::chrono::steady_clock::now();
    auto table = real_table(kRows2);
    if (!table) return;
    const double seconds = std::chrono::duration<double>(std::chrono::steady_clock::now() - start).count();
    std::cout << "rows2 loaded in " << seconds << " s (" << table->states() << " states)\n";
    CHECK_EQ(table->states(), std::int64_t{13123110});
    // the finished goal corner needs no move
    const std::span<const int, kCornerSize> goal = geometry::corner_cells(corner::kRPos);
    CHECK_EQ(table->count(goal), 0);
    // the start corner lies outside the region
    CHECK_EQ(table->count(geometry::corner_cells(corner::kRNeg)), -1);
    // every entry is a count or "unknown"
    int reachable = 0;
    const std::int64_t stride = table->states() / 20011 + 1;
    for (std::int64_t r = 0; r < table->states(); r += stride) {
        const int e = table->entry(r);
        if (e != RaceTable::kUnknown) ++reachable;
    }
    CHECK(reachable > 0);
}

SH_TEST(rows1_loads_and_agrees_with_rows2) {
    const auto start = std::chrono::steady_clock::now();
    auto rows1 = real_table(kRows1);
    if (!rows1) return;
    const double seconds = std::chrono::duration<double>(std::chrono::steady_clock::now() - start).count();
    std::cout << "rows1 loaded in " << seconds << " s (" << rows1->states() << " states)\n";
    CHECK_EQ(rows1->states(), std::int64_t{254186856});
    CHECK_EQ(rows1->count(geometry::corner_cells(corner::kRPos)), 0);
    auto rows2 = real_table(kRows2);
    if (!rows2) return;
    // a configuration of the smaller region is also in the larger one, whose count cannot be larger (more room to move)
    int compared = 0;
    const std::int64_t stride = rows2->states() / 4999 + 1;
    for (std::int64_t r = 0; r < rows2->states(); r += stride) {
        const std::vector<int> config = rows2->ranking().unrank(r).value();
        const int c2 = rows2->count(config);
        const int c1 = rows1->count(config);
        if (c2 >= 0) {
            CHECK(c1 >= 0);
            CHECK(c1 <= c2);
            ++compared;
        }
    }
    CHECK(compared > 100);
}

SH_TEST(race_counts_match_golden) {
    const auto lines = golden::lines_or_skip("race-counts.tsv");
    if (!lines) return;
    auto table = real_table(kRows1);
    if (!table) return;
    int rows_seen = 0;
    int covered = 0;
    int not_covered = 0;
    for (const std::string& line : *lines) {
        if (line.empty() || line.front() == '#') continue;
        const auto row = split(line, '\t');
        CHECK_EQ(static_cast<int>(row.size()), 5);
        if (row.size() != 5) continue;
        Position pos = Position::initial(RuleSet::defaults());
        std::array<int, kMaxMoves> buffer{};
        bool legal = true;
        for (const int m : int_list(row[2])) {
            const int n = pos.generate(buffer);
            const auto legal_moves = std::span<const int>(buffer).first(static_cast<std::size_t>(n));
            if (std::ranges::find(legal_moves, m) == legal_moves.end()) legal = false;
            if (!legal) break;
            pos.make(m);
        }
        CHECK(legal);
        if (!legal) continue;
        CHECK_EQ(pos.ply(), static_cast<int>(to_i64(row[1])));
        std::array<int, kPieces> cells0{};
        std::array<int, kPieces> cells1{};
        CHECK(rows::to_player0(0, pos.pieces(0), cells0));
        CHECK(rows::to_player0(1, pos.pieces(1), cells1));
        const int expected0 = static_cast<int>(to_i64(row[3]));
        const int expected1 = static_cast<int>(to_i64(row[4]));
        const int actual0 = table->count(cells0);
        const int actual1 = table->count(cells1);
        CHECK_EQ(actual0, expected0);
        CHECK_EQ(actual1, expected1);
        if (actual0 != expected0 || actual1 != expected1) {
            ::sh::test::fail(__FILE__, __LINE__, "golden line: " + std::string(row[0]) + " ply " + std::string(row[1]) + ": counts " + std::to_string(actual0) +
                                                     "/" + std::to_string(actual1) + ", expected " + std::to_string(expected0) + "/" + std::to_string(expected1));
        }
        covered += (expected0 >= 0) + (expected1 >= 0);
        not_covered += (expected0 < 0) + (expected1 < 0);
        ++rows_seen;
    }
    CHECK_EQ(rows_seen, 332);
    CHECK(covered > 100 && not_covered > 100);  // the file exercises both branches
    std::cout << "race-counts.tsv: " << rows_seen << " positions, " << covered << " covered counts, " << not_covered << " not covered\n";
}

int main() { return sh::test::run_all(); }
