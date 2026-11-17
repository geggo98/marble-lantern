// Tests of the search (WP5; WP6 adds race play). The golden files are the Java fingerprints (cpp/golden/fingerprint-*.txt) and the
// reference positions (reference.tsv); without STERNHALMA_GOLDEN_REQUIRED=1 a missing golden file skips its test, the constructed
// cases always run. The race table of racelin1-r3 comes from STERNHALMA_TABLE_DIR or tablebase/tables.
#include <algorithm>
#include <array>
#include <iostream>
#include <cstdint>
#include <memory>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

#include "alpha_beta.hpp"
#include "alpha_beta_player.hpp"
#include "check.hpp"
#include "fingerprint.hpp"
#include "geometry.hpp"
#include "golden.hpp"
#include "golden_test_support.hpp"
#include "linear_eval.hpp"
#include "options.hpp"
#include "parse_number.hpp"
#include "player_spec.hpp"
#include "position.hpp"
#include "race_eval.hpp"
#include "race_move.hpp"
#include "solitaire.hpp"
#include "scores.hpp"

using namespace sh;

namespace {

std::vector<std::string_view> split_text(std::string_view text, char sep) {
    std::vector<std::string_view> parts;
    std::size_t start = 0;
    while (true) {
        const std::size_t at = text.find(sep, start);
        if (at == std::string_view::npos) {
            parts.push_back(text.substr(start));
            return parts;
        }
        parts.push_back(text.substr(start, at - start));
        start = at + 1;
    }
}

constexpr std::size_t u(int i) { return static_cast<std::size_t>(i); }

PlayerSpec parse_ok(std::string_view text) {
    auto spec = PlayerSpec::parse(text);
    CHECK(spec.has_value());
    if (!spec) return PlayerSpec{};
    return *spec;
}

std::string parse_error(std::string_view text) {
    const auto spec = PlayerSpec::parse(text);
    CHECK(!spec.has_value());
    return spec ? std::string() : spec.error();
}

// Player 0 holds nine cells of its goal corner and a piece next to the tenth: one step wins. Player 1 stands in the centre.
struct MateInOne {
    Position pos = Position::empty(RuleSet::defaults());
    int from = -1;
    int to = -1;
};

MateInOne mate_in_one() {
    MateInOne m;
    const Layout& layout = m.pos.layout();
    const auto goal = geometry::corner_cells(layout.goal_corner(0));
    for (const int c : goal) {
        if (geometry::r(c) == 5 && m.to < 0) m.to = c;
    }
    for (int d = 0; d < kDirections && m.from < 0; ++d) {
        const int n = geometry::neighbor(m.to, d);
        if (n >= 0 && geometry::r(n) == 4) m.from = n;
    }
    for (const int c : goal) {
        if (c != m.to) CHECK(m.pos.put(0, c).has_value());
    }
    CHECK(m.pos.put(0, m.from).has_value());
    int placed = 0;
    for (int c = 0; c < kCells && placed < kPieces; ++c) {
        if (geometry::corner_of(c) < 0 && geometry::r(c) >= -1 && geometry::r(c) <= 1 && m.pos.owner(c) < 0) {
            CHECK(m.pos.put(1, c).has_value());
            ++placed;
        }
    }
    CHECK_EQ(placed, kPieces);
    CHECK(m.pos.set_side_to_move(0).has_value());
    return m;
}

// Both players stand in the central hexagon, nobody near a goal: an ordinary position.
Position centre_position(const RuleSet& rules) {
    Position pos = Position::empty(rules);
    int zero = 0;
    int one = 0;
    for (int c = 0; c < kCells; ++c) {
        if (geometry::corner_of(c) >= 0) continue;
        if (geometry::r(c) <= -1 && geometry::r(c) >= -3 && zero < kPieces) {
            CHECK(pos.put(0, c).has_value());
            ++zero;
        } else if (geometry::r(c) >= 1 && geometry::r(c) <= 3 && one < kPieces) {
            CHECK(pos.put(1, c).has_value());
            ++one;
        }
    }
    CHECK_EQ(zero, kPieces);
    CHECK_EQ(one, kPieces);
    return pos;
}

std::unique_ptr<AlphaBeta> searcher(std::string_view spec, bool verify = false) {
    const PlayerSpec p = parse_ok(spec);
    auto eval = p.make_evaluator();
    CHECK(eval.has_value());
    if (!eval) return nullptr;
    return std::make_unique<AlphaBeta>(std::move(*eval), p.table_bits, true, p.options, verify);
}

constexpr std::string_view kBestOptions = "pvs=1,killers=1,history=1,lmr=2,asp=1,ttrep=1,mate=1,partial=1";

}  // namespace

// ---- scores, options, spec ----

