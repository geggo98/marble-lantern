#include "rows.hpp"

#include <algorithm>

namespace sh::rows {

bool to_player0(int player, std::span<const int> cells, std::span<int> out) {
    if (out.size() != cells.size()) return false;
    std::ranges::transform(cells, out.begin(), [player](int c) { return to_player0(player, c); });
    if (player != 0) std::ranges::sort(out);
    return true;
}

std::vector<int> region_cells(int min_row) {
    std::vector<int> cells;
    for (int c = 0; c < kCells; ++c) {
        if (allowed(0, c) && geometry::r(c) >= min_row) cells.push_back(c);
    }
    return cells;
}

}  // namespace sh::rows
