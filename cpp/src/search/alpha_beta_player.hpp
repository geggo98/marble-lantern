// The alpha-beta search as a player (Java: AlphaBetaPlayer): searches to a fixed depth, for a time or for a number of nodes, keeps its
// transposition table for the whole game and counts nodes and time. With a RaceMove (spec key race), a passed two-player position is played by the race module when it offers a legal move; such a
// move costs no search nodes and is counted in race_moves().
#pragma once

#include <chrono>
#include <cstdint>
#include <memory>
#include <optional>
#include <string>

#include "alpha_beta.hpp"
#include "race_move.hpp"

namespace sh {

class AlphaBetaPlayer {
public:
    AlphaBetaPlayer(std::unique_ptr<Evaluator> evaluator, int max_depth, std::int64_t time_limit_millis, std::int64_t max_nodes,
                    int table_bits, const std::string& evaluator_name, const SearchOptions& options, std::unique_ptr<RaceMove> race = nullptr,
                    RuleSet::JumpRule jump_rule = RuleSet::JumpRule::Adjacent, RuleSet::TriangleRows rows = RuleSet::TriangleRows::Four);

    // Chooses a move for the side to move of `position` (-1 if there is none). Throws std::invalid_argument if the position has another
    // jump rule or house size than the player (the Java player throws IllegalArgumentException); callers that cannot throw check plays() first.
    int choose(const Position& position);

    // The search result of the last choose() call; empty before the first call or after clear_search().
    const std::optional<SearchResult>& last_result() const { return last_result_; }
    // The band of the last search score, or empty (as for lastResult).
    std::optional<scores::Kind> last_score_kind() const {
        if (!last_result_) return std::nullopt;
        return scores::kind(last_result_->score);
    }

    // Forgets the table, the killers, the history and the last result, so that the next game plays as with a fresh player of the same
    // spec. The counters keep counting.
    void clear_search();

    const std::string& name() const { return name_; }
    // The jump rule of the games this player plays; race play is never combined with Long (the constructor refuses it).
    RuleSet::JumpRule jump_rule() const { return jump_rule_; }
    // The house size of the games this player plays. The race module knows the 10-cell houses only and returns no move on Five; PlayerSpec refuses rows=5 together with race play.
    RuleSet::TriangleRows rows() const { return rows_; }
    bool plays(const Position& position) const {
        return position.rules().jump_rule() == jump_rule_ && position.rules().triangle_rows() == rows_;
    }
    std::int64_t total_nodes() const { return total_nodes_; }
    std::chrono::nanoseconds total_time() const { return total_time_; }
    std::int64_t moves() const { return moves_; }
    std::int64_t race_moves() const { return race_moves_; }
    // The race module of this player, or null.
    const RaceMove* race() const { return race_.get(); }

private:
    AlphaBeta search_;
    std::unique_ptr<RaceMove> race_;
    std::string name_;
    RuleSet::JumpRule jump_rule_;
    RuleSet::TriangleRows rows_;
    int max_depth_;
    std::int64_t time_limit_millis_;
    std::int64_t max_nodes_;
    std::int64_t total_nodes_ = 0;
    std::chrono::nanoseconds total_time_{0};
    std::int64_t moves_ = 0;
    std::int64_t race_moves_ = 0;
    std::optional<SearchResult> last_result_;
};

}  // namespace sh