SH_TEST(score_bands) {
    CHECK(scores::kind(0) == scores::Kind::Ordinary);
    CHECK(scores::kind(4999) == scores::Kind::Ordinary);
    CHECK(scores::kind(-4999) == scores::Kind::Ordinary);
    CHECK(scores::kind(5000) == scores::Kind::Race);
    CHECK(scores::kind(-5000) == scores::Kind::Race);
    CHECK(scores::kind(scores::kMateBound - 1) == scores::Kind::Race);
    CHECK(scores::kind(scores::kMateBound) == scores::Kind::Mate);
    CHECK(scores::kind(-scores::kMateBound) == scores::Kind::Mate);
    CHECK(scores::kind(AlphaBeta::kMate - 3) == scores::Kind::Mate);
    CHECK_EQ(scores::kRaceBase, RaceEvaluator::kRaceBase);
    CHECK_EQ(std::string(scores::kind_name(scores::Kind::Race)), std::string("RACE"));
}

SH_TEST(options_rules) {
    SearchOptions bad;
    bad.lmr = 4;
    CHECK(!SearchOptions::create(bad).has_value());
    bad = {};
    bad.aspiration = -1;
    CHECK(!SearchOptions::create(bad).has_value());
    bad = {};
    bad.futility2 = -1;
    CHECK(!SearchOptions::create(bad).has_value());
    // the futility gain is normalised away while futility is off, so that == ignores it
    SearchOptions off;
    off.futility_gain = 99;
    CHECK(SearchOptions::create(off).value() == SearchOptions{});
    SearchOptions on;
    on.futility = 1;
    on.futility_gain = 20;
    CHECK_EQ(SearchOptions::create(on).value().futility_gain, 20);
    CHECK(SearchOptions{}.is_default());
    CHECK(SearchOptions{}.exact_root());
    CHECK(!on.exact_root());
    CHECK(on.exact().exact_root());
    SearchOptions lmr;
    lmr.lmr = 1;
    CHECK(!lmr.exact_root());
    CHECK_EQ(SearchOptions{}.describe(), std::string());
    CHECK_EQ(parse_ok("ab:eval=linear-r1," + std::string(kBestOptions)).options.describe(),
             std::string("pvs,killers,history,lmr=2,asp=50,ttrep,mate,partial"));
    CHECK_EQ(on.describe(), std::string("fut=1,futk=20"));
}

SH_TEST(player_spec_parse) {
    const PlayerSpec r2 = parse_ok(fingerprint::kR2Spec);
    CHECK_EQ(r2.evaluator, std::string("linear-r1"));
    CHECK_EQ(r2.nodes, 20000);
    CHECK_EQ(r2.depth, 30);
    CHECK_EQ(r2.table_bits, 18);
    CHECK(r2.options.pvs && r2.options.killers && r2.options.history && r2.options.depth_preferred && r2.options.mate_fix &&
          r2.options.partial);
    CHECK_EQ(r2.options.lmr, 2);
    CHECK_EQ(r2.options.aspiration, SearchOptions::kDefaultAspiration);
    CHECK_EQ(r2.race, 0);
    const PlayerSpec best = parse_ok(fingerprint::kBestSpec);
    CHECK_EQ(best.evaluator, std::string("racelin1-r3"));
    CHECK_EQ(best.options.futility, 1);
    CHECK_EQ(best.options.futility_gain, 20);
    CHECK_EQ(best.options.futility2, 1);
    CHECK_EQ(best.race, 1);
    CHECK_EQ(best.race_rows, 1);
    // an explicit half-width, blanks, a later key wins, a trailing comma is dropped
    const PlayerSpec p = parse_ok("ab:eval=linear-r3, asp=77 ,tt=12,depth=5,nodes=100,nodes=300,");
    CHECK_EQ(p.options.aspiration, 77);
    CHECK_EQ(p.table_bits, 12);
    CHECK_EQ(p.depth, 5);
    CHECK_EQ(p.nodes, 300);
    CHECK_EQ(parse_ok("ab:eval=linear-r1").nodes, 20000);
    CHECK_EQ(parse_ok("ab:eval=linear-r1,racerows=2,race=2").race_rows, 2);
}

