// Start and goal corners, the cells each player may enter, and the single-agent distance tables (Java: Layout).
// Two or three players. Player 0 starts in the top corner (r < -4); with two players player 1 starts in the bottom corner, with
// three players the play order is clockwise: top, lower right (s < -4), lower left (q < -4). Every goal is the corner opposite to
// the start corner. All tables are built at compile time, one layout per player count and house size (RuleSet::TriangleRows).
// A house is the 10-cell corner (Four) or the corner plus the hexagon edge row in front of it (Five, 15 cells). With three players a
// Five start house shares its two hexagon-vertex cells with the goal houses of its neighbours, so membership is stored per player
// (in_start, in_goal), never as one corner index per cell. allowed() and the foreign corners stay on the 10-cell corners.
#pragma once

#include <array>
#include <span>

#include "geometry.hpp"
#include "rules.hpp"

namespace sh {

class Layout {
public:
    static constexpr int kMaxPlayers = 3;
    using Table = std::span<const int, kTableSize>;

    // players is 2 or 3; the rows of the tables of the missing players stay zero. The one-argument constructor is the 4-row layout.
    constexpr explicit Layout(int players) : Layout(players, RuleSet::TriangleRows::Four) {}
    constexpr Layout(int players, RuleSet::TriangleRows rows)
        : players_(players), rows_(rows), pieces_(rows == RuleSet::TriangleRows::Five ? 15 : 10) {
        if (players == 2) {
            start_corner_ = {corner::kRNeg, corner::kRPos, 0};
        } else {
            start_corner_ = {corner::kRNeg, corner::kSNeg, corner::kQNeg};
        }
        for (auto& v : goal_player_) v = -1;
        for (int p = 0; p < players_; ++p) {
            const auto ps = static_cast<std::size_t>(p);
            goal_corner_[ps] = corner::opposite(start_corner_[ps]);
            goal_player_[static_cast<std::size_t>(goal_corner_[ps])] = p;
        }
        const int row_count = rows == RuleSet::TriangleRows::Five ? 5 : 4;
        for (auto& v : goal_player_of_cell_) v = -1;
        for (int p = 0; p < players_; ++p) {
            const auto ps = static_cast<std::size_t>(p);
            int n_start = 0;
            int n_goal = 0;
            for (int c = 0; c < kCells; ++c) {
                const auto cs = static_cast<std::size_t>(c);
                in_start_[ps][cs] = geometry::in_house(start_corner_[ps], c, row_count);
                in_goal_[ps][cs] = geometry::in_house(goal_corner_[ps], c, row_count);
                if (in_start_[ps][cs]) {
                    if (n_start >= pieces_) {
                        valid_ = false;
                    } else {
                        start_cells_[ps][static_cast<std::size_t>(n_start)] = c;
                    }
                    ++n_start;
                }
                if (in_goal_[ps][cs]) {
                    if (n_goal >= pieces_) {
                        valid_ = false;
                    } else {
                        goal_cells_[ps][static_cast<std::size_t>(n_goal)] = c;
                    }
                    ++n_goal;
                    if (goal_player_of_cell_[cs] >= 0) valid_ = false;  // goal houses must not overlap
                    goal_player_of_cell_[cs] = p;
                }
            }
            if (n_start != pieces_ || n_goal != pieces_) valid_ = false;
        }
        for (int p = 0; p < players_; ++p) {
            const auto ps = static_cast<std::size_t>(p);
            for (int c = 0; c < kCells; ++c) {
                const int k = geometry::corner_of(c);
                allowed_[ps][static_cast<std::size_t>(c)] = k < 0 || k == start_corner_[ps] || k == goal_corner_[ps];
            }
            compute_distances(p);
        }
        for (int p = 0; p < players_; ++p) {
            const auto ps = static_cast<std::size_t>(p);
            for (std::size_t i = 0; i < kTableSize; ++i) {
                const int step = geometry::step_table()[i];
                const int land = geometry::land_table()[i];
                step_to_[ps][i] = step >= 0 && allowed_[ps][static_cast<std::size_t>(step)] ? step : -1;
                jump_to_[ps][i] = land >= 0 && allowed_[ps][static_cast<std::size_t>(land)] ? land : -1;
            }
        }
    }

