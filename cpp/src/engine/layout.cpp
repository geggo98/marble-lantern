#include "layout.hpp"

namespace sh {

// The tables are built at compile time; these assertions pin the values that every later module relies on.
static_assert(detail::kTwoPlayerLayout.valid());
static_assert(detail::kTwoPlayerLayout.start_corner(0) == corner::kRNeg);
static_assert(detail::kTwoPlayerLayout.goal_corner(0) == corner::kRPos);
static_assert(detail::kTwoPlayerLayout.max_distance(0) == 13);
static_assert(detail::kTwoPlayerLayout.max_distance(1) == 13);

}  // namespace sh
