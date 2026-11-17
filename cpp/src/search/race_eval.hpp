// Exact race arithmetic as a leaf value (Java: RaceEvaluator, spec name racelin1 over a base evaluator; racelin1-r3 over linear-r3).
// In a passed two-player position (RaceAdjudication::passed) whose two configurations the race table both covers, the value is the
// conversion rule of docs/endgame-and-opening.md: with a the table count of the side to move, b the opponent's and L the plies left
// before the move limit, the side to move wins if a <= b and 2a - 1 <= L, the opponent wins if b < a and 2b <= L. The score is
// RACE_BASE + UNIT * (b - a) for a won race and -(RACE_BASE + UNIT * (a - b - 1)) for a lost one, so every race value lies in the
// race band of the scores. Everywhere else (contact positions, a configuration outside the table, nobody finishes before the
// limit, blocking_states = Illegal) the wrapped evaluator decides.
//
// Deviation from Java: the weight wout=1 (estimate the count of pieces behind the region) is not ported. No measured spec uses it
// and the player spec of WP5 does not accept the key; the constructor has no estimate switch.
//
// Not thread-safe (scratch space for the table lookups); one instance per searcher. The table is shared and read only.
#pragma once

#include <array>
#include <cstdint>
#include <expected>
#include <limits>
#include <memory>

#include "evaluator.hpp"
#include "position.hpp"
#include "race_table.hpp"

namespace sh {

class RaceEvaluator final : public Evaluator {
public:
    // Score units per move of race margin; 20 moves of margin stay below the mate band.
    static constexpr int kUnit = 200;
    // The lowest score of the race band (Java: Scores.RACE_BASE). scores.hpp of WP5 must hold the same value.
    static constexpr int kRaceBase = 5000;
    // Returned by race_score when nobody finishes before the move limit (the evaluator falls back).
    static constexpr int kUndecided = std::numeric_limits<int>::min();

    // `base` decides every position without a race value; `table` null makes this evaluator identical to `base`.
    RaceEvaluator(std::unique_ptr<Evaluator> base, std::shared_ptr<const RaceTable> table);
    // The wrapped state points to this evaluator (see LinearEvaluator): one address for its whole life.
    RaceEvaluator(const RaceEvaluator&) = delete;
    RaceEvaluator& operator=(const RaceEvaluator&) = delete;

    // `racelin1-r3`: the race arithmetic of the rows >= 1 table over linear-r3 (the evaluator of BEST_SPEC and of R3).
    static std::unique_ptr<RaceEvaluator> racelin1_r3(std::shared_ptr<const RaceTable> rows1);
    // The rows >= 1 table from STERNHALMA_TABLE_DIR or tablebase/tables, loaded once per process and shared (Java: RaceTable.rows1()).
    static const std::expected<std::shared_ptr<const RaceTable>, LoadError>& rows1_table();
    // How often rows1_table() was called in this process (loaded or not): a test proves that a player never asks for it.
    static int rows1_requests();
    // `racelin-r3`: the same arithmetic and weights over the rows >= 2 table (the evaluator of BROWSER_SPEC; the table is the
    // one of RaceMove::shared_table(2), 12.5 MB, so no code path of the browser spec touches rows1).
    static std::unique_ptr<RaceEvaluator> racelin_r3(std::shared_ptr<const RaceTable> rows2);

    const Evaluator& base() const { return *base_; }
    const RaceTable* table() const { return table_.get(); }

    // The rule preconditions: two players and no blocking-state rule (the table knows nothing about it).
    static bool rules_apply(const RuleSet& rules) {
        return rules.players() == 2 && rules.blocking_states() == RuleSet::BlockingStates::None;
    }
    // RaceAdjudication::passed without the scan over the board: the piece lists are sorted by cell id, and cell ids are ordered by row
    // r first, so player 0's rearmost row is that of its first piece and player 1's rearmost row (towards player 0) that of its last.
    static bool passed(const Position& position);
    // The race value of counts a (side to move) and b (opponent) with plies_left plies before the move limit, or kUndecided.
    static int race_score(int a, int b, int plies_left);

    int evaluate(const Position& position) override;
    bool futility_safe(const Position& position, int depth) override;
    // An incremental state if the wrapped evaluator has one; the race value is cheap to form from scratch. The state points to this
    // evaluator, which must outlive it.
    std::unique_ptr<EvalState> incremental() override;

    // The race value of the position, or kUndecided if the wrapped evaluator decides.
    int race_value(const Position& position);

private:
    // The table count of a player's configuration in player 0's orientation, or -1 if the table does not cover it.
    int count(const Position& position, int player);

    std::unique_ptr<Evaluator> base_;
    std::shared_ptr<const RaceTable> table_;
    int region_min_row_ = 0;
    std::array<int, kPieces> scratch_{};
};

}  // namespace sh
