// A race table, read only (Java: RaceTable, RaceCounts, TableFile). The exact restricted solitaire move count for every
// configuration of player 0's ten pieces on the allowed cells with row >= min_row, one byte each, built with leave_goal =
// Forbidden. Cells are in player 0's orientation (map player 1's cells with rows::to_player0 first). A count is an *upper* bound
// of the unrestricted solitaire distance; it must never serve as a lower bound in an adjudication.
//
// File format (big-endian, as written by the Java DataOutput/ByteBuffer code):
//   magic "SHRT" 4 bytes | version int (1) | leaveGoal int (0 forbidden, 1 allowed) | minRow int | pieces int |
//   cell count int, then the cells as ints (ascending) | state count long | body SHA-256 32 bytes | body, one byte per rank
//   (255 = unreachable configuration).
//
// There is no mmap: the whole file is read into memory (242 MB for rows >= 1), as the Java code reads it into a byte[].
// Errors are values (std::expected); nothing throws.
#pragma once

#include <cstddef>
#include <cstdint>
#include <expected>
#include <filesystem>
#include <span>
#include <string>
#include <vector>

#include "ranking.hpp"

namespace sh {

// A table region with the name and the recorded SHA-256 of its whole file (equal to the Git LFS object id).
struct Region {
    int min_row = 0;
    std::string file_name;
    std::string sha256;
    bool committed = true;

    // The allowed cells of player 0 with row >= min_row, ranked as 10-piece subsets.
    std::expected<Ranking, std::string> ranking() const;
};

extern const Region kRows2;  // rows >= 2, 28 cells, 13,123,110 states, the default table of race play
extern const Region kRows1;  // rows >= 1, 36 cells, 254,186,856 states

struct LoadError {
    enum class Kind : std::uint8_t {
        Missing,           // no such file
        Unreadable,        // the file exists but could not be read
        LfsPointer,        // a Git LFS pointer file, the real content was never pulled
        BadMagic,
        BadVersion,
        ImplausibleHeader,
        Truncated,         // shorter than its header says
        TrailingBytes,     // longer than its header says
        FileChecksum,      // SHA-256 of the whole file differs from the constant of the region
        BodyChecksum,      // the SHA-256 of the body differs from the one in the header
        OtherRegion,       // a valid table of another region or another rule
    };
    Kind kind = Kind::Missing;
    std::string message;
};

class RaceTable {
public:
    static constexpr int kUnknown = 255;  // the entry of an unreachable configuration

    // Reads and verifies a table file: pointer file, magic, version, header, size, SHA-256 of the whole file against the
    // constant of `region`, body SHA-256 against the header, and the header against the region (leave-goal rule, minimum row,
    // 10 pieces, cell list, state count).
    static std::expected<RaceTable, LoadError> load(const std::filesystem::path& file, const Region& region);

    // STERNHALMA_TABLE_DIR, else <repository root>/tablebase/tables (the root is found by walking up from the working
    // directory to settings.gradle.kts, else from this source file).
    static std::filesystem::path table_dir();
    static std::filesystem::path default_path(const Region& region) { return table_dir() / region.file_name; }
    // load(default_path(region), region)
    static std::expected<RaceTable, LoadError> load_default(const Region& region);

    RaceTable(const RaceTable&) = delete;
    RaceTable& operator=(const RaceTable&) = delete;
    RaceTable(RaceTable&&) noexcept = default;
    RaceTable& operator=(RaceTable&&) noexcept = default;
    ~RaceTable() = default;

    const Region& region() const { return region_; }
    int min_row() const { return region_.min_row; }
    const Ranking& ranking() const { return ranking_; }
    std::int64_t states() const { return ranking_.count(); }
    // The raw entry of a rank (kUnknown for an unreachable configuration); rank must be in 0 .. states() - 1.
    int entry(std::int64_t rank) const;

    // Whether the configuration lies inside the region and is reachable (exactly 10 pieces).
    bool covers(std::span<const int> own_cells) const;
    // The remaining moves of a covered configuration, or -1 if covers() is false.
    int count(std::span<const int> own_cells) const;

private:
    RaceTable(Region region, Ranking ranking, std::vector<char> file, std::size_t body_offset)
        : region_(std::move(region)), ranking_(std::move(ranking)), file_(std::move(file)), body_offset_(body_offset) {}

    Region region_;
    Ranking ranking_;
    std::vector<char> file_;  // the whole file; the body starts at body_offset_
    std::size_t body_offset_ = 0;
};

}  // namespace sh
