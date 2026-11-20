// The C ABI of the browser engine (src/wasm/wasm_api.cpp) called natively: the jump argument of shw_game_new and shw_player_new, the
// refusal of a game of the other rule, shw_analyse in a long-hop game, and the three-player game (shw_game_new2, shw_players,
// shw_player_new3, the outcome codes, the refusals, the error code of shw_analyse). The node tests of cpp/wasm/test run the same calls on the
// WebAssembly build; this test needs no Emscripten and no race table (the long player does not read one).
#include <array>
#include <cstddef>
#include <cstdint>
#include <set>
#include <string>

#include "check.hpp"
#include "geometry.hpp"

extern "C" {
const char* shw_error();
int shw_player_new(int nodes, int jump);
int shw_game_new(int jump);
int shw_game_new2(int jump, int players);
int shw_game_new3(int jump, int players, int ranking);
int shw_ranking_rule();
int shw_rank(int i);
int shw_finished_mask();
int shw_players();
int shw_player_new3(int nodes, int jump);
int shw_ply();
int shw_last_depth(int id);
double shw_total_nodes(int id);
void shw_player_clear(int id);
int shw_jump_rule();
int shw_game_new4(int jump, int players, int ranking, int camps);
int shw_foreign_camps();
int shw_side_to_move();
int shw_owner(int cell);
int shw_legal_moves(std::int32_t* out, int cap);
int shw_play(int move);
int shw_outcome();
int shw_choose(int player_id);
int shw_has_result(int id);
double shw_race_moves(int id);
int shw_analyse(int nodes, const std::int32_t* moves_in, int n_in, std::int32_t* moves_out, std::int32_t* scores_out, int cap);
double shw_analysis_nodes();
int shw_evaluate3(std::int32_t* out3);
}

namespace {

constexpr int kCap = 1210;

std::set<std::pair<int, int>> legal_pairs() {
    std::array<std::int32_t, kCap> buf{};
    const int n = shw_legal_moves(buf.data(), kCap);
    std::set<std::pair<int, int>> out;
    for (int i = 0; i < n; ++i) out.insert({buf[static_cast<std::size_t>(i)] & 0xff, buf[static_cast<std::size_t>(i)] >> 8});
    return out;
}

}  // namespace

SH_TEST(game_new_takes_the_jump_rule) {
    CHECK_EQ(shw_game_new(0), 0);
    CHECK_EQ(shw_jump_rule(), 0);
    CHECK_EQ(shw_game_new(1), 0);
    CHECK_EQ(shw_jump_rule(), 1);
    CHECK_EQ(shw_game_new(2), -1);
    CHECK_EQ(shw_jump_rule(), 1);  // unchanged
    CHECK(std::string(shw_error()).find("jump must be 0") != std::string::npos);
    CHECK_EQ(shw_player_new(100, 7), -1);
}

SH_TEST(game_new4_takes_the_foreign_corner_rule) {
    CHECK_EQ(shw_game_new4(0, 2, 0, 2), 0);
    CHECK_EQ(shw_foreign_camps(), 2);
    CHECK_EQ(shw_game_new4(1, 3, 0, 1), 0);
    CHECK_EQ(shw_foreign_camps(), 1);
    CHECK_EQ(shw_players(), 3);
    CHECK_EQ(shw_jump_rule(), 1);
    CHECK_EQ(shw_game_new4(0, 2, 0, 3), -1);
    CHECK_EQ(shw_foreign_camps(), 1);  // unchanged
    CHECK(std::string(shw_error()).find("camps must be") != std::string::npos);
    CHECK_EQ(shw_game_new3(0, 2, 0), 0);
    CHECK_EQ(shw_foreign_camps(), 0);  // the older entry points start closed games
}

// The classical player (with race play) plays legal moves in an open game: the race guard keeps it to the search.
SH_TEST(classical_player_plays_legal_moves_with_open_corners) {
    const int player = shw_player_new(300, 0);
    CHECK(player >= 0);
    CHECK_EQ(shw_game_new4(0, 2, 0, 2), 0);
    for (int ply = 0; ply < 40 && shw_outcome() < 0; ++ply) {
        const auto legal = legal_pairs();
        const int move = shw_choose(player);
        CHECK(legal.contains({move & 0xff, move >> 8}));
        CHECK_EQ(shw_play(move), 0);
    }
}

