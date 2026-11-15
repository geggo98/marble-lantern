// The score scale and the score bands of the alpha-beta search (Java: Scores and the constants of AlphaBeta).
// Scores are integers from the side to move. A win at search height h scores kMate - h; a game that ends without a winner scores its
// outcome value times kEndScale. The band of a score tells a consumer how far to trust it.
#pragma once

#include <cstdint>

namespace sh {

namespace scores {

inline constexpr int kMate = 1'000'000;
inline constexpr int kEndScale = 10'000;
// Scores at least this far from zero are wins or losses in a known number of plies.
inline constexpr int kMateBound = kMate - 1000;
// The lowest score of the race band; equal to RaceEvaluator::kRaceBase (checked in scores.cpp).
inline constexpr int kRaceBase = 5000;

enum class Kind : std::uint8_t {
    Ordinary,  // the evaluator's own scale
    Race,      // exact race arithmetic as a leaf value: kRaceBase <= |score| < kMateBound
    Mate,      // |score| >= kMateBound: a forced win or loss inside the search tree
};

// The band of a score, from either side's point of view.
constexpr Kind kind(int score) {
    const int a = score < 0 ? -score : score;
    if (a >= kMateBound) return Kind::Mate;
    return a >= kRaceBase ? Kind::Race : Kind::Ordinary;
}

// The Java enum names, as the fingerprint prints them.
const char* kind_name(Kind kind);

}  // namespace scores

}  // namespace sh
