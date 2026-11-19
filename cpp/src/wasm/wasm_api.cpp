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
#include "three_player_player.hpp"

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
// The long-hop (jump=long) players: kBrowserLongSpec (BROWSER_LONG_SPEC of Java: no race play, eval linear-r3) plus the node budget, and
// the analysis spec of the same search with tt=16. The race table and the racelin-r3 evaluator count single jumps only.
constexpr const char* kAnalysisLongSpecPrefix =
    "ab:eval=linear-r3,pvs=1,killers=1,history=1,lmr=2,asp=1,ttrep=1,mate=1,partial=1,fut=1,futk=20,fut2=1,tt=16,jump=long,nodes=";
std::string g_error;
std::unique_ptr<sh::AlphaBetaPlayer> g_analysis;
int g_analysis_nodes = 0;
bool g_analysis_long = false;
std::int64_t g_analysis_visited = 0;
// The three-player analysis player (BROWSER_3P_SPEC with the budget of the call, its own table cleared before every scored move), separate
// from the game players and from the two-player analysis player.
std::unique_ptr<sh::ThreePlayerPlayer> g_analysis3;
int g_analysis3_nodes = 0;
bool g_analysis3_long = false;
std::optional<sh::Position> g_pos;
// A player of the table: a two-player player (two) or a three-player player (three); exactly one is set.
struct PlayerSlot {
    std::unique_ptr<sh::AlphaBetaPlayer> two;
    std::unique_ptr<sh::ThreePlayerPlayer> three;
};
std::vector<PlayerSlot> g_players;
PlayerSlot& slot(int id) { return g_players.at(static_cast<std::size_t>(id)); }
const std::optional<sh::SearchResult>& result_of(int id) {
    PlayerSlot& s = slot(id);
    return s.two ? s.two->last_result() : s.three->last_result();
}
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

// A BROWSER_SPEC player with a node budget per move; returns its id (>= 0) or -1. jump = 0: the adjacent jump rule (BROWSER_SPEC, race
// play with the rows >= 2 table); jump = 1: the Halma long hop (BROWSER_LONG_SPEC, no race play, no table needed); any other jump: -1.
// A player plays games of its own rule only (shw_choose returns -2 for a game of the other rule). A call without the second argument
// from JavaScript passes jump = 0.
SHW_API int shw_player_new(int nodes, int jump) {
    if (jump != 0 && jump != 1) { g_error = "jump must be 0 (adjacent) or 1 (long), got " + std::to_string(jump); return -1; }
    const auto spec = sh::PlayerSpec::parse(jump == 1 ? std::string(sh::kBrowserLongSpec) + ",nodes=" + std::to_string(nodes)
                                                      : kBrowserSpecPrefix + std::to_string(nodes));
    if (!spec) { g_error = spec.error(); return -1; }
    auto player = spec->create();
    if (!player) { g_error = player.error(); return -1; }
    PlayerSlot s;
    s.two = std::move(*player);
    g_players.push_back(std::move(s));
    return static_cast<int>(g_players.size()) - 1;
}
// A three-player player (ThreePlayerSpec, BROWSER_3P_SPEC: paranoid alpha-beta, eval linear-r2, no race table) with a node budget per move; returns
// its id (>= 0) or -1 (jump other than 0 or 1; nodes < 1). jump = 0 plays the adjacent rule, jump = 1 the Halma long hop. A three-player player
// plays the side to move of a three-player game of its rule; shw_choose returns -2 for any other game.
SHW_API int shw_player_new3(int nodes, int jump) {
    if (jump != 0 && jump != 1) { g_error = "jump must be 0 (adjacent) or 1 (long), got " + std::to_string(jump); return -1; }
    if (nodes < 1) { g_error = "nodes must be at least 1, got " + std::to_string(nodes); return -1; }
    const auto spec = sh::ThreePlayerSpec::parse(std::string("ab3:eval=linear-r2,tt=16,nodes=") + std::to_string(nodes) + (jump == 1 ? ",jump=long" : ""));
    if (!spec) { g_error = spec.error(); return -1; }
    auto player = spec->create();
    if (!player) { g_error = player.error(); return -1; }
    PlayerSlot s;
    s.three = std::move(*player);
    g_players.push_back(std::move(s));
    return static_cast<int>(g_players.size()) - 1;
}
SHW_API void shw_player_clear(int id) {
    PlayerSlot& s = slot(id);
    if (s.two) s.two->clear_search(); else s.three->clear_search();
}

