// The residual priors of the plane set classic1 (Java: ResidualPriors; docs/network.md, "The residual priors").
// Float arithmetic in exactly the order of Java, so that the logits stay bit-identical:
//   policy[c][FROM] += beta * 16 * dist_own[c]   policy[c][TO] -= the same term
//   value_i = tanh(net_i + k * sign_i * eval)    (this header adds the term; the tanh is the caller's)
// `input` is the encoded input of ONE position, [122][planes] with `stride` = planes floats per row.
#pragma once

#include <span>

namespace sh::nn {

// +1 for relative player 0, -1 for player 1, 0 for player 2.
int value_sign(int relative_player);

// ResidualPriors.valueBase: sign * input[row 0][EVAL]
float value_base(std::span<const float> input, int stride, int relative_player);

// ResidualPriors.addPolicy: `policy` is the 242 logits of the position.
void add_policy_prior(float beta, std::span<const float> input, int stride, std::span<float> policy);

// values[i] += k * value_base(input, stride, i), for every player (before the tanh).
void add_value_prior(float k, std::span<const float> input, int stride, std::span<float> values);

}  // namespace sh::nn

namespace sh::nn {

// Evaluator: the value of absolute player a of a position with n = `players` players and `mover` to move is output
// (a - mover) mod n of the network's value head (relative player order). A classic1v network has three outputs and serves n = 2
// and n = 3, so only the first n outputs are used; every other network has exactly n outputs.
// `absolute` has n elements. Returns false (and writes nothing) if n < 2, n > outputs.size(), mover is not in [0, n) or
// `absolute` has the wrong size.
bool absolute_values(std::span<const float> outputs, int players, int mover, std::span<float> absolute);

}  // namespace sh::nn

namespace sh::nn {

// TornadoNetwork.addPriors, the host work after a device pass: for `n` positions of `input` ([n][122][stride]) with the raw
// device outputs in `logits` ([n][242], the logits as they are) and `values` ([n][players], before the tanh): adds the policy
// prior, adds k * value_base to the value and applies the tanh (float(std::tanh(double))), as VectorNetwork does.
void apply_host_priors(float beta, float k, std::span<const float> input, int stride, int players, int n, std::span<float> logits,
                       std::span<float> values);

}  // namespace sh::nn