SH_TEST(long_player_plays_a_long_game_and_refuses_an_adjacent_game) {
    const int long_player = shw_player_new(800, 1);
    CHECK(long_player >= 0);
    if (long_player < 0) return;
    shw_game_new(0);
    CHECK_EQ(shw_choose(long_player), -2);
    CHECK(std::string(shw_error()).find("other jump rule") != std::string::npos);
    shw_game_new(1);
    // both sides played by the long player for 30 plies: every move is legal and found by the search
    for (int ply = 0; ply < 30 && shw_outcome() == -1; ++ply) {
        const int move = shw_choose(long_player);
        CHECK(move >= 0);
        CHECK(shw_has_result(long_player) == 1);
        CHECK_EQ(shw_play(move), 0);
    }
    CHECK_EQ(shw_race_moves(long_player), 0.0);
}

SH_TEST(adjacent_player_refuses_a_long_game_when_it_can_be_built) {
    const int player = shw_player_new(100, 0);
    if (player < 0) return;  // no race table on this machine: the adjacent browser player needs rows2
    shw_game_new(1);
    CHECK_EQ(shw_choose(player), -2);
}

// shw_analyse in a long-hop game scores every legal move of the long rule (with the long-hop analysis player), deterministically.
SH_TEST(analyse_in_a_long_game) {
    shw_game_new(1);
    // a fixed opening line: play the first legal move for 24 plies, until pieces stand in the middle
    for (int ply = 0; ply < 24; ++ply) {
        std::array<std::int32_t, kCap> buf{};
        const int n = shw_legal_moves(buf.data(), kCap);
        CHECK(n > 0);
        // the last legal move advances far (the generator lists pieces by ascending cell)
        CHECK_EQ(shw_play(buf[static_cast<std::size_t>(n - 1)]), 0);
    }
    const auto long_moves = legal_pairs();
    std::array<std::int32_t, kCap> moves_out{};
    std::array<std::int32_t, kCap> scores_out{};
    const int n = shw_analyse(300, nullptr, 0, moves_out.data(), scores_out.data(), kCap);
    CHECK_EQ(static_cast<std::size_t>(n), long_moves.size());
    CHECK(shw_analysis_nodes() > 0.0);
    std::set<std::pair<int, int>> scored;
    for (int i = 0; i < n; ++i) scored.insert({moves_out[static_cast<std::size_t>(i)] & 0xff, moves_out[static_cast<std::size_t>(i)] >> 8});
    CHECK(scored == long_moves);
    // the scores are deterministic
    std::array<std::int32_t, kCap> moves2{};
    std::array<std::int32_t, kCap> scores2{};
    CHECK_EQ(shw_analyse(300, nullptr, 0, moves2.data(), scores2.data(), kCap), n);
    CHECK(moves2 == moves_out);
    CHECK(scores2 == scores_out);
}

SH_TEST(game_new2_takes_players_and_keeps_the_old_call) {
    CHECK_EQ(shw_game_new(0), 0);
    CHECK_EQ(shw_players(), 2);
    CHECK_EQ(shw_game_new2(0, 3), 0);
    CHECK_EQ(shw_players(), 3);
    CHECK_EQ(shw_jump_rule(), 0);
    CHECK_EQ(shw_side_to_move(), 0);
    CHECK_EQ(shw_outcome(), -1);
    // the start position of three players: 10 pieces in each of the three start corners, nothing in the others
    std::array<int, 3> pieces{};
    for (int c = 0; c < sh::kCells; ++c) {
        const int o = shw_owner(c);
        CHECK(o >= -1 && o <= 2);
        if (o >= 0) ++pieces[static_cast<std::size_t>(o)];
    }
    CHECK(pieces == (std::array<int, 3>{10, 10, 10}));
    CHECK_EQ(shw_game_new2(1, 3), 0);
    CHECK_EQ(shw_jump_rule(), 1);
    CHECK_EQ(shw_players(), 3);
    // refused values leave the game unchanged
    CHECK_EQ(shw_game_new2(0, 4), -1);
    CHECK(std::string(shw_error()).find("players must be 2 or 3") != std::string::npos);
    CHECK_EQ(shw_game_new2(2, 3), -1);
    CHECK_EQ(shw_players(), 3);
    CHECK_EQ(shw_jump_rule(), 1);
    // shw_game_new(jump) is a two-player game again
    CHECK_EQ(shw_game_new(1), 0);
    CHECK_EQ(shw_players(), 2);
    CHECK_EQ(shw_player_new3(100, 5), -1);
    CHECK_EQ(shw_player_new3(0, 0), -1);
}

