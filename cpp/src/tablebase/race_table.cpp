#include "race_table.hpp"

#include <algorithm>
#include <array>
#include <bit>
#include <cstdlib>
#include <fstream>
#include <limits>
#include <optional>
#include <source_location>
#include <string_view>

#include "le_bytes.hpp"
#include "rows.hpp"
#include "sha256.hpp"

namespace sh {

const Region kRows2{2, "rows2.bin", "d62edf38bbccb229ca055da3054a11e6daffae817d2293dbfa523ad8bb3678e3", true};
const Region kRows1{1, "rows1.bin", "85850d6f47f526daf45ac5cd3a0dad8841244cb50d0d8d15a5b6a54f09d1bfd1", true};

std::expected<Ranking, std::string> Region::ranking() const {
    const std::vector<int> cells = rows::region_cells(min_row);
    return Ranking::create(cells, kPieces);
}

namespace {

constexpr std::string_view kMagic = "SHRT";
constexpr std::int32_t kVersion = 1;
constexpr std::string_view kLfsPointerPrefix = "version https://git-lfs.github.com/spec/v1";
constexpr std::size_t kMaxPointerSize = 1024;

std::unexpected<LoadError> fail(LoadError::Kind kind, std::string message) {
    return std::unexpected(LoadError{kind, std::move(message)});
}

// The file format is big-endian; le_bytes.hpp reads little-endian, so the bytes are swapped after the read.
template <class T>
std::optional<T> read_be(std::span<const std::byte> bytes, std::size_t offset) {
    const auto v = read_le<T>(bytes, offset);
    if (!v) return std::nullopt;
    return std::byteswap(*v);
}

std::string to_hex(std::span<const std::byte> bytes) {
    static constexpr std::string_view kDigits = "0123456789abcdef";
    std::string out;
    out.reserve(bytes.size() * 2);
    for (const std::byte b : bytes) {
        const auto v = std::to_integer<unsigned>(b);
        out.push_back(kDigits[v >> 4]);
        out.push_back(kDigits[v & 15U]);
    }
    return out;
}

// The "oid sha256:" line of a Git LFS pointer, or an empty string.
std::string pointer_oid(std::string_view text) {
    constexpr std::string_view kKey = "oid sha256:";
    const std::size_t at = text.find(kKey);
    if (at == std::string_view::npos) return {};
    std::string_view rest = text.substr(at + kKey.size());
    rest = rest.substr(0, rest.find_first_of("\r\n"));
    return std::string(rest);
}

std::filesystem::path repository_root() {
    std::error_code ec;
    for (std::filesystem::path dir = std::filesystem::current_path(ec); !ec && !dir.empty(); dir = dir.parent_path()) {
        if (std::filesystem::exists(dir / "settings.gradle.kts", ec)) return dir;
        if (dir == dir.parent_path()) break;
    }
    // <repo>/cpp/src/tablebase/race_table.cpp
    return std::filesystem::path(std::source_location::current().file_name()).parent_path().parent_path().parent_path().parent_path();
}

}  // namespace

std::filesystem::path RaceTable::table_dir() {
    if (const char* env = std::getenv("STERNHALMA_TABLE_DIR"); env != nullptr && *env != '\0') return env;
    return repository_root() / "tablebase" / "tables";
}

std::expected<RaceTable, LoadError> RaceTable::load_default(const Region& region) { return load(default_path(region), region); }

std::expected<RaceTable, LoadError> RaceTable::load(const std::filesystem::path& file, const Region& region) {
    const std::string name = file.filename().string();
    std::error_code ec;
    if (!std::filesystem::is_regular_file(file, ec)) return fail(LoadError::Kind::Missing, name + " does not exist");
    const std::uintmax_t size = std::filesystem::file_size(file, ec);
    if (ec) return fail(LoadError::Kind::Unreadable, name + ": cannot get the size: " + ec.message());
    std::vector<char> data(static_cast<std::size_t>(size));
    {
        std::ifstream in(file, std::ios::binary);
        if (!in) return fail(LoadError::Kind::Unreadable, name + ": cannot open");
        in.read(data.data(), static_cast<std::streamsize>(data.size()));
        if (in.gcount() != static_cast<std::streamsize>(data.size())) return fail(LoadError::Kind::Unreadable, name + ": short read");
    }
    const std::span<const std::byte> bytes = std::as_bytes(std::span<const char>(data));

    if (data.size() <= kMaxPointerSize && std::string_view(data.data(), data.size()).starts_with(kLfsPointerPrefix)) {
        const std::string oid = pointer_oid(std::string_view(data.data(), data.size()));
        return fail(LoadError::Kind::LfsPointer,
                    name + " is a Git LFS pointer (oid " + oid + ")" + (oid == region.sha256 ? "" : ", which differs from the recorded SHA-256, the committed table is stale") +
                        ": run git lfs pull");
    }

    // ---- header ----
    if (bytes.size() < kMagic.size() || !std::equal(kMagic.begin(), kMagic.end(), bytes.begin(), [](char a, std::byte b) { return static_cast<unsigned char>(a) == std::to_integer<unsigned>(b); })) {
        return fail(LoadError::Kind::BadMagic, name + " is not a race table (bad magic)");
    }
    const auto too_short = [&] { return fail(LoadError::Kind::Truncated, name + " is shorter than its header"); };
    const auto version = read_be<std::int32_t>(bytes, 4);
    if (!version) return too_short();
    if (*version != kVersion) return fail(LoadError::Kind::BadVersion, name + " has format version " + std::to_string(*version) + ", expected " + std::to_string(kVersion));
    const auto leave_goal = read_be<std::int32_t>(bytes, 8);
    const auto min_row = read_be<std::int32_t>(bytes, 12);
    const auto pieces = read_be<std::int32_t>(bytes, 16);
    const auto cell_count = read_be<std::int32_t>(bytes, 20);
    if (!leave_goal || !min_row || !pieces || !cell_count) return too_short();
    if (*leave_goal < 0 || *leave_goal > 1 || *pieces <= 0 || *cell_count <= 0 || *cell_count > kCells) {
        return fail(LoadError::Kind::ImplausibleHeader, name + " has an implausible header");
    }
    std::vector<int> cells;
    std::size_t at = 24;
    for (std::int32_t i = 0; i < *cell_count; ++i, at += 4) {
        const auto c = read_be<std::int32_t>(bytes, at);
        if (!c) return too_short();
        cells.push_back(*c);
    }
    const auto states = read_be<std::int64_t>(bytes, at);
    if (!states) return too_short();
    at += 8;
    if (*states <= 0 || *states > std::numeric_limits<std::int32_t>::max()) {
        return fail(LoadError::Kind::ImplausibleHeader, name + " has an implausible state count " + std::to_string(*states));
    }
    if (bytes.size() < at + 32) return too_short();
    const std::span<const std::byte> body_sha = bytes.subspan(at, 32);
    at += 32;
    const std::size_t body_offset = at;
    const auto body_size = static_cast<std::size_t>(*states);
    if (bytes.size() - body_offset < body_size) {
        return fail(LoadError::Kind::Truncated, name + " has " + std::to_string(bytes.size() - body_offset) + " body bytes, its header says " + std::to_string(body_size));
    }
    if (bytes.size() - body_offset > body_size) return fail(LoadError::Kind::TrailingBytes, name + " has trailing bytes");

    // ---- checksums ----
    const std::string file_sha = sha256_hex(bytes);
    if (file_sha != region.sha256) {
        return fail(LoadError::Kind::FileChecksum, name + " has sha256 " + file_sha + ", expected " + region.sha256);
    }
    if (sha256_hex(bytes.subspan(body_offset)) != to_hex(body_sha)) {
        return fail(LoadError::Kind::BodyChecksum, name + " body checksum mismatch");
    }

    // ---- the header against the region ----
    auto ranking = region.ranking();
    if (!ranking) return fail(LoadError::Kind::OtherRegion, name + ": the region has no valid ranking: " + ranking.error());
    if (*leave_goal != 0 || *min_row != region.min_row || *pieces != kPieces || !std::ranges::equal(cells, ranking->cells()) || *states != ranking->count()) {
        return fail(LoadError::Kind::OtherRegion, name + " describes another region");
    }
    return RaceTable(region, std::move(*ranking), std::move(data), body_offset);
}

int RaceTable::entry(std::int64_t rank) const {
    return static_cast<unsigned char>(file_[body_offset_ + static_cast<std::size_t>(rank)]);
}

bool RaceTable::covers(std::span<const int> own_cells) const {
    const auto rank = ranking_.rank(own_cells);
    return rank && entry(*rank) != kUnknown;
}

int RaceTable::count(std::span<const int> own_cells) const {
    const auto rank = ranking_.rank(own_cells);
    if (!rank) return -1;
    const int v = entry(*rank);
    return v == kUnknown ? -1 : v;
}

}  // namespace sh
