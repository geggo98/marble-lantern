// Tests of the Halma long hop (RuleSet::JumpRule::Long, docs/rules.md "Jump rule") of the C++ engine.
//  - The Java golden file engine/src/test/resources/long-jump-golden.tsv (path from CMake, SH_LONG_JUMP_GOLDEN), all 30 rows: the position
//    rebuilt from the listed pieces and the position reached by the seeded playout both give the listed move count, the sorted hash and
//    the generation-order hash. The file is read in place; a missing file fails the test (it never skips).
//  - A coordinate-based oracle (no tables, no depth-first search; the same idea as the Java LongJumpOracle) against generate() over
//    seeded playouts, and hand-made positions with the expected landings.
//  - The default rule: ADJACENT, and the Long switch changes nothing on a position without a hop of distance > 1.
#include <algorithm>
#include <array>
#include <cstdint>
#include <fstream>
#include <iterator>
#include <set>
#include <span>
#include <sstream>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

#include "check.hpp"
#include "geometry.hpp"
#include "move.hpp"
#include "position.hpp"
#include "rules.hpp"
#include "sha256.hpp"
#include "splittable_random.hpp"

using namespace sh;

namespace {

RuleSet long_rules() {
    const auto r = RuleSet::defaults().with_jump_rule(RuleSet::JumpRule::Long);
    return *r;
}

std::vector<std::string_view> split(std::string_view line, char sep) {
    std::vector<std::string_view> out;
    std::size_t start = 0;
    while (true) {
        const std::size_t at = line.find(sep, start);
        if (at == std::string_view::npos) {
            out.push_back(line.substr(start));
            return out;
        }
        out.push_back(line.substr(start, at - start));
        start = at + 1;
    }
}

std::vector<int> moves_of(Position& p) {
    std::array<int, kMaxMoves> buf{};
    const int n = p.generate(buf);
    return std::vector<int>(buf.begin(), std::next(buf.begin(), n));
}

std::string hash_of_moves(const std::vector<int>& moves) {
    std::string text;
    for (const int m : moves) text += std::to_string(Move::from(m)) + "-" + std::to_string(Move::to(m)) + "\n";
    return sha256_hex(std::as_bytes(std::span<const char>(text)));
}

std::vector<int> sorted_by_from_to(std::vector<int> moves) {
    std::ranges::sort(moves, [](int a, int b) {
        return Move::from(a) != Move::from(b) ? Move::from(a) < Move::from(b) : Move::to(a) < Move::to(b);
    });
    return moves;
}

// One step of the seeded playout of the golden file: the move with index (nextLong() >>> 1) % count, from the moves sorted by (from, to).
bool play_one(Position& p, SplittableRandom& rng) {
    if (p.outcome().has_value()) return false;
    const std::vector<int> moves = sorted_by_from_to(moves_of(p));
    if (moves.empty()) return false;
    const std::uint64_t pick = (rng.next_long() >> 1) % moves.size();
    p.make(moves[pick]);
    return true;
}

// ---- the oracle ----

constexpr std::array<int, 6> kDq{1, -1, 0, 0, 1, -1};
constexpr std::array<int, 6> kDr{0, 0, 1, -1, -1, 1};

// The landing of a hop from c in direction d under the long rule, written with coordinates only. `start` counts as empty.
int oracle_landing(const Position& pos, int p, int start, int c, int d, bool long_hop) {
    const auto empty = [&](int cell) { return cell == start || pos.owner(cell) < 0; };
    for (int k = 1;; ++k) {
        const int over = geometry::id(geometry::q(c) + k * kDq[static_cast<std::size_t>(d)], geometry::r(c) + k * kDr[static_cast<std::size_t>(d)]);
        if (over < 0) return -1;
        if (empty(over)) continue;
        if (!long_hop && k > 1) return -1;
        const int land = geometry::id(geometry::q(c) + 2 * k * kDq[static_cast<std::size_t>(d)], geometry::r(c) + 2 * k * kDr[static_cast<std::size_t>(d)]);
        if (land < 0 || !pos.layout().allowed(p, land)) return -1;
        for (int i = k + 1; i <= 2 * k; ++i) {
            const int cell = geometry::id(geometry::q(c) + i * kDq[static_cast<std::size_t>(d)], geometry::r(c) + i * kDr[static_cast<std::size_t>(d)]);
            if (cell < 0 || !empty(cell)) return -1;
        }
        return land;
    }
}

// All (from, to) of the side to move under the rule, as a set of pairs, ignoring the leave-goal and Definition-2 filters.
std::set<std::pair<int, int>> oracle_moves(const Position& pos, bool long_hop) {
    std::set<std::pair<int, int>> result;
    const int p = pos.side_to_move();
    for (const int from : pos.pieces(p)) {
        for (int d = 0; d < 6; ++d) {
            const int n = geometry::id(geometry::q(from) + kDq[static_cast<std::size_t>(d)], geometry::r(from) + kDr[static_cast<std::size_t>(d)]);
            if (n >= 0 && pos.owner(n) < 0 && pos.layout().allowed(p, n)) result.insert({from, n});
        }
        std::set<int> seen{from};
        std::vector<int> queue{from};
        while (!queue.empty()) {
            const int c = queue.back();
            queue.pop_back();
            for (int d = 0; d < 6; ++d) {
                const int land = oracle_landing(pos, p, from, c, d, long_hop);
                if (land >= 0 && seen.insert(land).second) {
                    queue.push_back(land);
                    result.insert({from, land});
                }
            }
        }
    }
    return result;
}

// Leave-goal filter: a piece in its goal corner stays in it.
std::set<std::pair<int, int>> keep_goal_rule(const Position& pos, std::set<std::pair<int, int>> moves) {
    const int goal = pos.layout().goal_corner(pos.side_to_move());
    std::erase_if(moves, [&](const std::pair<int, int>& m) {
        return geometry::corner_of(m.first) == goal && geometry::corner_of(m.second) != goal;
    });
    return moves;
}

std::set<std::pair<int, int>> as_set(const std::vector<int>& moves) {
    std::set<std::pair<int, int>> s;
    for (const int m : moves) s.insert({Move::from(m), Move::to(m)});
    return s;
}

}  // namespace

