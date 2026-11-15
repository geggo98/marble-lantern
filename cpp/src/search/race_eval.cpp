#include "race_eval.hpp"

#include <algorithm>
#include <utility>

#include "geometry.hpp"
#include "linear_eval.hpp"
#include "race_adjudication.hpp"

namespace sh {

namespace {

constexpr std::size_t u(int i) { return static_cast<std::size_t>(i); }

// The incremental state of the wrapped evaluator, with the race value in front of its value.
class RaceEvalState final : public EvalState {
public:
    RaceEvalState(RaceEvaluator& owner, std::unique_ptr<EvalState> inner) : owner_(&owner), inner_(std::move(inner)) {}

    bool reset(const Position& root) override { return inner_->reset(root); }
    void make(const Position& position, int move) override { inner_->make(position, move); }
    void unmake(const Position& position) override { inner_->unmake(position); }
    int evaluate(const Position& position) override {
        const int score = owner_->race_value(position);
        return score == RaceEvaluator::kUndecided ? inner_->evaluate(position) : score;
    }
    void verify(const Position& position) override { inner_->verify(position); }

private:
    RaceEvaluator* owner_;
    std::unique_ptr<EvalState> inner_;
};

}  // namespace

RaceEvaluator::RaceEvaluator(std::unique_ptr<Evaluator> base, std::shared_ptr<const RaceTable> table)
    : base_(std::move(base)), table_(std::move(table)), region_min_row_(table_ ? table_->min_row() : std::numeric_limits<int>::min()) {}

std::unique_ptr<RaceEvaluator> RaceEvaluator::racelin1_r3(std::shared_ptr<const RaceTable> rows1) {
    return std::make_unique<RaceEvaluator>(std::make_unique<LinearEvaluator>(LinearEvaluator::linear_r3()), std::move(rows1));
}

const std::expected<std::shared_ptr<const RaceTable>, LoadError>& RaceEvaluator::rows1_table() {
    static const std::expected<std::shared_ptr<const RaceTable>, LoadError> loaded =
        []() -> std::expected<std::shared_ptr<const RaceTable>, LoadError> {
        auto table = RaceTable::load_default(kRows1);
        if (!table) return std::unexpected(std::move(table.error()));
        return std::make_shared<const RaceTable>(std::move(*table));
    }();
    return loaded;
}

bool RaceEvaluator::passed(const Position& position) {
    if (position.piece_count(0) != kPieces || position.piece_count(1) != kPieces) return false;
    return geometry::r(position.piece_cell(0, 0)) > geometry::r(position.piece_cell(1, kPieces - 1));
}

int RaceEvaluator::race_score(int a, int b, int plies_left) {
    if (a <= b && 2 * a - 1 <= plies_left) return kRaceBase + kUnit * (b - a);
    if (b < a && 2 * b <= plies_left) return -(kRaceBase + kUnit * (a - b - 1));
    return kUndecided;
}

int RaceEvaluator::count(const Position& position, int player) {
    for (int i = 0; i < kPieces; ++i) scratch_[u(i)] = position.piece_cell(player, i);
    if (player == 1) {
        for (int& cell : scratch_) cell = geometry::player_flip(cell);
        std::ranges::sort(scratch_);
    }
    return table_->count(scratch_);
}

int RaceEvaluator::race_value(const Position& position) {
    if (!table_ || !rules_apply(position.rules()) || !passed(position)) return kUndecided;
    const int me = position.side_to_move();
    const int a = count(position, me);
    if (a < 0) return kUndecided;
    const int b = count(position, 1 - me);
    if (b < 0) return kUndecided;
    return race_score(a, b, position.rules().move_limit() - position.ply());
}

int RaceEvaluator::evaluate(const Position& position) {
    const int score = race_value(position);
    return score == kUndecided ? base_->evaluate(position) : score;
}

std::unique_ptr<EvalState> RaceEvaluator::incremental() {
    std::unique_ptr<EvalState> inner = base_->incremental();
    if (!inner) return nullptr;
    return std::make_unique<RaceEvalState>(*this, std::move(inner));
}

// False wherever a race value can appear within `depth` plies, so that futility pruning never compares a race value with an
// ordinary one. Two necessary conditions for a race value, each checked against what the moves within `depth` plies can change: the
// side to move moves ceil(depth / 2) times and the opponent floor(depth / 2) times, and each move moves one piece.
//  - Passed: after its moves, player 0's rearmost row is at least the rearmost row of the pieces it did not move, which is at most
//    the row of its (moves + 1)-th rearmost piece, and likewise for player 1 towards player 0; if those two rows do not pass each
//    other, no sequence of that many moves can pass.
//  - Covered: a race value needs both configurations inside the table's region, so every piece behind the region's first row must
//    move at least once; a side with more such pieces than moves cannot be covered in time.
// The test is necessary, not exact: it also says "unsafe" when the pieces exist but cannot make the jumps.
bool RaceEvaluator::futility_safe(const Position& position, int depth) {
    if (!table_ || !rules_apply(position.rules()) || position.piece_count(0) != kPieces || position.piece_count(1) != kPieces) {
        return true;  // never a race value
    }
    const int me = position.side_to_move();
    const int moves_me = (depth + 1) / 2;
    const int moves_opp = depth / 2;
    const int moves0 = me == 0 ? moves_me : moves_opp;
    const int moves1 = me == 0 ? moves_opp : moves_me;
    if (moves0 >= kPieces || moves1 >= kPieces) return false;
    const int rear0 = geometry::r(position.piece_cell(0, moves0));
    const int rear1 = geometry::r(position.piece_cell(1, kPieces - 1 - moves1));
    if (rear0 <= rear1) return true;  // cannot pass within depth plies
    // player 0's pieces behind the region have r < min_row and come first in its list; player 1's have -r < min_row and come last
    int behind0 = 0;
    while (behind0 < kPieces && geometry::r(position.piece_cell(0, behind0)) < region_min_row_) ++behind0;
    int behind1 = 0;
    while (behind1 < kPieces && -geometry::r(position.piece_cell(1, kPieces - 1 - behind1)) < region_min_row_) ++behind1;
    return behind0 > moves0 || behind1 > moves1;
}

}  // namespace sh
