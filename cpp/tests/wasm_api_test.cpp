// The C ABI of the browser engine (src/wasm/wasm_api.cpp) called natively: the jump argument of shw_game_new and shw_player_new, the
// refusal of a game of the other rule, and shw_analyse in a long-hop game. The node tests of cpp/wasm/test run the same calls on the
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
int shw_jump_rule();
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

int main() { return sh::test::run_all(); }
