#include "jump_graph.hpp"

#include <algorithm>
#include <bit>
#include <limits>

#include "linear_eval.hpp"

namespace sh {

namespace {

using linear_detail::kGainCap;
using linear_detail::kLinearTables;

constexpr std::size_t u(int i) { return static_cast<std::size_t>(i); }

// Per player and cell: the allowed neighbours closer to the goal (one step gains exactly 1), as a 128-bit set; the allowed cells.
struct JumpTables {
    std::uint64_t allowed_lo = 0;
    std::uint64_t allowed_hi = 0;
    std::array<std::array<std::uint64_t, kCells>, 2> closer_lo{};
    std::array<std::array<std::uint64_t, kCells>, 2> closer_hi{};
    bool ok = true;  // both players may stand on the same cells and neighbouring cells differ by at most 1 in distance
};

constexpr void set_bit(std::uint64_t& lo, std::uint64_t& hi, int c) { (c < 64 ? lo : hi) |= std::uint64_t{1} << (c & 63); }

constexpr JumpTables build_jump_tables() {
    JumpTables t;
    const Layout& layout = detail::kTwoPlayerLayout;
    for (int c = 0; c < kCells; ++c) {
        if (layout.allowed(0, c) != layout.allowed(1, c)) t.ok = false;
        if (layout.allowed(0, c)) set_bit(t.allowed_lo, t.allowed_hi, c);
    }
    for (int p = 0; p < 2; ++p) {
        for (int c = 0; c < kCells; ++c) {
            if (!layout.allowed(p, c)) continue;
            for (int d = 0; d < kDirections; ++d) {
                const int a = geometry::step_table()[table_index(c, d)];
                if (a < 0 || !layout.allowed(p, a)) continue;
                const int diff = layout.distance(p, c) - layout.distance(p, a);
                if (diff > 1 || diff < -1) t.ok = false;
                if (diff == 1) set_bit(t.closer_lo[u(p)][u(c)], t.closer_hi[u(p)][u(c)], a);
            }
        }
    }
    return t;
}

constexpr JumpTables kJumpTables = build_jump_tables();
static_assert(kJumpTables.ok, "two-player layout: players must share the allowed cells and neighbours differ by at most 1");

}  // namespace

void JumpGraph::clear() {
    occ_lo_ = 0;
    occ_hi_ = 0;
    adj_lo_.fill(0);
    adj_hi_.fill(0);
}

void JumpGraph::toggle(int x) {
    (x < 64 ? occ_lo_ : occ_hi_) ^= std::uint64_t{1} << (x & 63);
    const std::span<const int, kTableSize> steps = geometry::step_table();
    for (int e = 0; e < kDirections; ++e) {
        const int a = steps[table_index(x, e)];
        const int b = steps[table_index(x, e ^ 1)];
        if (a >= 0 && b >= 0) {
            // the cell a jumps over x onto b
            (b < 64 ? adj_lo_ : adj_hi_)[u(a)] ^= std::uint64_t{1} << (b & 63);
        }
    }
}

void JumpGraph::begin() {
    free_lo_ = kJumpTables.allowed_lo & ~occ_lo_;
    free_hi_ = kJumpTables.allowed_hi & ~occ_hi_;
    components_ = 0;
    if (++generation_ == 0) {
        stamp_.fill(0);
        generation_ = 1;
    }
}

int JumpGraph::best_gain(int player, std::span<const int> keys) {
    const auto ps = u(player);
    const std::array<std::uint64_t, kCells>& closer_lo = kJumpTables.closer_lo[ps];
    const std::array<std::uint64_t, kCells>& closer_hi = kJumpTables.closer_hi[ps];
    const std::array<int, kCells>& mins = player == 0 ? min_dist0_ : min_dist1_;
    const std::uint64_t f_lo = free_lo_;
    const std::uint64_t f_hi = free_hi_;
    int best = 0;
    for (const int key : keys) {
        const int from = key & 0xFF;
        const int d0 = static_cast<int>(static_cast<std::uint32_t>(key) >> 8);
        if (d0 <= best) break;  // every later piece is at most as far: none can gain more
        // a step gains 1 at most (neighbouring cells differ by 1 in distance)
        if (best == 0 && ((closer_lo[u(from)] & f_lo) | (closer_hi[u(from)] & f_hi)) != 0) best = 1;
        std::uint64_t lo = adj_lo_[u(from)] & f_lo;
        std::uint64_t hi = adj_hi_[u(from)] & f_hi;
        while (lo != 0) {
            const int land = std::countr_zero(lo);
            lo &= lo - 1;
            const int k = stamp_[u(land)] == generation_ ? component_[u(land)] : label(land);
            best = std::max(best, d0 - mins[u(k)]);
        }
        while (hi != 0) {
            const int land = 64 + std::countr_zero(hi);
            hi &= hi - 1;
            const int k = stamp_[u(land)] == generation_ ? component_[u(land)] : label(land);
            best = std::max(best, d0 - mins[u(k)]);
        }
        if (best >= kGainCap) return kGainCap;
    }
    return best;
}

int JumpGraph::label(int start) {
    const int k = components_++;
    const std::array<int, kCells>& d0 = kLinearTables.dist[0];
    const std::array<int, kCells>& d1 = kLinearTables.dist[1];
    const std::uint64_t f_lo = free_lo_;
    const std::uint64_t f_hi = free_hi_;
    std::uint64_t comp_lo = 0;
    std::uint64_t comp_hi = 0;
    std::uint64_t front_lo = 0;
    std::uint64_t front_hi = 0;
    set_bit(comp_lo, comp_hi, start);
    set_bit(front_lo, front_hi, start);
    int low0 = std::numeric_limits<int>::max();
    int low1 = std::numeric_limits<int>::max();
    while ((front_lo | front_hi) != 0) {
        int c = 0;
        if (front_lo != 0) {
            c = std::countr_zero(front_lo);
            front_lo &= front_lo - 1;
        } else {
            c = 64 + std::countr_zero(front_hi);
            front_hi &= front_hi - 1;
        }
        stamp_[u(c)] = generation_;
        component_[u(c)] = static_cast<std::uint8_t>(k);
        low0 = std::min(low0, d0[u(c)]);
        low1 = std::min(low1, d1[u(c)]);
        const std::uint64_t n_lo = adj_lo_[u(c)] & f_lo & ~comp_lo;
        const std::uint64_t n_hi = adj_hi_[u(c)] & f_hi & ~comp_hi;
        comp_lo |= n_lo;
        comp_hi |= n_hi;
        front_lo |= n_lo;
        front_hi |= n_hi;
    }
    min_dist0_[u(k)] = low0;
    min_dist1_[u(k)] = low1;
    return k;
}

bool JumpGraph::matches(const Position& position) const {
    JumpGraph fresh;
    for (int c = 0; c < kCells; ++c) {
        if (position.owner(c) >= 0) fresh.toggle(c);
    }
    return fresh.occ_lo_ == occ_lo_ && fresh.occ_hi_ == occ_hi_ && fresh.adj_lo_ == adj_lo_ && fresh.adj_hi_ == adj_hi_;
}

}  // namespace sh
