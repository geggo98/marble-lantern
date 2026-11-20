// Tests of the search on the 5-row board (RuleSet::TriangleRows::Five: 15-cell houses, 15 pieces; Java: TriangleRowsSearchTest):
//  - The Java golden file search/src/test/resources/triangle-rows-search-golden.tsv (path from CMake, SH_ROWS5_SEARCH_GOLDEN): the static
//    values (linear-r1, linear-r3 for two players, linear-r2 for three) and the move, score, depth and node count of searches with
//    1500 nodes, compared bit for bit with the Java engine. A missing file fails the test.
//  - The linear tables against the layout: the sums of a side and bestGain against references written from Layout alone.
//  - The race code and the incremental state, which know the 10-cell houses only, refuse Five; the specs and players name the house size.
//  - The futility near-win count scales with the house size.
#include <algorithm>
#include <array>
#include <cstdint>
#include <fstream>
#include <set>
#include <stdexcept>
#include <string>
#include <string_view>
#include <vector>

#include "alpha_beta.hpp"
#include "alpha_beta_player.hpp"
#include "check.hpp"
#include "geometry.hpp"
#include "layout.hpp"
#include "linear_eval.hpp"
#include "player_spec.hpp"
#include "position.hpp"
#include "race_adjudication.hpp"
#include "race_eval.hpp"
#include "rules.hpp"
#include "three_player_player.hpp"

using namespace sh;

namespace {

using Rows = RuleSet::TriangleRows;
using Camps = RuleSet::ForeignCamps;
using Jump = RuleSet::JumpRule;

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

int to_int(std::string_view s) { return std::stoi(std::string(s)); }

struct Row {
    int id = 0;
    int players = 0;
    Jump jump = Jump::Adjacent;
    Camps camps = Camps::Closed;
    int ply = 0;
    int side = 0;
    std::array<std::vector<int>, 3> pieces;
    std::vector<int> statics;
    std::vector<std::int64_t> search;  // empty: not searched
    RuleSet rules = RuleSet::defaults(2);
};

Camps camps_of(std::string_view s) { return s == "OPEN" ? Camps::Open : s == "PASS_THROUGH" ? Camps::PassThrough : Camps::Closed; }

std::vector<Row> read_golden() {
    std::vector<Row> rows;
    std::ifstream in(SH_ROWS5_SEARCH_GOLDEN);
    CHECK(in.good());
    std::string line;
    while (std::getline(in, line)) {
        if (line.empty() || line[0] == '#') continue;
        const auto f = split(line, '\t');
        CHECK_EQ(static_cast<int>(f.size()), 11);
        if (f.size() != 11) continue;
        Row r;
        r.id = to_int(f[0]);
        r.players = to_int(f[1]);
        r.jump = f[2] == "LONG" ? Jump::Long : Jump::Adjacent;
        r.camps = camps_of(f[3]);
        r.ply = to_int(f[4]);
        r.side = to_int(f[5]);
        for (std::size_t p = 0; p < 3; ++p) {
            if (f[6 + p] == "-") continue;
            for (const auto c : split(f[6 + p], ',')) r.pieces[p].push_back(to_int(c));
        }
        for (const auto v : split(f[9], ',')) r.statics.push_back(to_int(v));
        if (f[10] != "-") {
            for (const auto v : split(f[10], ',')) r.search.push_back(std::stoll(std::string(v)));
        }
        r.rules = *RuleSet::defaults(r.players, Rows::Five).with_jump_rule(r.jump)->with_foreign_camps(r.camps);
        rows.push_back(std::move(r));
    }
    return rows;
}

Position build(const Row& r) {
    Position p = Position::empty(r.rules);
    for (int q = 0; q < r.players; ++q) {
        for (const int c : r.pieces[static_cast<std::size_t>(q)]) CHECK(p.put(q, c).has_value());
    }
    CHECK(p.set_side_to_move(r.side).has_value());
    return p;
}

// The sums of the side p of the golden rows from the layout alone: pieces in the goal house, in the start house, the distance sum.
struct Sums {
    int goal = 0;
    int start = 0;
    int dsum = 0;
};

Sums reference_sums(const Position& pos, int p) {
    const Layout& layout = pos.layout();
    Sums s;
    for (const int c : pos.pieces(p)) {
        s.goal += layout.in_goal(p, c) ? 1 : 0;
        s.start += !layout.in_goal(p, c) && layout.in_start(p, c) ? 1 : 0;
        s.dsum += layout.distance(p, c);
    }
    return s;
}

// A piece of the house that is not in the 10-cell corner: the hexagon edge row (the cells that Five adds to a house).
bool on_edge_row(int cell) { return geometry::corner_of(cell) < 0; }

// One jump search per piece (a jump chain over any occupied cell onto a free allowed cell), distance gain floored at 0, capped at 6; the
// goal house of a player is left alone. Written from Layout::of(2, Five) and the neighbour tables only.
int ref_best_gain(const Position& pos, int p) {
    const Layout& layout = pos.layout();
    std::array<bool, kCells> occupied{};
    for (int c = 0; c < kCells; ++c) occupied[static_cast<std::size_t>(c)] = pos.owner(c) >= 0;
    int best = 0;
    for (const int from : pos.pieces(p)) {
        if (layout.in_goal(p, from)) continue;
        const int d0 = layout.distance(p, from);
        occupied[static_cast<std::size_t>(from)] = false;
        for (int dir = 0; dir < kDirections; ++dir) {
            const int a = geometry::neighbor(from, dir);
            if (a >= 0 && layout.allowed(p, a) && !occupied[static_cast<std::size_t>(a)]) best = std::max(best, d0 - layout.distance(p, a));
        }
        std::array<bool, kCells> seen{};
        std::vector<int> stack;
        const auto push = [&](int c) {
            for (int dir = 0; dir < kDirections; ++dir) {
                const int h = geometry::neighbor(c, dir);
                const int l = geometry::jump_landing(c, dir);
                if (h >= 0 && l >= 0 && occupied[static_cast<std::size_t>(h)] && layout.allowed(p, l) && !occupied[static_cast<std::size_t>(l)] &&
                    !seen[static_cast<std::size_t>(l)]) {
                    seen[static_cast<std::size_t>(l)] = true;
                    stack.push_back(l);
                }
            }
        };
        push(from);
        while (!stack.empty()) {
            const int c = stack.back();
            stack.pop_back();
            best = std::max(best, d0 - layout.distance(p, c));
            push(c);
        }
        occupied[static_cast<std::size_t>(from)] = true;
    }
    return std::min(best, 6);
}

}  // namespace

