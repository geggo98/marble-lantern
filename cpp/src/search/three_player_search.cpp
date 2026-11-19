#include "three_player_search.hpp"

#include <algorithm>
#include <bit>
#include <iterator>
#include <cstdlib>
#include <stdexcept>
#include <utility>

#include "geometry.hpp"
#include "layout.hpp"
#include "linear_weights.hpp"
#include "move.hpp"
#include "scores.hpp"

namespace sh {

namespace {

constexpr std::size_t u(int i) { return static_cast<std::size_t>(i); }

constexpr int kMate = scores::kMate;
constexpr int kMateBound = scores::kMateBound;
constexpr int kSecondBound = ThreePlayerSearch::kSecond - 1000;
constexpr int kExact = 0;
constexpr int kLower = 1;  // score >= stored
constexpr int kUpper = 2;  // score <= stored
constexpr std::array<std::uint64_t, 3> kRootKey = {0x9E3779B97F4A7C15ULL, 0xC2B2AE3D27D4EB4FULL, 0x165667B19E3779F9ULL};

// The per-player tables of the three-player layout for the side sums (Java: the GOOD, DIST and KIND tables of LinearEvaluator for
// layout 1, and LATERAL of ThreePlayerSearch), built at compile time.
struct Tables3 {
    // per player: kStepCloser | kJumpCloser per (cell, direction); 0 for cells the player may not enter
    std::array<std::array<std::uint8_t, kTableSize>, 3> good{};
    std::array<std::array<int, kCells>, 3> dist{};
    std::array<std::array<std::uint8_t, kCells>, 3> kind{};
    // per player and cell: the distance from the centre line of the player's own start-goal axis (|q - s|, |q - r|, |r - s|)
    std::array<std::array<int, kCells>, 3> lateral{};
};

constexpr int abs_int(int v) { return v < 0 ? -v : v; }

constexpr Tables3 build_tables3() {
    Tables3 t;
    const Layout& layout = detail::kThreePlayerLayout;
    for (int p = 0; p < 3; ++p) {
        const auto ps = u(p);
        for (int c = 0; c < kCells; ++c) {
            const auto cs = u(c);
            t.dist[ps][cs] = layout.distance(p, c);
            const int corner = geometry::corner_of(c);
            t.kind[ps][cs] = corner == layout.goal_corner(p) ? linear_detail::kInGoal : corner == layout.start_corner(p) ? linear_detail::kInStart : 0;
            const int q = geometry::q(c);
            const int r = geometry::r(c);
            const int s = geometry::s(c);
            t.lateral[ps][cs] = p == 0 ? abs_int(q - s) : p == 1 ? abs_int(q - r) : abs_int(r - s);
            if (!layout.allowed(p, c)) continue;  // a piece of p never stands here
            const int here = layout.distance(p, c);
            for (int d = 0; d < kDirections; ++d) {
                const std::size_t i = table_index(c, d);
                const int step = geometry::step_table()[i];
                const int land = geometry::land_table()[i];
                std::uint8_t bits = 0;
                if (step >= 0 && layout.allowed(p, step) && layout.distance(p, step) < here) bits |= linear_detail::kStepCloser;
                if (land >= 0 && layout.allowed(p, land) && layout.distance(p, land) < here) bits |= linear_detail::kJumpCloser;
                t.good[ps][i] = bits;
            }
        }
    }
    return t;
}

constexpr Tables3 kTables3 = build_tables3();

// The integer sums of player p (Java: LinearEvaluator.side with the three-player layout, plus the per-player lateral sum that
// ThreePlayerSearch.evaluate writes into the LAT slot).
void side3(const Position& position, int p, linear_detail::SideSums& s) {
    using namespace linear_detail;
    const auto ps = u(p);
    const auto& good = kTables3.good[ps];
    const auto& dist = kTables3.dist[ps];
    const auto& kind = kTables3.kind[ps];
    const auto& lat = kTables3.lateral[ps];
    int dsum = 0;
    int dmax = 0;
    int dmin = std::numeric_limits<int>::max();
    int dsq = 0;
    int in_goal = 0;
    int in_start = 0;
    int lateral = 0;
    int jumps = 0;
    int adjacent = 0;
    int steps = 0;
    const std::span<const int> pieces = position.pieces(p);
    for (const int c : pieces) {
        const int d = dist[u(c)];
        dsum += d;
        dsq += d * d;
        dmax = std::max(dmax, d);
        dmin = std::min(dmin, d);
        const int k = kind[u(c)];
        if (k == kInGoal) {
            ++in_goal;
        } else if (k == kInStart) {
            ++in_start;
        }
        lateral += lat[u(c)];
        const std::size_t base = u(c) * kDirections;
        for (std::size_t dir = 0; dir < kDirections; ++dir) {
            const int a = geometry::step_table()[base + dir];
            if (a < 0) continue;
            const int owner = position.owner(a);
            const int g = good[base + dir];
            if (owner < 0) {
                steps += g & kStepCloser;
            } else {
                if (owner == p && a > c) ++adjacent;
                if ((g & kJumpCloser) != 0 && position.owner(geometry::land_table()[base + dir]) < 0) ++jumps;
            }
        }
    }
    s[u(kDsum)] = dsum;
    s[u(kDmax)] = dmax;
    s[u(kDmin)] = dmin;
    s[u(kDsq)] = dsq;
    s[u(kGoal)] = in_goal;
    s[u(kStart)] = in_start;
    s[u(kLat)] = lateral;
    s[u(kJumps)] = jumps;
    s[u(kAdj)] = adjacent;
    s[u(kSteps)] = steps;
    s[u(kCount)] = static_cast<int>(pieces.size());
}

}  // namespace

ThreePlayerSearch::ThreePlayerSearch(std::span<const double> weights, int table_bits) {
    if (table_bits < 0 || table_bits > 30) throw std::invalid_argument("table_bits must be in 0..30, got " + std::to_string(table_bits));
    auto linear = LinearEvaluator::create(weights, linear_weights::kScale);
    if (!linear) throw std::invalid_argument(linear.error());
    linear_ = std::move(*linear);
    const std::size_t size = std::size_t{1} << table_bits;
    table_mask_ = static_cast<int>(size - 1);
    table_.resize(size);
    move_buffers_.resize(kMaxDepth + 2);
    order_buffers_.resize(kMaxDepth + 2);
}

SearchResult ThreePlayerSearch::search(const Position& position, int max_depth, std::int64_t time_limit_millis, std::int64_t max_nodes) {
    if (position.rules().players() != 3) throw std::invalid_argument("three-player search needs a three-player position");
    Position work = position.copy();
    pos_ = &work;
    root_ = work.side_to_move();
    nodes_ = 0;
    node_limit_ = max_nodes;
    aborted_ = false;
    has_deadline_ = time_limit_millis != kNoLimit;
    if (has_deadline_) deadline_ = std::chrono::steady_clock::now() + std::chrono::milliseconds(time_limit_millis);
    for (auto& k : killers_) k = {0, 0};
    std::array<int, kMaxMoves> moves{};
    const int n = work.generate(moves);
    if (n == 0) {
        const int score = evaluate(work, root_);
        pos_ = nullptr;
        return SearchResult{-1, score, 0, 0, true};
    }
    std::vector<int> ordered(moves.begin(), std::next(moves.begin(), n));
    int best_move = ordered[0];
    int best_score = 0;
    int done_depth = 0;
    bool complete = true;
    const int top = std::min(max_depth, kMaxDepth);
    for (int depth = 1; depth <= top; ++depth) {
        limited_ = depth > 1;  // the first iteration always finishes, so there is always a move
        int move = 0;
        int score = 0;
        if (!search_root(ordered, depth, best_move, move, score)) {
            complete = false;
            break;
        }
        best_move = move;
        best_score = score;
        done_depth = depth;
        if (std::abs(best_score) >= kMateBound) break;  // decided
    }
    pos_ = nullptr;
    return SearchResult{best_move, best_score, done_depth, nodes_, complete};
}

void ThreePlayerSearch::clear() {
    std::ranges::fill(table_, Entry{});
    for (auto& k : killers_) k = {0, 0};
}

SearchResult ThreePlayerSearch::search_after(const Position& position, int move, int max_depth, std::int64_t max_nodes) {
    if (position.rules().players() != 3) throw std::invalid_argument("three-player search needs a three-player position");
    Position work = position.copy();
    const int mover = work.side_to_move();
    work.make(move);
    const SearchResult child = search_as(work, mover, max_depth, max_nodes);
    int score = child.score;
    // The child is at height 0, a search of the parent counts its mate scores from height 1 (also second place).
    score = score >= kMateBound || (score >= kSecondBound && score <= kSecond) ? score - 1 : score <= -kMateBound ? score + 1 : score;
    return SearchResult{-1, score, child.depth, child.nodes, child.complete};
}

SearchResult ThreePlayerSearch::search_as(const Position& position, int root_player, int max_depth, std::int64_t max_nodes) {
    Position work = position.copy();
    pos_ = &work;
    root_ = root_player;
    nodes_ = 0;
    node_limit_ = max_nodes;
    aborted_ = false;
    has_deadline_ = false;
    for (auto& k : killers_) k = {0, 0};
    if (work.rules().plays_on_after_win()) {
        const int finished = work.finished_mask();
        if (finished != 0) {
            // the same terminal scores as in after(), at height 0
            if (work.is_finished(root_player)) {
                pos_ = nullptr;
                return SearchResult{-1, work.finishers()[0] == root_player ? kMate : kSecond, 0, 1, true};
            }
            if (std::popcount(static_cast<unsigned>(finished)) >= 2) {
                pos_ = nullptr;
                return SearchResult{-1, -kMate, 0, 1, true};  // the root player is the last one
            }
        }
    } else if (work.has_won(root_player)) {
        pos_ = nullptr;
        return SearchResult{-1, kMate, 0, 1, true};
    }
    int score = 0;
    int done_depth = 0;
    bool complete = true;
    const int top = std::min(max_depth, kMaxDepth);
    for (int depth = 1; depth <= top; ++depth) {
        limited_ = depth > 1;
        const int s = ab(depth, -kMate - 1, kMate + 1, 0);
        if (aborted_) {
            complete = false;
            break;
        }
        score = s;
        done_depth = depth;
        if (std::abs(score) >= kMateBound) break;
    }
    pos_ = nullptr;
    return SearchResult{-1, score, done_depth, nodes_, complete};
}

std::array<int, 3> ThreePlayerSearch::evaluate_all(const Position& position) {
    // A player that has finished holds its rank constant (kMate first, kSecond second); once two have finished the last player holds -kMate.
    const bool play_on = position.rules().plays_on_after_win();
    const bool over = play_on && std::popcount(static_cast<unsigned>(position.finished_mask())) >= 2;
    std::array<int, 3> all{};
    for (int p = 0; p < 3; ++p) {
        all[u(p)] = play_on && position.is_finished(p) ? (position.finishers()[0] == p ? kMate : kSecond) : over ? -kMate : evaluate(position, p);
    }
    return all;
}

bool ThreePlayerSearch::search_root(std::vector<int>& ordered, int depth, int previous_best, int& best_move, int& best_score_out) {
    const std::size_t n = ordered.size();
    std::vector<int> order_keys(n);
    for (std::size_t i = 0; i < n; ++i) {
        order_keys[i] = ordered[i] == previous_best ? std::numeric_limits<int>::max() : gain(root_, ordered[i]);
    }
    // stable insertion sort, descending: equal keys keep the generator's order
    for (std::size_t i = 1; i < n; ++i) {
        const int m = ordered[i];
        const int k = order_keys[i];
        std::size_t j = i;
        while (j > 0 && order_keys[j - 1] < k) {
            ordered[j] = ordered[j - 1];
            order_keys[j] = order_keys[j - 1];
            --j;
        }
        ordered[j] = m;
        order_keys[j] = k;
    }
    int alpha = -kMate - 1;
    int best = ordered[0];
    int best_score = -kMate - 1;
    for (std::size_t i = 0; i < n; ++i) {
        const int move = ordered[i];
        pos_->make(move);
        const int score = after(root_, depth - 1, alpha, kMate + 1, 1);
        pos_->unmake();
        if (aborted_) return false;
        if (score > best_score) {
            best_score = score;
            best = move;
            alpha = std::max(alpha, score);
        }
    }
    best_move = best;
    best_score_out = best_score;
    return true;
}

// The value of the position after the player `mover` made a move (not yet unmade).
int ThreePlayerSearch::after(int mover, int depth, int alpha, int beta, int height) {
    if (pos_->rules().plays_on_after_win()) {
        const int finished = pos_->finished_mask();
        if (finished != 0) {
            if (pos_->is_finished(root_)) {
                ++nodes_;
                return pos_->finishers()[0] == root_ ? kMate - height : kSecond - height;
            }
            if (std::popcount(static_cast<unsigned>(finished)) >= 2) {
                ++nodes_;
                return -(kMate - height);  // the root player is the last one
            }
        }
        return ab(depth, alpha, beta, height);
    }
    if (pos_->has_won(mover)) {
        ++nodes_;
        return mover == root_ ? kMate - height : -(kMate - height);
    }
    return ab(depth, alpha, beta, height);
}

bool ThreePlayerSearch::stop() {
    if (!limited_) return false;
    if (nodes_ >= node_limit_) return true;
    return (nodes_ & 1023) == 0 && has_deadline_ && std::chrono::steady_clock::now() > deadline_;
}

static int to_table(int score, int height) {
    return score >= kMateBound || (score >= kSecondBound && score <= ThreePlayerSearch::kSecond) ? score + height
           : score <= -kMateBound                                                                  ? score - height
                                                                                                   : score;
}
static int from_table(int score, int height) {
    return score >= kMateBound || (score >= kSecondBound && score <= ThreePlayerSearch::kSecond) ? score - height
           : score <= -kMateBound                                                                  ? score + height
                                                                                                   : score;
}

int ThreePlayerSearch::ab(int depth, int alpha_in, int beta_in, int height) {
    int alpha = alpha_in;
    int beta = beta_in;
    ++nodes_;
    if (stop()) {
        aborted_ = true;
        return 0;
    }
    if (depth <= 0 || pos_->ply() >= pos_->rules().move_limit() || height >= kMaxDepth) return evaluate(*pos_, root_);
    const int mover = pos_->side_to_move();
    const bool maximising = mover == root_;
    const std::uint64_t key = pos_->hash() ^ kRootKey[u(root_)];
    const std::size_t slot = static_cast<std::size_t>(static_cast<std::int32_t>(static_cast<std::uint32_t>(key ^ (key >> 29))) & table_mask_);
    Entry& entry = table_[slot];
    int tt_move = -1;
    if (entry.key == key) {
        tt_move = entry.best_move;
        if ((entry.meta >> 2) >= depth) {
            const int bound = entry.meta & 3;
            const int s = from_table(entry.score, height);
            if (bound == kExact || (bound == kLower && s >= beta) || (bound == kUpper && s <= alpha)) return s;
        }
    }
    MoveBuffer& moves = move_buffers_[u(height)];
    MoveBuffer& order = order_buffers_[u(height)];
    const int n = pos_->generate(moves);
    if (n == 0) return evaluate(*pos_, root_);
    auto& killers = killers_[u(height)];
    for (int i = 0; i < n; ++i) {
        const int m = moves[u(i)];
        order[u(i)] = m == tt_move ? std::numeric_limits<int>::max()
                      : m == killers[0] ? 1'000'000
                      : m == killers[1] ? 999'000
                                        : gain(mover, m);
    }
    const int original_alpha = alpha;
    const int original_beta = beta;
    int best = maximising ? -kMate - 1 : kMate + 1;
    int best_move = moves[0];
    for (int i = 0; i < n; ++i) {
        int pick = i;  // selection: the best remaining move is tried next, so a cutoff skips the sorting
        for (int j = i + 1; j < n; ++j) {
            if (order[u(j)] > order[u(pick)]) pick = j;
        }
        const int move = moves[u(pick)];
        moves[u(pick)] = moves[u(i)];
        order[u(pick)] = order[u(i)];
        moves[u(i)] = move;
        pos_->make(move);
        const int score = after(mover, depth - 1, alpha, beta, height + 1);
        pos_->unmake();
        if (aborted_) return 0;
        if (maximising) {
            if (score > best) {
                best = score;
                best_move = move;
                alpha = std::max(alpha, score);
            }
        } else if (score < best) {
            best = score;
            best_move = move;
            beta = std::min(beta, score);
        }
        if (alpha >= beta) {
            if (killers[0] != move) {
                killers[1] = killers[0];
                killers[0] = move;
            }
            break;
        }
    }
    const int bound = best <= original_alpha ? kUpper : best >= original_beta ? kLower : kExact;
    entry.key = key;
    entry.score = to_table(best, height);
    entry.meta = depth << 2 | bound;
    entry.best_move = best_move;
    return best;
}

// The distance the mover gains with the move; jump chains gain several steps at once.
int ThreePlayerSearch::gain(int player, int move) const {
    return kTables3.dist[u(player)][u(Move::from(move))] - kTables3.dist[u(player)][u(Move::to(move))];
}

int ThreePlayerSearch::evaluate(const Position& position, int r) {
    const bool play_on = position.rules().plays_on_after_win();
    for (int p = 0; p < 3; ++p) {
        if (position.is_finished(p)) continue;
        side3(position, p, sums_[u(p)]);
    }
    int total = 0;
    int opponents = 0;
    for (int q = 0; q < 3; ++q) {
        if (q != r && !position.is_finished(q)) {
            ++opponents;
            LinearEvaluator::combine(sums_[u(r)], sums_[u(q)], features_);
            features_[u(LinearEvaluator::kTempo)] = 0.0;  // no side-to-move bonus: the score is the root player's
            total += linear_->score(features_, 0.0);
        }
    }
    return opponents == 2 ? total / 2 : total - (play_on ? kNotFirst : 0);
}

}  // namespace sh
