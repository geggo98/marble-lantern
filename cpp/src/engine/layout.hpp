// Start and goal corners, the cells each player may enter, and the single-agent distance tables (Java: Layout).
// Two or three players. Player 0 starts in the top corner (r < -4); with two players player 1 starts in the bottom corner, with
// three players the play order is clockwise: top, lower right (s < -4), lower left (q < -4). Every goal is the corner opposite to
// the start corner. All tables are built at compile time, one layout per player count.
#pragma once

#include <array>
#include <span>

#include "geometry.hpp"

namespace sh {

class Layout {
public:
    static constexpr int kMaxPlayers = 3;
    using Table = std::span<const int, kTableSize>;

    // players is 2 or 3; the rows of the tables of the missing players stay zero.
    constexpr explicit Layout(int players) : players_(players) {
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
        for (int c = 0; c < kCells; ++c) {
            const int k = geometry::corner_of(c);
            goal_player_of_cell_[static_cast<std::size_t>(c)] = k < 0 ? -1 : goal_player_[static_cast<std::size_t>(k)];
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
    // Clockwise index of the start corner of a player.
    constexpr int start_corner(int player) const { return start_corner_[static_cast<std::size_t>(player)]; }
    // Clockwise index of the goal corner of a player.
    constexpr int goal_corner(int player) const { return goal_corner_[static_cast<std::size_t>(player)]; }
    // The player whose goal is the given corner, or -1 if nobody's goal is there.
    constexpr int goal_player(int corner_index) const {
        return corner_index < 0 ? -1 : goal_player_[static_cast<std::size_t>(corner_index)];
    }
    // The player whose goal corner contains the cell, or -1 (central hexagon or nobody's goal).
    constexpr int goal_player_of_cell(int cell) const { return goal_player_of_cell_[static_cast<std::size_t>(cell)]; }
    // Flat step table of a player with the allowed cells folded in: [cell * 6 + direction] is the neighbour if the player may enter it, else -1.
    constexpr Table step_table(int player) const { return step_to_[static_cast<std::size_t>(player)]; }
    // Flat jump-landing table of a player with the allowed cells folded in.
    constexpr Table jump_table(int player) const { return jump_to_[static_cast<std::size_t>(player)]; }
    // Whether the player may stand on the cell: central hexagon, own start corner or own goal corner.
    constexpr bool allowed(int player, int cell) const {
        return allowed_[static_cast<std::size_t>(player)][static_cast<std::size_t>(cell)];
    }
    // Single-agent distance of a cell for a player: the number of steps to the nearest goal cell on an empty board, over the
    // cells the player may enter.
    constexpr int distance(int player, int cell) const {
        return distance_[static_cast<std::size_t>(player)][static_cast<std::size_t>(cell)];
    }
    // The largest distance in the table of a player.
    constexpr int max_distance(int player) const { return max_distance_[static_cast<std::size_t>(player)]; }

    // The shared layouts of the two-player and the three-player game; of(players) takes 2 or 3 (3 gives the three-player layout, anything else the two-player one).
    static const Layout& two_players();
    static const Layout& three_players();
    static const Layout& of(int players);

private:
    // Breadth-first search from the goal cells over the cells the player may enter (the queue is a fixed array).
    constexpr void compute_distances(int p) {
        const auto ps = static_cast<std::size_t>(p);
        auto& dist = distance_[ps];
        for (auto& v : dist) v = -1;
        std::array<int, kCells> queue{};
        int head = 0;
        int tail = 0;
        for (const int c : geometry::corner_cells(goal_corner_[ps])) {
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
    std::array<std::array<int, kTableSize>, kMaxPlayers> step_to_{};
    std::array<std::array<int, kTableSize>, kMaxPlayers> jump_to_{};
    std::array<std::array<int, kCells>, kMaxPlayers> distance_{};
    std::array<int, kMaxPlayers> max_distance_{};
    int players_;
    bool valid_ = true;
};

namespace detail {
inline constexpr Layout kTwoPlayerLayout{2};
inline constexpr Layout kThreePlayerLayout{3};
}

inline const Layout& Layout::two_players() { return detail::kTwoPlayerLayout; }
inline const Layout& Layout::three_players() { return detail::kThreePlayerLayout; }
inline const Layout& Layout::of(int players) { return players == 3 ? detail::kThreePlayerLayout : detail::kTwoPlayerLayout; }

}  // namespace sh