SH_TEST(player_spec_errors) {
    CHECK(parse_error("ab:eval=linear-r1,bogus=1").find("unknown options [bogus]") != std::string::npos);
    CHECK(parse_error("ab:eval=linear-r1,ms=100").find("unknown options [ms]") != std::string::npos);
    CHECK(parse_error("ab:eval=distance").find("not ported") != std::string::npos);
    CHECK(parse_error("ab:").find("not ported") != std::string::npos);  // the default evaluator of Java is distance
    CHECK(parse_error("ab:eval=linear-r2").find("not ported") != std::string::npos);
    CHECK(parse_error("ab:eval=stage4").find("unknown evaluator") != std::string::npos);
    CHECK(parse_error("mcts:eval=linear-r1").find("unknown player kind") != std::string::npos);
    CHECK(parse_error("ab:eval=linear-r1,pvs").find("expected key=value") != std::string::npos);
    CHECK(parse_error("ab:eval=linear-r1,pvs=2").find("pvs must be 0 or 1") != std::string::npos);
    CHECK(parse_error("ab:eval=linear-r1,lmr=4").find("lmr must be") != std::string::npos);
    CHECK(parse_error("ab:eval=linear-r1,nodes=abc").find("nodes must be a number") != std::string::npos);
    CHECK(parse_error("ab:eval=linear-r1,racerows=1").find("racerows needs race") != std::string::npos);
    CHECK(parse_error("ab:eval=linear-r1,race=1,racerows=3").find("racerows must be 1 or 2") != std::string::npos);
    CHECK(parse_error("ab:eval=linear-r1,race=3").find("race must be") != std::string::npos);
    CHECK(parse_error("ab:eval=linear-r1,tt=31").find("tt must be") != std::string::npos);
    CHECK(parse_error("ab:eval=linear-r1,depth=65").find("depth must be") != std::string::npos);
    CHECK(parse_error("ab:eval=linear-r1,futk=-1").find("fut, fut2 and futk") != std::string::npos);
    // the Java option racetable is not ported
    CHECK(parse_error("ab:eval=linear-r1,race=1,racetable=0").find("unknown options") != std::string::npos);
}

SH_TEST(with_nodes_replaces_the_budget) {
    CHECK_EQ(fingerprint::with_nodes("ab:eval=linear-r1,nodes=20000,pvs=1", 1000), std::string("ab:eval=linear-r1,pvs=1,nodes=1000"));
    CHECK_EQ(fingerprint::with_nodes("ab:eval=linear-r1", 5000), std::string("ab:eval=linear-r1,nodes=5000"));
    CHECK_EQ(fingerprint::with_nodes("ab:", 7), std::string("ab:nodes=7"));
}

// ---- pieces of the search ----

SH_TEST(table_scores_of_mate) {
    CHECK_EQ(AlphaBeta::to_table(AlphaBeta::kMate - 3, 5), AlphaBeta::kMate + 2);
    CHECK_EQ(AlphaBeta::to_table(-(AlphaBeta::kMate - 3), 5), -(AlphaBeta::kMate + 2));
    CHECK_EQ(AlphaBeta::to_table(123, 5), 123);
    CHECK_EQ(AlphaBeta::to_table(-5000, 5), -5000);
    for (const int s : {AlphaBeta::kMate - 3, -(AlphaBeta::kMate - 40), 0, 77, -5000, 5000}) {
        for (int h = 0; h < 10; ++h) CHECK_EQ(AlphaBeta::from_table(AlphaBeta::to_table(s, h), h), s);
    }
}

SH_TEST(late_move_reduction_tables) {
    for (int mode = 1; mode <= 3; ++mode) {
        for (int d = 1; d <= AlphaBeta::kMaxDepth; d += 7) {
            for (int i = 1; i < kMaxMoves; i += 37) {
                const int r = AlphaBeta::lmr_reduction(mode, d, i);
                CHECK(r >= 1);
                CHECK(r <= std::max(1, d - 2));
                CHECK(AlphaBeta::lmr_reduction(3, d, i) >= AlphaBeta::lmr_reduction(2, d, i));
            }
        }
    }
    // worked by hand: 0.5 + ln 10 ln 20 / 2 = 3.95 -> 3; 0.5 + ln 10 ln 20 / 1.25 = 6.02 -> 6; mode 1 always reduces by one
    CHECK_EQ(AlphaBeta::lmr_reduction(2, 10, 20), 3);
    CHECK_EQ(AlphaBeta::lmr_reduction(3, 10, 20), 6);
    CHECK_EQ(AlphaBeta::lmr_reduction(1, 10, 20), 1);
    // the cap d - 2 and the floor of one ply
    CHECK_EQ(AlphaBeta::lmr_reduction(3, 4, 1000), 2);
    CHECK_EQ(AlphaBeta::lmr_reduction(3, 3, 1000), 1);
    CHECK_EQ(AlphaBeta::lmr_reduction(2, 1, 5), 1);
}

