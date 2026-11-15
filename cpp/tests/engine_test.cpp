// Tests of src/engine (WP2). Golden files (written by the Java exporter of WP1): zobrist.tsv, geometry.tsv, positions.tsv,
// reference.tsv. Without STERNHALMA_GOLDEN_REQUIRED=1 a missing golden file skips its test; the constructed cases always run.
#include <algorithm>
#include <array>
#include <cstdint>
#include <optional>
#include <span>
#include <string>
#include <string_view>
#include <vector>

#include "check.hpp"
#include "geometry.hpp"
#include "golden.hpp"
#include "golden_test_support.hpp"
#include "layout.hpp"
#include "move.hpp"
#include "outcome.hpp"
#include "position.hpp"
#include "rules.hpp"

using namespace sh;

namespace {

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

std::int64_t to_i64(std::string_view s) { return std::stoll(std::string(s)); }

std::uint64_t from_hex(std::string_view s) { return std::stoull(std::string(s), nullptr, 16); }

std::vector<int> int_list(std::string_view s) {
    std::vector<int> out;
    if (s == "-" || s.empty()) return out;
    for (const std::string_view part : split(s, ' ')) out.push_back(static_cast<int>(to_i64(part)));
    return out;
}

// Reads the data lines of a golden file (without the comment lines) split at tabs.
std::optional<std::vector<std::vector<std::string_view>>> data_rows(const std::vector<std::string>& lines) {
    std::vector<std::vector<std::string_view>> rows;
    for (const std::string& l : lines) {
        if (!l.empty() && l.front() != '#') rows.push_back(split(l, '\t'));
    }
    return rows;
}

std::array<int, kMaxMoves> g_buffer{};  // the out buffer of generate()

std::vector<int> legal(Position& pos) {
    const int n = pos.generate(g_buffer);
    const auto found = std::span<const int>(g_buffer).first(static_cast<std::size_t>(n));
    return std::vector<int>(found.begin(), found.end());
}

Position rules_position(const RuleSet& rules) { return Position::initial(rules); }

}  // namespace

SH_TEST(move_packing) {
    const int m = Move::of(5, 117);
    CHECK_EQ(Move::from(m), 5);
    CHECK_EQ(Move::to(m), 117);
    CHECK_EQ(Move::to_string(Move::of(geometry::id(4, -8), geometry::id(3, -7))), std::string("(4,-8)->(3,-7)"));
}

SH_TEST(geometry_invariants) {
    int on_board = 0;
    for (int r = -8; r <= 8; ++r) {
        for (int q = -8; q <= 8; ++q) {
            const int c = geometry::id(q, r);
            if (c >= 0) {
                ++on_board;
                CHECK_EQ(geometry::q(c), q);
                CHECK_EQ(geometry::r(c), r);
                CHECK_EQ(geometry::s(c), -q - r);
            }
        }
    }
    CHECK_EQ(on_board, kCells);
    CHECK_EQ(geometry::id(9, 0), -1);
    CHECK_EQ(geometry::id(-9, 3), -1);
    CHECK_EQ(geometry::id(5, 5), -1);  // s = -10: off the star
    CHECK_EQ(geometry::id(0, -5), -1);  // s = 5 and r = -5: neither the large nor the small bound
    for (int c = 0; c < kCells; ++c) {
        for (int d = 0; d < kDirections; ++d) {
            const int n = geometry::neighbor(c, d);
            if (n >= 0) CHECK_EQ(geometry::neighbor(n, d ^ 1), c);  // directions d and d ^ 1 are opposite
        }
        CHECK_EQ(geometry::mirror(geometry::mirror(c)), c);
        CHECK_EQ(geometry::rotate_180(geometry::rotate_180(c)), c);
        CHECK_EQ(geometry::player_flip(geometry::player_flip(c)), c);
    }
    for (int k = 0; k < 6; ++k) {
        CHECK_EQ(static_cast<int>(geometry::corner_cells(k).size()), kCornerSize);
        for (const int c : geometry::corner_cells(k)) CHECK_EQ(geometry::corner_of(c), k);
    }
}

