// A move packed into an int: the start cell in the low byte and the end cell above it (Java: Move).
#pragma once

#include <string>

namespace sh {

struct Move {
    static constexpr int of(int from, int to) { return from | (to << 8); }
    static constexpr int from(int move) { return move & 0xFF; }
    static constexpr int to(int move) { return static_cast<int>(static_cast<unsigned>(move) >> 8); }
    // Text form with axial coordinates, e.g. "(0,-5)->(1,-5)".
    static std::string to_string(int move);
};

}  // namespace sh
