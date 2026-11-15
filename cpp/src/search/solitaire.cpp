#include "solitaire.hpp"

#include <algorithm>
#include <bit>

namespace sh {

namespace {

// Open-addressing hash set of (lo, hi) pairs; 0/0 is never a configuration (Java: Solitaire.ConfigSet).
class ConfigSet {
public:
    explicit ConfigSet(std::size_t expected) {
        const std::size_t want = std::max<std::size_t>(16, expected) * 2 - 1;
        const std::size_t cap = std::bit_floor(want) << 1;
        lo_.assign(cap, 0);
        hi_.assign(cap, 0);
        mask_ = cap - 1;
    }

    void clear() {
        std::ranges::fill(lo_, 0);
        std::ranges::fill(hi_, 0);
        size_ = 0;
    }

    // true if the pair was new
    bool add(std::uint64_t l, std::uint64_t h) {
        if (size_ * 2 >= lo_.size()) grow();
        std::size_t i = static_cast<std::size_t>((l * 0x9E3779B97F4A7C15ULL ^ h * 0xC2B2AE3D27D4EB4FULL) >> 40) & mask_;
        while (lo_[i] != 0 || hi_[i] != 0) {
            if (lo_[i] == l && hi_[i] == h) return false;
            i = (i + 1) & mask_;
        }
        lo_[i] = l;
        hi_[i] = h;
        ++size_;
        return true;
    }

private:
    void grow() {
        std::vector<std::uint64_t> old_lo = std::move(lo_);
        std::vector<std::uint64_t> old_hi = std::move(hi_);
        lo_.assign(old_lo.size() * 2, 0);
        hi_.assign(old_lo.size() * 2, 0);
        mask_ = lo_.size() - 1;
        size_ = 0;
        for (std::size_t i = 0; i < old_lo.size(); ++i) {
            if (old_lo[i] != 0 || old_hi[i] != 0) add(old_lo[i], old_hi[i]);
        }
    }

