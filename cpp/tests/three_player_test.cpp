// Tests of the three-player game of the C++ engine (RuleSet::players() == 3, docs/rules.md "three players").
//  - The Java golden file engine/src/test/resources/three-player-golden.tsv (path from CMake, SH_THREE_PLAYER_GOLDEN), all 40 rows:
//    the seeded playout (random or greedy policy, both jump rules) is replayed from the start position and must reach the listed
//    pieces, side, ply, Zobrist hash, outcome (reason, winner, the bit patterns of the values), move count and both move-list
//    hashes; the position rebuilt from the listed pieces must give the same move lists. The file is read in place; a missing file
//    fails the test (it never skips).
//  - Hand-made facts of the three-player layout: start and goal corners, the cells each player may enter, the play order.
//  - Properties over playouts: make/unmake restores everything, a win is reported for the right player, the legal moves never leave
//    the cells the mover may enter.
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

// One step of the seeded playout of the golden file; false if the game is over or the mover has no move.
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

// The outcome column of the golden file: ONGOING, or REASON/winner/bits,bits,bits.
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

}  // namespace

SH_TEST(rule_set_and_layout_of_three_players) {
    const RuleSet rules = RuleSet::defaults(3);
    CHECK_EQ(rules.players(), 3);
    CHECK_EQ(rules.move_limit(), 300);
    CHECK_EQ(RuleSet::defaults(2).move_limit(), 200);
    CHECK(RuleSet::defaults(2) == RuleSet::defaults());
    const Layout& layout = Layout::three_players();
    CHECK_EQ(layout.players(), 3);
    CHECK_EQ(layout.start_corner(0), corner::kRNeg);
    CHECK_EQ(layout.start_corner(1), corner::kSNeg);
    CHECK_EQ(layout.start_corner(2), corner::kQNeg);
    for (int p = 0; p < 3; ++p) {
        CHECK_EQ(layout.goal_corner(p), corner::opposite(layout.start_corner(p)));
        CHECK_EQ(layout.goal_player(layout.goal_corner(p)), p);
        CHECK_EQ(layout.max_distance(p), 13);
    }
    // the start corners are nobody's goal; a player may not enter any corner but its own two
    CHECK_EQ(layout.goal_player(corner::kQPos), 2);
    CHECK_EQ(layout.goal_player(corner::kRNeg), -1);
    CHECK_EQ(layout.goal_player(corner::kSPos), 1);
    for (int c = 0; c < kCells; ++c) {
        for (int p = 0; p < 3; ++p) {
            const int k = geometry::corner_of(c);
            CHECK_EQ(layout.allowed(p, c), k < 0 || k == layout.start_corner(p) || k == layout.goal_corner(p));
        }
    }
    // the two-player layout is unchanged
    CHECK_EQ(Layout::of(2).players(), 2);
    CHECK_EQ(Layout::two_players().start_corner(1), corner::kRPos);
}

SH_TEST(start_position_and_play_order) {
    Position p = Position::initial(rules_of(RuleSet::JumpRule::Adjacent));
    for (int q = 0; q < 3; ++q) {
        CHECK_EQ(p.piece_count(q), 10);
        for (const int c : p.pieces(q)) CHECK_EQ(geometry::corner_of(c), p.layout().start_corner(q));
    }
    CHECK_EQ(p.side_to_move(), 0);
    for (int i = 0; i < 6; ++i) {
        const std::vector<int> moves = moves_of(p);
        CHECK(!moves.empty());
        CHECK_EQ(p.side_to_move(), i % 3);
        p.make(moves.front());
    }
    CHECK_EQ(p.ply(), 6);
    for (int i = 5; i >= 0; --i) {
        p.unmake();
        CHECK_EQ(p.side_to_move(), i % 3);
    }
    CHECK_EQ(p.hash(), Position::initial(rules_of(RuleSet::JumpRule::Adjacent)).hash());
}

SH_TEST(win_of_each_player_and_values) {
    // player q holds all 10 cells of its goal corner: q wins, the others get -1/2 each
    for (int q = 0; q < 3; ++q) {
        Position full = Position::empty(rules_of(RuleSet::JumpRule::Adjacent));
        const auto goal = geometry::corner_cells(full.layout().goal_corner(q));
        for (const int c : goal) CHECK(full.put(q, c).has_value());
        CHECK(full.has_won(q));
        const auto outcome = full.outcome();
        CHECK(outcome.has_value());
        if (outcome) {
            CHECK_EQ(outcome->winner, q);
            CHECK_EQ(outcome->players, 3);
            for (int r = 0; r < 3; ++r) CHECK_EQ(outcome->value(r), r == q ? 1.0 : -0.5);
        }
    }
}

