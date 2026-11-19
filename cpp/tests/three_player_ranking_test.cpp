// Tests of the three-player game with ranking=FULL_RANKING in the C++ engine.
//  - The Java golden file engine/src/test/resources/three-player-ranking-golden.tsv (path from CMake, SH_THREE_PLAYER_RANKING_GOLDEN),
//    all 56 rows: the seeded playout (random, greedy, stop-after-first-finisher or move-limit policy, both jump rules) is replayed from
//    the start position and must reach the listed pieces, side, ply, Zobrist hash (including the finished players), finished set,
//    outcome (reason, winner, ranking, the bit patterns of the values), move count and both move-list hashes; the position rebuilt from
//    the listed pieces and the finished set must give the same move lists. A missing file fails the test (it never skips).
//  - Hand-made facts: the turn order skips a finished player, unmake restores everything, the 2-player game ignores the switch.
#include <algorithm>
#include <array>
#include <bit>
#include <cstdint>
#include <fstream>
#include <iterator>
#include <string>
#include <string_view>
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

RuleSet rules_of(RuleSet::JumpRule jump, int limit = 600) {
    return *RuleSet::defaults(3).with_ranking(RuleSet::Ranking::FullRanking)->with_move_limit(limit)->with_jump_rule(jump);
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

// One step of the seeded playout of the golden file; false if the mover has no move (the caller checks the end of the game).
bool play_one(Position& p, SplittableRandom& rng, bool greedy) {
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

// The outcome column of the golden file: ONGOING, or REASON/winner/ranking/bits,bits,bits.
std::string outcome_text(const std::optional<Outcome>& o) {
    if (!o) return "ONGOING";
    std::string bits;
    for (int p = 0; p < o->players; ++p) bits += (p == 0 ? "" : ",") + hex16(std::bit_cast<std::uint64_t>(o->value(p)));
    std::string order;
    for (int i = 0; i < o->ranking_size; ++i) order += (i == 0 ? "" : ",") + std::to_string(o->ranking[static_cast<std::size_t>(i)]);
    return std::string(Outcome::reason_name(o->reason)) + "/" + std::to_string(o->winner) + "/" + order + "/" + bits;
}

std::string pieces_text(const Position& p, int player) {
    std::string cells;
    for (const int c : p.pieces(player)) cells += (cells.empty() ? "" : ",") + std::to_string(c);
    return cells;
}

}  // namespace

// All rows of the Java golden file, bit for bit.
SH_TEST(java_ranking_golden_all_rows) {
    std::ifstream in(SH_THREE_PLAYER_RANKING_GOLDEN);
    CHECK(in.good());
    int rows = 0;
    int finished_rows = 0;
    int complete = 0;
    std::string line;
    while (std::getline(in, line)) {
        if (line.empty() || line.front() == '#') continue;
        const auto f = split(line, '\t');
        CHECK_EQ(f.size(), std::size_t{17});
        if (f.size() != 17) continue;
        const int id = std::stoi(std::string(f[0]));
        CHECK_EQ(id, rows);
        ++rows;
        CHECK_EQ(std::string(f[1]), std::string("3"));
        const bool long_jump = f[2] == "LONG";
        CHECK(long_jump || f[2] == "ADJACENT");
        const std::string policy(f[3]);
        const bool greedy = policy != "RANDOM";
        const int limit = std::stoi(std::string(f[4]));
        CHECK_EQ(std::stoull(std::string(f[5]), nullptr, 16), std::uint64_t{0x3EED0000} + static_cast<std::uint64_t>(id));
        const int plies = std::stoi(std::string(f[6]));
        const int side = std::stoi(std::string(f[7]));
        const RuleSet rules = rules_of(long_jump ? RuleSet::JumpRule::Long : RuleSet::JumpRule::Adjacent, limit);

        Position played = Position::initial(rules);
        SplittableRandom rng(std::uint64_t{0x3EED0000} + static_cast<std::uint64_t>(id));
        const int target = greedy || id >= 36 ? 1'000'000 : 6 * id;
        int first_finished_ply = -1;
        while (played.ply() < target && !played.outcome().has_value() &&
               !(first_finished_ply >= 0 && played.ply() >= first_finished_ply + 7 && policy == "AFTER_FIRST")) {
            if (!play_one(played, rng, greedy)) break;
            if (first_finished_ply < 0 && played.finished_mask() != 0) first_finished_ply = played.ply();
        }
        CHECK_EQ(played.ply(), plies);
        CHECK_EQ(played.side_to_move(), side);
        for (int p = 0; p < 3; ++p) CHECK_EQ(pieces_text(played, p), std::string(f[static_cast<std::size_t>(8 + p)]));
        CHECK_EQ(hex16(played.hash()), std::string(f[14]));
        CHECK_EQ(played.finished_mask(), std::stoi(std::string(f[15])));
        finished_rows += played.finished_mask() != 0 ? 1 : 0;
        std::optional<Outcome> outcome = played.outcome();
        const std::vector<int> ordered = moves_of(played);
        if (!outcome && ordered.empty()) outcome = played.no_moves_outcome();
        CHECK_EQ(outcome_text(outcome), std::string(f[16]));
        complete += outcome && outcome->has_winner() ? 1 : 0;
        CHECK_EQ(static_cast<int>(ordered.size()), std::stoi(std::string(f[11])));
        CHECK_EQ(hash_of_moves(sorted_by_from_to(ordered)), std::string(f[12]));
        CHECK_EQ(hash_of_moves(ordered), std::string(f[13]));

        Position built = Position::empty(rules);
        for (int p = 0; p < 3; ++p) {
            for (const std::string_view cell : split(f[static_cast<std::size_t>(8 + p)], ',')) {
                CHECK(built.put(p, std::stoi(std::string(cell))).has_value());
            }
        }
        for (int p = 0; p < 3; ++p) {
            if ((played.finished_mask() >> p & 1) != 0) CHECK(built.finish(p).has_value());
        }
        CHECK(built.set_side_to_move(side).has_value());
        const std::vector<int> ordered2 = moves_of(built);
        CHECK_EQ(hash_of_moves(sorted_by_from_to(ordered2)), std::string(f[12]));
        CHECK_EQ(hash_of_moves(ordered2), std::string(f[13]));
        CHECK_EQ(hex16(built.hash()), std::string(f[14]));
    }
    CHECK_EQ(rows, 56);
    CHECK(finished_rows >= 6);
    CHECK(complete >= 2);
}

// The turn order skips a finished player, unmake restores everything, and a 2-player game ignores the switch.
SH_TEST(finished_players_are_skipped_and_two_players_ignore_the_switch) {
    for (const auto jump : {RuleSet::JumpRule::Adjacent, RuleSet::JumpRule::Long}) {
        Position p = Position::initial(rules_of(jump));
        const std::uint64_t start = p.hash();
        CHECK(p.finish(0).has_value());
        CHECK(p.is_finished(0));
        CHECK_EQ(p.side_to_move(), 1);
        CHECK(p.hash() != start);
        CHECK(!p.set_side_to_move(0).has_value());
        const std::uint64_t after_finish = p.hash();
        for (int i = 0; i < 6; ++i) {
            CHECK_EQ(p.side_to_move(), 1 + i % 2);
            const std::vector<int> moves = moves_of(p);
            CHECK(!moves.empty());
            for (const int m : moves) CHECK(p.owner(Move::from(m)) != 0);
            p.make(moves.front());
        }
        for (int i = 0; i < 6; ++i) p.unmake();
        CHECK_EQ(p.hash(), after_finish);
        CHECK_EQ(p.side_to_move(), 1);
        CHECK_EQ(p.finished_mask(), 1);
    }
    const RuleSet two = *RuleSet::defaults(2).with_ranking(RuleSet::Ranking::FullRanking);
    CHECK(!two.plays_on_after_win());
    Position a = Position::initial(RuleSet::defaults(2));
    Position b = Position::initial(two);
    CHECK_EQ(a.hash(), b.hash());
    CHECK(!b.finish(0).has_value());
}

int main() { return sh::test::run_all(); }
