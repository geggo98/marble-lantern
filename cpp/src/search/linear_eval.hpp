// A learned linear evaluation: a weighted sum of cheap features, each the difference "side to move minus opponent"
// (Java: LinearEvaluator). The features are 10 per-side sums, dsumPhase and a constant tempo term (12), and optionally
// bestGain (13th, GainMode).
//
// Bit-exactness: the integer sums come from the piece lists and compile-time tables, then the doubles are combined in the
// exact order of the Java code, without fused multiply-add (the build uses -ffp-contract=off) and rounded with java_round.
// Only two players are evaluated. An evaluator is not thread-safe (it owns scratch buffers).
#pragma once

#include <array>
#include <cstdint>
#include <expected>
#include <optional>
#include <span>
#include <string>

#include "evaluator.hpp"
#include "geometry.hpp"
#include "layout.hpp"
#include "position.hpp"

namespace sh {

namespace linear_detail {

inline constexpr int kFeatures = 12;
inline constexpr int kGainFeatures = 13;
inline constexpr int kTempo = 11;      // index of the constant tempo term
inline constexpr int kBestGain = 12;   // index of bestGain in a 13-feature vector
inline constexpr int kGainCap = 6;     // the largest bestGain reported per side

// Bits of LinearTables::good.
inline constexpr std::uint8_t kStepCloser = 1;  // the neighbour is an allowed cell closer to the goal than the piece
inline constexpr std::uint8_t kJumpCloser = 2;  // the jump landing is an allowed cell closer to the goal than the piece
// Values of LinearTables::kind.
inline constexpr std::uint8_t kInGoal = 1;
inline constexpr std::uint8_t kInStart = 2;

// Indices into the per-side integer sums.
inline constexpr int kDsum = 0;
inline constexpr int kDmax = 1;
inline constexpr int kDmin = 2;
inline constexpr int kDsq = 3;
inline constexpr int kGoal = 4;
inline constexpr int kStart = 5;
inline constexpr int kLat = 6;
inline constexpr int kJumps = 7;
inline constexpr int kAdj = 8;
inline constexpr int kSteps = 9;
inline constexpr int kCount = 10;
inline constexpr int kSideSums = 11;

using SideSums = std::array<int, kSideSums>;

// The tables of the two-player layout, built at compile time (Java: the static initialisers of LinearEvaluator).
struct LinearTables {
    std::array<int, kCells> lateral{};  // |q - s|
    // per player: kStepCloser | kJumpCloser per (cell, direction); 0 for cells the player may not enter
    std::array<std::array<std::uint8_t, kTableSize>, 2> good{};
    std::array<std::array<int, kCells>, 2> dist{};            // single-agent distance per cell
    std::array<std::array<std::uint8_t, kCells>, 2> kind{};   // kInGoal, kInStart or 0
    // per player: the jumps of cell c are edges[e] for edge_start[c] <= e < edge_start[c + 1], each hurdle << 8 | landing,
    // with the landing an allowed cell
    std::array<std::array<int, kCells + 1>, 2> edge_start{};
    std::array<std::array<int, kTableSize>, 2> edges{};
    // Both players may stand on the same cells (centre and both main corners): one jump graph serves both sides.
    bool shared_cells = true;
};

constexpr LinearTables build_linear_tables() {
    LinearTables t;
    const Layout& layout = detail::kTwoPlayerLayout;
    for (int c = 0; c < kCells; ++c) {
        const int lat = geometry::q(c) - geometry::s(c);
        t.lateral[static_cast<std::size_t>(c)] = lat < 0 ? -lat : lat;
    }
    for (int c = 0; c < kCells; ++c) {
        t.shared_cells = t.shared_cells && layout.allowed(1, c) == layout.allowed(0, c);
    }
    for (int p = 0; p < 2; ++p) {
        const auto ps = static_cast<std::size_t>(p);
        int count = 0;
        for (int c = 0; c < kCells; ++c) {
            const auto cs = static_cast<std::size_t>(c);
            t.dist[ps][cs] = layout.distance(p, c);
            const int corner = geometry::corner_of(c);
            t.kind[ps][cs] = corner == layout.goal_corner(p) ? kInGoal : corner == layout.start_corner(p) ? kInStart : 0;
            t.edge_start[ps][cs] = count;
            if (!layout.allowed(p, c)) continue;  // a piece of p never stands here
            const int here = layout.distance(p, c);
            for (int d = 0; d < kDirections; ++d) {
                const std::size_t i = table_index(c, d);
                const int step = geometry::step_table()[i];
                const int land = geometry::land_table()[i];
                std::uint8_t bits = 0;
                if (step >= 0 && layout.allowed(p, step) && layout.distance(p, step) < here) bits |= kStepCloser;
                if (land >= 0 && layout.allowed(p, land) && layout.distance(p, land) < here) bits |= kJumpCloser;
                t.good[ps][i] = bits;
                const int allowed_landing = layout.jump_table(p)[i];  // -1 unless the landing is an allowed cell
                if (allowed_landing >= 0) t.edges[ps][static_cast<std::size_t>(count++)] = step << 8 | allowed_landing;
            }
        }
        t.edge_start[ps][kCells] = count;
    }
    return t;
}

inline constexpr LinearTables kLinearTables = build_linear_tables();
static_assert(kLinearTables.shared_cells, "two-player layout: players must be allowed on the same cells");

// The bestGain search, by jump components instead of one jump search per piece. A jump chain moves over the free allowed
// cells along the edges "X to Y = X + 2e over an occupied hurdle X + e"; these edges are symmetric, so the cells reachable by
// a chain from a piece on A are the connected components of A's first landings. The moving piece leaves A, but that changes
// nothing: A is never a hurdle of its own chain (every landing lies at an even offset from A, every hurdle of a jump between
// such cells at an odd one). The components depend only on the occupancy and the allowed cells, so each is labelled once per
// position, with its smallest distance for both players, and shared by all pieces and both sides.
class GainSearch {
public:
    // Starts a new position: no component labelled, the occupancy from the piece lists.
    void begin(const Position& position);
    // The largest one-move distance gain of player p over its pieces outside its goal (steps and jump chains to allowed free
    // cells), floored at 0 and capped at kGainCap. begin() must have been called for this position.
    int best_gain(const Position& position, int p);

private:
    int component_of(std::span<const int> edge_start, std::span<const int> edges, int start);

