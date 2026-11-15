#include "linear_eval.hpp"

#include <algorithm>
#include <limits>

#include "java_compat.hpp"
#include "linear_weights.hpp"

namespace sh {

namespace {

using namespace linear_detail;

constexpr std::size_t u(int i) { return static_cast<std::size_t>(i); }

// Bit c of the 128-bit set (lo, hi): cells 0 to 63 in lo, 64 to 127 in hi at bit c - 64 (Java: shifts use c mod 64).
constexpr bool has(std::uint64_t lo, std::uint64_t hi, int c) { return ((c < 64 ? lo : hi) >> (c & 63) & 1U) != 0; }
constexpr void set_bit(std::uint64_t& lo, std::uint64_t& hi, int c) { (c < 64 ? lo : hi) |= std::uint64_t{1} << (c & 63); }

// Adds `sign` times the per-side features to out[0..9], with the operations of the original code (Java: add).
void add(std::span<double, kFeatures> out, const SideSums& s, int sign) {
    const int n = s[u(kCount)];
    out[0] += sign * s[u(kDsum)];
    out[1] += sign * s[u(kDmax)];
    out[2] += sign * (s[u(kDsq)] / 10.0);
    out[3] += sign * s[u(kGoal)];
    out[4] += sign * s[u(kStart)];
    out[5] += sign * (n == 0 ? 0 : s[u(kDmax)] - s[u(kDmin)]);
    out[6] += sign * s[u(kLat)];
    out[7] += sign * s[u(kJumps)];
    out[8] += sign * s[u(kAdj)];
    out[9] += sign * s[u(kSteps)];
}

}  // namespace

// ---- GainSearch ----

void GainSearch::begin(const Position& position) {
    std::uint64_t lo = 0;
    std::uint64_t hi = 0;
    for (int p = 0; p < 2; ++p) {
        for (const int c : position.pieces(p)) set_bit(lo, hi, c);
    }
    occ_lo_ = lo;
    occ_hi_ = hi;
    components_ = 0;
    done_lo_ = 0;
    done_hi_ = 0;
}

int GainSearch::best_gain(const Position& position, int p) {
    const auto ps = u(p);
    const LinearTables& t = kLinearTables;
    const std::array<int, kCells>& dist = t.dist[ps];
    const std::array<std::uint8_t, kCells>& kind = t.kind[ps];
    const std::array<int, kCells + 1>& edge_start = t.edge_start[ps];
    const std::array<int, kTableSize>& edges = t.edges[ps];
    const std::span<const int, kTableSize> steps = detail::kTwoPlayerLayout.step_table(p);
    const std::array<int, kMaxComponents>& mins = p == 1 ? min_b_ : min_a_;
    const std::uint64_t lo = occ_lo_;
    const std::uint64_t hi = occ_hi_;
    // pieces outside the goal by falling distance (packed distance << 8 | cell), so that the gain found early lets the bound
    // "no gain above the piece's distance" skip the rest
    int m = 0;
    for (const int c : position.pieces(p)) {
        if (kind[u(c)] == kInGoal) continue;
        const int key = dist[u(c)] << 8 | c;
        int j = m++;
        while (j > 0 && order_[u(j - 1)] < key) {
            order_[u(j)] = order_[u(j - 1)];
            --j;
        }
        order_[u(j)] = key;
    }
    int best = 0;
    for (int i = 0; i < m; ++i) {
        const int from = order_[u(i)] & 0xFF;
        const int d0 = order_[u(i)] >> 8;
        if (d0 <= best) break;  // every later piece is at most as far: none can gain more
        const std::size_t base = u(from) * kDirections;
        for (std::size_t dir = 0; dir < kDirections; ++dir) {
            const int a = steps[base + dir];
            if (a >= 0 && !has(lo, hi, a) && d0 - dist[u(a)] > best) best = d0 - dist[u(a)];
        }
        for (int e = edge_start[u(from)], end = edge_start[u(from + 1)]; e < end; ++e) {
            const int edge = edges[u(e)];
            const int land = edge & 0xFF;
            if (!has(lo, hi, land) && has(lo, hi, edge >> 8)) {
                const int gain = d0 - mins[u(component_of(edge_start, edges, land))];
                if (gain > best) best = gain;
            }
        }
        if (best >= kGainCap) return kGainCap;
    }
    return best;
}

// The component of the free cell `start`, labelled now (with its smallest distances for both players) if it is in no labelled
// component yet. A label is valid for the current position only (begin() resets them).
int GainSearch::component_of(std::span<const int> edge_start, std::span<const int> edges, int start) {
    if (has(done_lo_, done_hi_, start)) {
        for (int k = 0;; ++k) {
            if (has(comp_lo_[u(k)], comp_hi_[u(k)], start)) return k;
        }
    }
    const std::uint64_t lo = occ_lo_;
    const std::uint64_t hi = occ_hi_;
    // blocked = occupied or already in this component
    std::uint64_t b_lo = lo;
    std::uint64_t b_hi = hi;
    set_bit(b_lo, b_hi, start);
    const LinearTables& t = kLinearTables;
    int low_a = std::numeric_limits<int>::max();
    int low_b = std::numeric_limits<int>::max();
    int top = 0;
    stack_[u(top++)] = start;
    while (top > 0) {
        const int c = stack_[u(--top)];
        low_a = std::min(low_a, t.dist[0][u(c)]);
        low_b = std::min(low_b, t.dist[1][u(c)]);
        for (int e = edge_start[u(c)], end = edge_start[u(c + 1)]; e < end; ++e) {
            const int edge = edges[u(e)];
            const int y = edge & 0xFF;
            if (has(b_lo, b_hi, y) || !has(lo, hi, edge >> 8)) continue;
            set_bit(b_lo, b_hi, y);
            stack_[u(top++)] = y;
        }
    }
    const int k = components_++;
    comp_lo_[u(k)] = b_lo & ~lo;  // the component's cells: blocked minus occupied
    comp_hi_[u(k)] = b_hi & ~hi;
    done_lo_ |= comp_lo_[u(k)];
    done_hi_ |= comp_hi_[u(k)];
    min_a_[u(k)] = low_a;
    min_b_[u(k)] = low_b;  // the shared graph serves both players of a two-player game
    return k;
}

// ---- LinearEvaluator ----

std::expected<LinearEvaluator, std::string> LinearEvaluator::create(std::span<const double> weights, double scale,
                                                                   std::optional<GainMode> gain) {
    std::array<double, kGainFeatures> w{};
    if (weights.size() == kFeatures && !gain) {
        std::copy(weights.begin(), weights.end(), w.begin());
        return LinearEvaluator(w, scale, std::nullopt);
    }
    if (weights.size() == kGainFeatures) {
        std::copy(weights.begin(), weights.end(), w.begin());
        return LinearEvaluator(w, scale, gain.value_or(GainMode::Both));
    }
    return std::unexpected("need " + std::to_string(gain ? kGainFeatures : kFeatures) + " weights, got " + std::to_string(weights.size()));
}

LinearEvaluator LinearEvaluator::linear_r1() {
    return create(linear_weights::kR1, linear_weights::kScale).value();
}

LinearEvaluator LinearEvaluator::linear_r3() {
    return create(linear_weights::kRound3, linear_weights::kScale, GainMode::Both).value();
}

int LinearEvaluator::evaluate(const Position& position) {
    const int me = position.side_to_move();
    side(position, me, sums_);
    side(position, 1 - me, other_sums_);
    combine(sums_, other_sums_, scratch_);
    double best_gain = 0.0;
    if (gain_) {
        gain_search_.begin(position);
        const int mine = gain_search_.best_gain(position, me);
        best_gain = *gain_ == GainMode::Mover ? mine : mine - gain_search_.best_gain(position, 1 - me);
    }
    return score(scratch_, best_gain);
}

int LinearEvaluator::score(std::span<const double, kFeatures> features, double best_gain) const {
    double sum = 0;
    for (std::size_t i = 0; i < kFeatures; ++i) sum += weights_[i] * features[i];
    if (gain_) sum += weights_[u(kBestGain)] * best_gain;
    // (int) of a long: the low 32 bits, as in Java (the C++20 conversion is modular too)
    return static_cast<std::int32_t>(java_round(scale_ * sum));
}

void LinearEvaluator::features(const Position& position, std::span<double, kFeatures> out) {
    SideSums mine{};
    SideSums theirs{};
    const int me = position.side_to_move();
    side(position, me, mine);
    side(position, 1 - me, theirs);
    combine(mine, theirs, out);
}

void LinearEvaluator::features(const Position& position, std::span<double, kGainFeatures> out, GainMode mode) {
    features(position, out.first<kFeatures>());
    const int me = position.side_to_move();
    GainSearch search;
    search.begin(position);
    const int mine = search.best_gain(position, me);
    out[u(kBestGain)] = mode == GainMode::Mover ? mine : mine - search.best_gain(position, 1 - me);
}

int LinearEvaluator::best_gain(const Position& position, int p) {
    GainSearch search;
    search.begin(position);
    return search.best_gain(position, p);
}

void LinearEvaluator::combine(const SideSums& me, const SideSums& opponent, std::span<double, kFeatures> out) {
    std::fill(out.begin(), out.begin() + 10, 0.0);
    add(out, me, 1);
    add(out, opponent, -1);
    out[10] = out[0] * (me[u(kDsum)] + opponent[u(kDsum)]) / 100.0;
    out[11] = 1.0;
}

void LinearEvaluator::side(const Position& position, int p, SideSums& s) {
    const LinearTables& t = kLinearTables;
    const auto ps = u(p);
    const std::array<std::uint8_t, kTableSize>& good = t.good[ps];
    const std::array<int, kCells>& dist = t.dist[ps];
    const std::array<std::uint8_t, kCells>& kind = t.kind[ps];
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
        lateral += t.lateral[u(c)];
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

}  // namespace sh
