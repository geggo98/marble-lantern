#include "rules.hpp"

namespace sh {

std::expected<RuleSet, std::string> RuleSet::create(const Spec& spec) {
    if (spec.players != 2 && spec.players != 3) {
        return std::unexpected("players must be 2 or 3, got " + std::to_string(spec.players));
    }
    if (spec.move_limit <= 0) {
        return std::unexpected("moveLimit must be positive, got " + std::to_string(spec.move_limit));
    }
    if (spec.leave_goal != LeaveGoal::Forbidden) {
        return std::unexpected(std::string("leaveGoal=ALLOWED is not supported by the C++ port"));
    }
    if (spec.blocking_states == BlockingStates::Illegal) {
        return std::unexpected(std::string("blockingStates=ILLEGAL needs an exact definition of 'unreachable' for 10 pieces (docs/rules.md)"));
    }
    return RuleSet(spec);
}

RuleSet RuleSet::defaults() {
    // The default spec is valid by construction.
    return RuleSet(Spec{});
}

RuleSet RuleSet::defaults(int players) { return defaults(players, TriangleRows::Four); }

RuleSet RuleSet::defaults(int players, TriangleRows rows) {
    Spec spec;
    spec.players = players;
    spec.triangle_rows = rows;
    if (rows == TriangleRows::Five) {
        spec.move_limit = players == 2 ? 250 : 375;
    } else {
        spec.move_limit = players == 2 ? 200 : 300;
    }
    return RuleSet(spec);
}

std::expected<RuleSet, std::string> RuleSet::with_move_limit(int value) const {
    Spec s = spec_;
    s.move_limit = value;
    return create(s);
}

std::expected<RuleSet, std::string> RuleSet::with_repetition(Repetition value) const {
    Spec s = spec_;
    s.repetition = value;
    return create(s);
}

std::expected<RuleSet, std::string> RuleSet::with_blockade_rule(BlockadeRule value) const {
    Spec s = spec_;
    s.blockade_rule = value;
    return create(s);
}

std::expected<RuleSet, std::string> RuleSet::with_suicide_moves(SuicideMoves value) const {
    Spec s = spec_;
    s.suicide_moves = value;
    return create(s);
}

std::expected<RuleSet, std::string> RuleSet::with_draw_handling(DrawHandling value) const {
    Spec s = spec_;
    s.draw_handling = value;
    return create(s);
}

std::expected<RuleSet, std::string> RuleSet::with_ranking(Ranking value) const {
    Spec s = spec_;
    s.ranking = value;
    return create(s);
}

std::expected<RuleSet, std::string> RuleSet::with_jump_rule(JumpRule value) const {
    Spec s = spec_;
    s.jump_rule = value;
    return create(s);
}

std::expected<RuleSet, std::string> RuleSet::with_foreign_camps(ForeignCamps value) const {
    Spec s = spec_;
    s.foreign_camps = value;
    return create(s);
}

}  // namespace sh

namespace sh {

std::expected<RuleSet, std::string> RuleSet::with_triangle_rows(TriangleRows value) const {
    Spec s = spec_;
    s.triangle_rows = value;
    return create(s);
}

}  // namespace sh