SH_TEST(zobrist_keys_match_golden) {
    const auto lines = golden::lines_or_skip("zobrist.tsv");
    if (!lines) return;
    const auto rows = data_rows(*lines).value();
    int keys = 0;
    std::optional<std::uint64_t> initial_hash;
    for (const auto& row : rows) {
        if (row[0] == "initial-hash") {
            initial_hash = from_hex(row[1]);
            continue;
        }
        const int index = static_cast<int>(to_i64(row[0]));
        const int player = index / (kCells + 1);
        const int within = index % (kCells + 1);
        const std::uint64_t key = static_cast<std::uint64_t>(to_i64(row[1]));
        CHECK_EQ(key, within == 0 ? Position::side_key(player) : Position::piece_key(player, within - 1));
        ++keys;
    }
    CHECK_EQ(keys, 3 * (kCells + 1));
    CHECK(initial_hash.has_value());
    const Position initial = Position::initial(RuleSet::defaults());
    CHECK_EQ(initial.hash(), initial_hash.value_or(0));
}

SH_TEST(geometry_and_layout_match_golden) {
    const auto lines = golden::lines_or_skip("geometry.tsv");
    if (!lines) return;
    const Layout& layout = Layout::two_players();
    int cells = 0;
    int dists = 0;
    int layouts = 0;
    int corners = 0;
    for (const auto& row : data_rows(*lines).value()) {
        auto at = [&](std::size_t i) { return static_cast<int>(to_i64(row[i])); };
        if (row[0] == "cell") {
            const int c = at(1);
            CHECK_EQ(geometry::id(at(2), at(3)), c);
            CHECK_EQ(geometry::q(c), at(2));
            CHECK_EQ(geometry::r(c), at(3));
            CHECK_EQ(geometry::s(c), at(4));
            for (int d = 0; d < kDirections; ++d) {
                CHECK_EQ(geometry::neighbor(c, d), at(5 + static_cast<std::size_t>(d)));
                CHECK_EQ(geometry::jump_landing(c, d), at(11 + static_cast<std::size_t>(d)));
            }
            CHECK_EQ(geometry::corner_of(c), at(17));
            CHECK_EQ(geometry::mirror(c), at(18));
            CHECK_EQ(geometry::player_flip(c), at(19));
            CHECK_EQ(geometry::rotate_180(c), at(20));
            ++cells;
        } else if (row[0] == "layout") {
            const int p = at(1);
            CHECK_EQ(layout.start_corner(p), at(2));
            CHECK_EQ(layout.goal_corner(p), at(3));
            CHECK_EQ(layout.max_distance(p), at(4));
            ++layouts;
        } else if (row[0] == "dist") {
            CHECK_EQ(layout.distance(at(1), at(2)), at(3));
            CHECK_EQ(layout.allowed(at(1), at(2)) ? 1 : 0, at(4));
            ++dists;
        } else if (row[0] == "corner") {
            const auto expected = geometry::corner_cells(at(1));
            CHECK_EQ(static_cast<int>(row.size()) - 2, kCornerSize);
            for (std::size_t i = 0; i < expected.size() && i + 2 < row.size(); ++i) CHECK_EQ(expected[i], at(i + 2));
            ++corners;
        }
    }
    CHECK_EQ(cells, kCells);
    CHECK_EQ(layouts, 2);
    CHECK_EQ(dists, 2 * kCells);
    CHECK_EQ(corners, 6);
}

SH_TEST(layout_tables_fold_in_allowed_cells) {
    const Layout& layout = Layout::two_players();
    for (int p = 0; p < 2; ++p) {
        for (int c = 0; c < kCells; ++c) {
            for (int d = 0; d < kDirections; ++d) {
                const int n = geometry::neighbor(c, d);
                const int l = geometry::jump_landing(c, d);
                const int i = c * kDirections + d;
                CHECK_EQ(layout.step_table(p)[static_cast<std::size_t>(i)], n >= 0 && layout.allowed(p, n) ? n : -1);
                CHECK_EQ(layout.jump_table(p)[static_cast<std::size_t>(i)], l >= 0 && layout.allowed(p, l) ? l : -1);
            }
        }
        CHECK_EQ(layout.goal_player(layout.goal_corner(p)), p);
    }
    CHECK_EQ(layout.goal_player(-1), -1);
}