SH_TEST(pick_is_a_stable_selection_sort) {
    AlphaBeta::MoveBuffer moves{};
    AlphaBeta::MoveBuffer keys{};
    const std::array<int, 6> k = {5, 9, 9, 1, 9, 5};
    for (int i = 0; i < 6; ++i) {
        moves[u(i)] = 100 + i;
        keys[u(i)] = k[u(i)];
    }
    std::vector<int> order;
    for (int i = 0; i < 6; ++i) {
        AlphaBeta::pick(moves, keys, i, 6);
        order.push_back(moves[u(i)]);
    }
    // ties keep the earliest of equal keys: the three 9s come in the order 101, 102, 104; then the two 5s as 100 (swapped by the
    // selection steps) - the order is that of the Java selection sort, which is not a stable sort of the whole array
    CHECK_EQ(order[0], 101);
    CHECK_EQ(order[1], 102);
    CHECK_EQ(order[2], 104);
    // reference run of the same algorithm written out on plain vectors
    std::vector<int> m = {100, 101, 102, 103, 104, 105};
    std::vector<int> kk(k.begin(), k.end());
    std::vector<int> expected;
    for (std::size_t i = 0; i < 6; ++i) {
        std::size_t best = i;
        for (std::size_t j = i + 1; j < 6; ++j) {
            if (kk[j] > kk[best]) best = j;
        }
        std::swap(m[i], m[best]);
        std::swap(kk[i], kk[best]);
        expected.push_back(m[i]);
    }
    CHECK(order == expected);
}

SH_TEST(terminal_scores) {
    MateInOne m = mate_in_one();
    m.pos.make(Move::of(m.from, m.to));
    CHECK(m.pos.outcome().has_value());
    // player 1 is to move and has lost: its score is -(MATE - height); from player 0's view it is +
    CHECK_EQ(m.pos.side_to_move(), 1);
    CHECK_EQ(AlphaBeta::terminal_score(m.pos, 3), -(AlphaBeta::kMate - 3));
    CHECK_EQ(AlphaBeta::terminal_score(m.pos, 0), -AlphaBeta::kMate);
}

SH_TEST(futility_rules) {
    const auto eval_owner = LinearEvaluator::linear_r1();
    LinearEvaluator& eval = *eval_owner;
    Position start = centre_position(RuleSet::defaults());
    CHECK(AlphaBeta::futility_allowed(start, 1, 0, eval));
    CHECK(AlphaBeta::futility_allowed(start, 2, -4999, eval));
    // alpha outside the ordinary band switches it off: a race value or a mate
    CHECK(!AlphaBeta::futility_allowed(start, 1, 5000, eval));
    CHECK(!AlphaBeta::futility_allowed(start, 1, -AlphaBeta::kMate + 5, eval));
    // near a win: nine of ten goal cells filled
    MateInOne m = mate_in_one();
    CHECK(!AlphaBeta::futility_allowed(m.pos, 1, 0, eval));
    // near the move limit
    const RuleSet near_limit = RuleSet::defaults().with_move_limit(2).value();
    Position limited = centre_position(near_limit);
    CHECK(!AlphaBeta::futility_allowed(limited, 2, 0, eval));
    CHECK(AlphaBeta::futility_allowed(limited, 1, 0, eval));
    // repetition draws
    const RuleSet repeat = RuleSet::defaults().with_repetition(RuleSet::Repetition::RepetitionIsDraw).value();
    Position repeating = centre_position(repeat);
    CHECK(!AlphaBeta::futility_allowed(repeating, 1, 0, eval));
}

// ---- constructed searches ----

SH_TEST(finds_the_mate_in_one) {
    const MateInOne m = mate_in_one();
    for (const std::string_view spec : {"ab:eval=linear-r1", "ab:eval=linear-r1,pvs=1,killers=1,history=1,lmr=2,asp=1,ttrep=1,mate=1,partial=1",
                                        "ab:eval=linear-r3,pvs=1,killers=1,history=1,lmr=2,asp=1,ttrep=1,mate=1,partial=1,fut=1,futk=20,fut2=1"}) {
        const auto ab = searcher(spec);
        if (!ab) return;
        const SearchResult r = ab->search(m.pos, 6);
        CHECK_EQ(r.move, Move::of(m.from, m.to));
        CHECK_EQ(r.score, AlphaBeta::kMate - 1);
        CHECK_EQ(r.depth, 1);  // a known mate ends the iterative deepening
        CHECK(r.complete);
    }
}

SH_TEST(search_without_moves_or_with_a_finished_game) {
    MateInOne m = mate_in_one();
    m.pos.make(Move::of(m.from, m.to));  // the game is over now
    const auto ab = searcher("ab:eval=linear-r1");
    if (!ab) return;
    const SearchResult r = ab->search(m.pos, 3);
    CHECK_EQ(r.move, -1);
    CHECK_EQ(r.score, -AlphaBeta::kMate);
    CHECK_EQ(r.nodes, 0);
    CHECK_EQ(r.depth, 0);
}

