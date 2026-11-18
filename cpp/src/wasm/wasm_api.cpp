// The C API of the browser engine (WebAssembly, built by scripts/web/build-wasm.zsh): a flat C ABI, no embind. One global game and a
// small table of players; all calls are synchronous and the module is single-threaded. Errors are returned as codes; the text of the last error is shw_error().
#include <array>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <optional>
#include <span>
#include <string>
#include <vector>

#include "alpha_beta_player.hpp"
#include "geometry.hpp"
#include "player_spec.hpp"
#include "position.hpp"
#include "race_eval.hpp"
#include "race_move.hpp"
#include "race_table.hpp"
#include "rules.hpp"
#include "scores.hpp"

#ifdef __EMSCRIPTEN__
#include <emscripten.h>
#define SHW_API extern "C" EMSCRIPTEN_KEEPALIVE
#else
#define SHW_API extern "C"
#endif

// The raw pointers of the C ABI are the one unsafe zone of this file (as args.hpp in the apps).
#pragma clang unsafe_buffer_usage begin
namespace {
constexpr const char* kBrowserSpecPrefix =
    "ab:eval=racelin-r3,pvs=1,killers=1,history=1,lmr=2,asp=1,ttrep=1,mate=1,partial=1,fut=1,futk=20,fut2=1,race=1,racerows=2,nodes=";
// The analysis player: the same search as BROWSER_SPEC but without race play (so that a passed position is searched and scored, not
// played without a score) and with a small transposition table (it is cleared before every scored move). Not a game player: its table
// and budget are separate, so analysis never changes what shw_choose returns.
constexpr const char* kAnalysisSpecPrefix =
    "ab:eval=racelin-r3,pvs=1,killers=1,history=1,lmr=2,asp=1,ttrep=1,mate=1,partial=1,fut=1,futk=20,fut2=1,tt=16,nodes=";
std::string g_error;
std::unique_ptr<sh::AlphaBetaPlayer> g_analysis;
int g_analysis_nodes = 0;
std::int64_t g_analysis_visited = 0;
std::optional<sh::Position> g_pos;
std::vector<std::unique_ptr<sh::AlphaBetaPlayer>> g_players;
std::array<int, sh::kMaxMoves> g_moves{};
}  // namespace

SHW_API const char* shw_error() { return g_error.c_str(); }
SHW_API void* shw_alloc(std::size_t n) { return ::operator new(n, std::nothrow); }
SHW_API void shw_free(void* p) { ::operator delete(p); }

// Takes the inflated rows2.bin (13,123,286 bytes), checks the header and, if verify is not 0, the two SHA-256 values, and installs it.
// 0 = ok, else LoadError kind + 1 (4 bad magic, 7 truncated, 8 trailing bytes, 9 file checksum, 10 body checksum).
SHW_API int shw_load_rows2(const std::uint8_t* bytes, std::size_t len, int verify) {
    const std::span<const std::uint8_t> src(bytes, len);
    std::vector<char> data(src.begin(), src.end());
    auto table = sh::RaceTable::load_bytes(std::move(data), "rows2.bin", sh::kRows2, verify != 0);
    if (!table) {
        g_error = table.error().message;
        return static_cast<int>(table.error().kind) + 1;
    }
    sh::RaceMove::install_rows2(std::make_shared<const sh::RaceTable>(std::move(*table)));
    return 0;
}

// A BROWSER_SPEC player with a node budget per move; returns its id (>= 0) or -1.
SHW_API int shw_player_new(int nodes) {
    const auto spec = sh::PlayerSpec::parse(kBrowserSpecPrefix + std::to_string(nodes));
    if (!spec) { g_error = spec.error(); return -1; }
    auto player = spec->create();
    if (!player) { g_error = player.error(); return -1; }
    g_players.push_back(std::move(*player));
    return static_cast<int>(g_players.size()) - 1;
}
SHW_API void shw_player_clear(int id) { g_players.at(static_cast<std::size_t>(id))->clear_search(); }

