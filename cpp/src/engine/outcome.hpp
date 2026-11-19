// The end of a game (Java: Outcome). values holds one entry per player (the first `players` entries; the others are zero) and sums
// to zero. A win gives the winner +1 and every other player -1 / (players - 1). A game without a winner gives all zeros with
// DrawHandling::Draw, and a score from the remaining single-agent distances with DrawHandling::ScoreByDistance.
#pragma once

#include <array>
#include <cstdint>
#include <string>

namespace sh {

struct Outcome {
    enum class Reason : std::uint8_t {
        Win,
        MoveLimit,
        Repetition,
        NoMoves,  // the player to move has no legal move
    };

    Reason reason = Reason::MoveLimit;
    int winner = -1;
    int players = 2;
    std::array<double, 3> values{};

    bool has_winner() const { return reason == Reason::Win; }
    double value(int player) const { return values[static_cast<std::size_t>(player)]; }

    static Outcome win(int winner, int players = 2);
    static Outcome no_winner(Reason reason, const std::array<double, 3>& values, int players = 2);

    friend bool operator==(const Outcome&, const Outcome&) = default;
    // The Java names of the reasons: WIN, MOVE_LIMIT, REPETITION, NO_MOVES.
    static const char* reason_name(Reason reason);
    std::string to_string() const;
};

}  // namespace sh
