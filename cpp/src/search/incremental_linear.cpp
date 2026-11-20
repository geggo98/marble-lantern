#include "incremental_linear.hpp"

#include <algorithm>
#include <stdexcept>
#include <string>

#include "move.hpp"

namespace sh {

namespace {

using namespace linear_detail;

constexpr std::size_t u(int i) { return static_cast<std::size_t>(i); }

constexpr int max_distance() {
    int best = 0;
    for (int p = 0; p < 2; ++p) {
        for (const int d : kLinearTables.dist[u(p)]) best = d > best ? d : best;
    }
    return best;
}
constexpr int kMaxDistance = max_distance();

// Replaces `old_key` by `new_key` in the falling array `a` of its n keys, keeping it falling.
void replace_key(std::span<int> a, int old_key, int new_key) {
    const std::size_t n = a.size();
    std::size_t i = 0;
    while (a[i] != old_key) ++i;
    while (i > 0 && a[i - 1] < new_key) {
        a[i] = a[i - 1];
        --i;
    }
    while (i + 1 < n && a[i + 1] > new_key) {
        a[i] = a[i + 1];
        ++i;
    }
    a[i] = new_key;
}

}  // namespace

bool IncrementalLinear::reset(const Position& root) {
    // the tables and the jump graph are those of the 4-row houses; the full evaluation takes over on 5 rows
    if (root.rules().triangle_rows() != RuleSet::TriangleRows::Four) return false;
    if (root.rules().players() != 2 || root.piece_count(0) > kPieces || root.piece_count(1) > kPieces) return false;
    // the jump graph knows only landings on home cells; the full evaluation takes over
    if (root.rules().foreign_camps() != RuleSet::ForeignCamps::Closed) return false;
    height_ = 0;
    own_.fill(-1);
    graph_.clear();
    SideSums s{};
    for (int p = 0; p < 2; ++p) {
        const auto ps = u(p);
        const std::array<int, kCells>& dist = kLinearTables.dist[ps];
        histogram_[ps].assign(u(kMaxDistance) + 1, 0);
        for (const int c : root.pieces(p)) {
            if (!root.layout().allowed(p, c)) return false;  // a piece on a cell its owner may not enter
            own_[u(c)] = static_cast<std::int8_t>(p);
            ++histogram_[ps][u(dist[u(c)])];
            graph_.toggle(c);
        }
        // the sums by the full computation, so that the state starts from the oracle's numbers
        LinearEvaluator::side(root, p, s);
        const int base = p * kWidth;
        sum_[u(base + kDsum)] = s[u(linear_detail::kDsum)];
        sum_[u(base + kDsq)] = s[u(linear_detail::kDsq)];
        sum_[u(base + kGoal)] = s[u(linear_detail::kGoal)];
        sum_[u(base + kStart)] = s[u(linear_detail::kStart)];
        sum_[u(base + kLat)] = s[u(linear_detail::kLat)];
        sum_[u(base + kSteps)] = s[u(linear_detail::kSteps)];
        sum_[u(base + kJumps)] = s[u(linear_detail::kJumps)];
        sum_[u(base + kAdj)] = s[u(linear_detail::kAdj)];
        sum_[u(kDmax + p)] = s[u(linear_detail::kDmax)];
        sum_[u(kDmin + p)] = s[u(linear_detail::kDmin)];
        count_[ps] = s[u(linear_detail::kCount)];
        for (int i = 0; i < count_[ps]; ++i) {
            const int c = root.piece_cell(p, i);
            const int key = dist[u(c)] << 8 | c;
            int j = i;
            while (j > 0 && keys_[ps][u(j - 1)] < key) {
                keys_[ps][u(j)] = keys_[ps][u(j - 1)];
                --j;
            }
            keys_[ps][u(j)] = key;
        }
    }
    return true;
}

void IncrementalLinear::make(const Position& position, int move) {
    const int from = Move::from(move);
    const int to = Move::to(move);
    const int p = 1 - position.side_to_move();  // the move is already played: the mover is the side that is not to move now
    if (height_ == saved_.size()) saved_.resize(height_ * 2);
    Frame& frame = saved_[height_++];
    frame.sums = sum_;
    frame.from = from;
    frame.to = to;
    frame.player = p;

    // the moving piece: the per-piece terms
    const auto ps = u(p);
    const std::array<int, kCells>& dist = kLinearTables.dist[ps];
    const int df = dist[u(from)];
    const int dt = dist[u(to)];
    const int base = p * kWidth;
    sum_[u(base + kDsum)] += dt - df;
    sum_[u(base + kDsq)] += dt * dt - df * df;
    sum_[u(base + kLat)] += kLinearTables.lateral[u(to)] - kLinearTables.lateral[u(from)];
    const std::array<std::uint8_t, kCells>& kind = kLinearTables.kind[ps];
    const int kf = kind[u(from)];
    const int kt = kind[u(to)];
    if (kf != kt) {
        if (kf == kInGoal) {
            --sum_[u(base + kGoal)];
        } else if (kf == kInStart) {
            --sum_[u(base + kStart)];
        }
        if (kt == kInGoal) {
            ++sum_[u(base + kGoal)];
        } else if (kt == kInStart) {
            ++sum_[u(base + kStart)];
        }
    }
    replace_key(std::span<int>(keys_[ps]).first(u(count_[ps])), df << 8 | from, dt << 8 | to);
    if (df != dt) {
        std::vector<int>& h = histogram_[ps];
        --h[u(df)];
        ++h[u(dt)];
        int& top = sum_[u(kDmax + p)];
        if (dt > top) {
            top = dt;
        } else {
            while (h[u(top)] == 0) --top;
        }
        int& low = sum_[u(kDmin + p)];
        if (dt < low) {
            low = dt;
        } else {
            while (h[u(low)] == 0) ++low;
        }
    }

    // the pair terms
    toggle(from, p, -1);
    toggle(to, p, +1);
    graph_.toggle(from);
    graph_.toggle(to);
}

void IncrementalLinear::unmake(const Position& /*position*/) {
    const Frame& frame = saved_[--height_];
    const int from = frame.from;
    const int to = frame.to;
    const int p = frame.player;
    const auto ps = u(p);
    const int df = kLinearTables.dist[ps][u(from)];
    const int dt = kLinearTables.dist[ps][u(to)];
    replace_key(std::span<int>(keys_[ps]).first(u(count_[ps])), dt << 8 | to, df << 8 | from);
    if (df != dt) {
        --histogram_[ps][u(dt)];
        ++histogram_[ps][u(df)];
    }
    own_[u(to)] = -1;
    own_[u(from)] = static_cast<std::int8_t>(p);
    sum_ = frame.sums;
    graph_.toggle(to);
    graph_.toggle(from);
}

// Fills (sign +1) or empties (sign -1) the cell x for player p and adds the change of the pair terms to the sums. The six loop
// passes cover, per direction e from x with a = x + e and l = x + 2e: the pairs of x itself (neighbour a, landing l), the pair of a
// piece on a that looks at x as its neighbour (direction e ^ 1) and the pair of a piece on l that jumps over a onto x. No term
// looks at x in any other role, and no term of these pairs looks at x's own owner, so the order of own_[x] and the sums does not
// matter.
void IncrementalLinear::toggle(int x, int p, int sign) {
    own_[u(x)] = static_cast<std::int8_t>(sign > 0 ? p : -1);
    const std::array<std::uint8_t, kTableSize>& own_good = kLinearTables.good[u(p)];
    const std::span<const int, kTableSize> steps = geometry::step_table();
    const std::span<const int, kTableSize> lands = geometry::land_table();
    Sums& s = sum_;
    for (int e = 0; e < kDirections; ++e) {
        const std::size_t i = table_index(x, e);
        const int a = steps[i];
        if (a < 0) continue;
        const int oa = own_[u(a)];
        const int g = own_good[i];
        if (oa < 0) {
            // x's own pair: the neighbour is free, a step if it is closer
            s[u(p * kWidth + kSteps)] += sign * (g & kStepCloser);
        } else {
            // x's own pair: the neighbour is occupied, an adjacent pair if it is ours and above x, a jump if free landing closer
            if (oa == p && a > x) s[u(p * kWidth + kAdj)] += sign;
            if ((g & kJumpCloser) != 0 && own_[u(lands[i])] < 0) s[u(p * kWidth + kJumps)] += sign;
            // the piece on a looks at x as its neighbour: a free step becomes blocked, a blocked cell is an adjacent pair or a
            // possible jump
            const std::size_t j = table_index(a, e ^ 1);
            const int q = oa;
            const int gj = kLinearTables.good[u(q)][j];
            s[u(q * kWidth + kSteps)] -= sign * (gj & kStepCloser);
            if (q == p && x > a) s[u(p * kWidth + kAdj)] += sign;
            if ((gj & kJumpCloser) != 0 && own_[u(lands[j])] < 0) s[u(q * kWidth + kJumps)] += sign;
            // the piece on l jumps over the occupied a onto x: possible while x is free
            const int l = lands[i];
            if (l >= 0) {
                const int r = own_[u(l)];
                if (r >= 0 && (kLinearTables.good[u(r)][table_index(l, e ^ 1)] & kJumpCloser) != 0) s[u(r * kWidth + kJumps)] -= sign;
            }
        }
    }
}

int IncrementalLinear::evaluate(const Position& position) {
    const int me = position.side_to_move();
    fill(me, mine_);
    fill(1 - me, theirs_);
    LinearEvaluator::combine(mine_, theirs_, features_);
    double gain = 0.0;
    if (evaluator_->gain_mode()) {
        graph_.begin();
        const int mine_gain = graph_.best_gain(me, std::span<const int>(keys_[u(me)]).first(u(count_[u(me)])));
        gain = *evaluator_->gain_mode() == LinearEvaluator::GainMode::Mover
                   ? mine_gain
                   : mine_gain - graph_.best_gain(1 - me, std::span<const int>(keys_[u(1 - me)]).first(u(count_[u(1 - me)])));
    }
    return evaluator_->score(features_, gain);
}

// The sums of player p in the layout of LinearEvaluator::side.
void IncrementalLinear::fill(int p, SideSums& out) const {
    const int base = p * kWidth;
    out[u(linear_detail::kDsum)] = sum_[u(base + kDsum)];
    out[u(linear_detail::kDmax)] = sum_[u(kDmax + p)];
    out[u(linear_detail::kDmin)] = sum_[u(kDmin + p)];
    out[u(linear_detail::kDsq)] = sum_[u(base + kDsq)];
    out[u(linear_detail::kGoal)] = sum_[u(base + kGoal)];
    out[u(linear_detail::kStart)] = sum_[u(base + kStart)];
    out[u(linear_detail::kLat)] = sum_[u(base + kLat)];
    out[u(linear_detail::kJumps)] = sum_[u(base + kJumps)];
    out[u(linear_detail::kAdj)] = sum_[u(base + kAdj)];
    out[u(linear_detail::kSteps)] = sum_[u(base + kSteps)];
    out[u(linear_detail::kCount)] = count_[u(p)];
}

void IncrementalLinear::verify(const Position& position) {
    SideSums full{};
    SideSums mine{};
    for (int p = 0; p < 2; ++p) {
        LinearEvaluator::side(position, p, full);
        fill(p, mine);
        if (full != mine) {
            std::string message = "incremental sums of player " + std::to_string(p) + " differ from the full ones, first at index ";
            for (std::size_t i = 0; i < full.size(); ++i) {
                if (full[i] != mine[i]) {
                    message += std::to_string(i) + " (" + std::to_string(mine[i]) + " against " + std::to_string(full[i]) + ")";
                    break;
                }
            }
            throw std::logic_error(message);
        }
    }
    for (int p = 0; p < 2; ++p) {
        const auto ps = u(p);
        std::array<int, kPieces> expected{};
        const int n = count_[ps];
        for (int i = 0; i < n; ++i) {
            const int c = position.piece_cell(p, i);
            expected[u(i)] = kLinearTables.dist[ps][u(c)] << 8 | c;
        }
        const std::span<int> used = std::span<int>(expected).first(u(n));
        std::ranges::sort(used);
        for (int i = 0; i < n; ++i) {
            if (keys_[ps][u(i)] != expected[u(n - 1 - i)]) throw std::logic_error("piece order of player " + std::to_string(p) + " differs");
        }
    }
    if (!graph_.matches(position)) throw std::logic_error("jump graph differs from the position's");
    for (int c = 0; c < kCells; ++c) {
        if (own_[u(c)] != position.owner(c)) throw std::logic_error("owner of cell " + std::to_string(c) + " differs");
    }
}

}  // namespace sh
