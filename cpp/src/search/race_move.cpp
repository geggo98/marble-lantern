#include "race_move.hpp"

#include <algorithm>
#include <limits>

#include "race_adjudication.hpp"
#include "race_eval.hpp"

namespace sh {

namespace {
std::shared_ptr<const RaceTable>& installed_rows2() {
    static std::shared_ptr<const RaceTable> table;
    return table;
}
}  // namespace

void RaceMove::install_rows2(std::shared_ptr<const RaceTable> table) { installed_rows2() = std::move(table); }

std::expected<std::shared_ptr<const RaceTable>, std::string> RaceMove::shared_table(int rows) {
    if (rows == 1) {
        const auto& loaded = RaceEvaluator::rows1_table();
        if (!loaded) return std::unexpected("race table rows1: " + loaded.error().message);
        return *loaded;
    }
    if (installed_rows2()) return installed_rows2();
    static const std::expected<std::shared_ptr<const RaceTable>, LoadError> rows2 =
        []() -> std::expected<std::shared_ptr<const RaceTable>, LoadError> {
        auto table = RaceTable::load_default(kRows2);
        if (!table) return std::unexpected(std::move(table.error()));
        return std::make_shared<const RaceTable>(std::move(*table));
    }();
    if (!rows2) return std::unexpected("race table rows2: " + rows2.error().message);
    return *rows2;
}

Solitaire& RaceMove::solitaire(int player, const RuleSet& rules) {
    if (!leave_goal_ || *leave_goal_ != rules.leave_goal()) {
        solitaire_[0].reset();
        solitaire_[1].reset();
        leave_goal_ = rules.leave_goal();
    }
    auto& slot = solitaire_[static_cast<std::size_t>(player)];
    if (!slot) slot.emplace(player, *leave_goal_, table_.get());
    return *slot;
}

int RaceMove::choose(const Position& position) {
    const RuleSet& rules = position.rules();
    if (rules.triangle_rows() != RuleSet::TriangleRows::Four || rules.players() != 2 || rules.foreign_camps() != RuleSet::ForeignCamps::Closed || rules.blocking_states() != RuleSet::BlockingStates::None || !RaceAdjudication::passed(position)) return -1;
    const int me = position.side_to_move();
    const int opp = 1 - me;
    const std::span<const int> mine_view = position.pieces(me);
    const std::span<const int> theirs = position.pieces(opp);
    Solitaire& sol = solitaire(me, rules);
    if (sol.is_goal(mine_view)) return -1;
    Position scratch = position.copy();  // generate() is not const
    std::array<int, kMaxMoves> buffer{};
    const int n_legal = scratch.generate(buffer);
    if (n_legal == 0) return -1;
    const std::span<const int> legal = std::span<const int>(buffer).first(static_cast<std::size_t>(n_legal));
    std::array<int, kPieces> mine{};
    std::ranges::copy(mine_view, mine.begin());
    std::array<int, kPieces> mine0{};
    rows::to_player0(me, mine, mine0);
    if (mode_ == 2) {
        const int a = table_->count(mine0);
        std::array<int, kPieces> theirs0{};
        rows::to_player0(opp, theirs, theirs0);
        const int b = table_->count(theirs0);
        if (a >= 0 && b >= 0) {
            const int left = rules.move_limit() - position.ply();
            if (b < a && 2 * b <= left) return -1;  // the opponent finishes first: let the search look for a block
        }
    }
    const Layout& layout = Layout::two_players();
    if (table_->covers(mine0)) {
        int best = -1;
        int best_count = std::numeric_limits<int>::max();
        int best_gain = std::numeric_limits<int>::min();
        std::array<int, kPieces> cells = mine;
        std::array<int, kPieces> cells0{};
        for (const int m : legal) {
            const int from = Move::from(m);
            const int to = Move::to(m);
            for (int& c : cells) {
                if (c == from) c = to;
            }
            rows::to_player0(me, cells, cells0);
            const int count = table_->count(cells0);
            for (int& c : cells) {
                if (c == to) c = from;
            }
            if (count < 0) continue;
            const int gain = layout.distance(me, from) - layout.distance(me, to);
            if (count < best_count || (count == best_count && gain > best_gain)) {
                best = m;
                best_count = count;
                best_gain = gain;
            }
        }
        if (best >= 0) {
            ++table_moves_;
            return best;
        }
    }
    const int opp_rear = sol.front_row(theirs);  // the opponent piece closest to me, in my rows
    const auto plan = sol.plan(mine, kBeamWidth, kMaxPlanMoves, opp_rear + 1);
    if (plan && plan->moves() > 0) {
        const int m = plan->steps.front().move();
        if (std::ranges::contains(legal, m)) {
            ++plan_moves_;
            return m;
        }
    }
    return -1;
}

}  // namespace sh
