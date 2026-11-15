// The gather table of the hex convolution (Java: HexGrid; docs/network.md, "Gather table"): 121 x 7 entries, flat as [cell][k].
// k = 0 is the cell itself, k = 1..6 the neighbour in Geometry direction k - 1, or the padding row (121) off the board.
// The 121-cell star board is rebuilt here from its definition (cube coordinates, cells ordered by r then q) because the
// network core must not depend on the engine library; the golden outputs of the Java network pin the cell order.
#pragma once

#include <array>
#include <optional>

#include "config.hpp"

namespace sh::nn {

using GatherTable = std::array<int, static_cast<std::size_t>(kCells) * kStencil>;

const GatherTable& gather_table();

// HexGrid.gather: the row of cell for stencil entry k; nullopt if the cell or k is out of range.
std::optional<int> gather(int cell, int k);

// Geometry.neighbor: the neighbour of a cell in a direction (0 to 5), or -1 off the board; nullopt if an argument is out of range.
std::optional<int> neighbor(int cell, int direction);

}  // namespace sh::nn
