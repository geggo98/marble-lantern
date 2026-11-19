// The end of a game (Java: Outcome). values holds one entry per player (the first `players` entries; the others are zero) and sums
// to zero. A win gives the winner +1 and every other player -1 / (players - 1). A game without a winner gives all zeros with
// DrawHandling::Draw, and a score from the remaining single-agent distances with DrawHandling::ScoreByDistance.
// `ranking` lists the absolute players by rank, best first (`ranking_size` entries): just the winner (or none) with FirstWinnerOnly,
// all players whenever the game is over with FullRanking and three players. `winner` is set only for Reason::Win.
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
    std::array<int, 3> ranking{};
    int ranking_size = 0;

    bool has_winner() const { return reason == Reason::Win; }
    double value(int player) const { return values[static_cast<std::size_t>(player)]; }

    static Outcome win(int winner, int players = 2);
    // A game that ended with the complete ranking `order` (players entries); winner is order[0] for Reason::Win, else -1.
    static Outcome ranked(Reason reason, const std::array<int, 3>& order, const std::array<double, 3>& values, int players);
    static Outcome no_winner(Reason reason, const std::array<double, 3>& values, int players = 2);

    friend bool operator==(const Outcome&, const Outcome&) = default;
    // The Java names of the reasons: WIN, MOVE_LIMIT, REPETITION, NO_MOVES.
    static const char* reason_name(Reason reason);
    std::string to_string() const;
};

}  // namespace sh
