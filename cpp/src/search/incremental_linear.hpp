// The incremental state of a LinearEvaluator (Java: IncrementalLinear): the integer sums of both sides, kept up to date by make()
// and unmake() instead of being summed over all pieces at every leaf. The score comes out of the same LinearEvaluator::combine and
// LinearEvaluator::score as the full evaluation, from the same integers, so it is bit-identical to it.
//
// Piece terms (dsum, dsq, goal, start, lateral) change for the moving piece only. dmax and dmin come from a histogram of the piece
// distances per side. Pair terms (steps, jumps, adjacent) are sums over pairs (piece c, direction d) that look at the occupancy of
// the neighbour and of the jump landing: emptying or filling one cell x changes only the pairs in which x is the piece, the
// neighbour or the landing, at most 18 pairs. A move is the two toggles "empty from" and "fill to". bestGain is searched at the
// leaf, on a JumpGraph that this state keeps up to date; the pieces of each side are kept sorted by distance for that search.
//
// Protocol (differs from Java in one point, see evaluator.hpp): the CALLER plays the move on the position, then calls make();
// the caller takes the move back, then calls unmake(). A state follows exactly one Position object. Two players and at most
// kPieces pieces per side; reset() refuses anything else. The state keeps a pointer to the evaluator, which must outlive it and
// must not be moved while the state exists.
#pragma once

#include <array>
#include <cstdint>
#include <span>
#include <vector>

#include "evaluator.hpp"
#include "jump_graph.hpp"
#include "linear_eval.hpp"

namespace sh {

class IncrementalLinear final : public EvalState {
public:
    explicit IncrementalLinear(const LinearEvaluator& evaluator) : evaluator_(&evaluator), saved_(kInitialHeight) {}
    IncrementalLinear(const LinearEvaluator&&) = delete;  // would point to a temporary

    bool reset(const Position& root) override;
    void make(const Position& position, int move) override;
    void unmake(const Position& position) override;
    int evaluate(const Position& position) override;
    // Throws std::logic_error if the internal sums differ from a full recomputation (tests).
    void verify(const Position& position) override;

private:
    // Slots per side in sum_.
    static constexpr int kDsum = 0;
    static constexpr int kDsq = 1;
    static constexpr int kGoal = 2;
    static constexpr int kStart = 3;
    static constexpr int kLat = 4;
    static constexpr int kSteps = 5;
    static constexpr int kJumps = 6;
    static constexpr int kAdj = 7;
    static constexpr int kWidth = 8;
    // Slots after the sums, per side: the largest and the smallest piece distance.
    static constexpr int kDmax = 2 * kWidth;
    static constexpr int kDmin = kDmax + 2;
    static constexpr int kSaved = kDmin + 2;
    static constexpr std::size_t kInitialHeight = 64;

    using Sums = std::array<int, kSaved>;
    // One stack frame: the sums before the move, then the move's from cell, to cell and player.
    struct Frame {
        Sums sums{};
        int from = 0;
        int to = 0;
        int player = 0;
    };

    void toggle(int x, int p, int sign);
    void fill(int p, linear_detail::SideSums& out) const;

    const LinearEvaluator* evaluator_;
    JumpGraph graph_;
    // Owner of every cell, 0 or 1, or -1; the toggles edit it step by step, so it is not the position's array.
    std::array<std::int8_t, kCells> own_{};
    // Per side kWidth sums, then dmax and dmin per side.
    Sums sum_{};
    std::array<std::vector<int>, 2> histogram_;
    std::array<int, 2> count_{};
    // Per side the pieces as packed distance << 8 | cell, falling: the order that the bestGain search wants.
    std::array<std::array<int, kPieces>, 2> keys_{};
    std::vector<Frame> saved_;
    std::size_t height_ = 0;
    std::array<double, LinearEvaluator::kFeatures> features_{};
    linear_detail::SideSums mine_{};
    linear_detail::SideSums theirs_{};
};

}  // namespace sh
