// The optional enhancements of the search (Java: SearchOptions). Every field defaults to off, so that a default-constructed value
// searches like the original baseline. create() applies the checks of the Java constructor.
#pragma once

#include <expected>
#include <string>

namespace sh {

struct SearchOptions {
    // Default score units per step of distance gain in the futility bound.
    static constexpr int kDefaultFutilityGain = 30;
    // Default aspiration half-width: five steps of distance difference (Java: 5 * DistanceEvaluator.UNIT, UNIT = 10).
    static constexpr int kDefaultAspiration = 5 * 10;

    bool pvs = false;              // principal variation search: null windows after the first move, re-search on a fail high
    bool killers = false;          // two killer moves per height, ordered right after the table move
    bool history = false;          // a history table (from, to) that breaks ties of the distance gain in move ordering
    int lmr = 0;                   // late move reductions: 0 off, 1 by one ply, 2 by ln(depth) ln(move) / 2, 3 by ln(depth) ln(move) / 1.25
    int aspiration = 0;            // aspiration window half-width at the root in score units; 0 off
    bool depth_preferred = false;  // two entries per table bucket: one keeps the deeper entry of the current search
    bool mate_fix = false;         // mate scores are stored relative to the node, not to the root
    bool partial = false;          // use the best move of an interrupted iteration if its first root move was searched completely
    int futility = 0;              // futility pruning at remaining depth 1: the margin in score units, 0 off
    int futility_gain = kDefaultFutilityGain;  // score units per step of distance gain; only read when futility or futility2 is on
    int futility2 = 0;             // futility pruning at remaining depth 2: the margin, 0 off

    // Validates `options` (lmr in 0..3, no negative number) and normalises the futility gain when futility is off, so that == ignores it.
    static std::expected<SearchOptions, std::string> create(SearchOptions options);

    // Whether the root scores are exact: futility and late move reductions off.
    bool exact_root() const { return lmr == 0 && !futility_on(); }
    // These options with futility and late move reductions off (what AlphaBeta::root_scores requires).
    SearchOptions exact() const;
    // Whether futility pruning is on at any depth.
    bool futility_on() const { return futility > 0 || futility2 > 0; }
    bool is_default() const { return *this == SearchOptions{}; }
    // A short text for player names, e.g. "pvs,killers,lmr=1"; empty for the default.
    std::string describe() const;

    friend bool operator==(const SearchOptions&, const SearchOptions&) = default;
};

}  // namespace sh
