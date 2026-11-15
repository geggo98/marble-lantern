// The row axis of the two-player game (Java: Rows). Player 0 advances towards r > 4, player 1 towards r < -4; in a player's own
// orientation the row of a cell is r for player 0 and -r for player 1, so both players advance towards higher rows. The
// player symmetry (geometry::player_flip) maps player 1's orientation onto player 0's.
#pragma once

#include <span>
#include <vector>

#include "geometry.hpp"
#include "layout.hpp"

namespace sh::rows {

inline constexpr int kGoalRow = 5;  // first row of the goal corner in a player's own orientation
inline constexpr int kLastRow = 8;  // last row of the board in a player's own orientation

// The row of a cell in the orientation of a player.
constexpr int row(int player, int cell) { return player == 0 ? geometry::r(cell) : -geometry::r(cell); }

// The cell in player 0's orientation that corresponds to `cell` in `player`'s orientation.
constexpr int to_player0(int player, int cell) { return player == 0 ? cell : geometry::player_flip(cell); }

// Maps a player's cells to player 0's orientation and sorts them ascending (as Java Rows.toPlayer0(int, int[])).
// `out` must be exactly as long as `cells`; returns false (and writes nothing) otherwise.
bool to_player0(int player, std::span<const int> cells, std::span<int> out);

// Whether the two-player layout lets `player` stand on `cell`.
inline bool allowed(int player, int cell) { return Layout::two_players().allowed(player, cell); }

// The allowed cells of player 0 with row >= min_row, ascending.
std::vector<int> region_cells(int min_row);

}  // namespace sh::rows
