#include "race_adjudication.hpp"

#include <algorithm>
#include <limits>

#include "geometry.hpp"

namespace sh {

bool RaceAdjudication::passed(const Position& position) {
    if (position.rules().players() != 2 || position.rules().foreign_camps() != RuleSet::ForeignCamps::Closed) return false;
    int min0 = std::numeric_limits<int>::max();
    int max1 = std::numeric_limits<int>::min();
    int n0 = 0;
    int n1 = 0;
    for (int c = 0; c < kCells; ++c) {
        const int o = position.owner(c);
        if (o == 0) {
            ++n0;
            min0 = std::min(min0, geometry::r(c));
        } else if (o == 1) {
            ++n1;
            max1 = std::max(max1, geometry::r(c));
        }
    }
    return n0 == kPieces && n1 == kPieces && min0 > max1;
}

}  // namespace sh
