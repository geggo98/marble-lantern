#include "linear_weights.hpp"

namespace sh::linear_weights {

static_assert(kR1.size() == 12 && kRound3.size() == 13, "R1 has 12 weights, ROUND3 has 13");
static_assert(kRound3[12] == 0.156608, "the bestGain weight is the last entry of ROUND3");

}  // namespace sh::linear_weights