SH_TEST(static_values_and_searches_equal_the_java_golden) {
    const std::vector<Row> rows = read_golden();
    CHECK_EQ(static_cast<int>(rows.size()), 144);
    const auto r1 = LinearEvaluator::linear_r1();
    const auto r3 = LinearEvaluator::linear_r3();
    int searched = 0;
    int two_goal_edge = 0;
    int three_goal_edge = 0;
    int start_edge = 0;
    for (const Row& row : rows) {
        const Position pos = build(row);
        for (int q = 0; q < row.players; ++q) {
            for (const int c : pos.pieces(q)) {
                if (pos.layout().in_goal(q, c) && on_edge_row(c)) (row.players == 2 ? two_goal_edge : three_goal_edge)++;
                if (pos.layout().in_start(q, c) && on_edge_row(c)) ++start_edge;
            }
        }
        const std::string where = "row " + std::to_string(row.id);
        std::vector<int> mine;
        if (row.players == 2) {
            mine = {r1->evaluate(pos), r3->evaluate(pos)};
        } else {
            const auto spec = ThreePlayerSpec::parse(std::string("ab3:eval=linear-r2,rows=5,tt=14") + (row.jump == Jump::Long ? ",jump=long" : ""));
            CHECK(spec.has_value());
            const auto e = (*spec->create())->evaluation(pos);
            mine = {e[0], e[1], e[2]};
        }
        CHECK(mine == row.statics);
        if (mine != row.statics) ::sh::test::fail(__FILE__, __LINE__, where + ": static values differ");
        if (row.search.empty()) continue;
        ++searched;
        const std::string text = row.players == 2 ? "ab:eval=linear-r3,rows=5,tt=14" : "ab3:eval=linear-r2,rows=5,tt=14";
        const std::string full = text + (row.jump == Jump::Long ? ",jump=long" : "") + ",nodes=1500";
        std::vector<std::int64_t> got;
        if (row.players == 2) {
            const auto player = PlayerSpec::parse(full)->create();
            CHECK(player.has_value());
            const int move = (*player)->choose(pos);
            const SearchResult& res = *(*player)->last_result();
            got = {move, res.score, res.depth, res.nodes};
        } else {
            const auto player = ThreePlayerSpec::parse(full)->create();
            CHECK(player.has_value());
            const int move = (*player)->choose(pos);
            const SearchResult& res = *(*player)->last_result();
            got = {move, res.score, res.depth, res.nodes};
        }
        if (got != row.search) {
            ::sh::test::fail(__FILE__, __LINE__, where + ": search (move, score, depth, nodes) differs from the Java golden");
        }
    }
    CHECK(searched >= 40);
    // coverage floors: the golden positions reach the edge rows of both houses with both player counts
    CHECK(two_goal_edge > 20);
    CHECK(three_goal_edge > 5);
    CHECK(start_edge > 100);
}

