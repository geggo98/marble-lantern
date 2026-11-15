// The evaluator interfaces of the search (Java: Evaluator, EvalState). The concrete evaluators are final classes; the calls stay
// virtual, as in Java (devirtualisation is not part of the port).
#pragma once

#include <memory>

#include "position.hpp"

namespace sh {

// Keeps the features of a position up to date along a line of play (Java: EvalState). Implemented by IncrementalLinear (WP4b).
class EvalState {
public:
    virtual ~EvalState() = default;

    // Starts from `root`. Returns false if this state cannot follow the position (the caller then evaluates in full).
    virtual bool reset(const Position& root) = 0;
    // Called after `move` was made on `position`.
    virtual void make(const Position& position, int move) = 0;
    // Called after the last move was taken back on `position`.
    virtual void unmake(const Position& position) = 0;
    // The score of `position` from the state; equals Evaluator::evaluate for the same position.
    virtual int evaluate(const Position& position) = 0;
    // Tests only: compares the state with a full evaluation of `position`.
    virtual void verify(const Position& /*position*/) {}
};

// A static evaluation: the score from the view of the side to move, in the units of the search (Java: Evaluator).
// An evaluator owns scratch buffers and is not thread-safe; use one per search thread.
class Evaluator {
public:
    virtual ~Evaluator() = default;

    virtual int evaluate(const Position& position) = 0;
    // Whether the value of `position` can be trusted for futility pruning at the given remaining depth (default: always).
    virtual bool futility_safe(const Position& /*position*/, int /*depth*/) { return true; }
    // An incremental state, or null if the evaluator has none (default).
    virtual std::unique_ptr<EvalState> incremental() { return nullptr; }
};

}  // namespace sh
