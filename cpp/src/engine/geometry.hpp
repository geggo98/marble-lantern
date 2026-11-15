// The 121-cell star board: cube/axial hex coordinates, neighbour tables and corner membership (Java: Geometry, Corner).
// A cell (q, r) with s = -q - r is on the board if max(q, r, s) <= 4 or min(q, r, s) >= -4. Cell ids run from 0 to 120,
// ordered by r and then by q. All tables are built at compile time, so the hot loops read plain constants.
#pragma once

#include <array>
#include <cstdint>
#include <span>
#include <string>

namespace sh {

inline constexpr int kCells = 121;
inline constexpr int kDirections = 6;
inline constexpr int kCornerSize = 10;  // cells per corner triangle
inline constexpr int kPieces = 10;      // pieces per player
inline constexpr int kMaxMoves = kPieces * kCells;
inline constexpr std::size_t kTableSize = std::size_t{kCells} * kDirections;  // entries of the flat step and jump tables

// Index of cell c and direction d in the flat tables.
constexpr std::size_t table_index(int c, int d) { return static_cast<std::size_t>(c) * kDirections + static_cast<std::size_t>(d); }

// The six corners of the star, in clockwise order on screen (pointy-top layout, y pointing down); the value is the clockwise index.
namespace corner {
inline constexpr int kQPos = 0;  // q > 4, upper right
inline constexpr int kSNeg = 1;  // s < -4, lower right
inline constexpr int kRPos = 2;  // r > 4, bottom
inline constexpr int kQNeg = 3;  // q < -4, lower left
inline constexpr int kSPos = 4;  // s > 4, upper left
inline constexpr int kRNeg = 5;  // r < -4, top
constexpr int opposite(int index) { return (index + 3) % 6; }
}  // namespace corner

namespace detail {

inline constexpr int kOffset = 8;
inline constexpr int kSide = 2 * kOffset + 1;
inline constexpr std::array<int, kDirections> kDq{1, -1, 0, 0, 1, -1};  // directions d and d ^ 1 are opposite
inline constexpr std::array<int, kDirections> kDr{0, 0, 1, -1, -1, 1};

// Index of the axial coordinates (q, r) in the id grid; both must be within -kOffset..kOffset.
constexpr std::size_t grid_index(int q, int r) {
    return static_cast<std::size_t>(r + kOffset) * kSide + static_cast<std::size_t>(q + kOffset);
}

constexpr bool on_board(int q, int r) {
    const int s = -q - r;
    int max = q > r ? q : r;
    if (s > max) max = s;
    int min = q < r ? q : r;
    if (s < min) min = s;
    return max <= 4 || min >= -4;
}

// Clockwise corner index or -1 for the central hexagon.
constexpr int corner_index(int q, int r, int s) {
    if (q > 4) return corner::kQPos;
    if (s < -4) return corner::kSNeg;
    if (r > 4) return corner::kRPos;
    if (q < -4) return corner::kQNeg;
    if (s > 4) return corner::kSPos;
    if (r < -4) return corner::kRNeg;
    return -1;
}

struct GeometryTables {
    std::array<int, kCells> q{};
    std::array<int, kCells> r{};
    std::array<int, std::size_t{kSide} * kSide> id{};
    std::array<int, kTableSize> step{};  // neighbour of cell c in direction d at c * 6 + d, or -1 off the board
    std::array<int, kTableSize> land{};  // neighbour of the neighbour in the same direction, or -1 off the board
    std::array<int, kCells> corner{};
    std::array<std::array<int, kCornerSize>, 6> corner_cells{};
    std::array<int, kCells> mirror{};
    std::array<int, kCells> player_flip{};
    std::array<int, kCells> rotate_180{};
    bool valid = true;  // false if the board does not have 121 cells or a corner does not have 10 (checked by static_assert)
};

constexpr int tables_id(const GeometryTables& t, int q, int r) {
    if (q < -kOffset || q > kOffset || r < -kOffset || r > kOffset) return -1;
    return t.id[grid_index(q, r)];
}

constexpr GeometryTables build_geometry() {
    GeometryTables t;
    for (auto& v : t.id) v = -1;
    int n = 0;
    for (int r = -kOffset; r <= kOffset; ++r) {
        for (int q = -kOffset; q <= kOffset; ++q) {
            if (on_board(q, r)) {
                if (n >= kCells) {
                    t.valid = false;
                    return t;
                }
                t.q[static_cast<std::size_t>(n)] = q;
                t.r[static_cast<std::size_t>(n)] = r;
                t.id[grid_index(q, r)] = n;
                ++n;
            }
        }
    }
    if (n != kCells) {
        t.valid = false;
        return t;
    }
    std::array<int, 6> corner_count{};
    for (int c = 0; c < kCells; ++c) {
        const auto cs = static_cast<std::size_t>(c);
        for (int d = 0; d < kDirections; ++d) {
            const auto ds = static_cast<std::size_t>(d);
            t.step[cs * kDirections + ds] = tables_id(t, t.q[cs] + kDq[ds], t.r[cs] + kDr[ds]);
        }
        t.corner[cs] = corner_index(t.q[cs], t.r[cs], -t.q[cs] - t.r[cs]);
        if (t.corner[cs] >= 0) ++corner_count[static_cast<std::size_t>(t.corner[cs])];
    }
    for (const int count : corner_count) {
        if (count != kCornerSize) t.valid = false;
    }
    std::array<int, 6> fill{};
    for (int c = 0; c < kCells; ++c) {
        const int k = t.corner[static_cast<std::size_t>(c)];
        if (k >= 0) {
            const auto ks = static_cast<std::size_t>(k);
            const int slot = fill[ks]++;
            if (slot < kCornerSize) t.corner_cells[ks][static_cast<std::size_t>(slot)] = c;
        }
    }
    for (int c = 0; c < kCells; ++c) {
        const auto cs = static_cast<std::size_t>(c);
        const int q = t.q[cs];
        const int r = t.r[cs];
        const int s = -q - r;
        t.mirror[cs] = tables_id(t, s, r);        // left-right flip: (q, r, s) -> (s, r, q)
        t.player_flip[cs] = tables_id(t, -s, -r);  // player symmetry: (q, r, s) -> (-s, -r, -q)
        t.rotate_180[cs] = tables_id(t, -q, -r);   // 180-degree rotation
    }
    for (int c = 0; c < kCells; ++c) {
        for (int d = 0; d < kDirections; ++d) {
            const std::size_t i = table_index(c, d);
            const int over = t.step[i];
            t.land[i] = over < 0 ? -1 : t.step[table_index(over, d)];
        }
    }
    return t;
}

inline constexpr GeometryTables kGeometry = build_geometry();
static_assert(kGeometry.valid, "the board must have 121 cells and 10 cells per corner");

}  // namespace detail

namespace geometry {

// Cell id for axial coordinates, or -1 if the cell is off the board.
constexpr int id(int q, int r) { return detail::tables_id(detail::kGeometry, q, r); }
constexpr int q(int cell) { return detail::kGeometry.q[static_cast<std::size_t>(cell)]; }
constexpr int r(int cell) { return detail::kGeometry.r[static_cast<std::size_t>(cell)]; }
constexpr int s(int cell) { return -q(cell) - r(cell); }
// Neighbour of a cell in a direction (0 to 5), or -1 off the board.
constexpr int neighbor(int cell, int direction) {
    return detail::kGeometry.step[table_index(cell, direction)];
}
// The cell two steps away in a direction (the landing cell of a single jump), or -1 off the board.
constexpr int jump_landing(int cell, int direction) {
    return detail::kGeometry.land[table_index(cell, direction)];
}
// Flat tables for the hot loops: step_table()[c * 6 + d] is neighbor(c, d); land_table()[c * 6 + d] is jump_landing(c, d).
constexpr std::span<const int, kTableSize> step_table() { return detail::kGeometry.step; }
constexpr std::span<const int, kTableSize> land_table() { return detail::kGeometry.land; }
// Clockwise index of the corner that contains the cell, or -1 for the central hexagon.
constexpr int corner_of(int cell) { return detail::kGeometry.corner[static_cast<std::size_t>(cell)]; }
// The 10 cells of a corner, ascending.
constexpr std::span<const int, kCornerSize> corner_cells(int corner_index) {
    return detail::kGeometry.corner_cells[static_cast<std::size_t>(corner_index)];
}
constexpr int mirror(int cell) { return detail::kGeometry.mirror[static_cast<std::size_t>(cell)]; }              // (q, r, s) -> (s, r, q)
constexpr int player_flip(int cell) { return detail::kGeometry.player_flip[static_cast<std::size_t>(cell)]; }    // (q, r, s) -> (-s, -r, -q)
constexpr int rotate_180(int cell) { return detail::kGeometry.rotate_180[static_cast<std::size_t>(cell)]; }      // (q, r, s) -> (-q, -r, -s)

// "(q,r)" for display.
std::string cell_to_string(int cell);

}  // namespace geometry

}  // namespace sh
