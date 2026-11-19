// The three-player player and its text spec (Java: ThreePlayerPlayer, ThreePlayerSpec, Strategies.BROWSER_3P_SPEC).
//
// A spec is "ab3:" followed by key=value pairs: eval (linear-r2, the default, linear-r1 or linear: the 12-feature linear weights; the
// race evaluators are two-player only), nodes (per move, default 20000), depth (default 30), ms (time per move, default none; makes the
// result machine dependent), tt (log2 table entries, default 16) and jump (adjacent or long, default adjacent: the rule of the games
// the player plays). An unknown key is an error, as in Java.
#pragma once

#include <chrono>
#include <cstdint>
#include <expected>
#include <memory>
#include <optional>
#include <string>
#include <string_view>

#include "alpha_beta.hpp"
#include "rules.hpp"
#include "three_player_search.hpp"

namespace sh {

// The classical three-player player: ThreePlayerSearch on a node, depth or time budget. It keeps a transposition table for the whole
// game and needs no move history. The seat is the side to move of the position it is asked about, so one player may play every seat.
class ThreePlayerPlayer {
public:
    ThreePlayerPlayer(std::span<const double> weights, const std::string& evaluator_name, int max_depth, std::int64_t time_limit_millis,
                      std::int64_t max_nodes, int table_bits, RuleSet::JumpRule jump_rule);

    // Chooses a move for the side to move of `position` (-1 if there is none). Throws std::invalid_argument if the position does not
    // have three players or has another jump rule than the player; callers that cannot throw check plays() first.
    int choose(const Position& position);

    // The search result of the last choose() call, or empty (before the first call or after clear_search()).
    const std::optional<SearchResult>& last_result() const { return last_result_; }

    // The analysis score of one legal move of `position` (Java: ThreePlayerPlayer.analyseMove): the move is made and the child position is
    // searched for the mover within this player's node budget (paranoid score, higher is better for the mover). The table is cleared
    // first, so the score depends on the position and the move only. Throws std::invalid_argument like choose(). Does not touch
    // last_result(); counts the nodes in total_nodes() and the time.
    SearchResult analyse_move(const Position& position, int move);

    // The static score of the position for each absolute player 0, 1, 2 (Java: ThreePlayerPlayer.evaluation), no search. Throws like choose().
    std::array<int, 3> evaluation(const Position& position);

    // Forgets the table, the killers and the last result, so that the next game plays as with a fresh player of the same spec.
    // The counters keep counting.
    void clear_search();

    const std::string& name() const { return name_; }
    RuleSet::JumpRule jump_rule() const { return jump_rule_; }
    bool plays(const Position& position) const { return position.rules().players() == 3 && position.rules().jump_rule() == jump_rule_; }
    std::int64_t total_nodes() const { return total_nodes_; }
    std::chrono::nanoseconds total_time() const { return total_time_; }
    std::int64_t moves() const { return moves_; }

private:
    void check_plays(const Position& position) const;

    std::vector<double> weights_;
    int table_bits_;
    ThreePlayerSearch search_;
    std::string name_;
    RuleSet::JumpRule jump_rule_;
    int max_depth_;
    std::int64_t time_limit_millis_;
    std::int64_t max_nodes_;
    std::int64_t total_nodes_ = 0;
    std::chrono::nanoseconds total_time_{0};
    std::int64_t moves_ = 0;
    std::optional<SearchResult> last_result_;
};

struct ThreePlayerSpec {
    static constexpr std::int64_t kDefaultNodes = 20'000;
    static constexpr int kDefaultDepth = 30;
    static constexpr int kDefaultTableBits = 16;

    std::string text;
    std::string evaluator = "linear-r2";
    std::int64_t nodes = kDefaultNodes;
    int depth = kDefaultDepth;
    std::int64_t millis = ThreePlayerSearch::kNoLimit;
    int table_bits = kDefaultTableBits;
    RuleSet::JumpRule jump_rule = RuleSet::JumpRule::Adjacent;

    static std::expected<ThreePlayerSpec, std::string> parse(std::string_view text);
    // A new player; call once per game, because a player keeps its transposition table.
    std::expected<std::unique_ptr<ThreePlayerPlayer>, std::string> create() const;
};

// The browser player for three players (Java: Strategies.BROWSER_3P_SPEC) and the same for the Halma long hop (BROWSER_3P_LONG_SPEC).
inline constexpr const char* kBrowser3pSpec = "ab3:eval=linear-r2,nodes=20000,tt=16";
inline constexpr const char* kBrowser3pLongSpec = "ab3:eval=linear-r2,nodes=20000,tt=16,jump=long";

}  // namespace sh