namespace {

// Replays `moves` from the initial position; every move must be legal at its turn.
bool replay(Position& pos, const std::vector<int>& moves) {
    for (const int m : moves) {
        const std::vector<int> now = legal(pos);
        if (std::find(now.begin(), now.end(), m) == now.end()) return false;
        pos.make(m);
    }
    return true;
}

std::string outcome_text(const std::optional<Outcome>& o) {
    return o ? std::string(Outcome::reason_name(o->reason)) + ":" + std::to_string(o->winner) : std::string("none");
}

}  // namespace

SH_TEST(positions_match_golden) {
    const auto lines = golden::lines_or_skip("positions.tsv");
    if (!lines) return;
    const auto rows = data_rows(*lines).value();
    CHECK_EQ(static_cast<int>(rows.size()), 2000);
    int agreed = 0;
    int made_and_unmade = 0;
    for (const auto& row : rows) {
        const int index = static_cast<int>(to_i64(row[0]));
        Position pos = Position::initial(RuleSet::defaults());
        CHECK(replay(pos, int_list(row[4])));
        CHECK_EQ(pos.ply(), static_cast<int>(to_i64(row[1])));
        CHECK_EQ(pos.side_to_move(), static_cast<int>(to_i64(row[2])));
        CHECK_EQ(pos.hash(), from_hex(row[3]));
        CHECK_EQ(outcome_text(pos.outcome()), std::string(row[6]));
        const std::vector<int> expected = int_list(row[5]);
        const std::vector<int> actual = legal(pos);
        CHECK(actual == expected);  // same moves in the same order
        if (actual != expected) {
            ::sh::test::fail(__FILE__, __LINE__, "position " + std::to_string(index) + ": the move lists differ");
            continue;
        }
        ++agreed;
        // make then unmake restores hash, owner array, side, ply and the sorted piece lists
        std::array<int, kCells> owners{};
        for (int c = 0; c < kCells; ++c) owners[static_cast<std::size_t>(c)] = pos.owner(c);
        const std::uint64_t hash = pos.hash();
        const std::vector<int> pieces0(pos.pieces(0).begin(), pos.pieces(0).end());
        const std::vector<int> pieces1(pos.pieces(1).begin(), pos.pieces(1).end());
        const int goal0 = pos.goal_occupied(0);
        const int goal1 = pos.goal_occupied(1);
        for (const int m : expected) {
            pos.make(m);
            CHECK_EQ(pos.ply(), static_cast<int>(to_i64(row[1])) + 1);
            CHECK(std::is_sorted(pos.pieces(0).begin(), pos.pieces(0).end()));
            CHECK(std::is_sorted(pos.pieces(1).begin(), pos.pieces(1).end()));
            pos.unmake();
            ++made_and_unmade;
            bool same = pos.hash() == hash && pos.ply() == static_cast<int>(to_i64(row[1])) && goal0 == pos.goal_occupied(0) &&
                        goal1 == pos.goal_occupied(1);
            for (int c = 0; c < kCells && same; ++c) same = owners[static_cast<std::size_t>(c)] == pos.owner(c);
            same = same && std::equal(pieces0.begin(), pieces0.end(), pos.pieces(0).begin(), pos.pieces(0).end()) &&
                   std::equal(pieces1.begin(), pieces1.end(), pos.pieces(1).begin(), pos.pieces(1).end());
            CHECK(same);
        }
    }
    CHECK_EQ(agreed, 2000);
    CHECK(made_and_unmade > 10000);
}

