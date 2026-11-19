// Tests of the three-player player of the C++ engine (ThreePlayerSearch, ThreePlayerPlayer, ThreePlayerSpec).
//  - The Java golden file search/src/test/resources/three-player-choose-golden.tsv (path from CMake, SH_THREE_PLAYER_CHOOSE_GOLDEN),
//    all 26 rows: a new player with the spec of the row, at nodes=5000 and nodes=20000, gives Java's move, score, depth, node count and
//    completeness bit for bit. The positions come from the seeded playouts of the file. A missing file fails the test (never skips).
//  - The spec: keys, defaults, the browser constants, errors for unknown keys, values and evaluators.
//  - The player: determinism, clear_search equals a fresh player, refusal of other players and jump rules, a full game of three engine
//    seats ends legally.
#include <algorithm>
#include <array>
#include <cstdint>
#include <fstream>
#include <iterator>
#include <stdexcept>
#include <string>
#include <string_view>
#include <vector>

#include "check.hpp"
#include "geometry.hpp"
#include "move.hpp"
#include "position.hpp"
#include "rules.hpp"
#include "splittable_random.hpp"
#include "three_player_player.hpp"

using namespace sh;

namespace {

RuleSet rules_of(RuleSet::JumpRule jump) { return *RuleSet::defaults(3).with_jump_rule(jump); }

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

std::vector<int> sorted_moves(Position& p) {
    std::array<int, kMaxMoves> buf{};
    const int n = p.generate(buf);
    std::vector<int> moves(buf.begin(), std::next(buf.begin(), n));
    std::ranges::sort(moves, [](int a, int b) {
        return Move::from(a) != Move::from(b) ? Move::from(a) < Move::from(b) : Move::to(a) < Move::to(b);
    });
    return moves;
}

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

// The position of a row of the golden file: the seeded playout of its header.
Position position_of_row(int id) {
    const bool greedy = id >= 20;
    Position pos = Position::initial(rules_of(id % 2 == 0 ? RuleSet::JumpRule::Adjacent : RuleSet::JumpRule::Long));
    SplittableRandom rng(std::uint64_t{0xC0DE0000} + static_cast<std::uint64_t>(id));
    const int target = greedy ? 60 + 20 * (id - 20) : 9 * id + id % 3;
    while (pos.ply() < target && !pos.outcome().has_value()) {
        const std::vector<int> moves = sorted_moves(pos);
        if (moves.empty()) break;
        const std::vector<int> candidates = greedy ? best_gain(pos, moves) : moves;
        pos.make(candidates[(rng.next_long() >> 1) % candidates.size()]);
    }
    if (pos.outcome().has_value()) pos.unmake();  // one ply before the end
    return pos;
}

std::string pieces_text(const Position& p, int player) {
    std::string cells;
    for (const int c : p.pieces(player)) cells += (cells.empty() ? "" : ",") + std::to_string(c);
    return cells;
}

std::string spec_text(std::int64_t nodes, bool long_hop) {
    return "ab3:eval=linear-r2,nodes=" + std::to_string(nodes) + ",tt=16" + (long_hop ? ",jump=long" : "");
}

}  // namespace

// All rows of the Java golden file, bit for bit.
SH_TEST(java_choose_golden_all_rows) {
    std::ifstream in(SH_THREE_PLAYER_CHOOSE_GOLDEN);
    CHECK(in.good());
    int rows = 0;
    std::string line;
    while (std::getline(in, line)) {
        if (line.empty() || line.front() == '#') continue;
        const auto f = split(line, '\t');
        CHECK_EQ(f.size(), std::size_t{19});
        if (f.size() != 19) continue;
        const int id = std::stoi(std::string(f[0]));
        CHECK_EQ(id, rows);
        ++rows;
        const bool long_hop = f[1] == "LONG";
        Position pos = position_of_row(id);
        CHECK_EQ(pos.ply(), std::stoi(std::string(f[4])));
        CHECK_EQ(pos.side_to_move(), std::stoi(std::string(f[5])));
        for (int p = 0; p < 3; ++p) CHECK_EQ(pieces_text(pos, p), std::string(f[static_cast<std::size_t>(6 + p)]));
        const std::array<std::int64_t, 2> budgets{5000, 20000};
        for (std::size_t b = 0; b < budgets.size(); ++b) {
            const std::size_t at = 9 + 5 * b;
            const auto spec = ThreePlayerSpec::parse(spec_text(budgets[b], long_hop));
            CHECK(spec.has_value());
            auto player = spec->create();
            CHECK(player.has_value());
            const int move = (*player)->choose(pos);
            const auto& r = (*player)->last_result();
            CHECK(r.has_value());
            if (!r) continue;
            CHECK_EQ(std::to_string(Move::from(move)) + "-" + std::to_string(Move::to(move)), std::string(f[at]));
            CHECK_EQ(r->score, std::stoi(std::string(f[at + 1])));
            CHECK_EQ(r->depth, std::stoi(std::string(f[at + 2])));
            CHECK_EQ(r->nodes, std::stoll(std::string(f[at + 3])));
            CHECK_EQ(r->complete, f[at + 4] == "true");
        }
    }
    CHECK_EQ(rows, 26);
}

