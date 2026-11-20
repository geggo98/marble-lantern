// Tests of the house-size switch (RuleSet::TriangleRows, docs/rules.md "Board size") of the C++ engine: 15-cell houses, 15 pieces.
//  - The Java golden file engine/src/test/resources/triangle-rows-golden.tsv (path from CMake, SH_TRIANGLE_ROWS_GOLDEN), all 96 rows: the
//    position rebuilt from the listed pieces and the position reached by the seeded playout (RANDOM or GREEDY) both give the listed
//    move count, both move-list hashes, the Zobrist hash, the start hash and the outcome. A missing file fails the test.
//  - A coordinate-based oracle (houses from cube coordinates, no tables, no depth-first search; the same idea as the Java
//    TriangleRowsOracle) against Layout, generate() and has_won() over the positions of seeded playouts.
//  - A hand-built three-player position for the suicide rule on a 15-cell goal, the setup limit, the rule defaults and the 4-row
//    layout that must stay as it was.
#include <algorithm>
#include <array>
#include <bit>
#include <cstdint>
#include <cstdlib>
#include <fstream>
#include <iterator>
#include <optional>
#include <set>
#include <span>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

#include "check.hpp"
#include "geometry.hpp"
#include "layout.hpp"
#include "move.hpp"
#include "position.hpp"
#include "rules.hpp"
#include "sha256.hpp"
#include "splittable_random.hpp"

using namespace sh;

