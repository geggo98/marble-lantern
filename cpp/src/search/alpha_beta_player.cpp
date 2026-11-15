#include "alpha_beta_player.hpp"

#include <utility>

namespace sh {

AlphaBetaPlayer::AlphaBetaPlayer(std::unique_ptr<Evaluator> evaluator, int max_depth, std::int64_t time_limit_millis,
                                 std::int64_t max_nodes, int table_bits, const std::string& evaluator_name, const SearchOptions& options, std::unique_ptr<RaceMove> race)
    : search_(std::move(evaluator), table_bits, true, options),
      race_(std::move(race)),
      name_("ab[eval=" + evaluator_name + ", depth<=" + std::to_string(max_depth) +
            (time_limit_millis == AlphaBeta::kNoLimit ? "" : ", " + std::to_string(time_limit_millis) + " ms") +
            (max_nodes == AlphaBeta::kNoLimit ? "" : ", " + std::to_string(max_nodes) + " nodes") +
            (options.is_default() ? "" : ", " + options.describe()) +
            (race_ ? ", race=" + std::to_string(race_->mode()) + (race_->table_min_row() == 1 ? ", racerows=1" : "") : "") + "]"),
      max_depth_(max_depth),
      time_limit_millis_(time_limit_millis),
      max_nodes_(max_nodes) {}

int AlphaBetaPlayer::choose(const Position& position) {
    const auto t0 = std::chrono::steady_clock::now();
    if (race_ && position.rules().players() == 2) {
        const int move = race_->choose(position);
        if (move >= 0) {
            total_time_ += std::chrono::steady_clock::now() - t0;
            ++moves_;
            ++race_moves_;
            last_result_.reset();
            return move;
        }
    }
    const SearchResult result = search_.search(position, max_depth_, time_limit_millis_, max_nodes_);
    total_time_ += std::chrono::steady_clock::now() - t0;
    total_nodes_ += result.nodes;
    ++moves_;
    last_result_ = result;
    return result.move;
}

void AlphaBetaPlayer::clear_search() {
    search_.clear();
    last_result_.reset();
}

}  // namespace sh
