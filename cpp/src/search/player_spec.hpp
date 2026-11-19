// A player described by text, e.g. "ab:eval=linear-r1,pvs=1,killers=1,nodes=20000" (Java: PlayerSpec).
//
// Keys: eval (linear-r1, linear-r3, racelin1-r3), nodes (per move, default 20000), depth (default 30), tt (log2 table entries,
// default 18), the search options pvs, killers, history, ttrep, mate, partial (0 or 1), lmr (0..3), asp (1 for the default window or a
// half-width), fut, futk, fut2, the race keys race (0, 1 or 2) and racerows (1 or 2, needs race), and jump (adjacent or long; long is
// refused together with race=1, race=2 and the evaluators racelin1-r3 and racelin-r3, whose race table and arithmetic count single
// jumps only). An unknown key is an error, as in Java; so are the evaluators "distance" and "linear-r2" ("not ported"). The Java keys ms, racetable and the evaluator weights
// (w...) are not accepted: no measured spec uses them. A node budget, not a time budget, is the default, so that results do not
// depend on machine load.
#pragma once

#include <cstdint>
#include <expected>
#include <memory>
#include <string>
#include <string_view>

#include "alpha_beta.hpp"
#include "alpha_beta_player.hpp"
#include "options.hpp"

namespace sh {

struct PlayerSpec {
    static constexpr std::int64_t kDefaultNodes = 20'000;
    static constexpr int kDefaultDepth = 30;
    static constexpr int kDefaultTableBits = 18;

    std::string text;
    std::string evaluator = "";
    std::int64_t nodes = kDefaultNodes;
    int depth = kDefaultDepth;
    int table_bits = kDefaultTableBits;
    SearchOptions options;
    int race = 0;       // 0 off, 1 or 2 the race mode
    int race_rows = 2;  // the region of the race table: 2 (default) or 1
    RuleSet::JumpRule jump_rule = RuleSet::JumpRule::Adjacent;  // key jump: adjacent (default) or long; the rule of the games the player plays

    static std::expected<PlayerSpec, std::string> parse(std::string_view text);

    // The evaluator of this spec, fresh (an evaluator owns scratch space). Fails if the race table of racelin1-r3 cannot be loaded.
    std::expected<std::unique_ptr<Evaluator>, std::string> make_evaluator() const;
    // A new search with this spec's evaluator, table size and options (no player, no race play): what the positions part of the
    // fingerprint uses.
    std::expected<std::unique_ptr<AlphaBeta>, std::string> make_searcher() const;
    // A new player; call once per game, because a player keeps its transposition table.
    std::expected<std::unique_ptr<AlphaBetaPlayer>, std::string> create() const;
};

// The browser player for the Halma long hop (Java: Strategies.BROWSER_LONG_SPEC): the browser spec without race play, with the base
// evaluator linear-r3 and jump=long. A weaker player: the evaluator's jump features still count single jumps.
inline constexpr const char* kBrowserLongSpec =
    "ab:eval=linear-r3,pvs=1,killers=1,history=1,lmr=2,asp=1,ttrep=1,mate=1,partial=1,fut=1,futk=20,fut2=1,jump=long";

}  // namespace sh