SH_TEST(reference_move_lists_match_golden) {
    const auto lines = golden::lines_or_skip("reference.tsv");
    if (!lines) return;
    int position_rows = 0;
    int opening_rows = 0;
    for (const auto& row : data_rows(*lines).value()) {
        Position pos = Position::initial(RuleSet::defaults());
        CHECK(replay(pos, int_list(row[4])));
        CHECK_EQ(pos.ply(), static_cast<int>(to_i64(row[2])));
        CHECK_EQ(pos.hash(), from_hex(row[3]));
        (row[0] == "position" ? position_rows : opening_rows)++;
    }
    CHECK_EQ(position_rows, 64);
    CHECK_EQ(opening_rows, 2);
}

// Two jump routes that end on the same cell (a parallelogram of four jumped pieces): the visited set must report (A, D) once.
// One case lives in the cells below 64 and one in the cells from 64 up, the two halves of the 128-bit visited set.
SH_TEST(jump_routes_that_meet_give_one_move) {
    int cases_low = 0;
    int cases_high = 0;
    for (int a = 0; a < kCells && (cases_low == 0 || cases_high == 0); ++a) {
        const int u1 = 0;  // direction (1, 0)
        const int u2 = 4;  // direction (1, -1)
        const int x1 = geometry::neighbor(a, u1);
        const int b = x1 < 0 ? -1 : geometry::neighbor(x1, u1);
        const int c1 = geometry::neighbor(a, u2);
        const int c = c1 < 0 ? -1 : geometry::neighbor(c1, u2);
        const int x2 = b < 0 ? -1 : geometry::neighbor(b, u2);
        const int d = x2 < 0 ? -1 : geometry::neighbor(x2, u2);
        const int y2 = c < 0 ? -1 : geometry::neighbor(c, u1);
        if (x1 < 0 || b < 0 || c1 < 0 || c < 0 || x2 < 0 || d < 0 || y2 < 0) continue;
        if (geometry::neighbor(y2, u1) != d) continue;
        const std::array<int, 8> cells{a, x1, b, c1, c, x2, d, y2};
        if (std::any_of(cells.begin(), cells.end(), [](int cell) { return geometry::corner_of(cell) >= 0; })) continue;
        const bool low = std::max({a, b, c, d}) < 64;
        const bool high = std::min({a, b, c, d}) >= 64;
        if (!low && !high) continue;
        if ((low ? cases_low : cases_high) > 0) continue;
        Position pos = Position::empty(RuleSet::defaults());
        CHECK(pos.put(0, a).has_value());
        for (const int obstacle : {x1, c1, x2, y2}) CHECK(pos.put(1, obstacle).has_value());
        const std::vector<int> moves = legal(pos);
        const int target = Move::of(a, d);
        CHECK_EQ(std::count(moves.begin(), moves.end(), target), 1);
        std::vector<int> sorted = moves;
        std::sort(sorted.begin(), sorted.end());
        CHECK(std::adjacent_find(sorted.begin(), sorted.end()) == sorted.end());  // no duplicate move at all
        ++(low ? cases_low : cases_high);
    }
    CHECK_EQ(cases_low, 1);
    CHECK_EQ(cases_high, 1);
}

SH_TEST(copy_is_independent) {
    Position a = Position::initial(RuleSet::defaults());
    const std::vector<int> moves = legal(a);
    a.make(moves.front());
    Position b = a.copy();
    CHECK_EQ(b.hash(), a.hash());
    CHECK_EQ(b.ply(), 1);
    const std::vector<int> next = legal(b);
    b.make(next.front());
    CHECK_EQ(a.ply(), 1);
    CHECK_EQ(b.ply(), 2);
    CHECK(a.hash() != b.hash());
    b.unmake();
    b.unmake();
    CHECK_EQ(b.hash(), Position::initial(RuleSet::defaults()).hash());
}

