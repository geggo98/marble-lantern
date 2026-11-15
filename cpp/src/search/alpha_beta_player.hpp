// The alpha-beta search as a player (Java: AlphaBetaPlayer): searches to a fixed depth, for a time or for a number of nodes, keeps its
// transposition table for the whole game and counts nodes and time. Race play (spec key race) is WP6; until then a spec with race != 0
// is refused by PlayerSpec::create, and race_moves() is always 0.
#pragma once

#include <chrono>
#include <cstdint>
#include <memory>
#include <optional>
#include <string>

#include "alpha_beta.hpp"

namespace sh {

class AlphaBetaPlayer {
public:
    AlphaBetaPlayer(std::unique_ptr<Evaluator> evaluator, int max_depth, std::int64_t time_limit_millis, std::int64_t max_nodes,
                    int table_bits, const std::string& evaluator_name, const SearchOptions& options);

    // Chooses a move for the side to move of `position` (-1 if there is none).
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
    std::int64_t total_nodes() const { return total_nodes_; }
    std::chrono::nanoseconds total_time() const { return total_time_; }
    std::int64_t moves() const { return moves_; }
    std::int64_t race_moves() const { return 0; }

private:
    AlphaBeta search_;
    std::string name_;
    int max_depth_;
    std::int64_t time_limit_millis_;
    std::int64_t max_nodes_;
    std::int64_t total_nodes_ = 0;
    std::chrono::nanoseconds total_time_{0};
    std::int64_t moves_ = 0;
    std::optional<SearchResult> last_result_;
};

}  // namespace sh
