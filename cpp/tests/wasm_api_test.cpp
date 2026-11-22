// The C ABI of the browser engine (src/wasm/wasm_api.cpp) called natively: the jump argument of shw_game_new and shw_player_new, the
// refusal of a game of the other rule, shw_analyse in a long-hop game, and the three-player game (shw_game_new2, shw_players,
// shw_player_new3, the outcome codes, the refusals, the error code of shw_analyse). The node tests of cpp/wasm/test run the same calls on the
// WebAssembly build; this test needs no Emscripten and no race table (the long player does not read one).
#include <array>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <set>
#include <span>
#include <string>
#include <utility>
#include <vector>

#include "check.hpp"
#include "geometry.hpp"
#include "layout.hpp"
#include "linear_eval.hpp"
#include "position.hpp"
#include "rules.hpp"

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
int shw_game_new5(int jump, int players, int ranking, int camps, int rows);
int shw_triangle_rows();
int shw_move_limit();
int shw_player_new_rows(int nodes, int jump, int rows);
int shw_player_new3_rows(int nodes, int jump, int rows);
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
int shw_features(double* out13, std::int32_t* out_score_side, std::int32_t* out_dist242);
int shw_last_score(int id);
double shw_last_nodes(int id);
}

namespace {

constexpr int kCap = 1815;

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

// ---- the 5-row board (house size): shw_game_new5, shw_triangle_rows, shw_move_limit, the players of a house size ----

SH_TEST(game_new5_starts_a_five_row_game) {
    for (const int players : {2, 3}) {
        CHECK_EQ(shw_game_new5(0, players, 0, 0, 5), 0);
        CHECK_EQ(shw_triangle_rows(), 5);
        CHECK_EQ(shw_move_limit(), players == 2 ? 250 : 375);
        const sh::Layout& layout = sh::Layout::of(players, sh::RuleSet::TriangleRows::Five);
        std::array<int, 3> counts{};
        for (int c = 0; c < sh::kCells; ++c) {
            const int o = shw_owner(c);
            if (o < 0) continue;
            ++counts.at(static_cast<std::size_t>(o));
            CHECK(layout.in_start(o, c));  // every piece stands in the start house of its owner
        }
        for (int p = 0; p < players; ++p) CHECK_EQ(counts.at(static_cast<std::size_t>(p)), 15);
        CHECK(!legal_pairs().empty());
        // the 4-row board is unchanged: 10 pieces, the old move limits
        CHECK_EQ(shw_game_new5(0, players, 0, 0, 4), 0);
        CHECK_EQ(shw_triangle_rows(), 4);
        CHECK_EQ(shw_move_limit(), players == 2 ? 200 : 300);
        int four_pieces = 0;
        for (int c = 0; c < sh::kCells; ++c) four_pieces += shw_owner(c) >= 0 ? 1 : 0;
        CHECK_EQ(four_pieces, 10 * players);
    }
    // shw_game_new4 and the shorter forms are the 4-row board
    CHECK_EQ(shw_game_new4(0, 2, 0, 0), 0);
    CHECK_EQ(shw_triangle_rows(), 4);
    CHECK_EQ(shw_game_new(0), 0);
    CHECK_EQ(shw_triangle_rows(), 4);
}

SH_TEST(game_new5_refuses_another_house_size_and_keeps_the_game) {
    CHECK_EQ(shw_game_new5(0, 2, 0, 0, 5), 0);
    CHECK_EQ(shw_ply(), 0);
    for (const int rows : {0, 3, 6, -1}) {
        CHECK_EQ(shw_game_new5(1, 3, 0, 0, rows), -1);
        CHECK(std::string(shw_error()).find("rows must be 4 or 5") != std::string::npos);
        CHECK_EQ(shw_triangle_rows(), 5);  // the refused call changed nothing
        CHECK_EQ(shw_players(), 2);
    }
    CHECK_EQ(shw_player_new_rows(100, 1, 7), -1);
    CHECK(std::string(shw_error()).find("rows must be 4 or 5") != std::string::npos);
    CHECK_EQ(shw_player_new3_rows(100, 0, 2), -1);
}

SH_TEST(players_of_a_house_size_play_games_of_that_size_only) {
    for (const int jump : {0, 1}) {
        const int two5 = shw_player_new_rows(300, jump, 5);
        const int three5 = shw_player_new3_rows(300, jump, 5);
        const int three4 = shw_player_new3_rows(300, jump, 4);
        CHECK(two5 >= 0 && three5 >= 0 && three4 >= 0);
        CHECK_EQ(shw_game_new5(jump, 2, 0, 0, 4), 0);
        CHECK_EQ(shw_choose(two5), -2);
        CHECK(std::string(shw_error()).find("house size") != std::string::npos);
        CHECK_EQ(shw_game_new5(jump, 3, 0, 0, 5), 0);
        CHECK_EQ(shw_choose(three4), -2);
        CHECK_EQ(shw_game_new5(jump, 2, 0, 0, 5), 0);
        // 40 plies of the 5-row player against itself: every engine move is legal, and no race move is made
        for (int ply = 0; ply < 40 && shw_outcome() == -1; ++ply) {
            const int move = shw_choose(two5);
            CHECK(move >= 0);
            CHECK(legal_pairs().count({move & 0xff, move >> 8}) == 1);
            CHECK_EQ(shw_play(move), 0);
        }
        CHECK_EQ(shw_race_moves(two5), 0.0);
        CHECK_EQ(shw_game_new5(jump, 3, 0, 0, 5), 0);
        for (int ply = 0; ply < 30 && shw_outcome() == -1; ++ply) {
            const int move = shw_choose(three5);
            CHECK(move >= 0);
            CHECK(legal_pairs().count({move & 0xff, move >> 8}) == 1);
            CHECK_EQ(shw_play(move), 0);
        }
    }
}

// shw_analyse and shw_evaluate3 follow the house size of the game: the cached analysis players are keyed on it, so that a 5-row game after a
// 4-row game with the same budget gets a 5-row analysis player (the 4-row one would refuse the position and end the module).
SH_TEST(analysis_follows_the_house_size) {
    std::array<std::int32_t, kCap> moves_out{};
    std::array<std::int32_t, kCap> scores_out{};
    for (const int players : {2, 3}) {
        CHECK_EQ(shw_game_new5(1, players, 0, 0, 4), 0);  // long hop: no race table needed on 4 rows
        CHECK(shw_analyse(100, nullptr, 0, moves_out.data(), scores_out.data(), kCap) > 0);
        CHECK_EQ(shw_game_new5(1, players, 0, 0, 5), 0);
        const std::size_t legal = legal_pairs().size();
        CHECK(legal > 10);
        CHECK_EQ(static_cast<std::size_t>(shw_analyse(100, nullptr, 0, moves_out.data(), scores_out.data(), kCap)), legal);
        CHECK(shw_analysis_nodes() > 0.0);
        CHECK_EQ(shw_game_new5(1, players, 0, 0, 4), 0);  // and back
        CHECK(shw_analyse(100, nullptr, 0, moves_out.data(), scores_out.data(), kCap) > 0);
    }
    std::array<std::int32_t, 3> e{};
    CHECK_EQ(shw_game_new5(0, 3, 0, 0, 5), 0);
    CHECK_EQ(shw_evaluate3(e.data()), 0);
    CHECK_EQ(e[0], e[1]);  // the 5-row start position is symmetric as well
    CHECK_EQ(e[1], e[2]);
    // the move list of a 5-row start position does not fit a buffer of the old 1210 moves only when it is that long; kCap holds any list
    CHECK_EQ(shw_game_new5(1, 2, 0, 0, 5), 0);
    CHECK(shw_analyse(100, nullptr, 0, moves_out.data(), scores_out.data(), 3) == -3);  // cap too small is still reported
}

// ---- shw_features: the linear-r3 evaluation term by term (live V6 of the Info pages) ----

namespace {
// The position of web/src/about/data/eval-mid.json (ExplainerDataTest, Fixtures.mid()): 30 plies, moves from | to << 8 from that file's history.
constexpr std::array<int, 30> kMid = {4099,  26227, 7440,  22886, 10496, 19832, 4360,  16973, 13057, 14453, 15633, 26480, 17949, 23159, 20777,
                                      20071, 23603, 20596, 30033, 17497, 23366, 15182, 3844,  12868, 4613,  12088, 7174,  9519,  6919,  6693};
// The 13 features of eval-mid.json (rounded there to four decimals), order of LinearEvaluator.GAIN_NAMES.
constexpr std::array<double, 13> kMidFeatures = {-9, 0, -7.1, 1, -2, 2, -6, 2, 3, -3, -12.51, 1, 0};

struct Features {
    std::array<double, 13> values{};
    std::array<std::int32_t, 2> score_side{};
    std::array<std::int32_t, 242> distance{};
    int rc = 0;
};

Features features_now() {
    Features f;
    f.values.fill(99);
    f.score_side.fill(99);
    f.distance.fill(99);
    f.rc = shw_features(f.values.data(), f.score_side.data(), f.distance.data());
    return f;
}

void play_mid(std::size_t plies) {
    CHECK_EQ(shw_game_new5(0, 2, 0, 0, 4), 0);
    for (std::size_t i = 0; i < plies; ++i) CHECK_EQ(shw_play(kMid[i]), 0);
}
}  // namespace

SH_TEST(features_of_the_mid_position_equal_the_explainer_golden) {
    play_mid(kMid.size());
    const Features f = features_now();
    CHECK_EQ(f.rc, 0);
    for (std::size_t i = 0; i < 13; ++i) CHECK(std::abs(f.values[i] - kMidFeatures[i]) < 1e-4);
    CHECK_EQ(f.score_side[0], 179);  // eval-mid.json meta.score
    CHECK_EQ(f.score_side[1], 0);
    // per-piece distances of eval-mid.json, sides[].pieces: {cell, distance}; player 1 starts at index 121
    for (const auto [cell, d] : std::array<std::pair<int, int>, 4>{{{2, 12}, {61, 5}, {92, 2}, {117, 0}}})
        CHECK_EQ(f.distance[static_cast<std::size_t>(cell)], d);
    for (const auto [cell, d] : std::array<std::pair<int, int>, 4>{{{26, 2}, {66, 6}, {111, 10}, {118, 12}}})
        CHECK_EQ(f.distance[static_cast<std::size_t>(121 + cell)], d);
}

SH_TEST(features_after_an_odd_ply_are_for_player_one_and_every_distance_is_the_layouts) {
    play_mid(29);
    const Features f = features_now();
    CHECK_EQ(f.rc, 0);
    CHECK_EQ(f.score_side[1], 1);
    const sh::Layout& layout = sh::Layout::of(2);
    for (int p = 0; p < 2; ++p)
        for (int cell = 0; cell < 121; ++cell) CHECK_EQ(f.distance[static_cast<std::size_t>(p * 121 + cell)], layout.distance(p, cell));
}

SH_TEST(features_are_for_the_classic_game_only) {
    for (const std::array<int, 5> g : {std::array<int, 5>{1, 2, 0, 0, 4}, std::array<int, 5>{0, 3, 0, 0, 4}, std::array<int, 5>{0, 2, 0, 1, 4},
                                       std::array<int, 5>{0, 2, 0, 2, 4}, std::array<int, 5>{1, 2, 0, 0, 5}, std::array<int, 5>{0, 3, 0, 0, 5},
                                       std::array<int, 5>{0, 2, 0, 2, 5}}) {
        CHECK_EQ(shw_game_new5(g[0], g[1], g[2], g[3], g[4]), 0);
        CHECK_EQ(features_now().rc, -1);
        CHECK(std::string(shw_error()).find("classic") != std::string::npos);
    }
    CHECK_EQ(shw_game_new5(0, 2, 1, 0, 4), 0);  // the ranking rule changes nothing for two players
    CHECK_EQ(features_now().rc, 0);
    CHECK_EQ(shw_game_new5(0, 2, 0, 0, 5), 0);  // the 5-row classic game is explained as well
    CHECK_EQ(features_now().rc, 0);
}

// The 5-row classic game: the features, the linear-r3 score and the distances equal those of the engine's own 5-row position, along the first
// legal move of every ply (a deterministic line through the opening into the middle game).
SH_TEST(features_on_five_rows_equal_those_of_the_five_row_position) {
    CHECK_EQ(shw_game_new5(0, 2, 0, 0, 5), 0);
    sh::Position pos = sh::Position::initial(sh::RuleSet::defaults(2, sh::RuleSet::TriangleRows::Five));
    const auto evaluator = sh::LinearEvaluator::linear_r3();
    std::array<int, sh::kMaxMoves> moves{};
    for (int ply = 0; ply < 60 && !pos.outcome(); ++ply) {
        const Features f = features_now();
        CHECK_EQ(f.rc, 0);
        std::array<double, 13> expected{};
        sh::LinearEvaluator::features(pos, std::span<double, 13>(expected), sh::LinearEvaluator::GainMode::Both);
        for (std::size_t i = 0; i < 13; ++i) CHECK_EQ(f.values[i], expected[i]);
        CHECK_EQ(f.score_side[0], evaluator->evaluate(pos));
        CHECK_EQ(f.score_side[1], pos.side_to_move());
        for (int p = 0; p < 2; ++p)
            for (int cell = 0; cell < 121; ++cell) CHECK_EQ(f.distance[static_cast<std::size_t>(p * 121 + cell)], pos.layout().distance(p, cell));
        const int count = pos.generate(moves);
        CHECK(count > 0);
        const int move = moves[static_cast<std::size_t>(ply % count)];
        CHECK_EQ(shw_play(move), 0);
        pos.make(move);
    }
}

// The guard against shared state: features between the moves of a game leave every choose result (move, score, depth, nodes) as without them.
SH_TEST(features_between_two_choose_calls_leave_the_choose_results_unchanged) {
    struct Step {
        int move, score, depth;
        double nodes;
        bool operator==(const Step&) const = default;
    };
    const auto game = [](bool with_features) {
        const std::array<int, 2> players = {shw_player_new(1500, 0), shw_player_new(1500, 0)};
        CHECK(players[0] >= 0 && players[1] >= 0);
        std::vector<Step> steps;
        std::vector<int> history;
        for (int ply = 0; ply < 16; ++ply) {
            CHECK_EQ(shw_game_new5(0, 2, 0, 0, 4), 0);
            for (const int m : history) CHECK_EQ(shw_play(m), 0);
            if (with_features) {
                CHECK_EQ(features_now().rc, 0);
            }
            const int id = players.at(static_cast<std::size_t>(shw_side_to_move()));
            const int move = shw_choose(id);
            steps.push_back({move, shw_last_score(id), shw_last_depth(id), shw_last_nodes(id)});
            history.push_back(move);
        }
        return steps;
    };
    const std::vector<Step> plain = game(false);
    const std::vector<Step> interleaved = game(true);
    CHECK(plain == interleaved);
}

int main() { return sh::test::run_all(); }