    constexpr bool valid() const { return valid_; }
    constexpr int players() const { return players_; }
    constexpr RuleSet::TriangleRows rows() const { return rows_; }
    // Pieces per player, which is the number of cells of a house: 10 or 15. goal_size() is the same: a goal is full when that many cells hold a piece.
    constexpr int pieces() const { return pieces_; }
    constexpr int goal_size() const { return pieces_; }
    // Whether the cell is in the start (goal) house of the player. They overlap across players with Five and three players.
    constexpr bool in_start(int player, int cell) const { return in_start_[static_cast<std::size_t>(player)][static_cast<std::size_t>(cell)]; }
    constexpr bool in_goal(int player, int cell) const { return in_goal_[static_cast<std::size_t>(player)][static_cast<std::size_t>(cell)]; }
    // The cells of the start (goal) house of a player, ascending; pieces() entries.
    constexpr std::span<const int> start_cells(int player) const {
        return std::span<const int>(start_cells_[static_cast<std::size_t>(player)]).first(static_cast<std::size_t>(pieces_));
    }
    constexpr std::span<const int> goal_cells(int player) const {
        return std::span<const int>(goal_cells_[static_cast<std::size_t>(player)]).first(static_cast<std::size_t>(pieces_));
    }
    // Clockwise index of the start corner of a player.
    constexpr int start_corner(int player) const { return start_corner_[static_cast<std::size_t>(player)]; }
    // Clockwise index of the goal corner of a player.
    constexpr int goal_corner(int player) const { return goal_corner_[static_cast<std::size_t>(player)]; }
    // The player whose goal is the given corner, or -1 if nobody's goal is there.
    constexpr int goal_player(int corner_index) const {
        return corner_index < 0 ? -1 : goal_player_[static_cast<std::size_t>(corner_index)];
    }
    // The player whose goal house contains the cell, or -1 (nobody's goal).
    constexpr int goal_player_of_cell(int cell) const { return goal_player_of_cell_[static_cast<std::size_t>(cell)]; }
    // Flat step table of a player with the allowed cells folded in: [cell * 6 + direction] is the neighbour if the player may enter it, else -1.
    constexpr Table step_table(int player) const { return step_to_[static_cast<std::size_t>(player)]; }
    // Flat jump-landing table of a player with the allowed cells folded in.
    constexpr Table jump_table(int player) const { return jump_to_[static_cast<std::size_t>(player)]; }
    // Whether the cell is a home cell of the player: central hexagon, own start corner or own goal corner. Under ForeignCamps Closed and
    // PassThrough a move ends only on such a cell.
    constexpr bool allowed(int player, int cell) const {
        return allowed_[static_cast<std::size_t>(player)][static_cast<std::size_t>(cell)];
    }
    // Single-agent distance of a cell for a player: the number of steps to the nearest goal cell on an empty board, over the
    // home cells of the player. A cell of a foreign corner (a piece stands there only under ForeignCamps::Open) gets the number of
    // steps over all cells.
    constexpr int distance(int player, int cell) const {
        return distance_[static_cast<std::size_t>(player)][static_cast<std::size_t>(cell)];
    }
    // The largest distance of a home cell of the player.
    constexpr int max_distance(int player) const { return max_distance_[static_cast<std::size_t>(player)]; }