// All rows of the Java golden file, bit for bit.
SH_TEST(java_golden_all_rows) {
    std::ifstream in(SH_THREE_PLAYER_GOLDEN);
    CHECK(in.good());
    int rows = 0;
    int wins = 0;
    int move_limits = 0;
    std::string line;
    while (std::getline(in, line)) {
        if (line.empty() || line.front() == '#') continue;
        const auto f = split(line, '\t');
        CHECK_EQ(f.size(), std::size_t{15});
        if (f.size() != 15) continue;
        const int id = std::stoi(std::string(f[0]));
        const std::string label = "row " + std::to_string(id);
        CHECK_EQ(id, rows);
        ++rows;
        CHECK_EQ(std::string(f[1]), std::string("3"));
        const bool long_jump = f[2] == "LONG";
        CHECK(long_jump || f[2] == "ADJACENT");
        const bool greedy = f[3] == "GREEDY";
        CHECK(greedy || f[3] == "RANDOM");
        CHECK_EQ(std::stoull(std::string(f[4]), nullptr, 16), std::uint64_t{0x3EED0000} + static_cast<std::uint64_t>(id));
        const int plies = std::stoi(std::string(f[5]));
        const int side = std::stoi(std::string(f[6]));
        const RuleSet rules = rules_of(long_jump ? RuleSet::JumpRule::Long : RuleSet::JumpRule::Adjacent);

        // (a) the position reached by the seeded playout from the start position
        Position played = Position::initial(rules);
        SplittableRandom rng(std::uint64_t{0x3EED0000} + static_cast<std::uint64_t>(id));
        const int target = greedy || id >= 36 ? 1'000'000 : 6 * id;
        while (played.ply() < target && play_one(played, rng, greedy)) {
        }
        CHECK_EQ(played.ply(), plies);
        CHECK_EQ(played.side_to_move(), side);
        for (int p = 0; p < 3; ++p) CHECK_EQ(pieces_text(played, p), std::string(f[static_cast<std::size_t>(7 + p)]));
        CHECK_EQ(hex16(played.hash()), std::string(f[13]));
        std::optional<Outcome> outcome = played.outcome();
        const std::vector<int> ordered = moves_of(played);
        if (!outcome && ordered.empty()) outcome = played.no_moves_outcome();
        CHECK_EQ(outcome_text(outcome), std::string(f[14]));
        wins += outcome && outcome->has_winner() ? 1 : 0;
        move_limits += outcome && outcome->reason == Outcome::Reason::MoveLimit ? 1 : 0;
        CHECK_EQ(static_cast<int>(ordered.size()), std::stoi(std::string(f[10])));
        CHECK_EQ(hash_of_moves(sorted_by_from_to(ordered)), std::string(f[11]));
        CHECK_EQ(hash_of_moves(ordered), std::string(f[12]));

        // (b) the position rebuilt from the listed pieces gives the same move lists
        Position built = Position::empty(rules);
        for (int p = 0; p < 3; ++p) {
            for (const std::string_view cell : split(f[static_cast<std::size_t>(7 + p)], ',')) {
                CHECK(built.put(p, std::stoi(std::string(cell))).has_value());
            }
        }
        CHECK(built.set_side_to_move(side).has_value());
        const std::vector<int> ordered2 = moves_of(built);
        CHECK_EQ(hash_of_moves(sorted_by_from_to(ordered2)), std::string(f[11]));
        CHECK_EQ(hash_of_moves(ordered2), std::string(f[12]));
        CHECK_EQ(hex16(built.hash()), std::string(f[13]));
    }
    CHECK_EQ(rows, 40);
    CHECK(wins >= 2);
    CHECK_EQ(move_limits, 2);
}

// Playouts with make/unmake: unmaking every move restores the pieces, the hash and the side to move, and every legal move stays on
// the cells the mover may enter and never takes a piece out of its goal corner.
SH_TEST(make_unmake_and_legality_over_playouts) {
    for (const auto jump : {RuleSet::JumpRule::Adjacent, RuleSet::JumpRule::Long}) {
        for (std::uint64_t seed = 1; seed <= 4; ++seed) {
            Position p = Position::initial(rules_of(jump));
            SplittableRandom rng(seed * 104729);
            std::vector<std::uint64_t> hashes{p.hash()};
            for (int ply = 0; ply < 150; ++ply) {
                const int mover = p.side_to_move();
                const std::vector<int> moves = moves_of(p);
                if (moves.empty() || p.outcome().has_value()) break;
                for (const int m : moves) {
                    CHECK(p.layout().allowed(mover, Move::to(m)));
                    CHECK_EQ(p.owner(Move::from(m)), mover);
                    CHECK_EQ(p.owner(Move::to(m)), -1);
                    if (geometry::corner_of(Move::from(m)) == p.layout().goal_corner(mover)) {
                        CHECK_EQ(geometry::corner_of(Move::to(m)), p.layout().goal_corner(mover));
                    }
                }
                p.make(moves[(rng.next_long() >> 1) % moves.size()]);
                hashes.push_back(p.hash());
            }
            const int played = p.ply();
            for (int i = played; i > 0; --i) {
                CHECK_EQ(p.hash(), hashes[static_cast<std::size_t>(i)]);
                p.unmake();
                CHECK_EQ(p.side_to_move(), (i - 1) % 3);
            }
            CHECK_EQ(p.hash(), hashes[0]);
            for (int q = 0; q < 3; ++q) {
                for (const int c : p.pieces(q)) CHECK_EQ(geometry::corner_of(c), p.layout().start_corner(q));
            }
        }
    }
}

int main() { return sh::test::run_all(); }
