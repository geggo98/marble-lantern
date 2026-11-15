// The jump structure of a two-player position, kept up to date cell by cell, and the bestGain search on it (Java: JumpGraph).
// The answers are those of LinearEvaluator::best_gain / GainSearch, which stay the oracle.
//
// A jump chain moves over the free allowed cells along the edges "X to Y = X + 2e over an occupied hurdle X + e" (see GainSearch
// for why chains are the connected components of these edges and why the moving piece's own cell does not matter). Here
// adj_lo_/adj_hi_ hold, for every cell X, the set of landings Y whose hurdle is occupied, as a 128-bit set. Filling or emptying a
// cell x flips one bit in six sets, so a move costs twelve bit flips and toggle() undoes itself. A component is found by a flood
// fill with set operations. Components are labelled lazily, when the first landing of a piece reaches them; a generation stamp per
// cell marks the labels of the current position, so nothing is cleared.
//
// Two players with the same allowed cells only (the two-player layout), so one graph serves both sides.
#pragma once

#include <array>
#include <cstdint>
#include <span>

#include "geometry.hpp"
#include "position.hpp"

namespace sh {

class JumpGraph {
public:
    // Empties the board.
    void clear();
    // Fills the cell x if it is empty, else empties it; flipping the same cell again restores the graph.
    void toggle(int x);
    // Whether the cell is occupied (for checks).
    bool occupied(int c) const { return ((c < 64 ? occ_lo_ : occ_hi_) >> (c & 63) & 1U) != 0; }

    // Starts the labelling of the current position: no component known.
    void begin();
    // The best gain of `player` (see LinearEvaluator::best_gain) from its pieces' keys, packed distance << 8 | cell, falling;
    // pieces in the goal come last with distance 0 and are never reached. begin() must have been called for the position.
    int best_gain(int player, std::span<const int> keys);

    // Whether the graph equals one built from the position's occupancy (for tests).
    bool matches(const Position& position) const;

private:
    // Labels the component of the free cell `start` with the next number and returns it.
    int label(int start);

    std::uint64_t occ_lo_ = 0;  // occupied cells (any player), cells 0 to 63
    std::uint64_t occ_hi_ = 0;  // cells 64 to 120 at bit c - 64
    // Per cell X: the landings Y such that the cell between X and Y is occupied.
    std::array<std::uint64_t, kCells> adj_lo_{};
    std::array<std::uint64_t, kCells> adj_hi_{};

    // the labelling of the current position
    std::uint64_t free_lo_ = 0;
    std::uint64_t free_hi_ = 0;
    std::uint32_t generation_ = 1;  // unsigned: the wrap-around of Java's int is defined here
    std::array<std::uint32_t, kCells> stamp_{};
    std::array<std::uint8_t, kCells> component_{};
    int components_ = 0;
    // Per component: the smallest distance of player 0 and of player 1 over its cells.
    std::array<int, kCells> min_dist0_{};
    std::array<int, kCells> min_dist1_{};
};

}  // namespace sh