namespace {

using Camps = RuleSet::ForeignCamps;
using Jump = RuleSet::JumpRule;
using Rows = RuleSet::TriangleRows;

RuleSet five(int players, Jump jump, Camps camps) {
    return *RuleSet::defaults(players, Rows::Five).with_jump_rule(jump)->with_foreign_camps(camps);
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
    std::ranges::stable_sort(moves, [](int a, int b) {
        return Move::from(a) != Move::from(b) ? Move::from(a) < Move::from(b) : Move::to(a) < Move::to(b);
    });
    return moves;
}

// The moves with the largest distance gain of the mover (the greedy policy of the golden file), in the order of `sorted`.
std::vector<int> best_gain(const Position& p, const std::vector<int>& sorted) {
    const int mover = p.side_to_move();
    int best = -1000;
    std::vector<int> kept;
    for (const int m : sorted) {
        const int gain = p.layout().distance(mover, Move::from(m)) - p.layout().distance(mover, Move::to(m));
        if (gain > best) {
            best = gain;
            kept.clear();
        }
        if (gain == best) kept.push_back(m);
    }
    return kept;
}

bool play_one(Position& p, SplittableRandom& rng, bool greedy) {
    if (p.outcome().has_value()) return false;
    const std::vector<int> moves = sorted_by_from_to(moves_of(p));
    if (moves.empty()) return false;
    const std::vector<int> candidates = greedy ? best_gain(p, moves) : moves;
    p.make(candidates[(rng.next_long() >> 1) % candidates.size()]);
    return true;
}

std::string hex16(std::uint64_t v) {
    std::string s(16, '0');
    for (int i = 15; i >= 0; --i, v >>= 4) s[static_cast<std::size_t>(i)] = "0123456789abcdef"[v & 15];
    return s;
}

std::string outcome_text(const std::optional<Outcome>& o) {
    if (!o) return "ONGOING";
    std::string bits;
    for (int p = 0; p < o->players; ++p) bits += (p == 0 ? "" : ",") + hex16(std::bit_cast<std::uint64_t>(o->value(p)));
    return std::string(Outcome::reason_name(o->reason)) + "/" + std::to_string(o->winner) + "/" + bits;
}

std::string pieces_text(const Position& p, int player) {
    std::string cells;
    for (const int c : p.pieces(player)) cells += (cells.empty() ? "" : ",") + std::to_string(c);
    return cells;
}

std::set<std::pair<int, int>> as_set(const std::vector<int>& moves) {
    std::set<std::pair<int, int>> s;
    for (const int m : moves) s.insert({Move::from(m), Move::to(m)});
    return s;
}

// ---- the oracle: houses, moves and wins from cube coordinates only ----

constexpr std::array<int, 6> kDq{1, -1, 0, 0, 1, -1};
constexpr std::array<int, 6> kDr{0, 0, 1, -1, -1, 1};

struct Axes {
    int a;  // the defining coordinate: 0 = q, 1 = r, 2 = s
    int sign;
};

// The six corners in the clockwise order of Corner: q>4, s<-4, r>4, q<-4, s>4, r<-4.
constexpr std::array<Axes, 6> kCorners{{{0, +1}, {2, -1}, {1, +1}, {0, -1}, {2, +1}, {1, -1}}};

int coordinate(int cell, int axis) {
    const int q = geometry::q(cell);
    const int r = geometry::r(cell);
    return axis == 0 ? q : axis == 1 ? r : -q - r;
}

// A house of n rows (4 or 5) of a corner: the cells with sigma*a >= 9-n and sigma*b <= 0, sigma*c <= 0 for the other two coordinates.
bool in_house(int corner, int cell, int rows) {
    const Axes ax = kCorners[static_cast<std::size_t>(corner)];
    if (ax.sign * coordinate(cell, ax.a) < 9 - rows) return false;
    for (int axis = 0; axis < 3; ++axis) {
        if (axis != ax.a && ax.sign * coordinate(cell, axis) > 0) return false;
    }
    return true;
}

int start_corner(int players, int p) { return players == 2 ? (p == 0 ? 5 : 2) : p == 0 ? 5 : p == 1 ? 1 : 3; }
int goal_corner(int players, int p) { return (start_corner(players, p) + 3) % 6; }

std::vector<int> house(int corner, int rows) {
    std::vector<int> cells;
    for (int c = 0; c < kCells; ++c) {
        if (in_house(corner, c, rows)) cells.push_back(c);
    }
    return cells;
}

// The home cells (cells a move may end on unless foreign corners are open): the hexagon and the two corner triangles of the player.
bool home(int players, int p, int cell) {
    for (int k = 0; k < 6; ++k) {
        if (in_house(k, cell, 4) && k != start_corner(players, p) && k != goal_corner(players, p)) return false;
    }
    return true;
}

int cell_at(int c, int d, int k) {
    return geometry::id(geometry::q(c) + k * kDq[static_cast<std::size_t>(d)], geometry::r(c) + k * kDr[static_cast<std::size_t>(d)]);
}

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

// Whether the player meets the winning condition in the owner map: the goal house is full and holds an own piece.
bool oracle_has_won(const std::array<int, kCells>& owner, int players, int p) {
    int full = 0;
    int own = 0;
    for (const int c : house(goal_corner(players, p), 5)) {
        full += owner[static_cast<std::size_t>(c)] >= 0 ? 1 : 0;
        own += owner[static_cast<std::size_t>(c)] == p ? 1 : 0;
    }
    return full == 15 && own >= 1;
}

// All (from, to) of the side to move under the default rules and the given jump rule and camps. With `suicide` a move that fills an
// opponent's goal house (with an own piece of that opponent in it) is dropped (Definition 2); without it the move is kept.
std::set<std::pair<int, int>> oracle_moves(const Position& pos, bool long_hop, Camps camps, bool suicide) {
    const int players = pos.rules().players();
    const int p = pos.side_to_move();
    std::array<int, kCells> owner{};
    for (int c = 0; c < kCells; ++c) owner[static_cast<std::size_t>(c)] = pos.owner(c);
    const auto own_goal = [&](int cell) { return in_house(goal_corner(players, p), cell, 5); };
    std::set<std::pair<int, int>> result;
    const auto add = [&](int from, int to) {
        if (own_goal(from) && !own_goal(to)) return;
        if (suicide) {
            auto after = owner;
            after[static_cast<std::size_t>(from)] = -1;
            after[static_cast<std::size_t>(to)] = p;
            for (int q = 0; q < players; ++q) {
                if (q != p && oracle_has_won(after, players, q)) return;
            }
        }
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

SH_TEST(rule_defaults_and_validation) {
    CHECK(RuleSet::defaults().triangle_rows() == Rows::Four);
    CHECK(RuleSet::defaults(3).triangle_rows() == Rows::Four);
    CHECK(RuleSet::defaults(2, Rows::Four) == RuleSet::defaults(2));
    CHECK(RuleSet::defaults(3, Rows::Four) == RuleSet::defaults(3));
    CHECK_EQ(RuleSet::defaults(2).pieces(), 10);
    CHECK_EQ(RuleSet::defaults(2, Rows::Five).pieces(), 15);
    CHECK_EQ(RuleSet::defaults(2, Rows::Five).move_limit(), 250);
    CHECK_EQ(RuleSet::defaults(3, Rows::Five).move_limit(), 375);
    CHECK_EQ(RuleSet::defaults(2).move_limit(), 200);
    CHECK_EQ(RuleSet::defaults(3).move_limit(), 300);
    const auto r = RuleSet::defaults(2).with_triangle_rows(Rows::Five);
    CHECK(r.has_value());
    CHECK(r->triangle_rows() == Rows::Five);
    CHECK_EQ(r->move_limit(), 200);  // the switch alone does not change the move limit
    CHECK(!(*r == RuleSet::defaults(2)));
    CHECK(r->with_triangle_rows(Rows::Four).value() == RuleSet::defaults(2));
    CHECK(r->with_jump_rule(Jump::Long)->triangle_rows() == Rows::Five);
}

// The houses of the layout equal the coordinate oracle, for both sizes and player counts; the 3-player Five houses overlap in the two
// hexagon-vertex cells, and the goal houses never overlap.
SH_TEST(layout_houses_equal_the_oracle) {
    for (const Rows rows : {Rows::Four, Rows::Five}) {
        const int n = rows == Rows::Five ? 5 : 4;
        for (const int players : {2, 3}) {
            const Layout& layout = Layout::of(players, rows);
            CHECK_EQ(layout.pieces(), n == 5 ? 15 : 10);
            CHECK_EQ(layout.goal_size(), layout.pieces());
            int shared = 0;
            for (int p = 0; p < players; ++p) {
                const std::vector<int> start = house(start_corner(players, p), n);
                const std::vector<int> goal = house(goal_corner(players, p), n);
                CHECK_EQ(static_cast<int>(start.size()), layout.pieces());
                CHECK_EQ(static_cast<int>(goal.size()), layout.pieces());
                CHECK(std::ranges::equal(layout.start_cells(p), start));
                CHECK(std::ranges::equal(layout.goal_cells(p), goal));
                for (int c = 0; c < kCells; ++c) {
                    CHECK_EQ(layout.in_start(p, c), in_house(start_corner(players, p), c, n));
                    CHECK_EQ(layout.in_goal(p, c), in_house(goal_corner(players, p), c, n));
                    int owners = 0;
                    for (int q = 0; q < players; ++q) owners += layout.in_goal(q, c) ? 1 : 0;
                    CHECK(owners <= 1);
                    CHECK_EQ(layout.goal_player_of_cell(c), owners == 1 ? (layout.in_goal(0, c) ? 0 : layout.in_goal(1, c) ? 1 : 2) : -1);
                    const int g = layout.goal_player_of_cell(c);
                    // a start cell that is a goal cell of a player whose goal house is not this whole start house
                    shared += layout.in_start(p, c) && g >= 0 && g != p && layout.goal_corner(g) != layout.start_corner(p) ? 1 : 0;
                }
            }
            // Four: nothing is shared. Five with three players: each of the 3 start houses shares 2 cells with a neighbour's goal.
            CHECK_EQ(shared, rows == Rows::Five && players == 3 ? 6 : 0);
        }
    }
    const Layout& l = Layout::of(3, Rows::Five);
    const int vertex = geometry::id(0, -4);
    CHECK(l.in_start(0, vertex));
    CHECK(l.in_goal(1, vertex));
    CHECK_EQ(l.goal_player_of_cell(vertex), 1);
}

// 4-row layouts are what they were: the corner triangles, the old distances and the shared instance of of(players).
SH_TEST(four_row_layout_is_unchanged) {
    CHECK(&Layout::of(2) == &Layout::of(2, Rows::Four));
    CHECK(&Layout::of(3) == &Layout::of(3, Rows::Four));
    CHECK(&Layout::of(2) != &Layout::of(2, Rows::Five));
    for (const int players : {2, 3}) {
        const Layout& layout = Layout::of(players);
        CHECK_EQ(layout.max_distance(0), 13);
        for (int p = 0; p < players; ++p) {
            CHECK(std::ranges::equal(layout.start_cells(p), geometry::corner_cells(layout.start_corner(p))));
            CHECK(std::ranges::equal(layout.goal_cells(p), geometry::corner_cells(layout.goal_corner(p))));
        }
    }
    // the tip cell (4,-8) needs 12 steps to the goal row r = 4 with 5-row houses (13 to r = 5 with 4-row houses)
    CHECK_EQ(Layout::of(2, Rows::Five).distance(0, geometry::id(4, -8)), 12);
    CHECK_EQ(Layout::of(2, Rows::Five).max_distance(0), 12);
    CHECK_EQ(Layout::of(2).distance(0, geometry::id(4, -8)), 13);
    CHECK_EQ(Layout::of(2, Rows::Five).distance(0, geometry::id(0, 4)), 0);
    CHECK_EQ(Layout::of(2, Rows::Five).distance(0, geometry::id(0, 3)), 1);
}

SH_TEST(start_positions_and_setup_limit) {
    for (const int players : {2, 3}) {
        const Position p = Position::initial(RuleSet::defaults(players, Rows::Five));
        for (int q = 0; q < players; ++q) {
            CHECK_EQ(p.piece_count(q), 15);
            const std::vector<int> expected = house(start_corner(players, q), 5);
            CHECK(std::ranges::equal(p.pieces(q), expected));
        }
        CHECK_EQ(Position::initial(RuleSet::defaults(players)).piece_count(0), 10);
    }
    Position four = Position::initial(RuleSet::defaults(2));
    CHECK(!four.put(0, geometry::id(0, 0)).has_value());  // an 11th piece on the 4-row board
    Position full = Position::initial(RuleSet::defaults(2, Rows::Five));
    CHECK(!full.put(0, geometry::id(0, 0)).has_value());  // a 16th piece
    // 15 pieces fit on the hexagon, the 16th is refused with the limit named; on the 4-row board the 11th is refused
    for (const Rows rows : {Rows::Four, Rows::Five}) {
        const int limit = rows == Rows::Five ? 15 : 10;
        Position empty = Position::empty(RuleSet::defaults(2, rows));
        int put = 0;
        for (int c = 0; c < kCells && put < limit; ++c) {
            if (geometry::corner_of(c) < 0) {
                CHECK(empty.put(0, c).has_value());
                ++put;
            }
        }
        CHECK_EQ(put, limit);
        const auto refused = empty.put(0, geometry::id(0, 0) == 60 ? 61 : 60);
        CHECK(!refused.has_value());
        if (!refused) CHECK(refused.error().find(std::to_string(limit) + " pieces") != std::string::npos);
    }
}

// Every row of the Java golden file, bit for bit.
SH_TEST(java_golden_all_rows) {
    std::ifstream in(SH_TRIANGLE_ROWS_GOLDEN);
    CHECK(in.good());
    int rows = 0;
    int wins = 0;
    std::string line;
    while (std::getline(in, line)) {
        if (line.empty() || line.front() == '#') continue;
        const auto f = split(line, '\t');
        CHECK_EQ(f.size(), std::size_t{17});
        if (f.size() != 17) continue;
        const int id = std::stoi(std::string(f[0]));
        const int players = std::stoi(std::string(f[1]));
        const Jump jump = f[2] == "LONG" ? Jump::Long : Jump::Adjacent;
        const Camps camps = camps_of(f[3]);
        const bool greedy = f[4] == "GREEDY";
        CHECK(greedy || f[4] == "RANDOM");
        const int plies = std::stoi(std::string(f[6]));
        const int side = std::stoi(std::string(f[7]));
        CHECK_EQ(id, rows);
        ++rows;
        const std::uint64_t seed = std::uint64_t{0x15F00000} + static_cast<std::uint64_t>(id);
        CHECK_EQ(std::stoull(std::string(f[5]), nullptr, 16), seed);
        const RuleSet rules = five(players, jump, camps);

        // (a) the position rebuilt from the listed pieces
        Position built = Position::empty(rules);
        for (int p = 0; p < players; ++p) {
            for (const std::string_view cell : split(f[static_cast<std::size_t>(8 + p)], ',')) {
                CHECK(built.put(p, std::stoi(std::string(cell))).has_value());
            }
        }
        CHECK(built.set_side_to_move(side).has_value());
        const std::vector<int> ordered = moves_of(built);
        CHECK_EQ(static_cast<int>(ordered.size()), std::stoi(std::string(f[11])));
        CHECK_EQ(hash_of_moves(sorted_by_from_to(ordered)), std::string(f[12]));
        CHECK_EQ(hash_of_moves(ordered), std::string(f[13]));

        // (b) the position reached by the seeded playout from the start position
        Position played = Position::initial(rules);
        CHECK_EQ(hex16(played.hash()), std::string(f[15]));
        SplittableRandom rng(seed);
        while (played.ply() < plies && play_one(played, rng, greedy)) {
        }
        CHECK_EQ(played.ply(), plies);
        CHECK_EQ(played.side_to_move(), side);
        for (int p = 0; p < players; ++p) CHECK_EQ(pieces_text(played, p), std::string(f[static_cast<std::size_t>(8 + p)]));
        CHECK_EQ(hex16(played.hash()), std::string(f[14]));
        std::optional<Outcome> outcome = played.outcome();
        const std::vector<int> ordered2 = moves_of(played);
        if (!outcome && ordered2.empty()) outcome = played.no_moves_outcome();
        CHECK_EQ(outcome_text(outcome), std::string(f[16]));
        CHECK_EQ(hash_of_moves(ordered2), std::string(f[13]));
        wins += outcome && outcome->has_winner() ? 1 : 0;
    }
    CHECK_EQ(rows, 96);
    CHECK_EQ(wins, 12);  // the last greedy row of each of the 12 variants plays to a win
}

// A game that ends at the move limit scores by distance, scaled by pieces * largest distance (15 * 12 with five rows, 10 * 13 with four).
// After one ply that gains `gain` steps for player 0, the sums differ by `gain`, so the values are +-gain / 2 / scale.
SH_TEST(move_limit_score_scales_with_the_house) {
    for (const Rows rows : {Rows::Four, Rows::Five}) {
        const double scale = rows == Rows::Five ? 15.0 * 12 : 10.0 * 13;
        const RuleSet rules = *RuleSet::defaults(2, rows).with_move_limit(1);
        Position p = Position::initial(rules);
        CHECK(!p.outcome().has_value());
        const std::vector<int> moves = moves_of(p);
        int made = 0;
        for (const int m : moves) {
            const int gain = p.layout().distance(0, Move::from(m)) - p.layout().distance(0, Move::to(m));
            if (gain != 1) continue;
            p.make(m);
            made = gain;
            break;
        }
        CHECK_EQ(made, 1);
        const auto outcome = p.outcome();
        CHECK(outcome.has_value());
        if (!outcome) continue;
        CHECK(outcome->reason == Outcome::Reason::MoveLimit);
        CHECK_EQ(outcome->value(0), 0.5 / scale);
        CHECK_EQ(outcome->value(1), -0.5 / scale);
    }
}

// The oracle against Layout, generate() and has_won() over the positions of seeded random and greedy playouts.
SH_TEST(oracle_property_over_playouts) {
    int positions = 0;
    int goal_edge_pieces = 0;
    int nearly_full_goals = 0;
    int wins_seen = 0;
    for (const int players : {2, 3}) {
        for (const Jump jump : {Jump::Adjacent, Jump::Long}) {
            for (const Camps camps : {Camps::Closed, Camps::PassThrough, Camps::Open}) {
                const RuleSet rules = five(players, jump, camps);
                for (std::uint64_t seed = 1; seed <= 3; ++seed) {
                    const bool greedy = seed >= 2;
                    Position p = Position::initial(rules);
                    SplittableRandom rng(seed * 7919 + static_cast<std::uint64_t>(players));
                    for (int ply = 0; ply < 230; ++ply) {
                        const std::vector<int> generated = moves_of(p);
                        CHECK_EQ(as_set(generated), oracle_moves(p, jump == Jump::Long, camps, true));
                        CHECK_EQ(as_set(generated).size(), generated.size());  // no duplicate (from, to)
                        std::array<int, kCells> owner{};
                        for (int c = 0; c < kCells; ++c) owner[static_cast<std::size_t>(c)] = p.owner(c);
                        for (int q = 0; q < players; ++q) {
                            CHECK_EQ(p.has_won(q), oracle_has_won(owner, players, q));
                            int occupied = 0;
                            for (const int c : house(goal_corner(players, q), 5)) occupied += owner[static_cast<std::size_t>(c)] >= 0 ? 1 : 0;
                            CHECK_EQ(p.goal_occupied(q), occupied);
                            nearly_full_goals += occupied == 14 ? 1 : 0;
                            wins_seen += p.has_won(q) ? 1 : 0;
                            for (const int c : p.pieces(q)) {
                                goal_edge_pieces += in_house(goal_corner(players, q), c, 5) && !in_house(goal_corner(players, q), c, 4) ? 1 : 0;
                            }
                        }
                        ++positions;
                        if (!play_one(p, rng, greedy)) break;
                    }
                }
            }
        }
    }
    // The property must reach the interesting cases, else it is trivial.
    CHECK(positions > 1500);
    CHECK(goal_edge_pieces > 200);
    CHECK(nearly_full_goals > 20);
    CHECK(wins_seen > 0);
}

// Three players, 15-cell goals. Player 0 on the shared hexagon-vertex cell (0,-4), which is also a goal cell of player 1, and on (-1,-2).
// Player 1's goal (s > 3) holds 14 pieces; the last hole (-2,-2). Stepping into it would fill the goal, so it is illegal (Definition 2),
// while the same step exists when the suicide rule is off in the oracle. A mutated threshold (goal_size() - 1 -> 9) or a leave-goal
// check on the 10-cell corner would give a different move list.
SH_TEST(suicide_rule_on_a_fifteen_cell_goal) {
    const int hole = geometry::id(-2, -2);
    const int vertex = geometry::id(0, -4);
    const int mover = geometry::id(-1, -2);
    for (const Jump jump : {Jump::Adjacent, Jump::Long}) {
        const RuleSet rules = five(3, jump, Camps::Closed);
        const std::vector<int> goal_of_1 = house(goal_corner(3, 1), 5);
        CHECK(std::ranges::count(goal_of_1, hole) == 1 && std::ranges::count(goal_of_1, vertex) == 1 && std::ranges::count(goal_of_1, mover) == 0);
        Position pos = Position::empty(rules);
        CHECK(pos.put(0, vertex).has_value());
        CHECK(pos.put(0, mover).has_value());
        for (const int c : goal_of_1) {
            if (c == hole || c == vertex) continue;
            // the 10-cell corner is foreign to players 0 and 2: player 1 holds it; the edge row cells belong to player 2
            CHECK(pos.put(in_house(goal_corner(3, 1), c, 4) ? 1 : 2, c).has_value());
        }
        CHECK(pos.set_side_to_move(0).has_value());
        CHECK_EQ(pos.goal_occupied(1), 14);
        const auto generated = as_set(moves_of(pos));
        CHECK(!generated.contains({mover, hole}));
        CHECK(as_set(moves_of(pos)) == oracle_moves(pos, jump == Jump::Long, Camps::Closed, true));
        CHECK(oracle_moves(pos, jump == Jump::Long, Camps::Closed, false).contains({mover, hole}));
        // the other player's pieces on its own goal cells do not make the move illegal: a step elsewhere stays legal
        CHECK(generated.contains({mover, geometry::id(-1, -1)}));
    }
}

int main() { return sh::test::run_all(); }