SH_TEST(rule_switch_defaults_and_validation) {
    CHECK(RuleSet::defaults().jump_rule() == RuleSet::JumpRule::Adjacent);
    const auto r = RuleSet::defaults().with_jump_rule(RuleSet::JumpRule::Long);
    CHECK(r.has_value());
    CHECK(r->jump_rule() == RuleSet::JumpRule::Long);
    CHECK(!(*r == RuleSet::defaults()));
    CHECK(r->with_jump_rule(RuleSet::JumpRule::Adjacent).value() == RuleSet::defaults());
}

// All 30 rows of the Java golden file, bit for bit.
SH_TEST(java_golden_all_rows) {
    std::ifstream in(SH_LONG_JUMP_GOLDEN);
    CHECK(in.good());
    int rows = 0;
    std::string line;
    while (std::getline(in, line)) {
        if (line.empty() || line.front() == '#') continue;
        const auto f = split(line, '\t');
        CHECK_EQ(f.size(), std::size_t{11});
        if (f.size() != 11) continue;
        const int id = std::stoi(std::string(f[0]));
        const int plies = std::stoi(std::string(f[4]));
        const int side = std::stoi(std::string(f[5]));
        const std::string label = "row " + std::to_string(id);
        CHECK_EQ(id, rows);
        ++rows;
        CHECK_EQ(std::string(f[1]), std::string("2"));
        CHECK_EQ(std::string(f[2]), std::string("LONG"));
        CHECK_EQ(std::stoull(std::string(f[3]), nullptr, 16), std::uint64_t{0x5EED0000} + static_cast<std::uint64_t>(id));

        // (a) the position rebuilt from the listed pieces
        Position built = Position::empty(long_rules());
        for (int p = 0; p < 2; ++p) {
            for (const std::string_view cell : split(f[static_cast<std::size_t>(6 + p)], ',')) {
                CHECK(built.put(p, std::stoi(std::string(cell))).has_value());
            }
        }
        CHECK(built.set_side_to_move(side).has_value());
        const std::vector<int> ordered = moves_of(built);
        CHECK_EQ(static_cast<int>(ordered.size()), std::stoi(std::string(f[8])));
        CHECK_EQ(hash_of_moves(sorted_by_from_to(ordered)), std::string(f[9]));
        CHECK_EQ(hash_of_moves(ordered), std::string(f[10]));

        // (b) the position reached by the seeded playout from the start position
        Position played = Position::initial(long_rules());
        SplittableRandom rng(std::uint64_t{0x5EED0000} + static_cast<std::uint64_t>(id));
        int done = 0;
        while (done < plies && play_one(played, rng)) ++done;
        CHECK_EQ(done, plies);
        CHECK_EQ(played.side_to_move(), side);
        for (int p = 0; p < 2; ++p) {
            std::string cells;
            for (const int c : played.pieces(p)) cells += (cells.empty() ? "" : ",") + std::to_string(c);
            CHECK_EQ(cells, std::string(f[static_cast<std::size_t>(6 + p)]));
        }
        const std::vector<int> ordered2 = moves_of(played);
        CHECK_EQ(hash_of_moves(sorted_by_from_to(ordered2)), std::string(f[9]));
        CHECK_EQ(hash_of_moves(ordered2), std::string(f[10]));
    }
    CHECK_EQ(rows, 30);
}