SH_TEST(linear_sums_and_best_gain_agree_with_the_layout) {
    const std::vector<Row> rows = read_golden();
    int goal_edge_rows = 0;
    int capped = 0;
    int zero = 0;
    for (const Row& row : rows) {
        // bestGain looks at home cells only (a piece in a foreign corner, possible under OPEN, is not followed): Closed and PassThrough rows
        if (row.players != 2 || row.camps == Camps::Open) continue;
        const Position pos = build(row);
        for (int p = 0; p < 2; ++p) {
            linear_detail::SideSums s{};
            LinearEvaluator::side(pos, p, s);
            const Sums want = reference_sums(pos, p);
            CHECK_EQ(s[linear_detail::kGoal], want.goal);
            CHECK_EQ(s[linear_detail::kStart], want.start);
            CHECK_EQ(s[linear_detail::kDsum], want.dsum);
            CHECK_EQ(s[linear_detail::kCount], 15);
            const int got = LinearEvaluator::best_gain(pos, p);
            const int ref = ref_best_gain(pos, p);
            if (got != ref) ::sh::test::fail(__FILE__, __LINE__, "row " + std::to_string(row.id) + " player " + std::to_string(p) + ": best gain " + std::to_string(got) + " instead of " + std::to_string(ref));
            capped += ref == 6 ? 1 : 0;
            zero += ref == 0 ? 1 : 0;
            goal_edge_rows += want.goal > 10 ? 1 : 0;
        }
    }
    CHECK(capped > 5);
    CHECK(zero > 5);
    CHECK(goal_edge_rows > 0);  // a goal holds more than the 10 corner cells: pieces stand on the edge row
}

SH_TEST(a_piece_on_the_edge_row_counts_as_in_goal_or_in_start) {
    for (const int players : {2, 3}) {
        const Layout& layout = Layout::of(players, Rows::Five);
        Position pos = Position::empty(RuleSet::defaults(players, Rows::Five));
        int goal_edge = -1;
        int start_edge = -1;
        for (const int c : layout.goal_cells(0)) goal_edge = on_edge_row(c) && goal_edge < 0 ? c : goal_edge;
        for (const int c : layout.start_cells(0)) start_edge = on_edge_row(c) && start_edge < 0 ? c : start_edge;
        CHECK(goal_edge >= 0);
        CHECK(start_edge >= 0);
        CHECK(pos.put(0, goal_edge).has_value());
        CHECK(pos.put(0, start_edge).has_value());
        if (players == 2) {
            linear_detail::SideSums s{};
            LinearEvaluator::side(pos, 0, s);
            CHECK_EQ(s[linear_detail::kGoal], 1);
            CHECK_EQ(s[linear_detail::kStart], 1);
            CHECK_EQ(s[linear_detail::kDsum], layout.distance(0, goal_edge) + layout.distance(0, start_edge));
        } else {
            // with three players the static value counts the same two cells: a goal-edge piece scores better than the same piece on the start-edge cell
            Position goal_only = Position::empty(RuleSet::defaults(3, Rows::Five));
            Position start_only = Position::empty(RuleSet::defaults(3, Rows::Five));
            CHECK(goal_only.put(0, goal_edge).has_value());
            CHECK(start_only.put(0, start_edge).has_value());
            const auto player = ThreePlayerSpec::parse("ab3:eval=linear-r2,rows=5,tt=10,nodes=10")->create();
            CHECK((*player)->evaluation(goal_only)[0] > (*player)->evaluation(start_only)[0]);
        }
    }
}

