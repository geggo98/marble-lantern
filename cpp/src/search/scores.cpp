#include "scores.hpp"

#include "race_eval.hpp"

namespace sh::scores {

static_assert(kRaceBase == RaceEvaluator::kRaceBase, "the race band of the scores starts where the race evaluator's values start");

const char* kind_name(Kind kind) {
    switch (kind) {
        case Kind::Ordinary: return "ORDINARY";
        case Kind::Race: return "RACE";
        case Kind::Mate: return "MATE";
    }
    return "?";
}

}  // namespace sh::scores