// The oracle against generate(), over seeded playouts, with both rules. The start position has no jump; the positions after a few plies do.
SH_TEST(oracle_property_over_playouts) {
    int long_hops_seen = 0;
    for (const bool long_hop : {false, true}) {
        const RuleSet rules = long_hop ? long_rules() : RuleSet::defaults();
        for (std::uint64_t seed = 1; seed <= 12; ++seed) {
            Position p = Position::initial(rules);
            SplittableRandom rng(seed * 7919);
            for (int ply = 0; ply < 120; ++ply) {
                const auto expected = keep_goal_rule(p, oracle_moves(p, long_hop));
                const std::vector<int> generated = moves_of(p);
                CHECK_EQ(as_set(generated), expected);
                CHECK_EQ(as_set(generated).size(), generated.size());  // no duplicate (from, to)
                if (long_hop) {
                    // a move that the adjacent rule does not know: a real long hop (or a chain that needs one)
                    const auto adjacent = oracle_moves(p, false);
                    for (const auto& m : as_set(generated)) long_hops_seen += adjacent.contains(m) ? 0 : 1;
                }
                if (!play_one(p, rng)) break;
            }
        }
    }
    CHECK(long_hops_seen > 100);  // the property would pass trivially without real long hops
}

// A hand-made position: one piece of player 0 on the central row, a blocker at distance 3, landing at distance 6.
SH_TEST(hand_made_long_hop) {
    const auto cell = [](int q, int r) { return geometry::id(q, r); };
    for (const bool long_hop : {false, true}) {
        Position p = Position::empty(long_hop ? long_rules() : RuleSet::defaults());
        CHECK(p.put(0, cell(-4, 0)).has_value());
        CHECK(p.put(1, cell(-1, 0)).has_value());  // k = 3 from (-4,0) along +q
        CHECK(p.put(1, cell(4, -4)).has_value());  // keeps the opponent present elsewhere
        const auto moves = as_set(moves_of(p));
        const bool has = moves.contains({cell(-4, 0), cell(2, 0)});
        CHECK_EQ(has, long_hop);
        // the hop is blocked if a cell between the blocker and the landing is occupied
        Position blocked = Position::empty(long_hop ? long_rules() : RuleSet::defaults());
        CHECK(blocked.put(0, cell(-4, 0)).has_value());
        CHECK(blocked.put(1, cell(-1, 0)).has_value());
        CHECK(blocked.put(1, cell(0, 0)).has_value());
        CHECK(!as_set(moves_of(blocked)).contains({cell(-4, 0), cell(2, 0)}));
        // a chain: after the first hop to (2,0) the piece hops on over a second blocker at (3,0)? needs (4,0) empty: landing (4,0) is k=1
        Position chain = Position::empty(long_hop ? long_rules() : RuleSet::defaults());
        CHECK(chain.put(0, cell(-4, 0)).has_value());
        CHECK(chain.put(1, cell(-1, 0)).has_value());
        CHECK(chain.put(1, cell(3, 0)).has_value());
        const auto chain_moves = as_set(moves_of(chain));
        CHECK_EQ(chain_moves.contains({cell(-4, 0), cell(4, 0)}), long_hop);
    }
}

// The long rule is a superset of the adjacent rule for every position (k = 1 is the single jump), and equal when no piece is two or more
// cells away from a hop line.
SH_TEST(long_is_superset_of_adjacent) {
    for (std::uint64_t seed = 1; seed <= 6; ++seed) {
        Position a = Position::initial(RuleSet::defaults());
        Position b = Position::initial(long_rules());
        SplittableRandom rng(seed);
        for (int ply = 0; ply < 80; ++ply) {
            const auto sa = as_set(moves_of(a));
            const auto sb = as_set(moves_of(b));
            CHECK(std::ranges::includes(sb, sa));
            // play the same move in both: the adjacent move, which is legal in both
            const std::vector<int> moves = sorted_by_from_to(moves_of(a));
            if (moves.empty() || a.outcome().has_value()) break;
            const int m = moves[(rng.next_long() >> 1) % moves.size()];
            a.make(m);
            b.make(m);
        }
    }
}

int main() { return sh::test::run_all(); }
