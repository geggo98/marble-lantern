// Paranoid alpha-beta for three players (Java: ThreePlayerSearch; docs/search-and-evaluation.md, "Three-player browser player").
// The player at the root maximises its own score, both other players minimise it, so the game is a zero-sum game between the root
// player and a coalition.
//
// Score of a position for player r: the mean (integer division by 2) over the two opponents q of the linear evaluation of r against
// q (the 12 features as "r minus q", without the tempo term), in kScale units. A win of r at search height h scores kMate - h, a win
// of an opponent the negative. No race table: the search works for both jump rules.
//
// With ranking=FULL_RANKING (Java: the same class) a finished player makes no more moves and the search goes on until one active player
// is left. The root player then scores: first place kMate - h, last place -(kMate - h), second place kSecond - h. While one other player
// has finished, only one opponent is active: the search is a two-player zero-sum search of the root player against that opponent, and
// the evaluation compares the root player with that opponent alone, minus kNotFirst because first place is gone.
//
// Iterative deepening, a transposition table keyed by position hash and root player, killer moves and a gain-first move order. The
// search tree and the node count are those of the Java class, node for node: nodes are counted per call, leaves included; an unfinished
// iteration is discarded, the first iteration always finishes. Every score is an integer and the evaluation uses the same operations as
// Java (the build is -ffp-contract=off), so Java and C++ choose the same move for the same position and node budget.
#pragma once

#include <array>
#include <chrono>
#include <cstdint>
#include <limits>
#include <memory>
#include <vector>

#include "alpha_beta.hpp"
#include "linear_eval.hpp"
#include "position.hpp"

namespace sh {

class ThreePlayerSearch {
public:
    static constexpr int kMaxDepth = 40;
    static constexpr std::int64_t kNoLimit = std::numeric_limits<std::int64_t>::max();
    // Score of second place (the game is over, the root player finished second), before the height is subtracted.
    static constexpr int kSecond = scores::kMate / 2;
    // Penalty of a position in which another player has already finished first, in kScale units.
    static constexpr int kNotFirst = 4000;

    // `weights`: the 12 feature weights of the evaluation; `table_bits`: log2 of the number of table entries (0..30; the player
    // spec checks the range). Throws std::invalid_argument if the weights are not 12 or the table size is out of range.
    ThreePlayerSearch(std::span<const double> weights, int table_bits);

    // Iterative deepening up to `max_depth` plies (limited to kMaxDepth), until the time limit passes or `max_nodes` nodes are
    // visited. The position needs three players (std::invalid_argument otherwise). The result comes from a completed depth (depth 1
    // always completes); move is -1 if the side to move has no legal move.
    SearchResult search(const Position& position, int max_depth, std::int64_t time_limit_millis = kNoLimit, std::int64_t max_nodes = kNoLimit);

    // The score of the position for player r (the mean evaluation of r against both opponents), as the leaves of the search use it.
    int evaluate(const Position& position, int r);

private:
    // One iteration over the root moves (sorted in place, best first); false if the iteration was aborted.
    bool search_root(std::vector<int>& ordered, int depth, int previous_best, int& best_move, int& best_score);
    int after(int mover, int depth, int alpha, int beta, int height);
    int ab(int depth, int alpha, int beta, int height);
    bool stop();
    int gain(int player, int move) const;

    struct Entry {
        std::uint64_t key = 0;
        int score = 0;
        int meta = 0;  // depth << 2 | bound
        int best_move = 0;
    };

    using MoveBuffer = std::array<int, kMaxMoves>;

    int table_mask_;
    std::vector<Entry> table_;
    std::array<std::array<int, 2>, kMaxDepth + 2> killers_{};
    std::vector<MoveBuffer> move_buffers_;
    std::vector<MoveBuffer> order_buffers_;
    std::array<linear_detail::SideSums, 3> sums_{};
    std::array<double, LinearEvaluator::kFeatures> features_{};
    std::unique_ptr<LinearEvaluator> linear_;

    Position* pos_ = nullptr;
    int root_ = 0;
    std::int64_t nodes_ = 0;
    std::int64_t node_limit_ = kNoLimit;
    std::chrono::steady_clock::time_point deadline_{};
    bool has_deadline_ = false;
    bool limited_ = false;
    bool aborted_ = false;
};

}  // namespace sh