// Three engine seats play a whole game of each rule: every move is found and legal, the side to move cycles 0, 1, 2, the outcome is
// -1 while the game runs and 0 or 1 + winner (1 to 3) at the end, and the game ends within the move limit.
SH_TEST(three_engine_seats_play_a_full_game_of_each_rule) {
    for (const int jump : {0, 1}) {
        const int player = shw_player_new3(150, jump);
        CHECK(player >= 0);
        if (player < 0) continue;
        CHECK_EQ(shw_game_new2(jump, 3), 0);
        int ply = 0;
        while (shw_outcome() == -1 && ply < 400) {
            CHECK_EQ(shw_side_to_move(), ply % 3);
            const auto legal = legal_pairs();
            const int move = shw_choose(player);
            CHECK(move >= 0);
            CHECK(legal.contains({move & 0xff, move >> 8}));
            CHECK_EQ(shw_has_result(player), 1);
            CHECK(shw_last_depth(player) >= 1);
            CHECK_EQ(shw_play(move), 0);
            ++ply;
        }
        const int outcome = shw_outcome();
        CHECK(outcome >= 0 && outcome <= 3);
        CHECK_EQ(shw_ply(), ply);
        CHECK(ply <= 300);
        CHECK(shw_total_nodes(player) > 0.0);
        CHECK_EQ(shw_race_moves(player), 0.0);
        shw_player_clear(player);
        CHECK_EQ(shw_has_result(player), 0);
    }
}

SH_TEST(full_ranking_game_through_the_c_api) {
    CHECK_EQ(shw_game_new3(0, 3, 2), -1);
    CHECK(std::string(shw_error()).find("ranking must be") != std::string::npos);
    CHECK_EQ(shw_game_new3(0, 3, 0), 0);
    CHECK_EQ(shw_ranking_rule(), 0);
    CHECK_EQ(shw_game_new3(1, 2, 1), 0);  // two players: accepted, no effect
    CHECK_EQ(shw_ranking_rule(), 1);
    CHECK_EQ(shw_players(), 2);
    for (const int jump : {0, 1}) {
        const int player = shw_player_new3(150, jump);
        CHECK(player >= 0);
        CHECK_EQ(shw_game_new3(jump, 3, 1), 0);
        CHECK_EQ(shw_ranking_rule(), 1);
        CHECK_EQ(shw_finished_mask(), 0);
        CHECK_EQ(shw_rank(0), -1);
        int ply = 0;
        while (shw_outcome() == -1 && ply < 400) {
            const int mask = shw_finished_mask();
            CHECK_EQ(mask >> shw_side_to_move() & 1, 0);  // a finished player is never to move
            if (mask != 0) CHECK(shw_rank(0) >= 0 && (mask >> shw_rank(0) & 1) == 1);
            const int move = shw_choose(player);
            CHECK(move >= 0);
            CHECK_EQ(shw_play(move), 0);
            ++ply;
        }
        if (shw_outcome() > 0) {  // a complete ranking: three different players, the winner first
            const int a = shw_rank(0);
            const int b = shw_rank(1);
            const int c = shw_rank(2);
            CHECK_EQ(shw_outcome(), 1 + a);
            CHECK(a >= 0 && b >= 0 && c >= 0 && a != b && a != c && b != c);
        }
    }
}

SH_TEST(players_and_games_must_match) {
    const int three = shw_player_new3(100, 0);
    const int three_long = shw_player_new3(100, 1);
    const int two_long = shw_player_new(100, 1);
    CHECK(three >= 0 && three_long >= 0 && two_long >= 0);
    // a three-player player in a two-player game, a two-player player in a three-player game, a player of the other rule
    shw_game_new2(0, 2);
    CHECK_EQ(shw_choose(three), -2);
    CHECK(std::string(shw_error()).find("2 players") != std::string::npos);
    shw_game_new2(1, 2);
    CHECK_EQ(shw_choose(three_long), -2);
    shw_game_new2(1, 3);
    CHECK_EQ(shw_choose(two_long), -2);
    CHECK(std::string(shw_error()).find("3 players") != std::string::npos);
    CHECK_EQ(shw_choose(three), -2);
    CHECK(std::string(shw_error()).find("other jump rule") != std::string::npos);
    CHECK(shw_choose(three_long) >= 0);
}

