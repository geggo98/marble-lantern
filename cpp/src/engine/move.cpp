#include "move.hpp"

#include "geometry.hpp"

namespace sh {

std::string Move::to_string(int move) {
    return geometry::cell_to_string(from(move)) + "->" + geometry::cell_to_string(to(move));
}

}  // namespace sh
