// Two-player negamax with alpha-beta pruning, iterative deepening, a transposition table and move ordering by distance progress
// (Java: AlphaBeta). The search tree must be the Java tree node for node, so every detail that decides it is kept: the table layout
// (parallel arrays keys, scores, info; info packs depth << 24 | flag << 22 | move + 1; depth-preferred buckets of two with
// generations), the move buffers per height, the two killers per height, the history table (decayed by >>= 2 per search), the
// late-move-reduction tables, aspiration, futility, mate handling, partial results, node counting, the stable selection sort of
// the move order and the key salt of root_scores.
//
// Scores are integers from the side to move (scores.hpp). The table is keyed by the position hash only; a stored value may depend
// on the ply through the move limit and the table ignores that, as the Java one does.
//
// Time limits are supported but untested; the comparison uses node limits only. Not thread-safe; one instance per thread.
#pragma once

#include <array>
#include <chrono>
#include <cstdint>
#include <limits>
#include <memory>
#include <span>
#include <vector>

#include "evaluator.hpp"
#include "geometry.hpp"
#include "options.hpp"
#include "position.hpp"
#include "scores.hpp"

namespace sh {

// The result of a search (Java: SearchResult). move is -1 if the side to move has no legal move.
struct SearchResult {
    int move = -1;
    int score = 0;
    int depth = 0;           // depth of the last completed iteration (0 if none completed)
    std::int64_t nodes = 0;  // nodes visited in all iterations
    bool complete = true;    // whether the last requested depth finished before the limit

    friend bool operator==(const SearchResult&, const SearchResult&) = default;
};

class AlphaBeta {
public:
    static constexpr int kMate = scores::kMate;
    static constexpr int kEndScale = scores::kEndScale;
    static constexpr int kMaxDepth = 64;
    static constexpr int kMateBound = scores::kMateBound;
    static constexpr std::int64_t kNoLimit = std::numeric_limits<std::int64_t>::max();

    // `table_bits`: log2 of the number of table entries (0..30); `use_table` false disables the table; `verify_incremental` runs the
    // assert mode of the incremental evaluation (every evaluation is compared with the full one; slow, for tests).
    // Throws std::invalid_argument for a table size outside 0..30.
    AlphaBeta(std::unique_ptr<Evaluator> evaluator, int table_bits, bool use_table, const SearchOptions& options = {},
              bool verify_incremental = false);

    const SearchOptions& options() const { return options_; }
    // Forgets all stored positions, the history and the killers.
    void clear();

    // Iterative deepening up to `max_depth` plies (1..kMaxDepth, else std::invalid_argument), until the time limit passes or
    // `max_nodes` nodes are visited. An unfinished iteration is discarded unless `partial` is on, so the result comes from a
    // completed depth (depth 1 always completes).
    SearchResult search(const Position& position, int max_depth, std::int64_t time_limit_millis = kNoLimit, std::int64_t max_nodes = kNoLimit);

    // The exact score of every root move at a fixed depth, in the order of Position::generate (Java: rootScores). Needs futility and
    // late move reductions off (std::logic_error otherwise). Entries of other calls are invisible (a fresh key salt per call).
    std::vector<int> root_scores(const Position& position, int depth);

    // Nodes visited by the last search or root_scores call.
    std::int64_t nodes() const { return nodes_; }
    // Moves cut by futility pruning in the last search.
    std::int64_t futility_cuts() const { return futility_cuts_; }

    // The outcome of a finished game as a score at `height` (Java: terminalScore).
    static int terminal_score(const Position& position, int height);
    // Whether futility pruning may cut moves at this node (Java: futilityAllowed).
    static bool futility_allowed(const Position& position, int depth, int alpha, Evaluator& evaluator);
    // A score at `height` as stored in the table with mate_fix: mate scores relative to the node, and the inverse.
    static int to_table(int score, int height);
    static int from_table(int score, int height);
    using MoveBuffer = std::array<int, kMaxMoves>;
    // Step i of the stable selection sort of the move order (tests): moves and keys are reordered together.
    static void pick(MoveBuffer& moves, MoveBuffer& keys, int i, int count);
    // The reduction that late move reductions of `mode` (1..3) apply at remaining depth `depth` to the move at `index`.
    static int lmr_reduction(int mode, int depth, int index);

private:
    static constexpr int kExact = 0;
    static constexpr int kLower = 1;
    static constexpr int kUpper = 2;
    static constexpr int kLmrFullMoves = 3;  // moves searched at full depth before late move reductions start
    static constexpr int kLmrMinDepth = 3;   // minimum remaining depth for a late move reduction
    static constexpr int kHistoryMax = (1 << 20) - 1;
    static constexpr int kAspirationMax = 2000;  // beyond this half-width an aspiration window becomes the full window

    struct RootIteration {
        int move = -1;
        int score = 0;
        bool usable = false;  // at least one root move was searched completely and its score is above alpha
    };

    RootIteration aspiration_root(int depth, int first_guess, int previous);
    RootIteration search_root(int depth, int first_guess, int alpha_in, int beta);
    int negamax(int depth, int alpha_in, int beta_in, int height);

    void make(int move);
    void unmake();
    int evaluate();
    int probe(std::uint64_t key) const;
    void store(std::uint64_t key, int score, int depth, int flag, int move, int height);
    void order(int count, int height, int first_move);
    static int terminal_score_no_moves(const Position& position);
    void clear_killers();
    void begin_search(std::int64_t max_nodes);
    bool time_is_up() const;

    std::unique_ptr<Evaluator> evaluator_;
    // The incremental state of the evaluator, or null; follows root_ while tracking_.
    std::unique_ptr<EvalState> state_;
    bool tracking_ = false;
    bool use_table_;
    SearchOptions options_;

    // transposition table: parallel arrays, always-replace (default) or depth-preferred buckets of two
    std::vector<std::uint64_t> keys_;
    std::vector<int> scores_;
    std::vector<std::uint32_t> info_;          // depth (8 bits) | flag (2 bits) | move + 1 (21 bits)
    std::vector<std::uint16_t> generations_;   // search number of the entry; only for depth-preferred
    std::uint32_t mask_ = 0;
    std::uint16_t generation_ = 0;

    std::vector<MoveBuffer> move_buffers_;
    std::vector<MoveBuffer> order_keys_;
    std::vector<std::array<int, 2>> killer_moves_;
    std::vector<int> history_;
    int lmr_mode_;

    std::int64_t nodes_ = 0;
    std::int64_t futility_cuts_ = 0;
    std::int64_t node_limit_ = kNoLimit;
    bool has_deadline_ = false;
    std::chrono::steady_clock::time_point deadline_;
    bool stopped_ = false;
    bool timed_ = false;
    Position root_;
    // Mixed into every table key; 0 in search(). root_scores() sets a new value per call.
    std::uint64_t key_salt_ = 0;
    // Only table entries of exactly the depth asked for count; set by root_scores().
    bool exact_depth_ = false;
    std::uint64_t salt_counter_ = 0;
};

}  // namespace sh