// Starts a new game from the start position with a ranking rule: ranking = 0 ends the game at the first winner (RuleSet FIRST_WINNER_ONLY),
// 1 plays on for the lower ranks (FULL_RANKING: a three-player game; for two players it changes nothing). Other values of ranking, jump or
// players give -1. shw_game_new2(jump, players) is shw_game_new3(jump, players, 0).
// Starts a new game from the start position. jump = 0: the adjacent jump rule, 1: the Halma long hop; players = 2 (200 plies) or 3 (clockwise
// play order top, lower right, lower left; 300 plies). Returns 0, or -1 for another value of jump or players (the game is then unchanged;
// shw_error() tells which).
SHW_API int shw_game_new3(int jump, int players, int ranking) {
    if (jump != 0 && jump != 1) { g_error = "jump must be 0 (adjacent) or 1 (long), got " + std::to_string(jump); return -1; }
    if (players != 2 && players != 3) { g_error = "players must be 2 or 3, got " + std::to_string(players); return -1; }
    if (ranking != 0 && ranking != 1) { g_error = "ranking must be 0 (first winner only) or 1 (full ranking), got " + std::to_string(ranking); return -1; }
    const auto rules = sh::RuleSet::defaults(players)
                           .with_jump_rule(jump == 1 ? sh::RuleSet::JumpRule::Long : sh::RuleSet::JumpRule::Adjacent)
                           .and_then([ranking](const sh::RuleSet& r) { return r.with_ranking(ranking == 1 ? sh::RuleSet::Ranking::FullRanking : sh::RuleSet::Ranking::FirstWinnerOnly); });
    if (!rules) { g_error = rules.error(); return -1; }
    g_pos.emplace(sh::Position::initial(*rules));
    return 0;
}
SHW_API int shw_game_new2(int jump, int players) { return shw_game_new3(jump, players, 0); }
// The two-player game: shw_game_new2(jump, 2). A call without an argument from JavaScript passes jump = 0.
SHW_API int shw_game_new(int jump) { return shw_game_new2(jump, 2); }
// The number of players of the current game: 2 or 3.
SHW_API int shw_players() { return g_pos->rules().players(); }
// The jump rule of the current game: 0 adjacent, 1 long.
// The ranking rule of the current game: 0 first winner only, 1 full ranking.
SHW_API int shw_ranking_rule() { return g_pos->rules().ranking() == sh::RuleSet::Ranking::FullRanking ? 1 : 0; }
SHW_API int shw_jump_rule() { return g_pos->rules().jump_rule() == sh::RuleSet::JumpRule::Long ? 1 : 0; }
SHW_API int shw_side_to_move() { return g_pos->side_to_move(); }
SHW_API int shw_ply() { return g_pos->ply(); }
SHW_API int shw_owner(int cell) { return g_pos->owner(cell); }
SHW_API int shw_cell_count() { return sh::kCells; }
SHW_API int shw_cell_q(int cell) { return sh::geometry::q(cell); }
SHW_API int shw_cell_r(int cell) { return sh::geometry::r(cell); }
// Writes the legal moves (from | to << 8) of the side to move into the heap array `out` (capacity `cap`); returns their number. A game that
// is over has none (as in the Java facade): with a full-ranking game the finished players' goals no longer block the others' moves.
SHW_API int shw_legal_moves(std::int32_t* out, int cap) {
    if (g_pos->outcome().has_value()) return 0;
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
// -1 running, 0 draw (move limit, or the side to move has no legal move), 1 + winner otherwise: 1, 2 or 3 for the winner 0, 1 or 2 (3 only in a three-player game).
SHW_API int shw_outcome() {
    const auto o = g_pos->outcome();
    if (!o) return g_pos->generate(g_moves) > 0 ? -1 : 0;
    return o->has_winner() ? 1 + o->winner : 0;
}
// The absolute player of rank i (0 = first place), or -1 if that rank is not decided: in a running full-ranking game the players that have
// finished, by rank; after the end the whole ranking (a first-winner game: only rank 0, the winner).
SHW_API int shw_rank(int i) {
    if (i < 0 || i > 2) return -1;
    const auto o = g_pos->outcome();
    if (o) return i < o->ranking_size ? o->ranking[static_cast<std::size_t>(i)] : -1;
    const auto finishers = g_pos->finishers();
    return static_cast<std::size_t>(i) < finishers.size() ? finishers[static_cast<std::size_t>(i)] : -1;
}
// The players that have finished (filled the goal in a full-ranking game with three players) as bits: bit p for player p; 0 otherwise.
SHW_API int shw_finished_mask() { return g_pos->finished_mask(); }
// The engine's move for the side to move (-1 if none; -2 if the player plays the other jump rule than the game, or two players where the game has
// three or the reverse); the search is synchronous and blocks for the node budget. A three-player player plays whichever seat is to move.
SHW_API int shw_choose(int player_id) {
    PlayerSlot& s = slot(player_id);
    const std::string& name = s.two ? s.two->name() : s.three->name();
    const bool right_players = g_pos->rules().players() == (s.two ? 2 : 3);
    if (!right_players) { g_error = "player " + name + " cannot play a game with " + std::to_string(g_pos->rules().players()) + " players"; return -2; }
    if (!(s.two ? s.two->plays(*g_pos) : s.three->plays(*g_pos))) { g_error = "player " + name + " cannot play a game of the other jump rule"; return -2; }
    return s.two ? s.two->choose(*g_pos) : s.three->choose(*g_pos);
}
SHW_API int shw_last_score(int id) { const auto& r = result_of(id); return r ? r->score : 0; }
SHW_API int shw_last_depth(int id) { const auto& r = result_of(id); return r ? r->depth : 0; }
SHW_API double shw_last_nodes(int id) { const auto& r = result_of(id); return r ? static_cast<double>(r->nodes) : 0; }
SHW_API int shw_has_result(int id) { return result_of(id).has_value() ? 1 : 0; }
SHW_API double shw_total_nodes(int id) {
    PlayerSlot& s = slot(id);
    return static_cast<double>(s.two ? s.two->total_nodes() : s.three->total_nodes());
}
// Moves played by race arithmetic instead of search; always 0 for a three-player player.
SHW_API double shw_race_moves(int id) { PlayerSlot& s = slot(id); return s.two ? static_cast<double>(s.two->race_moves()) : 0.0; }
namespace {
// The scoring of a three-player position: the moves `chosen` (legal, checked by the caller), each searched by the analysis player of the
// three-player browser spec with `nodes` nodes (ThreePlayerPlayer::analyse_move: the move is made, the child is searched for the mover, the
// table is cleared first). Writes the moves and scores; returns the number of moves, or -4 (player could not be created).
int analyse3(sh::Position& pos, int nodes, bool long_hop, std::span<const int> chosen, std::int32_t* moves_out, std::int32_t* scores_out, int cap) {
    if (!g_analysis3 || g_analysis3_nodes != nodes || g_analysis3_long != long_hop) {
        const auto spec = sh::ThreePlayerSpec::parse(std::string("ab3:eval=linear-r2,tt=16,nodes=") + std::to_string(nodes) + (long_hop ? ",jump=long" : ""));
        if (!spec) { g_error = spec.error(); return -4; }
        auto player = spec->create();
        if (!player) { g_error = player.error(); return -4; }
        g_analysis3 = std::move(*player);
        g_analysis3_nodes = nodes;
        g_analysis3_long = long_hop;
    }
    const std::span<std::int32_t> out_moves(moves_out, static_cast<std::size_t>(cap));
    const std::span<std::int32_t> out_scores(scores_out, static_cast<std::size_t>(cap));
    g_analysis_visited = 0;
    for (std::size_t i = 0; i < chosen.size(); ++i) {
        const sh::SearchResult r = g_analysis3->analyse_move(pos, chosen[i]);
        g_analysis_visited += r.nodes;
        out_moves[i] = chosen[i];
        out_scores[i] = r.score;
    }
    return static_cast<int>(chosen.size());
}
}  // namespace

// Scores moves of the current position for the side to move. `moves_in` (n_in entries) lists the moves to score, or is null for all legal
// moves; the moves scored are written to `moves_out` and their scores to `scores_out` (capacity `cap` each; scores are integers of the
// search scale, from the point of view of the side that makes the move, higher is better). Two players: each move is made, the child
// position is searched with `nodes` nodes by the analysis player (cleared before each move), and the score is negated. A win or loss found
// by the search is one ply farther from the root than from the child, so mate scores move one step towards 0 (a move that wins at once scores
// kMate - 1, as in a search of the parent). The analysis player follows the jump rule of the game: the analysis spec of the browser, or
// for a long-hop game the same search with eval=linear-r3 and jump=long (no race arithmetic). Three players: the same contract with the
// paranoid three-player search (ThreePlayerPlayer::analyse_move, spec of shw_player_new3 with this node budget): the child is searched for
// the mover as root player, so no negation is needed, and the same mate shift applies. Returns the number of moves scored, or -1 a move in
// the list is not legal, -2 the game is over, -3 cap too small, -4 the player could not be created (shw_error), -5 nodes < 1.
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
    const bool long_hop = pos.rules().jump_rule() == sh::RuleSet::JumpRule::Long;
    if (pos.rules().players() == 3) {
        return analyse3(pos, nodes, long_hop, std::span<const int>(chosen).first(static_cast<std::size_t>(n)), moves_out, scores_out, cap);
    }
    if (!g_analysis || g_analysis_nodes != nodes || g_analysis_long != long_hop) {
        const auto spec = sh::PlayerSpec::parse((long_hop ? kAnalysisLongSpecPrefix : kAnalysisSpecPrefix) + std::to_string(nodes));
        if (!spec) { g_error = spec.error(); return -4; }
        auto player = spec->create();
        if (!player) { g_error = player.error(); return -4; }
        g_analysis = std::move(*player);
        g_analysis_nodes = nodes;
        g_analysis_long = long_hop;
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
// The static evaluation of the current three-player position: three integers, one per absolute player 0, 1, 2 (Java: ThreePlayerPlayer.evaluation),
// written to out3[0..2]. No search. Returns 0, or -1 if the game does not have three players (shw_error() tells), -2 the rule cannot be
// served (never at present).
SHW_API int shw_evaluate3(std::int32_t* out3) {
    if (g_pos->rules().players() != 3) { g_error = "the static three-player evaluation needs a three-player game"; return -1; }
    const bool long_hop = g_pos->rules().jump_rule() == sh::RuleSet::JumpRule::Long;
    const auto spec = sh::ThreePlayerSpec::parse(std::string("ab3:eval=linear-r2,tt=1,nodes=1") + (long_hop ? ",jump=long" : ""));
    if (!spec) { g_error = spec.error(); return -2; }
    auto player = spec->create();
    if (!player) { g_error = player.error(); return -2; }
    const std::array<int, 3> e = (*player)->evaluation(*g_pos);
    const std::span<std::int32_t> out(out3, 3);
    for (std::size_t i = 0; i < 3; ++i) out[i] = e[i];
    return 0;
}
// Nodes searched by the last shw_analyse call, all moves together.
SHW_API double shw_analysis_nodes() { return static_cast<double>(g_analysis_visited); }
SHW_API int shw_rows1_requests() { return sh::RaceEvaluator::rows1_requests(); }

#pragma clang unsafe_buffer_usage end