SH_TEST(depth_bounds) {
    const auto ab = searcher("ab:eval=linear-r1");
    if (!ab) return;
    const Position start = Position::initial(RuleSet::defaults());
    bool thrown = false;
    try {
        (void)ab->search(start, 0);
    } catch (const std::invalid_argument&) {
        thrown = true;
    }
    CHECK(thrown);
    thrown = false;
    try {
        (void)ab->search(start, AlphaBeta::kMaxDepth + 1);
    } catch (const std::invalid_argument&) {
        thrown = true;
    }
    CHECK(thrown);
    thrown = false;
    try {
        AlphaBeta bad(LinearEvaluator::linear_r1(), 31, true);
    } catch (const std::invalid_argument&) {
        thrown = true;
    }
    CHECK(thrown);
}

SH_TEST(search_is_deterministic_and_clear_resets) {
    const Position start = Position::initial(RuleSet::defaults());
    const std::string spec = "ab:eval=linear-r1," + std::string(kBestOptions);
    const auto a = searcher(spec);
    const auto b = searcher(spec);
    if (!a || !b) return;
    const SearchResult first = a->search(start, 4, AlphaBeta::kNoLimit, 5000);
    CHECK(first == b->search(start, 4, AlphaBeta::kNoLimit, 5000));
    CHECK(first.nodes <= 5000);
    CHECK(first.depth >= 1);
    // a second search keeps the table; after clear() the tree is the one of a fresh searcher again
    (void)a->search(start, 4, AlphaBeta::kNoLimit, 5000);
    a->clear();
    CHECK(first == a->search(start, 4, AlphaBeta::kNoLimit, 5000));
}

SH_TEST(node_limit_stops_the_search) {
    const Position start = Position::initial(RuleSet::defaults());
    const auto ab = searcher("ab:eval=linear-r1,pvs=1,killers=1,history=1,ttrep=1,mate=1");
    if (!ab) return;
    const SearchResult r = ab->search(start, 10, AlphaBeta::kNoLimit, 3000);
    CHECK(r.nodes <= 3000);
    CHECK(!r.complete);
    CHECK(r.depth >= 1);
    CHECK(r.depth < 10);
    CHECK_EQ(ab->nodes(), r.nodes);
    // without a limit the requested depth completes
    const SearchResult full = ab->search(start, 2);
    CHECK(full.complete);
    CHECK_EQ(full.depth, 2);
}

SH_TEST(player_counts_nodes_and_moves) {
    const PlayerSpec spec = parse_ok("ab:eval=linear-r1,pvs=1,killers=1,nodes=500");
    auto player = spec.create();
    CHECK(player.has_value());
    if (!player) return;
    Position pos = Position::initial(RuleSet::defaults());
    CHECK(!(*player)->last_score_kind().has_value());
    const int m1 = (*player)->choose(pos);
    pos.make(m1);
    const int m2 = (*player)->choose(pos);
    CHECK(m1 >= 0 && m2 >= 0);
    CHECK_EQ((*player)->moves(), 2);
    CHECK(static_cast<bool>((*player)->last_result()));
    CHECK(((*player)->total_nodes()) > 0 && (*player)->total_nodes() <= 1000);
    CHECK(((*player)->last_score_kind()) == scores::Kind::Ordinary);
    CHECK_EQ((*player)->race_moves(), 0);
    CHECK_EQ((*player)->name(), std::string("ab[eval=linear-r1, depth<=30, 500 nodes, pvs,killers]"));
    (*player)->clear_search();
    CHECK(!(*player)->last_result().has_value());
    CHECK_EQ((*player)->moves(), 2);  // the counters keep counting
}

// ---- tests on the reference positions ----

SH_TEST(incremental_state_agrees_in_the_search) {
    const auto reference = fingerprint::load_reference();
    if (!reference) {
        if (golden::required()) {
            CHECK(reference.has_value());
        } else {
            ::sh::test::skip(reference.error());
        }
        return;
    }
    // The assert mode compares every evaluation with the full one and checks the sums after every move; the search must give the
    // same result as without it.
    for (const std::string_view spec :
         {"ab:eval=linear-r1,pvs=1,killers=1,history=1,lmr=2,asp=1,ttrep=1,mate=1,partial=1",
          "ab:eval=racelin1-r3,pvs=1,killers=1,history=1,lmr=2,asp=1,ttrep=1,mate=1,partial=1,fut=1,futk=20,fut2=1"}) {
        for (const std::size_t index : {std::size_t{3}, std::size_t{15}, std::size_t{40}, std::size_t{62}}) {
            const auto pos = fingerprint::replay(reference->positions[index]);
            CHECK(pos.has_value());
            if (!pos) return;
            const auto plain = searcher(spec);
            const auto verified = searcher(spec, true);
            if (!plain || !verified) return;
            const SearchResult a = plain->search(*pos, 5, AlphaBeta::kNoLimit, 2500);
            const SearchResult b = verified->search(*pos, 5, AlphaBeta::kNoLimit, 2500);
            CHECK(a == b);
        }
    }
}

