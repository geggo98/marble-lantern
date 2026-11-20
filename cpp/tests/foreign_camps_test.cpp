// Tests of the foreign-corner switch (RuleSet::ForeignCamps, docs/rules.md "Foreign corners") of the C++ engine.
//  - The Java golden file engine/src/test/resources/foreign-camps-golden.tsv (path from CMake, SH_FOREIGN_CAMPS_GOLDEN), all rows: the
//    position rebuilt from the listed pieces and the position reached by the seeded playout both give the listed move count, the sorted
//    hash and the generation-order hash. A missing file fails the test (it never skips).
//  - A coordinate-based oracle (no tables, no depth-first search; the same idea as the Java ForeignCampsOracle) against generate()
//    over seeded playouts for every switch value, both jump rules and both player counts.
//  - A hand-made position, the setup rule, the distances of foreign cells and the guards of race play and of the incremental evaluator.
#include <algorithm>
#include <array>
#include <cstdint>
#include <cstdlib>
#include <fstream>
#include <iterator>
#include <set>
#include <span>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

#include "check.hpp"
#include "geometry.hpp"
#include "incremental_linear.hpp"
#include "linear_eval.hpp"
#include "move.hpp"
#include "position.hpp"
#include "race_adjudication.hpp"
#include "race_eval.hpp"
#include "rules.hpp"
#include "sha256.hpp"
#include "splittable_random.hpp"

using namespace sh;