SH_TEST(race_code_and_incremental_state_refuse_five_rows) {
    const RuleSet four = RuleSet::defaults(2);
    const RuleSet five = RuleSet::defaults(2, Rows::Five);
    CHECK(RaceEvaluator::rules_apply(four));
    CHECK(!RaceEvaluator::rules_apply(five));
    CHECK(RaceAdjudication::applicable(four));
    CHECK(!RaceAdjudication::applicable(five));
    // Ten pieces each, the armies passed (every piece of player 0 on a larger row than every piece of player 1): passed on the 4-row board; with Five only the rule gate refuses it
    // (the piece counts would also be 10, because Position::empty takes up to 15).
    for (const RuleSet& rules : {four, five}) {
        Position pos = Position::empty(rules);
        int placed0 = 0;
        int placed1 = 0;
        for (int c = 0; c < kCells; ++c) {
            if (geometry::corner_of(c) >= 0) continue;
            if (geometry::r(c) >= 1 && placed0 < 10) { CHECK(pos.put(0, c).has_value()); ++placed0; }
            if (geometry::r(c) <= -1 && placed1 < 10) { CHECK(pos.put(1, c).has_value()); ++placed1; }
        }
        CHECK_EQ(placed0, 10);
        CHECK_EQ(placed1, 10);
        const bool is_four = rules.triangle_rows() == Rows::Four;
        CHECK_EQ(RaceAdjudication::passed(pos), is_four);
    }
    // the incremental state follows the 4-row start position and refuses the 5-row one, also with 10 pieces per side (which its tables
    // and jump graph could otherwise take in) and a position that is not at the start
    const auto linear = LinearEvaluator::linear_r3();
    CHECK(linear->incremental()->reset(Position::initial(four)));
    CHECK(!linear->incremental()->reset(Position::initial(five)));
    Position ten = Position::empty(five);
    int placed = 0;
    for (const int c : Layout::of(2, Rows::Five).start_cells(0)) {
        if (placed++ < 10) CHECK(ten.put(0, c).has_value());
    }
    placed = 0;
    for (const int c : Layout::of(2, Rows::Five).start_cells(1)) {
        if (placed++ < 10) CHECK(ten.put(1, c).has_value());
    }
    CHECK_EQ(ten.piece_count(0), 10);
    CHECK(!linear->incremental()->reset(ten));
}