SH_TEST(root_scores_agree_with_the_search) {
    const auto reference = fingerprint::load_reference();
    if (!reference) {
        if (golden::required()) {
            CHECK(reference.has_value());
        } else {
            ::sh::test::skip(reference.error());
        }
        return;
    }
    const std::string spec = "ab:eval=linear-r1,pvs=1,killers=1,history=1,ttrep=1,mate=1";  // exact: no lmr, no futility, no aspiration
    for (const std::size_t index : {std::size_t{0}, std::size_t{9}, std::size_t{26}, std::size_t{50}}) {
        const auto pos = fingerprint::replay(reference->positions[index]);
        CHECK(pos.has_value());
        if (!pos) return;
        const auto a = searcher(spec);
        const auto b = searcher(spec);
        if (!a || !b) return;
        const std::vector<int> root = a->root_scores(*pos, 3);
        std::array<int, kMaxMoves> legal{};
        Position copy = pos->copy();
        const int n = copy.generate(legal);
        CHECK_EQ(static_cast<int>(root.size()), n);
        const SearchResult r = b->search(*pos, 3);
        CHECK_EQ(r.depth, 3);
        CHECK_EQ(r.score, *std::ranges::max_element(root));
        // the root scores do not depend on the searcher's earlier calls
        CHECK(a->root_scores(*pos, 3) == root);
    }
    bool thrown = false;
    try {
        const auto lmr = searcher("ab:eval=linear-r1,lmr=2");
        if (lmr) (void)lmr->root_scores(Position::initial(RuleSet::defaults()), 2);
    } catch (const std::logic_error&) {
        thrown = true;
    }
    CHECK(thrown);
}

// ---- the Java fingerprints ----

namespace {

// Compares what we print with the golden lines from `first` on, and reports the first differing line.
void compare_lines(const std::vector<std::string>& golden_lines, std::size_t first, const std::vector<std::string>& ours, const std::string& what) {
    for (std::size_t i = 0; i < ours.size(); ++i) {
        const std::size_t at = first + i;
        if (at >= golden_lines.size() || golden_lines[at] != ours[i]) {
            ::sh::test::count_check();
            ::sh::test::fail(__FILE__, __LINE__,
                             what + ": first divergence at golden line " + std::to_string(at + 1) + "\n  golden: " +
                                 (at < golden_lines.size() ? golden_lines[at] : std::string("<end of file>")) + "\n  ours:   " + ours[i]);
            return;
        }
        ::sh::test::count_check();
    }
}

// The positions part: the printed lines of Fingerprint.main for the spec (without the spec line), as "  <line>".
void check_positions(const std::string& file, std::string_view spec) {
    const auto golden_lines = golden::lines_or_skip(file);
    const auto reference = fingerprint::load_reference();
    if (!golden_lines) return;
    CHECK(reference.has_value());
    if (!reference) return;
    CHECK_EQ(golden_lines->front(), "spec " + std::string(spec));
    const auto part = fingerprint::positions(*reference, spec);
    CHECK(part.has_value());
    if (!part) return;
    CHECK_EQ(static_cast<int>(part->lines.size()), 64);
    std::vector<std::string> ours;
    for (const std::string& l : part->lines) ours.push_back("  " + l);
    ours.push_back("  positions checksum " + part->checksum_text());
    compare_lines(*golden_lines, 1, ours, file + " positions");
}

void check_game(const std::string& file, std::string_view spec, std::int64_t nodes, std::size_t first_line) {
    const auto golden_lines = golden::lines_or_skip(file);
    const auto reference = fingerprint::load_reference();
    if (!golden_lines) return;
    CHECK(reference.has_value());
    if (!reference) return;
    const auto part = fingerprint::game(*reference, spec, nodes);
    CHECK(part.has_value());
    if (!part) return;
    const std::string prefix = "  game@" + std::to_string(nodes) + " ";
    std::vector<std::string> ours;
    for (const std::string& l : part->lines) ours.push_back(prefix + l);
    ours.push_back(prefix + "checksum " + part->checksum_text());
    compare_lines(*golden_lines, first_line, ours, file + " game@" + std::to_string(nodes));
}

}  // namespace

SH_TEST(fingerprint_r2_positions_match_java) { check_positions("fingerprint-r2.txt", fingerprint::kR2Spec); }

SH_TEST(fingerprint_r2_game_1000_matches_java) { check_game("fingerprint-r2.txt", fingerprint::kR2Spec, 1000, 66); }

SH_TEST(fingerprint_r2_game_5000_matches_java) { check_game("fingerprint-r2.txt", fingerprint::kR2Spec, 5000, 70); }

SH_TEST(fingerprint_r3_positions_match_java) { check_positions("fingerprint-r3.txt", fingerprint::kR3Spec); }

