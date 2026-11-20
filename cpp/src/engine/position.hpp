// A mutable game position with make/unmake, legal move generation and end-of-game detection (Java: Position).
// Two or three players, default rule family (see rules.hpp). Not thread-safe. The copy constructor is deleted; copy() is explicit, as in Java.
#pragma once

#include <array>
#include <cstdint>
#include <expected>
#include <optional>
#include <span>
#include <string>
#include <vector>

#include "geometry.hpp"
#include "layout.hpp"
#include "move.hpp"
#include "outcome.hpp"
#include "rules.hpp"

namespace sh {

class Position {
public:
    // An empty board with player 0 to move. Use put() to set up a position.
    static Position empty(const RuleSet& rules);
    // The start position: every player fills the own start house (10 or 15 cells, rules.triangle_rows()), player 0 moves first.
    static Position initial(const RuleSet& rules);

    Position(const Position&) = delete;
    Position& operator=(const Position&) = delete;
    Position(Position&&) noexcept = default;
    Position& operator=(Position&&) noexcept = default;
    ~Position() = default;

    Position copy() const;

    // ---- setup (before the first move only) ----

    // Places a piece; the error says why not (player or cell does not exist, cell not allowed or occupied, a full house of pieces already, or a move was played).
    std::expected<void, std::string> put(int player, int cell);
    std::expected<void, std::string> put(int player, int q, int r);
    std::expected<void, std::string> set_side_to_move(int player);
    // Declares a player finished with the next free rank, as if the player had filled the goal (FullRanking with three players only).
    // If the player is to move, the turn passes to the next player who has not finished. Java: Position.finish.
    std::expected<void, std::string> finish(int player);

    // ---- accessors ----

    const RuleSet& rules() const { return rules_; }
    const Layout& layout() const { return *layout_; }
    int side_to_move() const { return side_; }
    // Number of plies played since the position was set up.
    int ply() const { return ply_; }
    // Whether the player has finished (filled the goal with ranking FullRanking, three players); the set as bits; the finishers by rank.
    bool is_finished(int player) const { return (finished_mask_ & (1 << player)) != 0; }
    int finished_mask() const { return finished_mask_; }
    std::span<const int> finishers() const { return std::span<const int>(finishers_).first(static_cast<std::size_t>(finished_count_)); }
    // Zobrist hash of the pieces, the side to move and the set of finished players.
    std::uint64_t hash() const { return hash_; }
    // Owner of a cell, or -1 if it is empty.
    int owner(int cell) const { return owner_[static_cast<std::size_t>(cell)]; }
    // The cells of a player's pieces in ascending order (a view; make and unmake invalidate it).
    std::span<const int> pieces(int player) const {
        const auto ps = static_cast<std::size_t>(player);
        return std::span<const int>(piece_cells_[ps]).first(static_cast<std::size_t>(piece_count_[ps]));
    }
    int piece_count(int player) const { return piece_count_[static_cast<std::size_t>(player)]; }
    // The cell of a player's piece number `index`, counted in ascending cell order (0 <= index < piece_count(player)).
    int piece_cell(int player, int index) const {
        return piece_cells_[static_cast<std::size_t>(player)][static_cast<std::size_t>(index)];
    }
    // Number of cells of the player's goal house that hold a piece of any player.
    int goal_occupied(int player) const { return goal_occupied_[static_cast<std::size_t>(player)]; }
    // Sum of the single-agent distances of all pieces of a player.
    int distance_sum(int player) const;

    // ---- make / unmake ----

    // Plays a move. The move must come from generate().
    void make(int move);
    // Takes back the last move; a no-op when no move has been played (the Java method throws).
    void unmake();

    // ---- legal moves ----

    // Writes the legal moves of the side to move into `out` and returns their number. Order: pieces by ascending cell; per piece
    // the steps by direction, then the jump landings in the order of the depth-first search. The search tree depends on this order.
    int generate(std::span<int, kMaxMoves> out);

    // ---- end of game ----

    // Whether the player meets the winning condition in this position.
    bool has_won(int player) const { return is_win(goal_occupied_[static_cast<std::size_t>(player)], goal_own_[static_cast<std::size_t>(player)]); }
    // The outcome if the game is over. Checks in this order: a win, a repeated position (only with RepetitionIsDraw), the move limit.
    std::optional<Outcome> outcome() const;
    // The outcome to use when the side to move has no legal move.
    Outcome no_moves_outcome() const;

    // The Zobrist keys (tests compare them with the Java ones).
    static std::uint64_t side_key(int player);
    static std::uint64_t piece_key(int player, int cell);
    static std::uint64_t finished_key(int player);

private:
    explicit Position(const RuleSet& rules);

    void insert_piece(int player, int cell);
    void move_piece(int player, int from, int to);
    void update_goal_counts(int player, int cell, int delta);
    bool opponent_goal_nearly_full(int p) const;
    int add_jumps(int p, int from, std::span<int, kMaxMoves> out, int count, Layout::Table jumps, bool stay_in_goal, bool suicide_check,
                  bool home_end);
    int add_long_jumps(int p, int from, std::span<int, kMaxMoves> out, int count, bool stay_in_goal, bool suicide_check, bool home_end);
    int long_landing(int p, int c, int d) const;
    bool hands_opponent_the_win(int p, int from, int to) const;
    bool is_win(int occupied, int own) const;
    bool is_repeated() const;
    std::array<double, 3> draw_values() const;
    void mark_finished(int player);
    int next_active(int p) const;
    void record_finishers(int p);
    std::optional<Outcome> play_on_outcome(bool no_moves) const;
    Outcome unfinished_outcome(Outcome::Reason reason) const;

    RuleSet rules_;
    const Layout* layout_;  // the shared layout of rules_.players()
    bool long_jump_ = false;  // rules_.jump_rule() == Long, cached for generate()
    bool pass_foreign_ = false;  // rules_.foreign_camps() != Closed: a jump chain may land on a foreign corner, cached
    bool end_foreign_ = false;   // rules_.foreign_camps() == Open: a move may end on a foreign corner, cached
    bool play_on_ = false;    // rules_.plays_on_after_win(), cached
    std::array<std::int8_t, kCells> owner_{};
    std::array<int, Layout::kMaxPlayers> goal_occupied_{};  // cells of goal(p) that hold any piece
    std::array<int, Layout::kMaxPlayers> goal_own_{};       // cells of goal(p) that hold a piece of p
    std::array<std::array<int, kMaxPieces>, Layout::kMaxPlayers> piece_cells_{};  // cells of each player's pieces, ascending
    std::array<int, Layout::kMaxPlayers> piece_count_{};
    std::array<int, kCells> slot_{};  // for an occupied cell, its index in its owner's row of piece_cells_
    int side_ = 0;
    int ply_ = 0;
    std::uint64_t hash_ = 0;
    std::vector<int> moves_;
    std::vector<std::uint64_t> hashes_;
    std::vector<int> movers_;        // per ply: the player who made the move
    std::vector<int> masks_before_;  // per ply: the set of finished players before the move
    int finished_mask_ = 0;
    std::array<int, 3> finishers_{};  // the finished players by rank; finished_count_ entries are valid
    int finished_count_ = 0;
    std::array<int, kCells> stack_{};  // scratch space for move generation
};

}  // namespace sh
