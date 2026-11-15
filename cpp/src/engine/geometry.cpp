#include "geometry.hpp"

namespace sh::geometry {

std::string cell_to_string(int cell) {
    return "(" + std::to_string(q(cell)) + "," + std::to_string(r(cell)) + ")";
}

}  // namespace sh::geometry