SH_TEST(fingerprint_best_positions_equal_r3) {
    check_positions("fingerprint-best.txt", fingerprint::kBestSpec);
    const auto r3 = golden::load_lines("fingerprint-r3.txt");
    const auto best = golden::load_lines("fingerprint-best.txt");
    if (r3 && best) CHECK(*r3 == *best);  // the Java BEST_SPEC at 20000 nodes is the R3 player
    CHECK(fingerprint::kBestSpec == fingerprint::kR3Spec);
}

// BROWSER_SPEC (racelin-r3, rows >= 2 table): the tree equals the Java tree, and no rows >= 1 table is requested on the way.
SH_TEST(fingerprint_browser_matches_java_and_never_asks_for_rows1) {
    const int before = RaceEvaluator::rows1_requests();
    check_positions("fingerprint-browser.txt", fingerprint::kBrowserSpec);
    check_game("fingerprint-browser.txt", fingerprint::kBrowserSpec, 1000, 66);
    check_game("fingerprint-browser.txt", fingerprint::kBrowserSpec, 5000, 70);
    CHECK_EQ(RaceEvaluator::rows1_requests(), before);
}

SH_TEST(fingerprint_r3_game_1000_matches_java) { check_game("fingerprint-r3.txt", fingerprint::kR3Spec, 1000, 66); }

SH_TEST(fingerprint_r3_game_5000_matches_java) { check_game("fingerprint-r3.txt", fingerprint::kR3Spec, 5000, 70); }

SH_TEST(fingerprint_best_games_match_java) {
    check_game("fingerprint-best.txt", fingerprint::kBestSpec, 1000, 66);
    check_game("fingerprint-best.txt", fingerprint::kBestSpec, 5000, 70);
}

// ---- race play (WP6): every line of race-moves.tsv ----

SH_TEST(race_moves_match_java) {
    const auto lines = golden::lines_or_skip("race-moves.tsv");
    if (!lines) return;
    const auto table = RaceMove::shared_table(1);
    CHECK(table.has_value());
    if (!table) return;
    int rows_checked = 0;
    int table_moves = 0;
    int plan_moves = 0;
    int none = 0;
    for (const std::string& line : *lines) {
        if (line.empty() || line.front() == '#') continue;
        const auto cols = split_text(line, '\t');
        CHECK_EQ(static_cast<int>(cols.size()), 5);
        if (cols.size() != 5) return;
        std::vector<int> moves;
        for (const std::string_view part : split_text(cols[2], ' ')) {
            const auto m = parse_number<int>(part);
            CHECK(m.has_value());
            if (m) moves.push_back(*m);
        }
        auto pos = fingerprint::replay(moves);
        CHECK(pos.has_value());
        if (!pos) return;
        CHECK_EQ(pos->ply(), *parse_number<int>(cols[1]));
        RaceMove race(*table, 1);  // a fresh RaceMove for every position, as the exporter
        const int got = race.choose(*pos);
        const int want = *parse_number<int>(cols[3]);
        const std::string origin = race.table_moves() == 1 ? "table" : race.plan_moves() == 1 ? "plan" : "none";
        if (got != want || origin != cols[4]) {
            ::sh::test::count_check();
            ::sh::test::fail(__FILE__, __LINE__,
                             "race-moves.tsv " + std::string(cols[0]) + " ply " + std::string(cols[1]) + ": golden move " + std::to_string(want) +
                                 " (" + std::string(cols[4]) + "), ours " + std::to_string(got) + " (" + origin + ")");
            return;
        }
        ::sh::test::count_check();
        ++rows_checked;
        if (origin == "table") ++table_moves;
        else if (origin == "plan") ++plan_moves;
        else ++none;
    }
    CHECK_EQ(rows_checked, 332);
    CHECK(table_moves > 0 && plan_moves > 0);
    std::cout << "race-moves.tsv: " << rows_checked << " rows, table " << table_moves << ", plan " << plan_moves << ", none " << none << "\n";
}

// ---- solitaire (constructed cases) ----

SH_TEST(solitaire_goal_and_lower_bound) {
    const Layout& layout = Layout::two_players();
    for (const int player : {0, 1}) {
        Solitaire sol(player, RuleSet::LeaveGoal::Forbidden, nullptr);
        const auto goal = geometry::corner_cells(layout.goal_corner(player));
        const std::vector<int> cells(goal.begin(), goal.end());
        CHECK(sol.is_goal(cells));
        CHECK_EQ(sol.lower_bound(cells), 0);
        const auto start = geometry::corner_cells(layout.start_corner(player));
        const std::vector<int> from(start.begin(), start.end());
        CHECK(!sol.is_goal(from));
        CHECK(sol.lower_bound(from) >= 10);  // every piece needs at least one move
        std::uint64_t lo = 0;
        std::uint64_t hi = 0;
        for (const int c : cells) {
            lo = Solitaire::with_lo(lo, c);
            hi = Solitaire::with_hi(hi, c);
        }
        CHECK(sol.is_goal(lo, hi));
        CHECK(Solitaire::cells_of(lo, hi) == cells);
    }
}

