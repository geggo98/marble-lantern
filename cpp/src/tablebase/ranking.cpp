#include "ranking.hpp"

#include <algorithm>

namespace sh {

std::expected<Ranking, std::string> Ranking::create(std::span<const int> cells, int pieces) {
    if (pieces < 1 || pieces > kMaxPieces) return std::unexpected("pieces must be between 1 and " + std::to_string(kMaxPieces));
    Ranking r;
    r.pieces_ = pieces;
    r.cells_.assign(cells.begin(), cells.end());
    r.index_of_cell_.fill(-1);
    for (std::size_t i = 0; i < cells.size(); ++i) {
        if (cells[i] < 0 || cells[i] >= kCells) return std::unexpected("cell " + std::to_string(cells[i]) + " is not on the board");
        if (i > 0 && cells[i] <= cells[i - 1]) return std::unexpected("cells must be strictly ascending");
        r.index_of_cell_[static_cast<std::size_t>(cells[i])] = static_cast<int>(i);
    }
    const std::size_t n = cells.size();
    const std::size_t width = kMaxPieces + 1;
    r.binomial_.assign((n + 1) * width, 0);
    for (std::size_t row = 0; row <= n; ++row) {
        r.binomial_[row * width] = 1;
        for (std::size_t k = 1; k <= std::min<std::size_t>(row, static_cast<std::size_t>(pieces)); ++k) {
            r.binomial_[row * width + k] = r.binomial_[(row - 1) * width + k - 1] + (k <= row - 1 ? r.binomial_[(row - 1) * width + k] : 0);
        }
    }
    r.count_ = r.binomial(static_cast<int>(n), pieces);
    return r;
}

bool Ranking::covers(std::span<const int> own_cells) const {
    if (own_cells.size() != static_cast<std::size_t>(pieces_)) return false;
    return std::ranges::all_of(own_cells, [this](int c) { return contains(c); });
}

std::optional<std::int64_t> Ranking::rank(std::span<const int> own_cells) const {
    if (!covers(own_cells)) return std::nullopt;
    std::array<int, kMaxPieces> idx{};
    const auto k = static_cast<std::size_t>(pieces_);
    for (std::size_t j = 0; j < k; ++j) idx[j] = index_of(own_cells[j]);
    std::ranges::sort(std::span(idx).first(k));
    std::int64_t rank = 0;
    for (std::size_t j = 0; j < k; ++j) {
        if (j > 0 && idx[j] <= idx[j - 1]) return std::nullopt;  // duplicate cell
        rank += binomial(idx[j], static_cast<int>(j) + 1);
    }
    return rank;
}

std::optional<std::vector<int>> Ranking::unrank(std::int64_t rank) const {
    if (rank < 0 || rank >= count_) return std::nullopt;
    std::vector<int> result(static_cast<std::size_t>(pieces_));
    std::int64_t rest = rank;
    int bound = static_cast<int>(cells_.size());
    for (int j = pieces_ - 1; j >= 0; --j) {
        // the largest i < bound with C(i, j + 1) <= rest
        int lo = j;
        int hi = bound - 1;
        while (lo < hi) {
            const int mid = lo + (hi - lo + 1) / 2;
            if (binomial(mid, j + 1) <= rest) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        result[static_cast<std::size_t>(j)] = cells_[static_cast<std::size_t>(lo)];
        rest -= binomial(lo, j + 1);
        bound = lo;
    }
    if (rest != 0) return std::nullopt;
    return result;
}

}  // namespace sh