SH_TEST(specs_and_players_name_the_house_size) {
    CHECK(PlayerSpec::parse(kBrowserRows5Spec).has_value());
    CHECK(PlayerSpec::parse(kBrowserRows5LongSpec).has_value());
    CHECK(PlayerSpec::parse(kBrowserRows5Spec)->rows == Rows::Five);
    CHECK(PlayerSpec::parse("ab:eval=linear-r3")->rows == Rows::Four);
    CHECK(PlayerSpec::parse("ab:eval=linear-r3,rows=4")->rows == Rows::Four);
    const auto bad = PlayerSpec::parse("ab:eval=linear-r3,rows=6");
    CHECK(!bad.has_value());
    if (!bad) CHECK(bad.error().find("rows must be 4 or 5") != std::string::npos);
    for (const char* text : {"ab:eval=linear-r3,rows=5,race=1", "ab:eval=linear-r3,rows=5,race=2", "ab:eval=racelin-r3,rows=5", "ab:eval=racelin1-r3,rows=5"}) {
        const auto refused = PlayerSpec::parse(text);
        CHECK(!refused.has_value());
        if (!refused) CHECK(refused.error().find("rows=5 cannot be combined") != std::string::npos);
    }
    CHECK(ThreePlayerSpec::parse(kBrowser3pRows5Spec)->rows == Rows::Five);
    CHECK(ThreePlayerSpec::parse(kBrowser3pRows5LongSpec)->rows == Rows::Five);
    CHECK(ThreePlayerSpec::parse("ab3:eval=linear-r2")->rows == Rows::Four);
    CHECK(!ThreePlayerSpec::parse("ab3:rows=7").has_value());

    // a player plays its own house size only
    const auto two5 = PlayerSpec::parse("ab:eval=linear-r3,rows=5,nodes=50,tt=10")->create();
    const auto two4 = PlayerSpec::parse("ab:eval=linear-r3,nodes=50,tt=10")->create();
    CHECK(two5.has_value() && two4.has_value());
    const Position p4 = Position::initial(RuleSet::defaults(2));
    const Position p5 = Position::initial(RuleSet::defaults(2, Rows::Five));
    CHECK((*two5)->plays(p5));
    CHECK(!(*two5)->plays(p4));
    CHECK((*two4)->plays(p4));
    CHECK(!(*two4)->plays(p5));
    CHECK((*two5)->choose(p5) >= 0);
    bool refused = false;
    try {
        (*two5)->choose(p4);
    } catch (const std::invalid_argument& e) {
        refused = std::string(e.what()).find("rows=5") != std::string::npos && std::string(e.what()).find("rows=4") != std::string::npos;
    }
    CHECK(refused);
    refused = false;
    try {
        (*two4)->choose(p5);
    } catch (const std::invalid_argument&) {
        refused = true;
    }
    CHECK(refused);

    const auto three5 = ThreePlayerSpec::parse("ab3:eval=linear-r2,rows=5,nodes=50,tt=10")->create();
    const auto three4 = ThreePlayerSpec::parse("ab3:eval=linear-r2,nodes=50,tt=10")->create();
    const Position t4 = Position::initial(RuleSet::defaults(3));
    const Position t5 = Position::initial(RuleSet::defaults(3, Rows::Five));
    CHECK((*three5)->plays(t5) && !(*three5)->plays(t4));
    CHECK((*three4)->plays(t4) && !(*three4)->plays(t5));
    CHECK((*three5)->choose(t5) >= 0);
    refused = false;
    try {
        (*three5)->choose(t4);
    } catch (const std::invalid_argument& e) {
        refused = std::string(e.what()).find("rows=5") != std::string::npos;
    }
    CHECK(refused);
    refused = false;
    try {
        (*three4)->evaluation(t5);
    } catch (const std::invalid_argument&) {
        refused = true;
    }
    CHECK(refused);
}

SH_TEST(futility_near_win_count_scales_with_the_goal_size) {
    const auto eval = LinearEvaluator::linear_r3();
    const Layout& layout = Layout::of(2, Rows::Five);
    const std::span<const int> goal = layout.goal_cells(0);
    CHECK_EQ(static_cast<int>(goal.size()), 15);
    const auto position_with = [&](int in_goal) {
        Position pos = Position::empty(RuleSet::defaults(2, Rows::Five));
        for (int i = 0; i < in_goal; ++i) CHECK(pos.put(0, goal[static_cast<std::size_t>(i)]).has_value());
        // the rest of player 0 in its start house, player 1 on 15 cells of the central hexagon
        const std::span<const int> start = layout.start_cells(0);
        for (int i = 0; i < 15 - in_goal; ++i) CHECK(pos.put(0, start[static_cast<std::size_t>(i)]).has_value());
        int placed = 0;
        for (int c = 0; c < kCells && placed < 15; ++c) {
            if (geometry::corner_of(c) < 0 && pos.owner(c) < 0 && !layout.in_goal(0, c) && !layout.in_start(0, c)) {
                CHECK(pos.put(1, c).has_value());
                ++placed;
            }
        }
        CHECK_EQ(placed, 15);
        return pos;
    };
    // depth 2: a goal holding 13 or more of 15 is near a win (no futility cut); 12 is not, although it is above the 4-row count 10 - 2 = 8
    CHECK(!AlphaBeta::futility_allowed(position_with(13), 2, 0, *eval));
    CHECK(AlphaBeta::futility_allowed(position_with(12), 2, 0, *eval));
    CHECK(AlphaBeta::futility_allowed(position_with(9), 2, 0, *eval));
    // an ordinary alpha is required
    CHECK(!AlphaBeta::futility_allowed(position_with(9), 2, scores::kMate - 5, *eval));
}

int main() { return ::sh::test::run_all(); }
