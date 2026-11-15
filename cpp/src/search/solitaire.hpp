// The solitaire game of one player (Java: Solitaire): only that player's pieces are on the board, they are the only hurdles, and the
// player wants all of them in the goal corner in as few moves as possible. Used by RaceMove to play a race. The beam search (plan) is a
// beam over configurations, layer by layer, ordered by the race table's exact count where the table covers a configuration and by the
// distance sum otherwise. The adjudication part of the Java class (lowerBound is ported, the callers in RaceAdjudication are not) is not
// needed by the comparison.
//
// Rows are in the player's own orientation (rows::row). Move generation restricts every landing cell of a move, the intermediate
// landings of a jump chain included, to rows >= min_landing. With LeaveGoal::Forbidden a piece in the goal corner never leaves it. Not
// thread-safe (scratch space); one instance per thread.
#pragma once

#include <array>
#include <cstdint>
#include <functional>
#include <limits>
#include <optional>
#include <span>
#include <vector>

#include "geometry.hpp"
#include "layout.hpp"
#include "move.hpp"
#include "race_table.hpp"
#include "rows.hpp"
#include "rules.hpp"

namespace sh {

class Solitaire {
public:
    // A planned move: the start cell, the landing cells of its chain in order (the last is the end cell).
    struct Step {
        int from = -1;
        std::vector<int> path;
        int to() const { return path.back(); }
        int move() const { return Move::of(from, to()); }
    };

    // A plan: the moves and the configurations after each move (states[0] is the start; configurations as ascending cell arrays).
    struct Plan {
        std::vector<Step> steps;
        std::vector<std::vector<int>> states;
        int moves() const { return static_cast<int>(steps.size()); }
    };

    static constexpr int kNoRearLimit = std::numeric_limits<int>::min();

    // table: the race counts for the beam's ordering, or null to order by distance sums only (not owned, must outlive this object)
    Solitaire(int player, RuleSet::LeaveGoal leave_goal, const RaceTable* table);

    int player() const { return player_; }

    // The smallest row among the pieces of a configuration (the rearmost piece).
    int rear_row(std::uint64_t lo, std::uint64_t hi) const;
    // The largest row among the given cells (the frontmost piece).
    int front_row(std::span<const int> cells) const;
    bool is_goal(std::uint64_t lo, std::uint64_t hi) const { return lo == goal_lo_ && hi == goal_hi_; }
    // Whether every cell is in the goal corner (and there are 10 of them).
    bool is_goal(std::span<const int> cells) const;

    // Enumerates the end cells of all moves of the piece on `from` whose landing cells (all of them) have rows >= min_landing, given the
    // occupancy of the player's pieces. Steps first, then jump chain landings in breadth-first order. Afterwards path() reproduces the
    // chain of the last generated landing set. `out` is called with the end cell.
    template <class Consumer>
    void landings(std::uint64_t lo, std::uint64_t hi, int from, int min_landing, Consumer&& out) {
        const std::uint64_t occ_lo = without_lo(lo, from);
        const std::uint64_t occ_hi = without_hi(hi, from);
        for (int d = 0; d < kDirections; ++d) {
            const int n = geometry::neighbor(from, d);
            if (n >= 0 && allowed_[idx(n)] && row_of_[idx(n)] >= min_landing && !has(occ_lo, occ_hi, n) && end_allowed(from, n)) out(n);
        }
        parent_.fill(-1);
        parent_[idx(from)] = from;
        int head = 0;
        int tail = 0;
        queue_[idx(tail++)] = from;
        while (head < tail) {
            const int c = queue_[idx(head++)];
            for (int d = 0; d < kDirections; ++d) {
                const int over = geometry::neighbor(c, d);
                if (over < 0 || !has(occ_lo, occ_hi, over)) continue;
                const int land = geometry::neighbor(over, d);
                if (land < 0 || has(occ_lo, occ_hi, land) || !allowed_[idx(land)] || row_of_[idx(land)] < min_landing ||
                    parent_[idx(land)] >= 0) {
                    continue;
                }
                parent_[idx(land)] = c;
                queue_[idx(tail++)] = land;
                if (end_allowed(from, land)) out(land);
            }
        }
    }

    // The landing cells (in order, ending in `to`) of the move from -> to as the last call of landings() found it; a step has the
    // single cell `to`. Empty if there is no such path.
    std::vector<int> path(int from, int to) const;

    // The move from -> to with its chain, or empty if it is not a legal solitaire move with all landings at rows >= min_landing.
    std::optional<Step> step(std::uint64_t lo, std::uint64_t hi, int from, int to, int min_landing);

    // The parity-assignment lower bound of the moves needed to bring all pieces into the goal corner.
    int lower_bound(std::span<const int> cells) const;

    // A shortest plan the beam finds from `cells` to the goal, with at most max_moves moves, or empty. min_landing(t) is the smallest
    // allowed landing row of move number t (1-based); min_rear(t) the smallest allowed row of the rearmost piece after that move (not
    // applied to the goal configuration; kNoRearLimit for no constraint).
    std::optional<Plan> plan(std::span<const int> cells, int width, int max_moves, const std::function<int(int)>& min_landing,
                             const std::function<int(int)>& min_rear);
    // A plan without rear constraints: landing rows >= min_landing for every move.
    std::optional<Plan> plan(std::span<const int> cells, int width, int max_moves, int min_landing);

    // ---- bitboards over 121 cells in two words ----
    static bool has(std::uint64_t lo, std::uint64_t hi, int cell) { return cell < 64 ? ((lo >> cell) & 1U) != 0 : ((hi >> (cell - 64)) & 1U) != 0; }
    static std::uint64_t with_lo(std::uint64_t lo, int cell) { return cell < 64 ? lo | (std::uint64_t{1} << cell) : lo; }
    static std::uint64_t with_hi(std::uint64_t hi, int cell) { return cell >= 64 ? hi | (std::uint64_t{1} << (cell - 64)) : hi; }
    static std::uint64_t without_lo(std::uint64_t lo, int cell) { return cell < 64 ? lo & ~(std::uint64_t{1} << cell) : lo; }
    static std::uint64_t without_hi(std::uint64_t hi, int cell) { return cell >= 64 ? hi & ~(std::uint64_t{1} << (cell - 64)) : hi; }
    static std::vector<int> cells_of(std::uint64_t lo, std::uint64_t hi);

private:
    static constexpr std::size_t idx(int i) { return static_cast<std::size_t>(i); }

    bool end_allowed(int from, int to) const {
        return leave_goal_ == RuleSet::LeaveGoal::Allowed || geometry::corner_of(from) != goal_corner_ || geometry::corner_of(to) == goal_corner_;
    }
    static bool one_move_class(int from, int to);
    int heuristic(std::uint64_t lo, std::uint64_t hi) const;

    int player_;
    RuleSet::LeaveGoal leave_goal_;
    const RaceTable* table_;
    const Layout& layout_ = Layout::two_players();
    int goal_corner_;
    std::uint64_t goal_lo_ = 0;
    std::uint64_t goal_hi_ = 0;
    std::array<bool, kCells> allowed_{};
    std::array<int, kCells> row_of_{};
    std::array<int, kCells> queue_{};
    std::array<int, kCells> parent_{};
};

}  // namespace sh