    std::vector<std::uint64_t> lo_;
    std::vector<std::uint64_t> hi_;
    std::size_t mask_ = 0;
    std::size_t size_ = 0;
};

struct Layer {
    std::vector<std::uint64_t> lo;
    std::vector<std::uint64_t> hi;
    std::vector<int> parent;
    std::vector<int> move;
};

}  // namespace

Solitaire::Solitaire(int player, RuleSet::LeaveGoal leave_goal, const RaceTable* table)
    : player_(player), leave_goal_(leave_goal), table_(table), goal_corner_(layout_.goal_corner(player)) {
    for (const int c : geometry::corner_cells(goal_corner_)) {
        goal_lo_ = with_lo(goal_lo_, c);
        goal_hi_ = with_hi(goal_hi_, c);
    }
    for (int c = 0; c < kCells; ++c) {
        allowed_[idx(c)] = layout_.allowed(player, c);
        row_of_[idx(c)] = rows::row(player, c);
    }
}

std::vector<int> Solitaire::cells_of(std::uint64_t lo, std::uint64_t hi) {
    std::vector<int> cells;
    cells.reserve(static_cast<std::size_t>(std::popcount(lo) + std::popcount(hi)));
    for (std::uint64_t b = lo; b != 0; b &= b - 1) cells.push_back(std::countr_zero(b));
    for (std::uint64_t b = hi; b != 0; b &= b - 1) cells.push_back(64 + std::countr_zero(b));
    return cells;
}

int Solitaire::rear_row(std::uint64_t lo, std::uint64_t hi) const {
    int min = std::numeric_limits<int>::max();
    for (std::uint64_t b = lo; b != 0; b &= b - 1) min = std::min(min, row_of_[idx(std::countr_zero(b))]);
    for (std::uint64_t b = hi; b != 0; b &= b - 1) min = std::min(min, row_of_[idx(64 + std::countr_zero(b))]);
    return min;
}

int Solitaire::front_row(std::span<const int> cells) const {
    int max = std::numeric_limits<int>::min();
    for (const int c : cells) max = std::max(max, row_of_[idx(c)]);
    return max;
}

bool Solitaire::is_goal(std::span<const int> cells) const {
    for (const int c : cells) {
        if (geometry::corner_of(c) != goal_corner_) return false;
    }
    return static_cast<int>(cells.size()) == kCornerSize;
}

std::vector<int> Solitaire::path(int from, int to) const {
    if (parent_[idx(to)] < 0 || to == from) {
        // a step, or a chain landing that is also adjacent: reproduce as the step if adjacent
        for (int d = 0; d < kDirections; ++d) {
            if (geometry::neighbor(from, d) == to) return {to};
        }
        return {};
    }
    int n = 0;
    for (int c = to; c != from; c = parent_[idx(c)]) ++n;
    std::vector<int> result(static_cast<std::size_t>(n));
    int i = n - 1;
    for (int c = to; c != from; c = parent_[idx(c)], --i) result[idx(i)] = c;
    return result;
}

std::optional<Solitaire::Step> Solitaire::step(std::uint64_t lo, std::uint64_t hi, int from, int to, int min_landing) {
    bool found = false;
    landings(lo, hi, from, min_landing, [&](int c) {
        if (c == to) found = true;
    });
    if (!found) return std::nullopt;
    std::vector<int> p = path(from, to);
    if (p.empty()) return std::nullopt;
    return Step{from, std::move(p)};
}

bool Solitaire::one_move_class(int from, int to) {
    if (((geometry::q(from) ^ geometry::q(to)) & 1) == 0 && ((geometry::r(from) ^ geometry::r(to)) & 1) == 0) return true;
    for (int d = 0; d < kDirections; ++d) {
        if (geometry::neighbor(from, d) == to) return true;
    }
    return false;
}

namespace {

bool augment(std::size_t piece, const std::vector<int>& outside, const std::vector<int>& free_cells, std::vector<int>& match_of_free,
             std::vector<bool>& seen, bool (*one_move)(int, int)) {
    for (std::size_t j = 0; j < free_cells.size(); ++j) {
        if (seen[j] || !one_move(outside[piece], free_cells[j])) continue;
        seen[j] = true;
        if (match_of_free[j] < 0 || augment(static_cast<std::size_t>(match_of_free[j]), outside, free_cells, match_of_free, seen, one_move)) {
            match_of_free[j] = static_cast<int>(piece);
            return true;
        }
    }
    return false;
}

}  // namespace

int Solitaire::lower_bound(std::span<const int> cells) const {
    std::array<bool, kCells> taken{};
    for (const int c : cells) taken[idx(c)] = true;
    std::vector<int> outside;
    for (const int c : cells) {
        if (geometry::corner_of(c) != goal_corner_) outside.push_back(c);
    }
    std::vector<int> free_cells;
    for (const int g : geometry::corner_cells(goal_corner_)) {
        if (!taken[idx(g)]) free_cells.push_back(g);
    }
    std::vector<int> match_of_free(free_cells.size(), -1);
    int matched = 0;
    std::vector<bool> seen(free_cells.size());
    for (std::size_t i = 0; i < outside.size(); ++i) {
        seen.assign(seen.size(), false);
        if (augment(i, outside, free_cells, match_of_free, seen, &Solitaire::one_move_class)) ++matched;
    }
    return 2 * static_cast<int>(outside.size()) - matched;
}

int Solitaire::heuristic(std::uint64_t lo, std::uint64_t hi) const {
    const std::vector<int> cells = cells_of(lo, hi);
    if (table_ != nullptr) {
        std::vector<int> own(cells.size());
        rows::to_player0(player_, cells, own);
        const int count = table_->count(own);
        if (count >= 0) return count;
    }
    int dsum = 0;
    for (const int c : cells) dsum += layout_.distance(player_, c);
    // outside the table: configurations ordered by their distance sum, behind every covered configuration
    return 100 + dsum;
}

std::optional<Solitaire::Plan> Solitaire::plan(std::span<const int> cells, int width, int max_moves, int min_landing) {
    return plan(cells, width, max_moves, [min_landing](int) { return min_landing; }, [](int) { return kNoRearLimit; });
}

std::optional<Solitaire::Plan> Solitaire::plan(std::span<const int> cells, int width, int max_moves, const std::function<int(int)>& min_landing,
                                               const std::function<int(int)>& min_rear) {
    std::uint64_t lo = 0;
    std::uint64_t hi = 0;
    for (const int c : cells) {
        lo = with_lo(lo, c);
        hi = with_hi(hi, c);
    }
    if (is_goal(lo, hi)) return Plan{{}, {std::vector<int>(cells.begin(), cells.end())}};
    std::vector<Layer> layers;
    layers.push_back(Layer{{lo}, {hi}, {-1}, {-1}});
    ConfigSet seen(static_cast<std::size_t>(width) * 32);
    for (int t = 1; t <= max_moves; ++t) {
        const std::size_t prev_index = static_cast<std::size_t>(t - 1);
        const int landing = min_landing(t);
        const int rear = min_rear(t);
        seen.clear();
        Layer cand;
        const std::vector<std::uint64_t>& prev_lo = layers[prev_index].lo;
        const std::vector<std::uint64_t>& prev_hi = layers[prev_index].hi;
        for (std::size_t s = 0; s < prev_lo.size(); ++s) {
            const std::uint64_t s_lo = prev_lo[s];
            const std::uint64_t s_hi = prev_hi[s];
            for (const int from : cells_of(s_lo, s_hi)) {
                const std::uint64_t base_lo = without_lo(s_lo, from);
                const std::uint64_t base_hi = without_hi(s_hi, from);
                landings(s_lo, s_hi, from, landing, [&](int to) {
                    const std::uint64_t n_lo = with_lo(base_lo, to);
                    const std::uint64_t n_hi = with_hi(base_hi, to);
                    if (!seen.add(n_lo, n_hi)) return;
                    if (!is_goal(n_lo, n_hi) && rear != kNoRearLimit && rear_row(n_lo, n_hi) < rear) return;
                    cand.lo.push_back(n_lo);
                    cand.hi.push_back(n_hi);
                    cand.parent.push_back(static_cast<int>(s));
                    cand.move.push_back(Move::of(from, to));
                });
            }
        }
        if (cand.lo.empty()) return std::nullopt;
        // the goal in this layer?
        for (std::size_t i = 0; i < cand.lo.size(); ++i) {
            if (is_goal(cand.lo[i], cand.hi[i])) {
                layers.push_back(Layer{{cand.lo[i]}, {cand.hi[i]}, {cand.parent[i]}, {cand.move[i]}});
                // reconstruct: walk back from the goal entry
                const int depth = t;
                std::vector<int> moves(static_cast<std::size_t>(depth));
                std::vector<int> indices(static_cast<std::size_t>(depth) + 1);
                int at = 0;
                for (int k = depth; k >= 1; --k) {
                    indices[static_cast<std::size_t>(k)] = at;
                    moves[static_cast<std::size_t>(k - 1)] = layers[static_cast<std::size_t>(k)].move[static_cast<std::size_t>(at)];
                    at = layers[static_cast<std::size_t>(k)].parent[static_cast<std::size_t>(at)];
                }
                indices[0] = at;
                Plan result;
                result.states.push_back(cells_of(layers[0].lo[static_cast<std::size_t>(indices[0])], layers[0].hi[static_cast<std::size_t>(indices[0])]));
                for (int k = 1; k <= depth; ++k) {
                    const auto prev = static_cast<std::size_t>(indices[static_cast<std::size_t>(k - 1)]);
                    const std::uint64_t p_lo = layers[static_cast<std::size_t>(k - 1)].lo[prev];
                    const std::uint64_t p_hi = layers[static_cast<std::size_t>(k - 1)].hi[prev];
                    const int m = moves[static_cast<std::size_t>(k - 1)];
                    auto st = step(p_lo, p_hi, Move::from(m), Move::to(m), min_landing(k));
                    if (!st) return std::nullopt;  // Java throws IllegalStateException: the beam produced a move its generator cannot reproduce
                    result.steps.push_back(std::move(*st));
                    const auto now = static_cast<std::size_t>(indices[static_cast<std::size_t>(k)]);
                    result.states.push_back(cells_of(layers[static_cast<std::size_t>(k)].lo[now], layers[static_cast<std::size_t>(k)].hi[now]));
                }
                for (std::size_t a = 0; a < result.states.size(); ++a) {
                    for (std::size_t b = a + 1; b < result.states.size(); ++b) {
                        if (result.states[a] == result.states[b]) return std::nullopt;  // Java throws: plan states are not pairwise distinct
                    }
                }
                return result;
            }
        }
        const std::size_t keep = std::min(static_cast<std::size_t>(width), cand.lo.size());
        std::vector<std::uint64_t> keys(cand.lo.size());
        for (std::size_t i = 0; i < cand.lo.size(); ++i) {
            keys[i] = (static_cast<std::uint64_t>(heuristic(cand.lo[i], cand.hi[i])) << 32) | i;
        }
        std::ranges::sort(keys);
        Layer next;
        for (std::size_t i = 0; i < keep; ++i) {
            const auto k = static_cast<std::size_t>(keys[i] & 0xFFFFFFFFU);
            next.lo.push_back(cand.lo[k]);
            next.hi.push_back(cand.hi[k]);
            next.parent.push_back(cand.parent[k]);
            next.move.push_back(cand.move[k]);
        }
        layers.push_back(std::move(next));
    }
    return std::nullopt;
}

}  // namespace sh
