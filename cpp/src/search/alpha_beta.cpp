#include "alpha_beta.hpp"

#include <algorithm>
#include <cmath>
#include <stdexcept>
#include <string>
#include <utility>

#include "java_compat.hpp"
#include "verifying_eval_state.hpp"

namespace sh {

namespace {

constexpr std::size_t u(int i) { return static_cast<std::size_t>(i); }

// Late move reductions per mode (1..3), indexed [depth * kMaxMoves + move index]; computed once per process with the formula of
// the Java tables. Mode 0 (off) shares the mode-1 table, which is never read then.
std::vector<int> build_lmr_table(int mode) {
    std::vector<int> table(u(AlphaBeta::kMaxDepth + 1) * u(kMaxMoves), 0);
    for (int d = 1; d <= AlphaBeta::kMaxDepth; ++d) {
        for (int i = 1; i < kMaxMoves; ++i) {
            int r = 1;
            if (mode == 2) {
                r = java_d2i(0.5 + std::log(static_cast<double>(d)) * std::log(static_cast<double>(i)) / 2.0);
            } else if (mode == 3) {
                r = java_d2i(0.5 + std::log(static_cast<double>(d)) * std::log(static_cast<double>(i)) / 1.25);
            }
            table[u(d) * u(kMaxMoves) + u(i)] = std::max(1, std::min(r, d - 2));
        }
    }
    return table;
}

const std::vector<int>& lmr_table(int mode) {
    static const std::array<std::vector<int>, 3> tables = {build_lmr_table(1), build_lmr_table(2), build_lmr_table(3)};
    return tables[u(mode == 0 ? 0 : mode - 1)];
}

}  // namespace

AlphaBeta::AlphaBeta(std::unique_ptr<Evaluator> evaluator, int table_bits, bool use_table, const SearchOptions& options,
                     bool verify_incremental)
    : evaluator_(std::move(evaluator)),
      use_table_(use_table),
      options_(options),
      move_buffers_(u(kMaxDepth + 1)),
      order_keys_(u(kMaxDepth + 1)),
      killer_moves_(u(kMaxDepth + 2)),
      lmr_mode_(options.lmr),
      root_(Position::empty(RuleSet::defaults())) {
    if (table_bits < 0 || table_bits > 30) throw std::invalid_argument("table_bits must be in 0..30");
    std::unique_ptr<EvalState> incremental = evaluator_->incremental();
    if (incremental && verify_incremental) {
        state_ = std::make_unique<VerifyingEvalState>(*evaluator_, std::move(incremental));
    } else {
        state_ = std::move(incremental);
    }
    std::size_t size = use_table ? (std::size_t{1} << table_bits) : 1;
    if (options_.depth_preferred && size < 2) size = 2;  // one bucket of two entries
    keys_.assign(size, 0);
    scores_.assign(size, 0);
    info_.assign(size, 0);
    if (options_.depth_preferred) generations_.assign(size, 0);
    mask_ = static_cast<std::uint32_t>(size - 1);
    if (options_.history) history_.assign(u(kCells) * u(kCells), 0);
    (void)lmr_table(options_.lmr);  // build the shared table now, not inside the first search
    clear_killers();
}

void AlphaBeta::clear() {
    std::ranges::fill(keys_, 0);
    std::ranges::fill(info_, 0);
    std::ranges::fill(generations_, 0);
    std::ranges::fill(history_, 0);
    clear_killers();
}

void AlphaBeta::clear_killers() {
    for (auto& k : killer_moves_) k = {-1, -1};
}

int AlphaBeta::lmr_reduction(int mode, int depth, int index) {
    return lmr_table(mode)[u(depth) * u(kMaxMoves) + u(index)];
}

void AlphaBeta::begin_search(std::int64_t max_nodes) {
    node_limit_ = max_nodes;
    nodes_ = 0;
    futility_cuts_ = 0;
    stopped_ = false;
}

bool AlphaBeta::time_is_up() const { return has_deadline_ && std::chrono::steady_clock::now() > deadline_; }

SearchResult AlphaBeta::search(const Position& position, int max_depth, std::int64_t time_limit_millis, std::int64_t max_nodes) {
    if (max_depth < 1 || max_depth > kMaxDepth) throw std::invalid_argument("max_depth must be in 1.." + std::to_string(kMaxDepth));
    root_ = position.copy();
    tracking_ = state_ && state_->reset(root_);
    begin_search(max_nodes);
    has_deadline_ = time_limit_millis != kNoLimit;
    if (has_deadline_) deadline_ = std::chrono::steady_clock::now() + std::chrono::milliseconds(time_limit_millis);
    ++generation_;
    if (generation_ == 0) generation_ = 1;  // 0 marks an empty entry
    clear_killers();
    for (int& h : history_) h >>= 2;
    MoveBuffer root_moves{};
    const int root_count = root_.generate(root_moves);
    if (root_count == 0 || root_.outcome().has_value()) {
        return SearchResult{-1, terminal_score(root_, 0), 0, 0, true};
    }
    int best_move = root_moves[0];
    int best_score = 0;
    int done_depth = 0;
    bool complete = true;
    for (int depth = 1; depth <= max_depth; ++depth) {
        timed_ = depth > 1;  // depth 1 always completes, so there is always a move to return
        // a previous score outside the ordinary band (a mate, or a race value) gets the full window: a narrow window around such a
        // value fails and would only be widened until it is full anyway
        const RootIteration iteration = options_.aspiration > 0 && depth > 1 && scores::kind(best_score) == scores::Kind::Ordinary
                                            ? aspiration_root(depth, best_move, best_score)
                                            : search_root(depth, best_move, -kMate - 1, kMate + 1);
        if (stopped_ && depth > 1) {
            complete = false;
            if (options_.partial && iteration.usable) {
                best_move = iteration.move;
                best_score = iteration.score;
            }
            break;
        }
        best_move = iteration.move;
        best_score = iteration.score;
        done_depth = depth;
        if (std::abs(best_score) >= kMate - kMaxDepth) break;  // a forced win or loss is known; a deeper search cannot change who wins
    }
    return SearchResult{best_move, best_score, done_depth, nodes_, complete};
}

// A root iteration in a window around the previous score, widened until the score falls inside.
AlphaBeta::RootIteration AlphaBeta::aspiration_root(int depth, int first_guess, int previous) {
    int delta = options_.aspiration;
    int alpha = std::max(-kMate - 1, previous - delta);
    int beta = std::min(kMate + 1, previous + delta);
    int guess = first_guess;
    while (true) {
        const RootIteration iteration = search_root(depth, guess, alpha, beta);
        if (stopped_) return iteration;
        const int score = iteration.score;
        if (score <= alpha && alpha > -kMate - 1) {
            delta *= 4;
            alpha = delta > kAspirationMax ? -kMate - 1 : std::max(-kMate - 1, score - delta);
        } else if (score >= beta && beta < kMate + 1) {
            guess = iteration.move;  // a fail high has found a better move; search it first
            delta *= 4;
            beta = delta > kAspirationMax ? kMate + 1 : std::min(kMate + 1, score + delta);
        } else {
            return iteration;
        }
    }
}

// One root iteration in the window (alpha, beta). If the limit stops the iteration, move and score come from the root moves
// searched completely, and `usable` is set if there was at least one such move and its score is above alpha (so it is a real value
// or a fail high, not a bound from a fail low).
AlphaBeta::RootIteration AlphaBeta::search_root(int depth, int first_guess, int alpha_in, int beta) {
    MoveBuffer& moves = move_buffers_[0];
    const int count = root_.generate(moves);
    order(count, 0, first_guess);
    MoveBuffer& keys = order_keys_[0];
    pick(moves, keys, 0, count);
    int alpha = alpha_in;
    int best_move = moves[0];
    int best_score = -kMate - 1;
    int completed = 0;
    for (int i = 0; i < count; ++i) {
        if (i > 0) pick(moves, keys, i, count);
        make(moves[u(i)]);
        int score = 0;
        if (options_.pvs && i > 0) {
            score = -negamax(depth - 1, -alpha - 1, -alpha, 1);
            if (score > alpha && score < beta && !stopped_) score = -negamax(depth - 1, -beta, -alpha, 1);
        } else {
            score = -negamax(depth - 1, -beta, -alpha, 1);
        }
        unmake();
        if (stopped_) break;
        ++completed;
        if (score > best_score) {
            best_score = score;
            best_move = moves[u(i)];
            if (score > alpha) alpha = score;
        }
        if (alpha >= beta) break;  // only inside an aspiration window
    }
    if (use_table_ && !stopped_) {
        const int flag = best_score <= alpha_in ? kUpper : best_score >= beta ? kLower : kExact;
        store(root_.hash(), best_score, depth, flag, best_move, 0);
    }
    return RootIteration{best_move, best_score, completed > 0 && best_score > alpha_in};
}

int AlphaBeta::negamax(int depth, int alpha_in, int beta_in, int height) {
    ++nodes_;
    if (timed_ && (nodes_ >= node_limit_ || ((nodes_ & 2047) == 0 && time_is_up()))) stopped_ = true;
    if (stopped_) return 0;
    if (root_.outcome().has_value()) return terminal_score(root_, height);
    if (depth == 0) return evaluate();
    int alpha = alpha_in;
    int beta = beta_in;
    const std::uint64_t key = root_.hash() ^ key_salt_;
    int tt_move = -1;
    if (use_table_) {
        const int slot = probe(key);
        if (slot >= 0) {
            const std::uint32_t stored = info_[u(slot)];
            const int stored_depth = static_cast<int>(stored >> 24);
            const int flag = static_cast<int>((stored >> 22) & 3);
            tt_move = static_cast<int>(stored & 0x1FFFFF) - 1;
            if (exact_depth_ ? stored_depth == depth : stored_depth >= depth) {
                const int s = options_.mate_fix ? from_table(scores_[u(slot)], height) : scores_[u(slot)];
                if (flag == kExact) return s;
                if (flag == kLower && s > alpha) {
                    alpha = s;
                } else if (flag == kUpper && s < beta) {
                    beta = s;
                }
                if (alpha >= beta) return s;
            }
        }
    }
    MoveBuffer& moves = move_buffers_[u(height)];
    const int count = root_.generate(moves);
    if (count == 0) return terminal_score_no_moves(root_);
    order(count, height, tt_move);
    MoveBuffer& keys = order_keys_[u(height)];
    pick(moves, keys, 0, count);
    int best = -kMate - 1;
    int best_move = moves[0];
    const int original_alpha = alpha;
    const bool pvs = options_.pvs;
    const bool lmr = options_.lmr > 0 && depth >= kLmrMinDepth;
    std::array<int, 2>& killers = killer_moves_[u(height)];
    const int margin = depth == 1 ? options_.futility : depth == 2 ? options_.futility2 : 0;
    const bool futile = margin > 0 && futility_allowed(root_, depth, alpha, *evaluator_);
    int stand = 0;
    bool stand_known = false;
    for (int i = 0; i < count; ++i) {
        if (i > 0) pick(moves, keys, i, count);
        const int move = moves[u(i)];
        if (futile && i > 0 && move != tt_move && move != killers[0] && move != killers[1]) {
            // the rest is ordered by distance gain, so once this move cannot reach alpha, none can
            const int gain = history_.empty() ? keys[u(i)] : keys[u(i)] >> 20;
            if (!stand_known) {
                stand = evaluate();
                stand_known = true;
            }
            const int bound = stand + options_.futility_gain * gain + margin;
            if (bound <= alpha) {
                ++futility_cuts_;
                if (bound > best) best = bound;
                break;
            }
        }
        make(move);
        int score = 0;
        const int reduction = lmr && i >= kLmrFullMoves && move != tt_move && move != killers[0] && move != killers[1]
                                  ? lmr_table(lmr_mode_)[u(depth) * u(kMaxMoves) + u(i)]
                                  : 0;
        if (i == 0 || (!pvs && reduction == 0)) {
            score = -negamax(depth - 1, -beta, -alpha, height + 1);
        } else {
            score = -negamax(depth - 1 - reduction, -alpha - 1, -alpha, height + 1);
            if (reduction > 0 && score > alpha && !stopped_) {
                score = -negamax(depth - 1, pvs ? -alpha - 1 : -beta, -alpha, height + 1);
            }
            if (pvs && score > alpha && score < beta && !stopped_) score = -negamax(depth - 1, -beta, -alpha, height + 1);
        }
        unmake();
        if (stopped_) return 0;
        if (score > best) {
            best = score;
            best_move = move;
        }
        if (score > alpha) alpha = score;
        if (alpha >= beta) {
            if (options_.killers && move != killers[0]) {
                killers[1] = killers[0];
                killers[0] = move;
            }
            if (!history_.empty()) {
                const int h = Move::from(move) * kCells + Move::to(move);
                int& entry = history_[u(h)];
                entry = std::min(kHistoryMax, entry + depth * depth);
            }
            break;
        }
    }
    if (use_table_) {
        const int flag = best <= original_alpha ? kUpper : best >= beta ? kLower : kExact;
        store(key, best, depth, flag, best_move, height);
    }
    return best;
}

// Whether futility pruning may cut moves at this node. It is off where the bound "static value + futility_gain x distance gain +
// margin" can fail because a child within `depth` plies is not scored by the evaluation: near a win, near the move limit, with
// repetition draws, where the evaluator says so (a race value can appear) and when alpha is not an ordinary score.
bool AlphaBeta::futility_allowed(const Position& position, int depth, int alpha, Evaluator& evaluator) {
    if (scores::kind(alpha) != scores::Kind::Ordinary) return false;
    const int near_win = position.layout().goal_size() - depth;
    const int me = position.side_to_move();
    if (position.goal_occupied(me) >= near_win || position.goal_occupied(1 - me) >= near_win) return false;
    const RuleSet& rules = position.rules();
    if (position.ply() + depth >= rules.move_limit() || rules.repetition() == RuleSet::Repetition::RepetitionIsDraw) return false;
    return evaluator.futility_safe(position, depth);
}

std::vector<int> AlphaBeta::root_scores(const Position& position, int depth) {
    if (depth < 1 || depth > kMaxDepth) throw std::invalid_argument("depth must be in 1.." + std::to_string(kMaxDepth));
    if (!options_.exact_root()) {
        throw std::logic_error("root_scores needs futility and late move reductions off, got " + options_.describe());
    }
    root_ = position.copy();
    tracking_ = state_ && state_->reset(root_);
    begin_search(kNoLimit);
    has_deadline_ = false;
    timed_ = false;
    ++generation_;
    if (generation_ == 0) generation_ = 1;
    clear_killers();
    std::ranges::fill(history_, 0);
    ++salt_counter_;
    key_salt_ = salt_counter_ * 0x9E3779B97F4A7C15ULL;
    exact_depth_ = true;
    MoveBuffer moves{};
    const int count = root_.outcome().has_value() ? 0 : root_.generate(moves);
    std::vector<int> result(u(count), 0);
    for (int i = 0; i < count; ++i) {
        make(moves[u(i)]);
        result[u(i)] = -negamax(depth - 1, -kMate - 1, kMate + 1, 1);
        unmake();
    }
    key_salt_ = 0;
    exact_depth_ = false;
    return result;
}

// Plays a move on the root position, through the incremental state when there is one. The state's protocol: the caller plays the
// move, then calls make(); the caller takes it back, then calls unmake().
void AlphaBeta::make(int move) {
    root_.make(move);
    if (tracking_) state_->make(root_, move);
}

void AlphaBeta::unmake() {
    root_.unmake();
    if (tracking_) state_->unmake(root_);
}

int AlphaBeta::evaluate() { return tracking_ ? state_->evaluate(root_) : evaluator_->evaluate(root_); }

// The slot holding `key`, or -1.
int AlphaBeta::probe(std::uint64_t key) const {
    const std::uint32_t slot = static_cast<std::uint32_t>(key ^ (key >> 32)) & mask_;
    if (!options_.depth_preferred) return keys_[slot] == key ? static_cast<int>(slot) : -1;
    const std::uint32_t first = slot & ~std::uint32_t{1};
    if (keys_[first] == key && generations_[first] != 0) return static_cast<int>(first);
    const std::uint32_t second = first | 1;
    return keys_[second] == key && generations_[second] != 0 ? static_cast<int>(second) : -1;
}

void AlphaBeta::store(std::uint64_t key, int score, int depth, int flag, int move, int height) {
    std::uint32_t slot = static_cast<std::uint32_t>(key ^ (key >> 32)) & mask_;
    if (options_.depth_preferred) {
        const std::uint32_t first = slot & ~std::uint32_t{1};
        const bool replace_first = keys_[first] == key || generations_[first] != generation_ ||
                                   depth >= static_cast<int>(info_[first] >> 24);
        slot = replace_first ? first : (first | 1);
        generations_[slot] = generation_;
    }
    keys_[slot] = key;
    scores_[slot] = options_.mate_fix ? to_table(score, height) : score;
    info_[slot] = (static_cast<std::uint32_t>(std::min(depth, 255)) << 24) | (static_cast<std::uint32_t>(flag) << 22) |
                  static_cast<std::uint32_t>(move + 1);
}

int AlphaBeta::to_table(int score, int height) {
    if (score >= kMateBound) return score + height;
    if (score <= -kMateBound) return score - height;
    return score;
}

int AlphaBeta::from_table(int score, int height) {
    if (score >= kMateBound) return score - height;
    if (score <= -kMateBound) return score + height;
    return score;
}

// The ordering keys of the moves into order_keys_[height], without sorting: the table move first, then the killer moves (if on),
// then by the distance a move gains for the mover, with ties broken by the history table (if on). The caller brings move i to the
// front with pick() just before it searches move i, so a node that cuts off after a few moves never sorts the rest.
void AlphaBeta::order(int count, int height, int first_move) {
    const Layout& layout = root_.layout();
    const int player = root_.side_to_move();
    MoveBuffer& gain = order_keys_[u(height)];
    const MoveBuffer& moves = move_buffers_[u(height)];
    const int killer0 = options_.killers ? killer_moves_[u(height)][0] : -1;
    const int killer1 = options_.killers ? killer_moves_[u(height)][1] : -1;
    const bool use_history = !history_.empty();
    for (int i = 0; i < count; ++i) {
        const int m = moves[u(i)];
        if (m == first_move) {
            gain[u(i)] = std::numeric_limits<int>::max();
        } else if (m == killer0) {
            gain[u(i)] = std::numeric_limits<int>::max() - 1;
        } else if (m == killer1) {
            gain[u(i)] = std::numeric_limits<int>::max() - 2;
        } else {
            const int from = Move::from(m);
            const int to = Move::to(m);
            const int g = layout.distance(player, from) - layout.distance(player, to);
            gain[u(i)] = use_history ? g * (1 << 20) + history_[u(from * kCells + to)] : g;
        }
    }
}

// Step i of a selection sort on `keys` (descending), applied to both arrays: swaps the first entry with the largest key among
// positions i..count-1 into position i. The strict comparison keeps the earliest of tied keys, so calling it for i = 0, 1, ... in
// turn yields exactly the order of the full selection sort of the Java search; any other sort orders ties differently.
void AlphaBeta::pick(MoveBuffer& moves, MoveBuffer& keys, int i, int count) {
    int best = i;
    int best_key = keys[u(i)];
    for (int j = i + 1; j < count; ++j) {
        if (keys[u(j)] > best_key) {
            best = j;
            best_key = keys[u(j)];
        }
    }
    if (best != i) {
        std::swap(moves[u(i)], moves[u(best)]);
        keys[u(best)] = keys[u(i)];
        keys[u(i)] = best_key;
    }
}

int AlphaBeta::terminal_score(const Position& position, int height) {
    const std::optional<Outcome> outcome = position.outcome();
    if (!outcome) return terminal_score_no_moves(position);
    const int me = position.side_to_move();
    if (outcome->has_winner()) return outcome->winner == me ? kMate - height : -(kMate - height);
    return static_cast<int>(java_round(outcome->value(me) * kEndScale));
}

int AlphaBeta::terminal_score_no_moves(const Position& position) {
    return static_cast<int>(java_round(position.no_moves_outcome().value(position.side_to_move()) * kEndScale));
}

}  // namespace sh