SH_TEST(spec_keys_defaults_and_errors) {
    const auto d = ThreePlayerSpec::parse("ab3");
    CHECK(d.has_value());
    CHECK_EQ(d->evaluator, std::string("linear-r2"));
    CHECK_EQ(d->nodes, std::int64_t{20000});
    CHECK_EQ(d->depth, 30);
    CHECK_EQ(d->table_bits, 16);
    CHECK_EQ(d->millis, ThreePlayerSearch::kNoLimit);
    CHECK(d->jump_rule == RuleSet::JumpRule::Adjacent);
    const auto all = ThreePlayerSpec::parse("ab3:eval=linear-r1,nodes=123,depth=7,ms=50,tt=10,jump=long");
    CHECK(all.has_value());
    CHECK_EQ(all->evaluator, std::string("linear-r1"));
    CHECK_EQ(all->nodes, std::int64_t{123});
    CHECK_EQ(all->depth, 7);
    CHECK_EQ(all->millis, std::int64_t{50});
    CHECK_EQ(all->table_bits, 10);
    CHECK(all->jump_rule == RuleSet::JumpRule::Long);
    CHECK(ThreePlayerSpec::parse("ab3:eval=linear").has_value());
    CHECK(ThreePlayerSpec::parse("ab3:jump=adjacent").has_value());
    CHECK(ThreePlayerSpec::parse(kBrowser3pSpec).value().jump_rule == RuleSet::JumpRule::Adjacent);
    CHECK(ThreePlayerSpec::parse(kBrowser3pLongSpec).value().jump_rule == RuleSet::JumpRule::Long);
    CHECK_EQ(ThreePlayerSpec::parse(kBrowser3pSpec).value().nodes, std::int64_t{20000});
    // errors
    CHECK(!ThreePlayerSpec::parse("ab:nodes=10").has_value());                // two-player kind
    CHECK(!ThreePlayerSpec::parse("ab3:eval=racelin-r3").has_value());        // race evaluators are two-player only
    CHECK(!ThreePlayerSpec::parse("ab3:eval=linear-r3").has_value());         // bestGain is two-player only
    CHECK(!ThreePlayerSpec::parse("ab3:bogus=1").has_value());
    CHECK(!ThreePlayerSpec::parse("ab3:nodes").has_value());
    CHECK(!ThreePlayerSpec::parse("ab3:nodes=ten").has_value());
    CHECK(!ThreePlayerSpec::parse("ab3:jump=far").has_value());
    CHECK(!ThreePlayerSpec::parse("ab3:tt=31").has_value());
    CHECK(!ThreePlayerSpec::parse("ab3:depth=0").has_value());
}

SH_TEST(player_is_deterministic_and_clear_search_resets) {
    Position pos = position_of_row(8);
    const auto spec = ThreePlayerSpec::parse(spec_text(3000, false));
    auto a = *spec->create();
    auto b = *spec->create();
    const int ma = a->choose(pos);
    const int mb = b->choose(pos);
    CHECK_EQ(ma, mb);
    CHECK(a->last_result() == b->last_result());
    // a second call on the same player uses the table of the first: it may differ from a fresh player, but clear_search restores it
    a->choose(pos);
    a->clear_search();
    CHECK(!a->last_result().has_value());
    a->choose(pos);
    CHECK(a->last_result() == b->last_result());
    CHECK_EQ(a->moves(), std::int64_t{3});
    CHECK(a->total_nodes() > 0);
    CHECK_EQ(a->name(), std::string("ab3[eval=linear-r2, depth<=30, 3000 nodes]"));
}

SH_TEST(player_refuses_other_games) {
    auto adjacent = *ThreePlayerSpec::parse(spec_text(100, false))->create();
    Position long_game = Position::initial(rules_of(RuleSet::JumpRule::Long));
    CHECK(!adjacent->plays(long_game));
    bool threw = false;
    try {
        adjacent->choose(long_game);
    } catch (const std::invalid_argument&) {
        threw = true;
    }
    CHECK(threw);
    Position two = Position::initial(RuleSet::defaults());
    CHECK(!adjacent->plays(two));
    threw = false;
    try {
        adjacent->choose(two);
    } catch (const std::invalid_argument&) {
        threw = true;
    }
    CHECK(threw);
    CHECK(adjacent->plays(Position::initial(rules_of(RuleSet::JumpRule::Adjacent))));
}

SH_TEST(three_engine_seats_play_legal_games) {
    for (const auto jump : {RuleSet::JumpRule::Adjacent, RuleSet::JumpRule::Long}) {
        Position pos = Position::initial(rules_of(jump));
        auto player = *ThreePlayerSpec::parse(std::string("ab3:nodes=300,tt=12") + (jump == RuleSet::JumpRule::Long ? ",jump=long" : ""))->create();
        int guard = 0;
        while (!pos.outcome().has_value() && guard++ < 400) {
            const int mover = pos.side_to_move();
            std::array<int, kMaxMoves> buf{};
            const int n = pos.generate(buf);
            if (n == 0) break;
            const int move = player->choose(pos);
            CHECK(std::find(buf.begin(), std::next(buf.begin(), n), move) != std::next(buf.begin(), n));  // legal
            CHECK_EQ(pos.owner(Move::from(move)), mover);
            pos.make(move);
        }
        const auto outcome = pos.outcome();
        CHECK(outcome.has_value());
        if (outcome) CHECK(outcome->has_winner() || pos.ply() >= pos.rules().move_limit());
        CHECK(pos.ply() <= pos.rules().move_limit());
    }
}

int main() { return sh::test::run_all(); }