SH_TEST(repetition_on_a_constructed_cycle) {
    const auto draw = RuleSet::defaults().with_repetition(RuleSet::Repetition::RepetitionIsDraw);
    CHECK(draw.has_value());
    for (const bool repetition_rule : {true, false}) {
        Position pos = Position::initial(repetition_rule ? *draw : RuleSet::defaults());
        std::array<std::array<int, 2>, 2> steps{};  // per player: from and to of a single step
        std::array<int, 4> played{};
        for (int ply = 0; ply < 2; ++ply) {
            const int p = pos.side_to_move();
            const std::vector<int> moves = legal(pos);
            int chosen = -1;
            for (const int m : moves) {
                bool adjacent = false;
                for (int d = 0; d < kDirections; ++d) adjacent = adjacent || geometry::neighbor(Move::from(m), d) == Move::to(m);
                if (adjacent) {
                    chosen = m;
                    break;
                }
            }
            CHECK(chosen >= 0);
            steps[static_cast<std::size_t>(p)] = {Move::from(chosen), Move::to(chosen)};
            played[static_cast<std::size_t>(ply)] = chosen;
            pos.make(chosen);
        }
        CHECK(!pos.outcome().has_value());
        for (int back = 0; back < 2; ++back) {
            const int p = pos.side_to_move();
            const int reverse = Move::of(steps[static_cast<std::size_t>(p)][1], steps[static_cast<std::size_t>(p)][0]);
            const std::vector<int> moves = legal(pos);
            CHECK(std::find(moves.begin(), moves.end(), reverse) != moves.end());
            pos.make(reverse);
            if (back == 0) CHECK(!pos.outcome().has_value());
        }
        CHECK_EQ(pos.hash(), Position::initial(RuleSet::defaults()).hash());
        const auto outcome = pos.outcome();
        if (repetition_rule) {
            CHECK(outcome.has_value());
            if (outcome) CHECK(outcome->reason == Outcome::Reason::Repetition);
            if (outcome) CHECK_EQ(outcome->winner, -1);
        } else {
            CHECK(!outcome.has_value());
        }
    }
}

SH_TEST(rule_sets_outside_the_port_are_refused) {
    RuleSet::Spec spec;
    CHECK(RuleSet::create(spec).has_value());
    RuleSet::Spec three = spec;
    three.players = 3;
    CHECK(!RuleSet::create(three).has_value());
    RuleSet::Spec leave = spec;
    leave.leave_goal = RuleSet::LeaveGoal::Allowed;
    CHECK(!RuleSet::create(leave).has_value());
    RuleSet::Spec blocking = spec;
    blocking.blocking_states = RuleSet::BlockingStates::Illegal;
    CHECK(!RuleSet::create(blocking).has_value());
    RuleSet::Spec ranking = spec;
    ranking.ranking = RuleSet::Ranking::FullRanking;
    CHECK(!RuleSet::create(ranking).has_value());
    RuleSet::Spec limit = spec;
    limit.move_limit = 0;
    CHECK(!RuleSet::create(limit).has_value());
    CHECK_EQ(RuleSet::defaults().move_limit(), 200);
    CHECK(RuleSet::defaults() == *RuleSet::create(spec));
}

SH_TEST(move_limit_and_draw_values) {
    const RuleSet score = *RuleSet::defaults().with_move_limit(4);
    const RuleSet plain = *score.with_draw_handling(RuleSet::DrawHandling::Draw);
    Position pos = Position::initial(score);
    Position pos_plain = Position::initial(plain);
    for (int i = 0; i < 4; ++i) {
        CHECK(!pos.outcome().has_value());
        const std::vector<int> moves = legal(pos);
        const int m = moves[moves.size() / 2];
        pos.make(m);
        pos_plain.make(m);
    }
    const auto outcome = pos.outcome();
    CHECK(outcome.has_value());
    if (!outcome) return;
    CHECK(outcome->reason == Outcome::Reason::MoveLimit);
    const double d0 = pos.distance_sum(0);
    const double d1 = pos.distance_sum(1);
    const double mean = (d0 + d1) / 2;
    CHECK_EQ(outcome->value(0), (mean - d0) / (10.0 * 13));
    CHECK_EQ(outcome->value(1), (mean - d1) / (10.0 * 13));
    CHECK_EQ(outcome->value(0) + outcome->value(1), 0.0);
    CHECK_EQ(pos_plain.outcome().value().value(0), 0.0);
    CHECK_EQ(pos_plain.outcome().value().value(1), 0.0);
    CHECK(pos.no_moves_outcome().reason == Outcome::Reason::NoMoves);
    CHECK(Outcome::win(1) == Outcome::win(1));
    CHECK_EQ(Outcome::win(1).value(0), -1.0);
    CHECK_EQ(Outcome::win(1).value(1), 1.0);
}