SH_TEST(solitaire_plan_one_move_from_the_goal) {
    const Layout& layout = Layout::two_players();
    Solitaire sol(0, RuleSet::LeaveGoal::Forbidden, nullptr);
    const auto goal = geometry::corner_cells(layout.goal_corner(0));
    int missing = -1;  // the goal cell in the first row of the corner stays empty, its neighbour outside holds the tenth piece
    for (const int c : goal) {
        if (geometry::r(c) == 5) missing = c;
    }
    int outside = -1;
    for (int d = 0; d < kDirections && outside < 0; ++d) {
        const int n = geometry::neighbor(missing, d);
        if (n >= 0 && geometry::corner_of(n) < 0 && layout.allowed(0, n)) outside = n;
    }
    CHECK(outside >= 0);
    if (outside < 0) return;
    std::vector<int> cells;
    for (const int c : goal) {
        if (c != missing) cells.push_back(c);
    }
    cells.push_back(outside);
    std::ranges::sort(cells);
    const auto plan = sol.plan(cells, 20, 5, -100);
    CHECK(plan.has_value());
    if (!plan) return;
    CHECK_EQ(plan->moves(), 1);
    CHECK_EQ(plan->steps.front().from, outside);
    CHECK_EQ(plan->steps.front().to(), missing);
    CHECK_EQ(static_cast<int>(plan->states.size()), 2);
    CHECK(sol.is_goal(plan->states.back()));
    // a goal start is a plan of no moves
    const std::vector<int> done(goal.begin(), goal.end());
    const auto none = sol.plan(done, 20, 5, -100);
    CHECK(none.has_value() && none->moves() == 0);
}

SH_TEST(solitaire_landings_follow_the_rules) {
    // two pieces in a row: the one behind may jump over the other; every landing row is >= min_landing
    Solitaire sol(0, RuleSet::LeaveGoal::Forbidden, nullptr);
    const int a = geometry::id(0, 0);
    const int b = geometry::id(0, 1);
    std::uint64_t lo = 0;
    std::uint64_t hi = 0;
    for (const int c : {a, b}) {
        lo = Solitaire::with_lo(lo, c);
        hi = Solitaire::with_hi(hi, c);
    }
    std::vector<int> ends;
    sol.landings(lo, hi, a, -100, [&](int to) { ends.push_back(to); });
    CHECK(std::ranges::contains(ends, geometry::id(0, 2)));  // the jump over b
    CHECK(!std::ranges::contains(ends, b));
    CHECK_EQ(sol.path(a, geometry::id(0, 2)).size(), std::size_t{1});
    std::vector<int> high;
    sol.landings(lo, hi, a, 1, [&](int to) { high.push_back(to); });
    for (const int to : high) CHECK(geometry::r(to) >= 1);
    CHECK(high.size() < ends.size());
    const auto st = sol.step(lo, hi, a, geometry::id(0, 2), -100);
    CHECK(st.has_value() && st->move() == Move::of(a, geometry::id(0, 2)));
    CHECK(!sol.step(lo, hi, a, b, -100).has_value());
}

SH_TEST(browser_spec_parses_and_uses_the_rows2_table_only) {
    const PlayerSpec browser = parse_ok(fingerprint::kBrowserSpec);
    CHECK_EQ(browser.evaluator, std::string("racelin-r3"));
    CHECK_EQ(browser.race, 1);
    CHECK_EQ(browser.race_rows, 2);
    CHECK_EQ(browser.options.futility_gain, 20);
    const int before = RaceEvaluator::rows1_requests();
    const auto player = browser.create();
    const auto eval = browser.make_evaluator();
    if (player) CHECK(((*player)->race()) != nullptr);
    CHECK_EQ(RaceEvaluator::rows1_requests(), before);
    // the guard can fail: BEST_SPEC asks for rows1 as soon as its evaluator is built (also when the file is missing)
    const auto best_eval = parse_ok(fingerprint::kBestSpec).make_evaluator();
    CHECK(RaceEvaluator::rows1_requests() > before);
    (void)best_eval;
}

SH_TEST(player_with_race_play_has_the_java_name) {
    const PlayerSpec spec = parse_ok("ab:eval=linear-r1,race=1,racerows=1,nodes=500");
    const auto player = spec.create();
    if (!player) return;  // no table on this machine
    CHECK_EQ((*player)->name(), std::string("ab[eval=linear-r1, depth<=30, 500 nodes, race=1, racerows=1]"));
    CHECK(((*player)->race()) != nullptr);
}

int main() { return sh::test::run_all(); }
