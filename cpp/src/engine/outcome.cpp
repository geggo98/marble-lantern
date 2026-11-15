#include "outcome.hpp"

namespace sh {

Outcome Outcome::win(int winner) {
    Outcome o;
    o.reason = Reason::Win;
    o.winner = winner;
    o.values = {-1.0, -1.0};  // -1 / (players - 1) with two players
    o.values[static_cast<std::size_t>(winner)] = 1.0;
    return o;
}

Outcome Outcome::no_winner(Reason reason, const std::array<double, 2>& values) {
    Outcome o;
    o.reason = reason;
    o.winner = -1;
    o.values = values;
    return o;
}

const char* Outcome::reason_name(Reason reason) {
    switch (reason) {
        case Reason::Win: return "WIN";
        case Reason::MoveLimit: return "MOVE_LIMIT";
        case Reason::Repetition: return "REPETITION";
        case Reason::NoMoves: return "NO_MOVES";
    }
    return "?";
}

std::string Outcome::to_string() const {
    return std::string("Outcome[") + reason_name(reason) + ", winner=" + std::to_string(winner) + ", values=[" +
           std::to_string(values[0]) + ", " + std::to_string(values[1]) + "]]";
}

}  // namespace sh
