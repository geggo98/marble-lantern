// The race criterion S1 (Java: RaceAdjudication). WP4b ports only what the race evaluator needs: the rule precondition and the
// "passed" test. The verdict search (adjudicate, with Solitaire) belongs to WP6.
#pragma once

#include "position.hpp"
#include "rules.hpp"

namespace sh {

class RaceAdjudication {
public:
    // The rule preconditions of the soundness statement: two players, no blocking-state rule, repetition decided by the move limit.
    static bool applicable(const RuleSet& rules) {
        return rules.players() == 2 && rules.blocking_states() == RuleSet::BlockingStates::None &&
               rules.repetition() == RuleSet::Repetition::MoveLimit;
    }

    // Whether the armies have passed each other: every piece of player 0 has a larger r than every piece of player 1 (both with
    // all their pieces on the board). Scans the board, as Java does.
    static bool passed(const Position& position);
};

}  // namespace sh
