// Race play (Java: RaceMove): once the armies have passed (RaceAdjudication::passed), the side to move plays its solitaire instead of
// searching. Among the legal moves it takes the one whose resulting configuration has the smallest race table count (ties: larger
// distance gain, then move order); when the table does not cover the configuration, the first move of a beam plan whose landings all lie
// above the opponent's rearmost piece; when neither gives a legal move, choose() returns -1 and the caller searches. Every returned move
// is taken from the position's legal move list. Mode 2 races only while the table arithmetic does not say the race is lost. Two players
// only. Not thread-safe; one instance per player. The Java option racetable=0 (beam only, no table) is not ported.
#pragma once

#include <array>
#include <cstdint>
#include <memory>
#include <optional>

#include "position.hpp"
#include "race_table.hpp"
#include "solitaire.hpp"

namespace sh {

class RaceMove {
public:
    static constexpr int kBeamWidth = 200;
    static constexpr int kMaxPlanMoves = 60;

    // mode: 1 always races in a passed position, 2 only while the race is not known to be lost; table: shared, never null
    RaceMove(std::shared_ptr<const RaceTable> table, int mode) : table_(std::move(table)), mode_(mode) {}

    // The race table of a region (1 or 2), loaded once per process and shared; the error is the load error text.
    static std::expected<std::shared_ptr<const RaceTable>, std::string> shared_table(int rows);
    // The browser path: shared_table(2) returns this table (built from bytes) instead of reading rows2.bin from disk. Call it before
    // the first player is created; nullptr removes it again. Not thread-safe.
    static void install_rows2(std::shared_ptr<const RaceTable> table);

    int mode() const { return mode_; }
    int table_min_row() const { return table_->min_row(); }
    std::int64_t table_moves() const { return table_moves_; }
    std::int64_t plan_moves() const { return plan_moves_; }

    // A legal race move for the side to move, or -1 to let the search decide.
    int choose(const Position& position);

private:
    Solitaire& solitaire(int player, const RuleSet& rules);

    std::shared_ptr<const RaceTable> table_;
    int mode_;
    std::array<std::optional<Solitaire>, 2> solitaire_;
    std::optional<RuleSet::LeaveGoal> leave_goal_;
    std::int64_t table_moves_ = 0;
    std::int64_t plan_moves_ = 0;
};

}  // namespace sh
