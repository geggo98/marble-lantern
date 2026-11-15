// Combinatorial number system over the cells of a region (Java: Ranking): a bijection between the k-subsets of the region's
// cells and the integers 0 .. C(n, k) - 1, so that a table needs no keys. With the subset as ascending region indices
// i_0 < ... < i_(k-1), the rank is the sum of C(i_j, j + 1).
//
// Deviation from Java: nothing throws. A configuration that is not covered (wrong size, a cell outside the region, a duplicate
// cell) gives std::nullopt from rank(); Java throws IllegalArgumentException for the same input. covers() keeps the Java meaning
// (size and region membership; a duplicate cell does not make it false).
#pragma once

#include <array>
#include <cstdint>
#include <expected>
#include <optional>
#include <span>
#include <string>
#include <vector>

#include "geometry.hpp"

namespace sh {

class Ranking {
public:
    static constexpr int kMaxPieces = kPieces;

    // `cells`: the region's cells, strictly ascending cell ids on the board; `pieces`: the subset size k (1 to kMaxPieces).
    static std::expected<Ranking, std::string> create(std::span<const int> cells, int pieces);

    // Number of states, C(n, k).
    std::int64_t count() const { return count_; }
    int pieces() const { return pieces_; }
    // The region's cells, ascending.
    std::span<const int> cells() const { return cells_; }

    bool contains(int cell) const { return index_of(cell) >= 0; }
    // The region index of a cell (its position in cells()), or -1 if the cell lies outside the region.
    int index_of(int cell) const {
        return cell < 0 || cell >= kCells ? -1 : index_of_cell_[static_cast<std::size_t>(cell)];
    }
    // Whether every cell lies in the region and there are exactly `pieces` of them.
    bool covers(std::span<const int> own_cells) const;

    // The rank of a configuration given as cells in any order; std::nullopt if it is not covered or has a duplicate cell.
    std::optional<std::int64_t> rank(std::span<const int> own_cells) const;
    // The configuration of a rank as ascending cell ids; std::nullopt if the rank is out of range.
    std::optional<std::vector<int>> unrank(std::int64_t rank) const;

private:
    Ranking() = default;
    std::int64_t binomial(int n, int k) const {
        return binomial_[static_cast<std::size_t>(n) * (kMaxPieces + 1) + static_cast<std::size_t>(k)];
    }

    std::vector<int> cells_;
    std::array<int, kCells> index_of_cell_{};
    int pieces_ = 0;
    std::vector<std::int64_t> binomial_;  // (cells + 1) rows of kMaxPieces + 1 entries
    std::int64_t count_ = 0;
};

}  // namespace sh
