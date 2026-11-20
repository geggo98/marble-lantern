#include "alpha_beta_player.hpp"

#include <stdexcept>
#include <utility>

namespace sh {

AlphaBetaPlayer::AlphaBetaPlayer(std::unique_ptr<Evaluator> evaluator, int max_depth, std::int64_t time_limit_millis,
                                 std::int64_t max_nodes, int table_bits, const std::string& evaluator_name, const SearchOptions& options, std::unique_ptr<RaceMove> race,
                                 RuleSet::JumpRule jump_rule, RuleSet::TriangleRows rows)
    : search_(std::move(evaluator), table_bits, true, options),
      race_(std::move(race)),
      name_("ab[eval=" + evaluator_name + ", depth<=" + std::to_string(max_depth) +
            (time_limit_millis == AlphaBeta::kNoLimit ? "" : ", " + std::to_string(time_limit_millis) + " ms") +
            (max_nodes == AlphaBeta::kNoLimit ? "" : ", " + std::to_string(max_nodes) + " nodes") +
            (options.is_default() ? "" : ", " + options.describe()) +
            (jump_rule == RuleSet::JumpRule::Long ? ", jump=long" : "") +
            (rows == RuleSet::TriangleRows::Five ? ", rows=5" : "") +
            (race_ ? ", race=" + std::to_string(race_->mode()) + (race_->table_min_row() == 1 ? ", racerows=1" : "") : "") + "]"),
      jump_rule_(jump_rule),
      rows_(rows),
      max_depth_(max_depth),
      time_limit_millis_(time_limit_millis),
      max_nodes_(max_nodes) {
    if (jump_rule == RuleSet::JumpRule::Long && race_) {
        throw std::invalid_argument("race play is computed for single jumps and cannot play jump=long");
    }
}

int AlphaBetaPlayer::choose(const Position& position) {
    if (position.rules().triangle_rows() != rows_) {
        throw std::invalid_argument("player " + name_ + " plays rows=" + (rows_ == RuleSet::TriangleRows::Five ? "5" : "4") +
                                    " but the position has rows=" + (position.rules().triangle_rows() == RuleSet::TriangleRows::Five ? "5" : "4"));
    }
    if (!plays(position)) {
        throw std::invalid_argument("player " + name_ + " plays jump=" + (jump_rule_ == RuleSet::JumpRule::Long ? "long" : "adjacent") +
                                    " but the position has jump=" + (position.rules().jump_rule() == RuleSet::JumpRule::Long ? "long" : "adjacent"));
    }
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
