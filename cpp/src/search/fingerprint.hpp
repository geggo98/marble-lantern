// The search-tree fingerprint of a player spec (Java: Fingerprint): what it plays and how many nodes it needs on the reference
// positions. Two builds that claim to search the identical tree must print the identical lines; any difference in move ordering,
// pruning, hashing, tie-breaking or evaluation shows up as a different node count or move somewhere.
//
// Two parts, each a checksum over the lines' numbers:
//  - positions(): per reference position the move, score, completed depth and node count of the spec's node-limited search, and
//    the move, score and exact node count of a search to the fixed depth 4 without a node limit (a fresh searcher each time),
//    plus the move lists of the positions themselves;
//  - game(): the move sequence and the node totals of a full self-play game from full-game opening 0 at a node budget, with the
//    players keeping their tables between moves.
//
// The reference move lists come from the golden file reference.tsv (the Java ReferencePositions), verified against its manifest.
#pragma once

#include <array>
#include <cstdint>
#include <expected>
#include <string>
#include <string_view>
#include <vector>

#include "position.hpp"

namespace sh::fingerprint {

inline constexpr int kFixedDepth = 4;
inline constexpr std::array<std::int64_t, 2> kGameNodes = {1'000, 5'000};

// The reference players of Java ReferencePositions and Strategies.BEST_SPEC (+ ",nodes=20000").
inline constexpr std::string_view kR2Spec = "ab:eval=linear-r1,pvs=1,killers=1,history=1,lmr=2,asp=1,ttrep=1,mate=1,partial=1,nodes=20000";
inline constexpr std::string_view kR3Spec =
    "ab:eval=racelin1-r3,pvs=1,killers=1,history=1,lmr=2,asp=1,ttrep=1,mate=1,partial=1,fut=1,futk=20,fut2=1,race=1,racerows=1,nodes=20000";
inline constexpr std::string_view kBestSpec = kR3Spec;

// A fingerprint part: the checksum (Java long, wrapping) and the lines it was computed from.
struct Part {
    std::uint64_t checksum = 0;
    std::vector<std::string> lines;
    // The checksum as Java prints it: signed decimal with an L suffix.
    std::string checksum_text() const;
};

struct ReferenceData {
    std::vector<std::vector<int>> positions;  // the 64 move lists of ReferencePositions.moveLists()
    std::vector<std::vector<int>> openings;   // the 2 full-game openings
};

// Reads reference.tsv from the golden directory and checks every row against its ply and hash column.
std::expected<ReferenceData, std::string> load_reference();

// Replays a move list from the initial position; fails on an illegal move.
std::expected<Position, std::string> replay(const std::vector<int>& moves);

std::expected<Part, std::string> positions(const ReferenceData& reference, std::string_view spec);
std::expected<Part, std::string> game(const ReferenceData& reference, std::string_view spec, std::int64_t nodes);

// `spec` with its node budget replaced by `nodes`.
std::string with_nodes(std::string_view spec, std::int64_t nodes);

}  // namespace sh::fingerprint
