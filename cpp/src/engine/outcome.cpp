#include "outcome.hpp"

namespace sh {

Outcome Outcome::win(int winner, int players) {
    Outcome o;
    o.reason = Reason::Win;
    o.winner = winner;
    o.players = players;
    for (int p = 0; p < players; ++p) o.values[static_cast<std::size_t>(p)] = -1.0 / (players - 1);
    o.values[static_cast<std::size_t>(winner)] = 1.0;
    return o;
}

Outcome Outcome::no_winner(Reason reason, const std::array<double, 3>& values, int players) {
    Outcome o;
    o.reason = reason;
    o.winner = -1;
    o.players = players;
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
    std::string text;
    for (int p = 0; p < players; ++p) text += (p == 0 ? "" : ", ") + std::to_string(values[static_cast<std::size_t>(p)]);
    return std::string("Outcome[") + reason_name(reason) + ", winner=" + std::to_string(winner) + ", values=[" + text + "]]";
}

}  // namespace sh
