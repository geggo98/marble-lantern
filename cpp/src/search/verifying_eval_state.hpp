// The assert mode of the incremental evaluation (Java: VerifyingEvalState): an EvalState that, on every evaluate(), also runs the
// full Evaluator::evaluate on the same position and throws std::logic_error on any difference; after reset(), make() and unmake()
// it checks the internal sums (EvalState::verify). Slow; for tests and the assert mode of the search.
#pragma once

#include <memory>
#include <stdexcept>
#include <string>
#include <utility>

#include "evaluator.hpp"

namespace sh {

class VerifyingEvalState final : public EvalState {
public:
    // `oracle` must outlive this state.
    VerifyingEvalState(Evaluator& oracle, std::unique_ptr<EvalState> inner) : oracle_(&oracle), inner_(std::move(inner)) {}

    // Evaluations compared so far.
    long long evaluations() const { return evaluations_; }

    bool reset(const Position& root) override {
        const bool ok = inner_->reset(root);
        if (ok) inner_->verify(root);
        return ok;
    }
    void make(const Position& position, int move) override {
        inner_->make(position, move);
        inner_->verify(position);
    }
    void unmake(const Position& position) override {
        inner_->unmake(position);
        inner_->verify(position);
    }
    int evaluate(const Position& position) override {
        const int incremental = inner_->evaluate(position);
        const int full = oracle_->evaluate(position);
        ++evaluations_;
        if (incremental != full) {
            throw std::logic_error("incremental " + std::to_string(incremental) + " differs from the full evaluation " + std::to_string(full));
        }
        return incremental;
    }
    void verify(const Position& position) override { inner_->verify(position); }

private:
    Evaluator* oracle_;
    std::unique_ptr<EvalState> inner_;
    long long evaluations_ = 0;
};

}  // namespace sh
