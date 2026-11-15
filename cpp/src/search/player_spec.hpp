// A player described by text, e.g. "ab:eval=linear-r1,pvs=1,killers=1,nodes=20000" (Java: PlayerSpec).
//
// Keys: eval (linear-r1, linear-r3, racelin1-r3), nodes (per move, default 20000), depth (default 30), tt (log2 table entries,
// default 18), the search options pvs, killers, history, ttrep, mate, partial (0 or 1), lmr (0..3), asp (1 for the default window or a
// half-width), fut, futk, fut2, and the race keys race (0, 1 or 2) and racerows (1 or 2, needs race). An unknown key is an error, as in
// Java; so are the evaluators "distance" and "linear-r2" ("not ported"). The Java keys ms, racetable and the evaluator weights
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

    static std::expected<PlayerSpec, std::string> parse(std::string_view text);

    // The evaluator of this spec, fresh (an evaluator owns scratch space). Fails if the race table of racelin1-r3 cannot be loaded.
    std::expected<std::unique_ptr<Evaluator>, std::string> make_evaluator() const;
    // A new search with this spec's evaluator, table size and options (no player, no race play): what the positions part of the
    // fingerprint uses.
    std::expected<std::unique_ptr<AlphaBeta>, std::string> make_searcher() const;
    // A new player; call once per game, because a player keeps its transposition table.
    std::expected<std::unique_ptr<AlphaBetaPlayer>, std::string> create() const;
};

}  // namespace sh
