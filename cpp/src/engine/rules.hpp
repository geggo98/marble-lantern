// The rule switches of docs/rules.md (Java: RuleSet), restricted to what the port supports: two players and a goal that may
// not be left (LeaveGoal::Forbidden, the project default). Every other value of players, leave_goal, blocking_states and
// ranking is refused by create(), as the Java class refuses the values that the rules document reserves. The switches that
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
        friend bool operator==(const Spec&, const Spec&) = default;
    };

    static std::expected<RuleSet, std::string> create(const Spec& spec);
    // The project defaults: two players, 200 plies, goal not leavable, opponent fills, suicide moves illegal, score by distance.
    static RuleSet defaults();

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
    const Spec& spec() const { return spec_; }

    // Copies with one switch changed; the result is validated again (move limit must stay positive).
    std::expected<RuleSet, std::string> with_move_limit(int value) const;
    std::expected<RuleSet, std::string> with_repetition(Repetition value) const;
    std::expected<RuleSet, std::string> with_blockade_rule(BlockadeRule value) const;
    std::expected<RuleSet, std::string> with_suicide_moves(SuicideMoves value) const;
    std::expected<RuleSet, std::string> with_draw_handling(DrawHandling value) const;
    std::expected<RuleSet, std::string> with_jump_rule(JumpRule value) const;

    friend bool operator==(const RuleSet&, const RuleSet&) = default;

private:
    explicit RuleSet(const Spec& spec) : spec_(spec) {}
    Spec spec_;
};

}  // namespace sh