// shw_analyse in a three-player game scores every legal move with the paranoid three-player search, deterministically, a subset in any
// order gives the values of the full list, and the error codes are those of two players. The bit-exact comparison with Java is in
// three_player_search_test (the golden file); here the ABI.
SH_TEST(analyse_scores_three_players) {
    for (const int jump : {0, 1}) {
        CHECK_EQ(shw_game_new2(jump, 3), 0);
        std::array<std::int32_t, kCap> moves_out{};
        std::array<std::int32_t, kCap> scores_out{};
        const int legal_count = static_cast<int>(legal_pairs().size());
        const int n = shw_analyse(300, nullptr, 0, moves_out.data(), scores_out.data(), kCap);
        CHECK_EQ(n, legal_count);
        CHECK(shw_analysis_nodes() > 0);
        std::array<std::int32_t, kCap> moves2{};
        std::array<std::int32_t, kCap> scores2{};
        CHECK_EQ(shw_analyse(300, nullptr, 0, moves2.data(), scores2.data(), kCap), n);
        for (std::size_t i = 0; i < static_cast<std::size_t>(n); ++i) {
            CHECK_EQ(moves_out[i], moves2[i]);
            CHECK_EQ(scores_out[i], scores2[i]);
        }
        // a subset in reverse order, after another budget
        std::array<std::int32_t, 2> pick{moves_out[3], moves_out[1]};
        CHECK_EQ(shw_analyse(100, nullptr, 0, moves2.data(), scores2.data(), kCap), n);
        CHECK_EQ(shw_analyse(300, pick.data(), 2, moves2.data(), scores2.data(), kCap), 2);
        CHECK_EQ(moves2[0], moves_out[3]);
        CHECK_EQ(scores2[0], scores_out[3]);
        CHECK_EQ(scores2[1], scores_out[1]);
        // error codes
        std::array<std::int32_t, 2> bad{moves_out[0], 0};
        CHECK_EQ(shw_analyse(300, bad.data(), 2, moves2.data(), scores2.data(), kCap), -1);
        CHECK_EQ(shw_analyse(300, nullptr, 0, moves2.data(), scores2.data(), 3), -3);
        CHECK_EQ(shw_analyse(0, nullptr, 0, moves2.data(), scores2.data(), kCap), -5);
        // the analysis changes no game player
        const int player = shw_player_new3(200, jump);
        CHECK(shw_choose(player) >= 0);
        const double nodes_before = shw_total_nodes(player);
        CHECK(shw_analyse(300, nullptr, 0, moves2.data(), scores2.data(), kCap) > 0);
        CHECK_EQ(shw_total_nodes(player), nodes_before);
    }
    shw_game_new2(1, 2);
    std::array<std::int32_t, kCap> moves_out{};
    std::array<std::int32_t, kCap> scores_out{};
    CHECK(shw_analyse(100, nullptr, 0, moves_out.data(), scores_out.data(), kCap) > 0);  // two players are unchanged
}

SH_TEST(evaluate3_gives_one_integer_per_absolute_player) {
    std::array<std::int32_t, 3> e{};
    CHECK_EQ(shw_game_new2(0, 2), 0);
    CHECK_EQ(shw_evaluate3(e.data()), -1);
    CHECK(std::string(shw_error()).find("three-player") != std::string::npos);
    for (const int jump : {0, 1}) {
        CHECK_EQ(shw_game_new2(jump, 3), 0);
        CHECK_EQ(shw_evaluate3(e.data()), 0);
        CHECK_EQ(e[0], e[1]);  // the start position is symmetric
        CHECK_EQ(e[1], e[2]);
        std::array<std::int32_t, kCap> buf{};
        for (int ply = 0; ply < 30; ++ply) {
            const int n = shw_legal_moves(buf.data(), kCap);
            CHECK(n > 0);
            CHECK_EQ(shw_play(buf[static_cast<std::size_t>((ply * 7) % n)]), 0);
        }
        CHECK_EQ(shw_evaluate3(e.data()), 0);
        CHECK(e[0] != e[1] || e[1] != e[2]);
    }
}

int main() { return sh::test::run_all(); }