    // The shared layouts of the two-player and the three-player game with 4-row houses; of(players) takes 2 or 3 (3 gives the three-player
    // layout, anything else the two-player one). of(players, rows) picks the house size.
    static const Layout& two_players();
    static const Layout& three_players();
    static const Layout& of(int players);
    static const Layout& of(int players, RuleSet::TriangleRows rows);

private:
    // Breadth-first search from the goal cells over the cells the player may enter (the queue is a fixed array).
    constexpr void compute_distances(int p) {
        const auto ps = static_cast<std::size_t>(p);
        auto& dist = distance_[ps];
        for (auto& v : dist) v = -1;
        std::array<int, kCells> queue{};
        int head = 0;
        int tail = 0;
        for (const int c : goal_cells(p)) {
            dist[static_cast<std::size_t>(c)] = 0;
            queue[static_cast<std::size_t>(tail++)] = c;
        }
        while (head < tail) {
            const int c = queue[static_cast<std::size_t>(head++)];
            for (int d = 0; d < kDirections; ++d) {
                const int n = geometry::neighbor(c, d);
                if (n >= 0 && allowed_[ps][static_cast<std::size_t>(n)] && dist[static_cast<std::size_t>(n)] < 0) {
                    dist[static_cast<std::size_t>(n)] = dist[static_cast<std::size_t>(c)] + 1;
                    queue[static_cast<std::size_t>(tail++)] = n;
                }
            }
        }
        // Foreign corners (RuleSet::ForeignCamps): a piece of p stands there only under Open. Their distance is the number of steps to
        // the nearest goal cell over all board cells; the cells above keep their values.
        std::array<int, kCells> all{};
        for (auto& v : all) v = -1;
        head = 0;
        tail = 0;
        for (const int c : goal_cells(p)) {
            all[static_cast<std::size_t>(c)] = 0;
            queue[static_cast<std::size_t>(tail++)] = c;
        }
        while (head < tail) {
            const int c = queue[static_cast<std::size_t>(head++)];
            for (int d = 0; d < kDirections; ++d) {
                const int n = geometry::neighbor(c, d);
                if (n >= 0 && all[static_cast<std::size_t>(n)] < 0) {
                    all[static_cast<std::size_t>(n)] = all[static_cast<std::size_t>(c)] + 1;
                    queue[static_cast<std::size_t>(tail++)] = n;
                }
            }
        }
        for (int c = 0; c < kCells; ++c) {
            if (!allowed_[ps][static_cast<std::size_t>(c)]) dist[static_cast<std::size_t>(c)] = all[static_cast<std::size_t>(c)];
        }
        for (int c = 0; c < kCells; ++c) {
            if (allowed_[ps][static_cast<std::size_t>(c)]) {
                if (dist[static_cast<std::size_t>(c)] < 0) valid_ = false;  // a cell that cannot reach the goal (checked by static_assert)
                if (dist[static_cast<std::size_t>(c)] > max_distance_[ps]) max_distance_[ps] = dist[static_cast<std::size_t>(c)];
            }
        }
    }

    std::array<int, kMaxPlayers> start_corner_{};
    std::array<int, kMaxPlayers> goal_corner_{};
    std::array<int, 6> goal_player_{};
    std::array<int, kCells> goal_player_of_cell_{};
    std::array<std::array<bool, kCells>, kMaxPlayers> allowed_{};
    std::array<std::array<bool, kCells>, kMaxPlayers> in_start_{};
    std::array<std::array<bool, kCells>, kMaxPlayers> in_goal_{};
    std::array<std::array<int, kMaxPieces>, kMaxPlayers> start_cells_{};
    std::array<std::array<int, kMaxPieces>, kMaxPlayers> goal_cells_{};
    std::array<std::array<int, kTableSize>, kMaxPlayers> step_to_{};
    std::array<std::array<int, kTableSize>, kMaxPlayers> jump_to_{};
    std::array<std::array<int, kCells>, kMaxPlayers> distance_{};
    std::array<int, kMaxPlayers> max_distance_{};
    int players_;
    RuleSet::TriangleRows rows_;
    int pieces_;
    bool valid_ = true;
};

namespace detail {
inline constexpr Layout kTwoPlayerLayout{2};
inline constexpr Layout kThreePlayerLayout{3};
inline constexpr Layout kTwoPlayerLayoutFive{2, RuleSet::TriangleRows::Five};
inline constexpr Layout kThreePlayerLayoutFive{3, RuleSet::TriangleRows::Five};
}

inline const Layout& Layout::two_players() { return detail::kTwoPlayerLayout; }
inline const Layout& Layout::three_players() { return detail::kThreePlayerLayout; }
inline const Layout& Layout::of(int players) { return players == 3 ? detail::kThreePlayerLayout : detail::kTwoPlayerLayout; }
inline const Layout& Layout::of(int players, RuleSet::TriangleRows rows) {
    if (rows == RuleSet::TriangleRows::Five) return players == 3 ? detail::kThreePlayerLayoutFive : detail::kTwoPlayerLayoutFive;
    return of(players);
}

}  // namespace sh
