// The weight vectors of the linear evaluators (Java: LinearWeights). The literals are the decimal digits of the Java source;
// the C++ compiler and Java's parser both round a decimal literal to the nearest double, so the values are identical.
// R1 (12 features), ROUND2 (12 features, the three-player evaluation) and ROUND3 (13 features, with bestGain) are ported: no measured spec uses the others.
#pragma once

#include <array>

namespace sh::linear_weights {

// Score units per logit unit.
inline constexpr double kScale = 100.0;

// linear-r1 (Java: LinearWeights.R1, equal to LinearWeights.WEIGHTS).
inline constexpr std::array<double, 12> kR1 = {
    -0.361185,    // dsum
    -0.353700,    // dmax
    -0.120344,    // dsq
    0.257194,     // inGoal
    0.0330329,    // inStart
    0.0581328,    // spread
    -0.0303039,   // lateral
    0.119662,     // jumps
    -0.00720263,  // adjacent
    0.0532558,    // steps
    0.270701,     // dsumPhase
    0.229450,     // tempo
};

// linear-r2 (Java: LinearWeights.ROUND2), the evaluation of the three-player player.
inline constexpr std::array<double, 12> kRound2 = {
    -0.264514,   // dsum
    -0.385392,   // dmax
    -0.167810,   // dsq
    0.230892,    // inGoal
    0.110844,    // inStart
    0.119476,    // spread
    -0.109568,   // lateral
    0.0946884,   // jumps
    -0.0295270,  // adjacent
    0.0637964,   // steps
    0.223242,    // dsumPhase
    0.282466,    // tempo
};

// linear-r3 (Java: LinearWeights.ROUND3): the 12 features and bestGain.
inline constexpr std::array<double, 13> kRound3 = {
    -0.259472,   // dsum
    -0.386404,   // dmax
    -0.234400,   // dsq
    0.285310,    // inGoal
    0.142903,    // inStart
    0.0773203,   // spread
    -0.116410,   // lateral
    0.0949370,   // jumps
    -0.0580585,  // adjacent
    0.0452636,   // steps
    0.261648,    // dsumPhase
    0.327666,    // tempo
    0.156608,    // bestGain
};

}  // namespace sh::linear_weights