namespace {

using Camps = RuleSet::ForeignCamps;
using Jump = RuleSet::JumpRule;

RuleSet rules_of(int players, Jump jump, Camps camps) {
    return *RuleSet::defaults(players).with_jump_rule(jump)->with_foreign_camps(camps);
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

bool play_one(Position& p, SplittableRandom& rng) {
    if (p.outcome().has_value()) return false;
    const std::vector<int> moves = sorted_by_from_to(moves_of(p));
    if (moves.empty()) return false;
    const std::uint64_t pick = (rng.next_long() >> 1) % moves.size();
    p.make(moves[pick]);
    return true;
}

std::set<std::pair<int, int>> as_set(const std::vector<int>& moves) {
    std::set<std::pair<int, int>> s;
    for (const int m : moves) s.insert({Move::from(m), Move::to(m)});
    return s;
}

// ---- the oracle ----

constexpr std::array<int, 6> kDq{1, -1, 0, 0, 1, -1};
constexpr std::array<int, 6> kDr{0, 0, 1, -1, -1, 1};

// The home cells of docs/rules.md, from coordinates: the hexagon, and r for two players; r, s or q for player 0, 1 or 2 of three.
bool home(int players, int p, int cell) {
    const int q = geometry::q(cell);
    const int r = geometry::r(cell);
    const int s = -q - r;
    if (std::abs(q) <= 4 && std::abs(r) <= 4 && std::abs(s) <= 4) return true;
    const int axis = players == 2 ? r : p == 0 ? r : p == 1 ? s : q;
    return std::abs(axis) > 4;
}

int cell_at(int c, int d, int k) {
    return geometry::id(geometry::q(c) + k * kDq[static_cast<std::size_t>(d)], geometry::r(c) + k * kDr[static_cast<std::size_t>(d)]);
}

// The landing of a hop from c in direction d on any board cell, or -1. `start` counts as empty.
int landing(const Position& pos, int start, int c, int d, bool long_hop) {
    const auto empty = [&](int cell) { return cell == start || pos.owner(cell) < 0; };
    for (int k = 1; k <= (long_hop ? 8 : 1); ++k) {
        const int over = cell_at(c, d, k);
        if (over < 0) return -1;
        if (empty(over)) continue;
        for (int i = k + 1; i <= 2 * k; ++i) {
            const int cell = cell_at(c, d, i);
            if (cell < 0 || !empty(cell)) return -1;
        }
        return cell_at(c, d, 2 * k);
    }
    return -1;
}

// All (from, to) of the side to move, with the leave-goal filter of the default rules (Definition 2 is not modelled; a rejection
// would show as a difference).
std::set<std::pair<int, int>> oracle_moves(const Position& pos, bool long_hop, Camps camps) {
    std::set<std::pair<int, int>> result;
    const int p = pos.side_to_move();
    const int players = pos.rules().players();
    const int goal = pos.layout().goal_corner(p);
    const auto add = [&](int from, int to) {
        if (geometry::corner_of(from) == goal && geometry::corner_of(to) != goal) return;
        result.insert({from, to});
    };
    for (const int from : pos.pieces(p)) {
        for (int d = 0; d < 6; ++d) {
            const int n = cell_at(from, d, 1);
            if (n >= 0 && pos.owner(n) < 0 && (camps == Camps::Open || home(players, p, n))) add(from, n);
        }
        std::set<int> seen{from};
        std::vector<int> queue{from};
        while (!queue.empty()) {
            const int c = queue.back();
            queue.pop_back();
            for (int d = 0; d < 6; ++d) {
                const int land = landing(pos, from, c, d, long_hop);
                if (land < 0 || seen.contains(land)) continue;
                const bool is_home = home(players, p, land);
                if (camps == Camps::Closed && !is_home) continue;
                seen.insert(land);
                queue.push_back(land);
                if (camps == Camps::Open || is_home) add(from, land);
            }
        }
    }
    return result;
}

Camps camps_of(std::string_view text) {
    return text == "OPEN" ? Camps::Open : text == "PASS_THROUGH" ? Camps::PassThrough : Camps::Closed;
}

}  // namespace

SH_TEST(rule_switch_defaults_and_validation) {
    CHECK(RuleSet::defaults().foreign_camps() == Camps::Closed);
    CHECK(RuleSet::defaults(3).foreign_camps() == Camps::Closed);
    const auto r = RuleSet::defaults().with_foreign_camps(Camps::Open);
    CHECK(r.has_value());
    CHECK(r->foreign_camps() == Camps::Open);
    CHECK(!(*r == RuleSet::defaults()));
    CHECK(r->with_foreign_camps(Camps::Closed).value() == RuleSet::defaults());
    CHECK(r->with_jump_rule(Jump::Long)->foreign_camps() == Camps::Open);
}

// Every row of the Java golden file, bit for bit.
SH_TEST(java_golden_all_rows) {
    std::ifstream in(SH_FOREIGN_CAMPS_GOLDEN);
    CHECK(in.good());
    int rows = 0;
    std::string line;
    while (std::getline(in, line)) {
        if (line.empty() || line.front() == '#') continue;
        const auto f = split(line, '\t');
        CHECK_EQ(f.size(), std::size_t{13});
        if (f.size() != 13) continue;
        const int id = std::stoi(std::string(f[0]));
        const int players = std::stoi(std::string(f[1]));
        const Jump jump = f[2] == "LONG" ? Jump::Long : Jump::Adjacent;
        const Camps camps = camps_of(f[3]);
        const int plies = std::stoi(std::string(f[5]));
        const int side = std::stoi(std::string(f[6]));
        CHECK_EQ(id, rows);
        ++rows;
        CHECK(camps != Camps::Closed);
        const RuleSet rules = rules_of(players, jump, camps);
        CHECK_EQ(std::stoull(std::string(f[4]), nullptr, 16), std::uint64_t{0xF0C40000} + static_cast<std::uint64_t>(id));

        // (a) the position rebuilt from the listed pieces
        Position built = Position::empty(rules);
        for (int p = 0; p < players; ++p) {
            for (const std::string_view cell : split(f[static_cast<std::size_t>(7 + p)], ',')) {
                CHECK(built.put(p, std::stoi(std::string(cell))).has_value());
            }
        }
        CHECK(built.set_side_to_move(side).has_value());
        const std::vector<int> ordered = moves_of(built);
        CHECK_EQ(static_cast<int>(ordered.size()), std::stoi(std::string(f[10])));
        CHECK_EQ(hash_of_moves(sorted_by_from_to(ordered)), std::string(f[11]));
        CHECK_EQ(hash_of_moves(ordered), std::string(f[12]));

        // (b) the position reached by the seeded playout from the start position
        Position played = Position::initial(rules);
        SplittableRandom rng(std::uint64_t{0xF0C40000} + static_cast<std::uint64_t>(id));
        int done = 0;
        while (done < plies && play_one(played, rng)) ++done;
        CHECK_EQ(done, plies);
        CHECK_EQ(played.side_to_move(), side);
        for (int p = 0; p < players; ++p) {
            std::string cells;
            for (const int c : played.pieces(p)) cells += (cells.empty() ? "" : ",") + std::to_string(c);
            CHECK_EQ(cells, std::string(f[static_cast<std::size_t>(7 + p)]));
        }
        const std::vector<int> ordered2 = moves_of(played);
        CHECK_EQ(hash_of_moves(ordered2), std::string(f[12]));
    }
    CHECK_EQ(rows, 48);
}

// The oracle against generate(), over seeded playouts: every switch value, both jump rules, two and three players.
SH_TEST(oracle_property_over_playouts) {
    int foreign_ends = 0;
    for (const int players : {2, 3}) {
        for (const Jump jump : {Jump::Adjacent, Jump::Long}) {
            for (const Camps camps : {Camps::Closed, Camps::PassThrough, Camps::Open}) {
                const RuleSet rules = rules_of(players, jump, camps);
                for (std::uint64_t seed = 1; seed <= 4; ++seed) {
                    Position p = Position::initial(rules);
                    SplittableRandom rng(seed * 104729 + static_cast<std::uint64_t>(players));
                    for (int ply = 0; ply < 120; ++ply) {
                        const std::vector<int> generated = moves_of(p);
                        CHECK_EQ(as_set(generated), oracle_moves(p, jump == Jump::Long, camps));
                        CHECK_EQ(as_set(generated).size(), generated.size());  // no duplicate (from, to)
                        for (const int m : generated) foreign_ends += home(players, p.side_to_move(), Move::to(m)) ? 0 : 1;
                        if (!play_one(p, rng)) break;
                    }
                }
            }
        }
    }
    CHECK(foreign_ends > 100);  // OPEN must really end moves on foreign cells, else the property is trivial
}

// Player 0 on (3,-1), player 1 on (4,-1) and (4,0): the jump over (4,-1) lands on (5,-1) in the foreign corner q > 4, and from there the
// jump over (4,0) lands on (3,1).
SH_TEST(hand_made_chain_through_a_foreign_corner) {
    const auto cell = [](int q, int r) { return geometry::id(q, r); };
    for (const Camps camps : {Camps::Closed, Camps::PassThrough, Camps::Open}) {
        Position p = Position::empty(rules_of(2, Jump::Adjacent, camps));
        CHECK(p.put(0, cell(3, -1)).has_value());
        CHECK(p.put(1, cell(4, -1)).has_value());
        CHECK(p.put(1, cell(4, 0)).has_value());
        const auto moves = as_set(moves_of(p));
        CHECK_EQ(moves.contains({cell(3, -1), cell(3, 1)}), camps != Camps::Closed);
        CHECK_EQ(moves.contains({cell(3, -1), cell(5, -1)}), camps == Camps::Open);
    }
}

SH_TEST(setup_on_a_foreign_cell_only_when_open) {
    const int foreign = geometry::id(5, -1);
    CHECK(!Position::empty(rules_of(2, Jump::Adjacent, Camps::Closed)).put(0, foreign).has_value());
    CHECK(!Position::empty(rules_of(2, Jump::Adjacent, Camps::PassThrough)).put(0, foreign).has_value());
    CHECK(Position::empty(rules_of(2, Jump::Adjacent, Camps::Open)).put(0, foreign).has_value());
}

// Foreign cells get the step distance over the whole board; it stays within the largest home distance (13 for two players), which
// the evaluation tables size their histograms by.
SH_TEST(distances_of_foreign_cells) {
    for (const int players : {2, 3}) {
        const Layout& layout = Layout::of(players);
        for (int p = 0; p < players; ++p) {
            for (int c = 0; c < kCells; ++c) {
                if (layout.allowed(p, c)) continue;
                CHECK(layout.distance(p, c) > 0);
                CHECK(layout.distance(p, c) <= layout.max_distance(p));
            }
        }
    }
    CHECK_EQ(Layout::of(2).max_distance(0), 13);
}

// Race play and the race evaluation know only the default move set; the incremental evaluator falls back to the full evaluation.
SH_TEST(race_and_incremental_evaluation_are_off_unless_closed) {
    for (const Camps camps : {Camps::Closed, Camps::PassThrough, Camps::Open}) {
        const RuleSet rules = rules_of(2, Jump::Adjacent, camps);
        const bool closed = camps == Camps::Closed;
        CHECK_EQ(RaceAdjudication::applicable(rules), closed);
        CHECK_EQ(RaceEvaluator::rules_apply(rules), closed);
        // a passed position: every piece of player 0 below every piece of player 1
        Position passed = Position::empty(rules);
        for (const int c : geometry::corner_cells(passed.layout().goal_corner(0))) CHECK(passed.put(0, c).has_value());
        for (const int c : geometry::corner_cells(passed.layout().goal_corner(1))) CHECK(passed.put(1, c).has_value());
        CHECK_EQ(RaceAdjudication::passed(passed), closed);
        const auto evaluator = LinearEvaluator::linear_r3();
        IncrementalLinear state(*evaluator);
        CHECK_EQ(state.reset(Position::initial(rules)), closed);
    }
}

int main() { return sh::test::run_all(); }