SH_TEST(blockade_rule_and_suicide_moves) {
    // Player 0's goal is the bottom corner, where player 1 starts. Nine cells of it are full (8 pieces of player 1, one of
    // player 0); the tenth is empty and a piece of player 1 stands next to it.
    const int goal = Layout::two_players().goal_corner(0);
    const auto goal_cells = geometry::corner_cells(goal);
    int empty_cell = -1;
    int neighbour = -1;
    for (const int c : goal_cells) {
        for (int d = 0; d < kDirections && neighbour < 0; ++d) {
            const int n = geometry::neighbor(c, d);
            if (n >= 0 && geometry::corner_of(n) < 0) {
                empty_cell = c;
                neighbour = n;
            }
        }
        if (neighbour >= 0) break;
    }
    CHECK(empty_cell >= 0);
    for (const RuleSet::SuicideMoves suicide : {RuleSet::SuicideMoves::Illegal, RuleSet::SuicideMoves::Legal}) {
        const RuleSet rules = *RuleSet::defaults().with_suicide_moves(suicide);
        Position pos = Position::empty(rules);
        bool own_placed = false;
        for (const int c : goal_cells) {
            if (c == empty_cell) continue;
            CHECK(pos.put(own_placed ? 1 : 0, c).has_value());
            own_placed = true;
        }
        CHECK(pos.put(1, neighbour).has_value());
        CHECK(pos.set_side_to_move(1).has_value());
        CHECK_EQ(pos.goal_occupied(0), 9);
        CHECK(!pos.has_won(0));
        const std::vector<int> moves = legal(pos);
        const bool completes = std::find(moves.begin(), moves.end(), Move::of(neighbour, empty_cell)) != moves.end();
        CHECK_EQ(completes, suicide == RuleSet::SuicideMoves::Legal);
        if (suicide == RuleSet::SuicideMoves::Legal) {
            pos.make(Move::of(neighbour, empty_cell));
            CHECK(pos.has_won(0));
            CHECK_EQ(outcome_text(pos.outcome()), std::string("WIN:0"));
            // With the plain rule a full goal needs ten own pieces
            const RuleSet plain = *rules.with_blockade_rule(RuleSet::BlockadeRule::None);
            Position q = Position::empty(plain);
            for (const int c : goal_cells) CHECK(q.put(1, c).has_value());
            CHECK(!q.has_won(0));
        }
    }
}

SH_TEST(setup_errors) {
    Position pos = Position::empty(RuleSet::defaults());
    CHECK(!pos.put(2, 0).has_value());
    CHECK(!pos.put(0, 200).has_value());
    CHECK(!pos.put(0, 9, 9).has_value());
    const int top = geometry::corner_cells(corner::kRNeg)[0];
    const int far = geometry::corner_cells(corner::kQPos)[0];  // not allowed for a two-player game
    CHECK(pos.put(0, top).has_value());
    CHECK(!pos.put(0, top).has_value());  // occupied
    CHECK(!pos.put(0, far).has_value());
    Position full = Position::initial(RuleSet::defaults());
    CHECK_EQ(full.piece_count(0), kPieces);
    const int centre = geometry::id(0, 0);
    CHECK(!full.put(0, centre).has_value());  // more than ten pieces
    const int m = legal(full).front();
    full.make(m);
    CHECK(!full.put(1, centre).has_value());  // setup after the first move
    CHECK(!full.set_side_to_move(0).has_value());
    CHECK(rules_position(RuleSet::defaults()).ply() == 0);
}

int main() { return sh::test::run_all(); }
