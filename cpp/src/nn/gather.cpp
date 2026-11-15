#include "gather.hpp"

#include <algorithm>

namespace sh::nn {
namespace {

constexpr std::array<int, kDirections> kDq = {1, -1, 0, 0, 1, -1};
constexpr std::array<int, kDirections> kDr = {0, 0, 1, -1, -1, 1};
constexpr int kOffset = 8;
constexpr int kSide = 2 * kOffset + 1;

bool on_board(int q, int r) {
    const int s = -q - r;
    const int hi = std::max({q, r, s});
    const int lo = std::min({q, r, s});
    return hi <= 4 || lo >= -4;
}

struct Board {
    std::array<std::array<int, kDirections>, kCells> neighbor{};
    Board() {
        std::array<int, kSide * kSide> id{};
        id.fill(-1);
        std::array<int, kCells> q{};
        std::array<int, kCells> r{};
        int n = 0;
        for (int rr = -kOffset; rr <= kOffset; ++rr) {
            for (int qq = -kOffset; qq <= kOffset; ++qq) {
                if (on_board(qq, rr) && n < kCells) {
                    q[static_cast<std::size_t>(n)] = qq;
                    r[static_cast<std::size_t>(n)] = rr;
                    id[static_cast<std::size_t>((rr + kOffset) * kSide + (qq + kOffset))] = n;
                    ++n;
                }
            }
        }
        const auto lookup = [&](int qq, int rr) {
            if (qq < -kOffset || qq > kOffset || rr < -kOffset || rr > kOffset) return -1;
            return id[static_cast<std::size_t>((rr + kOffset) * kSide + (qq + kOffset))];
        };
        for (std::size_t c = 0; c < static_cast<std::size_t>(kCells); ++c) {
            for (std::size_t d = 0; d < static_cast<std::size_t>(kDirections); ++d) {
                neighbor[c][d] = lookup(q[c] + kDq[d], r[c] + kDr[d]);
            }
        }
    }
};

const Board& board() {
    static const Board b;
    return b;
}

GatherTable build() {
    GatherTable table{};
    const Board& b = board();
    for (std::size_t c = 0; c < static_cast<std::size_t>(kCells); ++c) {
        table[c * kStencil] = static_cast<int>(c);
        for (std::size_t d = 0; d < static_cast<std::size_t>(kDirections); ++d) {
            const int n = b.neighbor[c][d];
            table[c * kStencil + 1 + d] = n < 0 ? kPadding : n;
        }
    }
    return table;
}

}  // namespace

const GatherTable& gather_table() {
    static const GatherTable table = build();
    return table;
}

std::optional<int> gather(int cell, int k) {
    if (cell < 0 || cell >= kCells || k < 0 || k >= kStencil) return std::nullopt;
    return gather_table()[static_cast<std::size_t>(cell) * kStencil + static_cast<std::size_t>(k)];
}

std::optional<int> neighbor(int cell, int direction) {
    if (cell < 0 || cell >= kCells || direction < 0 || direction >= kDirections) return std::nullopt;
    return board().neighbor[static_cast<std::size_t>(cell)][static_cast<std::size_t>(direction)];
}

}  // namespace sh::nn