    static constexpr int kMaxComponents = kCells;  // at most one component per free cell
    std::uint64_t occ_lo_ = 0;  // occupied cells (any player), cells 0 to 63
    std::uint64_t occ_hi_ = 0;  // cells 64 to 120 (bit c - 64)
    std::array<std::uint64_t, kMaxComponents> comp_lo_{};
    std::array<std::uint64_t, kMaxComponents> comp_hi_{};
    std::uint64_t done_lo_ = 0;  // union of the labelled components
    std::uint64_t done_hi_ = 0;
    std::array<int, kMaxComponents> min_a_{};  // smallest distance of player 0 per component
    std::array<int, kMaxComponents> min_b_{};  // smallest distance of player 1 per component
    int components_ = 0;
    std::array<int, kCells> stack_{};
    std::array<int, kPieces> order_{};
};

}  // namespace linear_detail

class LinearEvaluator final : public Evaluator {
public:
    static constexpr int kFeatures = linear_detail::kFeatures;
    static constexpr int kGainFeatures = linear_detail::kGainFeatures;
    static constexpr int kTempo = linear_detail::kTempo;
    static constexpr int kBestGain = linear_detail::kBestGain;
    static constexpr int kGainCap = linear_detail::kGainCap;

    // How the bestGain feature combines the two sides.
    enum class GainMode : std::uint8_t {
        Both,   // the side to move's best gain minus the opponent's
        Mover,  // the side to move's best gain only
    };

    // 12 weights: no bestGain; `gain` must be empty. 13 weights: bestGain in mode `gain` (empty means Both). Any other combination
    // (12 weights with a mode, any other number of weights) is an error.
    static std::expected<LinearEvaluator, std::string> create(std::span<const double> weights, double scale,
                                                              std::optional<GainMode> gain = std::nullopt);
    // `linear-r1`: R1 weights, scale 100.
    static LinearEvaluator linear_r1();
    // `linear-r3`: ROUND3 weights with bestGain (Both), scale 100.
    static LinearEvaluator linear_r3();

    // The mode of bestGain, or nullopt for the 12-feature evaluator.
    std::optional<GainMode> gain_mode() const { return gain_; }

    int evaluate(const Position& position) override;
    // A state that keeps the sums up to date along a line of play (E13). It points to this evaluator, which must outlive it and not move.
    std::unique_ptr<EvalState> incremental() override;

    // The rounded score of 12 features and, for a 13-feature evaluator, the bestGain value:
    // (int) Math.round(scale * (sum of weight * feature, in index order)).
    int score(std::span<const double, kFeatures> features, double best_gain) const;

    // The feature vector of a position (side to move minus opponent).
    static void features(const Position& position, std::span<double, kFeatures> out);
    // The 13 features: the 12 of features() (bit-identical) and bestGain in the given mode at kBestGain.
    static void features(const Position& position, std::span<double, kGainFeatures> out, GainMode mode);
    // bestGain of player p (see GainSearch).
    static int best_gain(const Position& position, int p);

    // The features from the integer sums of the side to move and of the opponent (the entry point that the incremental state
    // of WP4b shares with the full computation, so that both round identically).
    static void combine(const linear_detail::SideSums& me, const linear_detail::SideSums& opponent, std::span<double, kFeatures> out);
    // The integer sums of player p into `s`, over the pieces in cell order.
    static void side(const Position& position, int p, linear_detail::SideSums& s);

private:
    LinearEvaluator(const std::array<double, kGainFeatures>& weights, double scale, std::optional<GainMode> gain)
        : weights_(weights), scale_(scale), gain_(gain) {}

    // Only the first kFeatures entries are used when gain_ is empty.
    std::array<double, kGainFeatures> weights_{};
    double scale_ = 0;
    std::optional<GainMode> gain_;
    std::array<double, kFeatures> scratch_{};
    linear_detail::SideSums sums_{};
    linear_detail::SideSums other_sums_{};
    linear_detail::GainSearch gain_search_;
};

}  // namespace sh
