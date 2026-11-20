// The rule switches of docs/rules.md (Java: RuleSet), restricted to what the port supports: two or three players and a goal that may
// not be left (LeaveGoal::Forbidden, the project default). Every other value of players (not 2 or 3), leave_goal and blocking_states
// is refused by create(), as the Java class refuses the values that the rules document reserves. The switches that
// the search benchmarks never change (move limit, blockade rule, suicide moves, repetition, draw handling) stay switchable,
// because the engine tests exercise them.
#pragma once

#include <cstdint>
#include <expected>
#include <string>

namespace sh {

class RuleSet {
public:
    enum class LeaveGoal : std::uint8_t { Forbidden, Allowed };
    enum class BlockadeRule : std::uint8_t { None, OpponentFills };
    enum class SuicideMoves : std::uint8_t { Illegal, Legal };
    enum class BlockingStates : std::uint8_t { None, Illegal };
    enum class Repetition : std::uint8_t { MoveLimit, RepetitionIsDraw };
    enum class DrawHandling : std::uint8_t { Draw, ScoreByDistance };
    enum class Ranking : std::uint8_t { FirstWinnerOnly, FullRanking };
    // How a piece jumps: Adjacent = over one adjacent piece to the cell right behind it (default); Long = the Halma long hop (docs/rules.md, "Jump rule").
    enum class JumpRule : std::uint8_t { Adjacent, Long };
    // Whether a piece may enter a foreign corner, a corner that is neither its start nor its goal corner (docs/rules.md, "Foreign
    // corners"): Closed = never, not even between two jumps (default); PassThrough = a jump chain may land there between two jumps,
    // but the move must not end there; Open = a move may also end there.
    enum class ForeignCamps : std::uint8_t { Closed, PassThrough, Open };
    // The size of a house (start and goal), Java: RuleSet.TriangleRows. Four = the 10-cell corner triangle, 10 pieces (default); Five = the
    // 4-row corner plus the edge row of the central hexagon in front of it, 15 cells and 15 pieces, on the same 121-cell board.
    enum class TriangleRows : std::uint8_t { Four, Five };

    // The fields of a rule set before validation; the defaults are the project defaults of docs/rules.md (200 plies for two players).
    struct Spec {
        int players = 2;
        int move_limit = 200;
        LeaveGoal leave_goal = LeaveGoal::Forbidden;
        BlockadeRule blockade_rule = BlockadeRule::OpponentFills;
        SuicideMoves suicide_moves = SuicideMoves::Illegal;
        BlockingStates blocking_states = BlockingStates::None;
        Repetition repetition = Repetition::MoveLimit;
        DrawHandling draw_handling = DrawHandling::ScoreByDistance;
        Ranking ranking = Ranking::FirstWinnerOnly;
        JumpRule jump_rule = JumpRule::Adjacent;
        ForeignCamps foreign_camps = ForeignCamps::Closed;
        TriangleRows triangle_rows = TriangleRows::Four;
        friend bool operator==(const Spec&, const Spec&) = default;
    };

    static std::expected<RuleSet, std::string> create(const Spec& spec);
    // The project defaults: two players, 200 plies, goal not leavable, opponent fills, suicide moves illegal, score by distance.
    static RuleSet defaults();
    // The project defaults for 2 or 3 players: 200 plies for two players, 300 for three (docs/rules.md).
    static RuleSet defaults(int players);
    // As defaults(players) for a house size: with Five, 250 plies for two players and 375 for three (15 instead of 10 pieces to bring home).
    static RuleSet defaults(int players, TriangleRows rows);

    int players() const { return spec_.players; }
    int move_limit() const { return spec_.move_limit; }
    LeaveGoal leave_goal() const { return spec_.leave_goal; }
    BlockadeRule blockade_rule() const { return spec_.blockade_rule; }
    SuicideMoves suicide_moves() const { return spec_.suicide_moves; }
    BlockingStates blocking_states() const { return spec_.blocking_states; }
    Repetition repetition() const { return spec_.repetition; }
    DrawHandling draw_handling() const { return spec_.draw_handling; }
    Ranking ranking() const { return spec_.ranking; }
    JumpRule jump_rule() const { return spec_.jump_rule; }
    ForeignCamps foreign_camps() const { return spec_.foreign_camps; }
    TriangleRows triangle_rows() const { return spec_.triangle_rows; }
    // Pieces per player and cells per house: 10 with Four, 15 with Five.
    int pieces() const { return spec_.triangle_rows == TriangleRows::Five ? 15 : 10; }
    const Spec& spec() const { return spec_; }

    // Copies with one switch changed; the result is validated again (move limit must stay positive).
    std::expected<RuleSet, std::string> with_move_limit(int value) const;
    std::expected<RuleSet, std::string> with_repetition(Repetition value) const;
    std::expected<RuleSet, std::string> with_blockade_rule(BlockadeRule value) const;
    std::expected<RuleSet, std::string> with_suicide_moves(SuicideMoves value) const;
    std::expected<RuleSet, std::string> with_draw_handling(DrawHandling value) const;
    std::expected<RuleSet, std::string> with_jump_rule(JumpRule value) const;
    std::expected<RuleSet, std::string> with_ranking(Ranking value) const;
    std::expected<RuleSet, std::string> with_foreign_camps(ForeignCamps value) const;
    std::expected<RuleSet, std::string> with_triangle_rows(TriangleRows value) const;

    // Whether a player who fills the goal leaves the game while the others play on for the lower ranks (FullRanking with three
    // players; with two players the switch has no effect). Java: RuleSet.playsOnAfterWin.
    bool plays_on_after_win() const { return spec_.ranking == Ranking::FullRanking && spec_.players > 2; }

    friend bool operator==(const RuleSet&, const RuleSet&) = default;

private:
    explicit RuleSet(const Spec& spec) : spec_(spec) {}
    Spec spec_;
};

}  // namespace sh