SHW_API void shw_game_new() { g_pos.emplace(sh::Position::initial(sh::RuleSet::defaults())); }
SHW_API int shw_side_to_move() { return g_pos->side_to_move(); }
SHW_API int shw_ply() { return g_pos->ply(); }
SHW_API int shw_owner(int cell) { return g_pos->owner(cell); }
SHW_API int shw_cell_count() { return sh::kCells; }
SHW_API int shw_cell_q(int cell) { return sh::geometry::q(cell); }
SHW_API int shw_cell_r(int cell) { return sh::geometry::r(cell); }
// Writes the legal moves (from | to << 8) of the side to move into the heap array `out` (capacity `cap`); returns their number.
SHW_API int shw_legal_moves(std::int32_t* out, int cap) {
    const int n = g_pos->generate(g_moves);
    const std::span<std::int32_t> dest(out, static_cast<std::size_t>(cap));
    for (int i = 0; i < n && i < cap; ++i) dest[static_cast<std::size_t>(i)] = g_moves[static_cast<std::size_t>(i)];
    return n;
}
SHW_API int shw_play(int move) {
    const int n = g_pos->generate(g_moves);
    for (int i = 0; i < n; ++i)
        if (g_moves[static_cast<std::size_t>(i)] == move) { g_pos->make(move); return 0; }
    return -1;
}
SHW_API void shw_unmake() { g_pos->unmake(); }
// -1 running, 0 draw, 1 + winner otherwise.
SHW_API int shw_outcome() {
    const auto o = g_pos->outcome();
    if (!o) return g_pos->generate(g_moves) > 0 ? -1 : 0;
    return o->has_winner() ? 1 + o->winner : 0;
}
// The engine's move for the side to move (-1 if none); the search is synchronous and blocks for the node budget.
SHW_API int shw_choose(int player_id) { return g_players.at(static_cast<std::size_t>(player_id))->choose(*g_pos); }
SHW_API int shw_last_score(int id) { const auto& r = g_players.at(static_cast<std::size_t>(id))->last_result(); return r ? r->score : 0; }
SHW_API int shw_last_depth(int id) { const auto& r = g_players.at(static_cast<std::size_t>(id))->last_result(); return r ? r->depth : 0; }
SHW_API double shw_last_nodes(int id) { const auto& r = g_players.at(static_cast<std::size_t>(id))->last_result(); return r ? static_cast<double>(r->nodes) : 0; }
SHW_API int shw_has_result(int id) { return g_players.at(static_cast<std::size_t>(id))->last_result().has_value() ? 1 : 0; }
SHW_API double shw_total_nodes(int id) { return static_cast<double>(g_players.at(static_cast<std::size_t>(id))->total_nodes()); }
SHW_API double shw_race_moves(int id) { return static_cast<double>(g_players.at(static_cast<std::size_t>(id))->race_moves()); }
// Scores moves of the current position for the side to move. `moves_in` (n_in entries) lists the moves to score, or is null for all legal
// moves; the moves scored are written to `moves_out` and their scores to `scores_out` (capacity `cap` each; scores are integers of the
// search scale, from the point of view of the side that makes the move, higher is better). Each move is made, the child position is
// searched with `nodes` nodes by the analysis player (cleared before each move), and the score is negated. A win or loss found by the
// search is one ply farther from the root than from the child, so mate scores move one step towards 0 (a move that wins at once scores
// kMate - 1, as in a search of the parent). Returns the number of moves scored, or -1 a move in the list is not legal, -2 the game is
// over, -3 cap too small, -4 the player could not be created (shw_error), -5 nodes < 1.
SHW_API int shw_analyse(int nodes, const std::int32_t* moves_in, int n_in, std::int32_t* moves_out, std::int32_t* scores_out, int cap) {
    if (nodes < 1) return -5;
    sh::Position& pos = *g_pos;
    std::array<int, sh::kMaxMoves> legal{};
    const int count = pos.generate(legal);
    if (pos.outcome().has_value() || count == 0) return -2;
    std::array<int, sh::kMaxMoves> chosen{};
    int n = 0;
    if (moves_in == nullptr) {
        for (; n < count; ++n) chosen[static_cast<std::size_t>(n)] = legal[static_cast<std::size_t>(n)];
    } else {
        const std::span<const std::int32_t> list(moves_in, static_cast<std::size_t>(n_in));
        if (n_in > count) return -1;
        for (const std::int32_t m : list) {
            bool found = false;
            for (int i = 0; i < count; ++i) found = found || legal[static_cast<std::size_t>(i)] == m;
            if (!found) return -1;
            chosen[static_cast<std::size_t>(n++)] = m;
        }
    }
    if (n > cap) return -3;
    if (!g_analysis || g_analysis_nodes != nodes) {
        const auto spec = sh::PlayerSpec::parse(kAnalysisSpecPrefix + std::to_string(nodes));
        if (!spec) { g_error = spec.error(); return -4; }
        auto player = spec->create();
        if (!player) { g_error = player.error(); return -4; }
        g_analysis = std::move(*player);
        g_analysis_nodes = nodes;
    }
    const std::span<std::int32_t> out_moves(moves_out, static_cast<std::size_t>(cap));
    const std::span<std::int32_t> out_scores(scores_out, static_cast<std::size_t>(cap));
    g_analysis_visited = 0;
    for (int i = 0; i < n; ++i) {
        const int move = chosen[static_cast<std::size_t>(i)];
        pos.make(move);
        g_analysis->clear_search();
        g_analysis->choose(pos);
        pos.unmake();
        const auto& r = g_analysis->last_result();
        int score = r ? -r->score : 0;
        if (score >= sh::scores::kMateBound) --score;
        else if (score <= -sh::scores::kMateBound) ++score;
        if (r) g_analysis_visited += r->nodes;
        out_moves[static_cast<std::size_t>(i)] = move;
        out_scores[static_cast<std::size_t>(i)] = score;
    }
    return n;
}
// Nodes searched by the last shw_analyse call, all moves together.
SHW_API double shw_analysis_nodes() { return static_cast<double>(g_analysis_visited); }
SHW_API int shw_rows1_requests() { return sh::RaceEvaluator::rows1_requests(); }

#pragma clang unsafe_buffer_usage end
