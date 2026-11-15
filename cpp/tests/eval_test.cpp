// Tests of src/search evaluators (WP4a: LinearEvaluator; WP4b adds the incremental state and the race evaluator).
// Golden file (Java exporter of WP1): positions.tsv with linear-r1 and linear-r3 per position, compared bit for bit.
// Without STERNHALMA_GOLDEN_REQUIRED=1 a missing golden file skips its test; the constructed and reference tests always run.
#include <algorithm>
#include <array>
#include <bit>
#include <cmath>
#include <cstdint>
#include <iostream>
#include <optional>
#include <span>
#include <string>
#include <string_view>
#include <vector>

#include "check.hpp"
#include "geometry.hpp"
#include "golden.hpp"
#include "golden_test_support.hpp"
#include "layout.hpp"
#include "linear_eval.hpp"
#include "linear_weights.hpp"
#include "position.hpp"
#include "rules.hpp"

using namespace sh;

namespace {

std::vector<std::string_view> split(std::string_view line, char sep) {
    std::vector<std::string_view> out;
    std::size_t start = 0;
    while (true) {
        const std::size_t at = line.find(sep, start);
        if (at == std::string_view::npos) {
            out.push_back(line.substr(start));
            return out;
        }
        out.push_back(line.substr(start, at - start));
        start = at + 1;
    }
}

std::int64_t to_i64(std::string_view s) { return std::stoll(std::string(s)); }

std::vector<int> int_list(std::string_view s) {
    std::vector<int> out;
    if (s == "-" || s.empty()) return out;
    for (const std::string_view part : split(s, ' ')) out.push_back(static_cast<int>(to_i64(part)));
    return out;
}

struct GoldenPosition {
    int index = 0;
    std::vector<int> moves;
    bool final_position = false;  // the golden file has "-" instead of the values
    int r1 = 0;
    int r3 = 0;
};

std::vector<GoldenPosition> golden_positions(const std::vector<std::string>& lines) {
    std::vector<GoldenPosition> out;
    for (const std::string& l : lines) {
        if (l.empty() || l.front() == '#') continue;
        const auto row = split(l, '\t');
        const bool final_position = row[7] == "-";
        out.push_back({static_cast<int>(to_i64(row[0])), int_list(row[4]), final_position, final_position ? 0 : static_cast<int>(to_i64(row[7])),
                       final_position ? 0 : static_cast<int>(to_i64(row[8]))});
    }
    return out;
}

bool replay(Position& pos, const std::vector<int>& moves) {
    std::array<int, kMaxMoves> buffer{};
    for (const int m : moves) {
        const int n = pos.generate(buffer);
        const auto legal = std::span<const int>(buffer).first(static_cast<std::size_t>(n));
        if (std::find(legal.begin(), legal.end(), m) == legal.end()) return false;
        pos.make(m);
    }
    return true;
}

// ---- references written from the definitions, not from the tables of the implementation ----

struct RefSide {
    int dsum = 0, dmax = 0, dmin = 1000000, dsq = 0, goal = 0, start = 0, lateral = 0, jumps = 0, adjacent = 0, steps = 0, count = 0;
};

RefSide ref_side(const Position& pos, int p) {
    const Layout& layout = Layout::two_players();
    RefSide s;
    for (int c = 0; c < kCells; ++c) {
        if (pos.owner(c) != p) continue;
        const int d = layout.distance(p, c);
        s.dsum += d;
        s.dsq += d * d;
        s.dmax = std::max(s.dmax, d);
        s.dmin = std::min(s.dmin, d);
        ++s.count;
        const int corner = geometry::corner_of(c);
        if (corner == layout.goal_corner(p)) ++s.goal;
        if (corner == layout.start_corner(p)) ++s.start;
        s.lateral += std::abs(geometry::q(c) - geometry::s(c));
        for (int dir = 0; dir < kDirections; ++dir) {
            const int a = geometry::neighbor(c, dir);
            if (a < 0) continue;
            const int land = geometry::jump_landing(c, dir);
            if (pos.owner(a) < 0) {
                if (layout.allowed(p, a) && layout.distance(p, a) < d) ++s.steps;
            } else {
                if (pos.owner(a) == p && a > c) ++s.adjacent;
                if (land >= 0 && layout.allowed(p, land) && layout.distance(p, land) < d && pos.owner(land) < 0) ++s.jumps;
            }
        }
    }
    return s;
}

std::array<double, 12> ref_features(const Position& pos) {
    const int me = pos.side_to_move();
    const RefSide a = ref_side(pos, me);
    const RefSide b = ref_side(pos, 1 - me);
    std::array<double, 12> f{};
    auto add = [&](const RefSide& s, int sign) {
        f[0] += sign * s.dsum;
        f[1] += sign * s.dmax;
        f[2] += sign * (s.dsq / 10.0);
        f[3] += sign * s.goal;
        f[4] += sign * s.start;
        f[5] += sign * (s.count == 0 ? 0 : s.dmax - s.dmin);
        f[6] += sign * s.lateral;
        f[7] += sign * s.jumps;
        f[8] += sign * s.adjacent;
        f[9] += sign * s.steps;
    };
    add(a, 1);
    add(b, -1);
    f[10] = f[0] * (a.dsum + b.dsum) / 100.0;
    f[11] = 1.0;
    return f;
}

// One jump search per piece: the moving piece's own cell counts as empty; a jump goes over any occupied cell onto a free
// allowed cell; the gain is distance(from) - distance(reached), floored at 0, capped at 6.
int ref_best_gain(const Position& pos, int p) {
    const Layout& layout = Layout::two_players();
    std::array<bool, kCells> occupied{};
    for (int c = 0; c < kCells; ++c) occupied[static_cast<std::size_t>(c)] = pos.owner(c) >= 0;
    int best = 0;
    for (const int from : pos.pieces(p)) {
        if (geometry::corner_of(from) == layout.goal_corner(p)) continue;
        const int d0 = layout.distance(p, from);
        occupied[static_cast<std::size_t>(from)] = false;
        for (int dir = 0; dir < kDirections; ++dir) {
            const int a = geometry::neighbor(from, dir);
            if (a >= 0 && layout.allowed(p, a) && !occupied[static_cast<std::size_t>(a)]) best = std::max(best, d0 - layout.distance(p, a));
        }
        std::array<bool, kCells> seen{};
        std::vector<int> stack;
        // the jump chains: reached cells are free by construction
        for (int dir = 0; dir < kDirections; ++dir) {
            const int h = geometry::neighbor(from, dir);
            const int l = geometry::jump_landing(from, dir);
            if (h >= 0 && l >= 0 && occupied[static_cast<std::size_t>(h)] && layout.allowed(p, l) && !occupied[static_cast<std::size_t>(l)] &&
                !seen[static_cast<std::size_t>(l)]) {
                seen[static_cast<std::size_t>(l)] = true;
                stack.push_back(l);
            }
        }
        while (!stack.empty()) {
            const int c = stack.back();
            stack.pop_back();
            best = std::max(best, d0 - layout.distance(p, c));
            for (int dir = 0; dir < kDirections; ++dir) {
                const int h = geometry::neighbor(c, dir);
                const int l = geometry::jump_landing(c, dir);
                if (h >= 0 && l >= 0 && occupied[static_cast<std::size_t>(h)] && layout.allowed(p, l) && !occupied[static_cast<std::size_t>(l)] &&
                    !seen[static_cast<std::size_t>(l)]) {
                    seen[static_cast<std::size_t>(l)] = true;
                    stack.push_back(l);
                }
            }
        }
        occupied[static_cast<std::size_t>(from)] = true;
    }
    return std::min(best, 6);
}

bool same_bits(double a, double b) { return std::bit_cast<std::uint64_t>(a) == std::bit_cast<std::uint64_t>(b); }

// Every position of positions.tsv and every position one move later (about 20,000 positions): `visit` is called on each.
template <typename F>
void for_each_position(const std::vector<GoldenPosition>& rows, F&& visit) {
    std::array<int, kMaxMoves> buffer{};
    for (const GoldenPosition& row : rows) {
        Position pos = Position::initial(RuleSet::defaults());
        if (!replay(pos, row.moves)) {
            CHECK(false);
            continue;
        }
        visit(pos, &row);
        const int n = pos.generate(buffer);
        const auto found = std::span<const int>(buffer).first(static_cast<std::size_t>(n));
        const std::vector<int> legal(found.begin(), found.end());
        for (std::size_t i = 0; i < legal.size(); i += 5) {  // every fifth child keeps the run short
            pos.make(legal[i]);
            visit(pos, nullptr);
            pos.unmake();
        }
    }
}

}  // namespace

SH_TEST(weights_are_the_java_values) {
    CHECK_EQ(linear_weights::kScale, 100.0);
    CHECK_EQ(linear_weights::kR1.size(), std::size_t{12});
    CHECK_EQ(linear_weights::kRound3.size(), std::size_t{13});
    // the first 12 values of ROUND3 differ from R1; the tempo of R1 keeps its trailing zero in the source
    CHECK_EQ(linear_weights::kR1[0], -0.361185);
    CHECK_EQ(linear_weights::kR1[11], 0.22945);
    CHECK_EQ(linear_weights::kRound3[12], 0.156608);
    CHECK(same_bits(linear_weights::kRound3[8], -0.0580585));
}

SH_TEST(create_checks_the_number_of_weights) {
    const std::array<double, 12> w12{};
    const std::array<double, 13> w13{};
    const std::array<double, 5> w5{};
    CHECK(LinearEvaluator::create(w12, 100.0).has_value());
    CHECK(!LinearEvaluator::create(w12, 100.0).value().gain_mode().has_value());
    CHECK(LinearEvaluator::create(w13, 100.0).has_value());
    CHECK(LinearEvaluator::create(w13, 100.0).value().gain_mode() == LinearEvaluator::GainMode::Both);
    CHECK(LinearEvaluator::create(w13, 100.0, LinearEvaluator::GainMode::Mover).value().gain_mode() == LinearEvaluator::GainMode::Mover);
    CHECK(!LinearEvaluator::create(w12, 100.0, LinearEvaluator::GainMode::Both).has_value());
    CHECK(!LinearEvaluator::create(w5, 100.0).has_value());
}

SH_TEST(initial_position_is_balanced) {
    Position pos = Position::initial(RuleSet::defaults());
    std::array<double, 12> f{};
    LinearEvaluator::features(pos, f);
    // everything is symmetric except the tempo term (1) and dsumPhase (0 because the dsum difference is 0)
    for (int i = 0; i < 11; ++i) CHECK_EQ(f[static_cast<std::size_t>(i)], 0.0);
    CHECK_EQ(f[11], 1.0);
    CHECK_EQ(LinearEvaluator::best_gain(pos, 0), LinearEvaluator::best_gain(pos, 1));
    LinearEvaluator r1 = LinearEvaluator::linear_r1();
    CHECK_EQ(r1.evaluate(pos), 23);  // 100 * 0.22945, rounded: the first line of positions.tsv has 23 as well
}

SH_TEST(linear_values_match_golden_bit_for_bit) {
    const auto lines = golden::lines_or_skip("positions.tsv");
    if (!lines) return;
    const auto rows = golden_positions(*lines);
    CHECK_EQ(static_cast<int>(rows.size()), 2000);
    LinearEvaluator r1 = LinearEvaluator::linear_r1();
    LinearEvaluator r3 = LinearEvaluator::linear_r3();
    int differences = 0;
    int compared = 0;
    int finals = 0;
    for (const GoldenPosition& row : rows) {
        Position pos = Position::initial(RuleSet::defaults());
        CHECK(replay(pos, row.moves));
        if (row.final_position) {
            ++finals;
            continue;
        }
        const int a = r1.evaluate(pos);
        const int b = r3.evaluate(pos);
        compared += 2;
        if (a != row.r1 || b != row.r3) {
            if (differences++ < 5) {
                ::sh::test::fail(__FILE__, __LINE__, "position " + std::to_string(row.index) + ": linear-r1 " + std::to_string(a) + " (golden " +
                                                         std::to_string(row.r1) + "), linear-r3 " + std::to_string(b) + " (golden " + std::to_string(row.r3) + ")");
            }
        }
    }
    CHECK_EQ(compared + 2 * finals, 4000);
    CHECK(finals < 200);  // final positions have no golden value; most rows are compared
    std::cout << "compared " << compared << " values bit for bit (" << finals << " final positions without a value)\n";
    CHECK_EQ(differences, 0);
}

SH_TEST(features_match_the_reference_bit_for_bit) {
    const auto lines = golden::lines_or_skip("positions.tsv");
    if (!lines) return;
    int positions = 0;
    int bad = 0;
    for_each_position(golden_positions(*lines), [&](const Position& pos, const GoldenPosition*) {
        std::array<double, 12> f{};
        LinearEvaluator::features(pos, f);
        const std::array<double, 12> ref = ref_features(pos);
        ++positions;
        for (std::size_t i = 0; i < 12; ++i) {
            if (!same_bits(f[i], ref[i]) && bad++ < 5) ::sh::test::fail(__FILE__, __LINE__, "feature " + std::to_string(i) + " differs");
        }
    });
    CHECK(positions > 10000);
    CHECK_EQ(bad, 0);
}

SH_TEST(best_gain_matches_the_reference_search) {
    const auto lines = golden::lines_or_skip("positions.tsv");
    if (!lines) return;
    int positions = 0;
    int bad = 0;
    int capped = 0;
    int zero = 0;
    for_each_position(golden_positions(*lines), [&](const Position& pos, const GoldenPosition*) {
        for (int p = 0; p < 2; ++p) {
            const int got = LinearEvaluator::best_gain(pos, p);
            const int want = ref_best_gain(pos, p);
            if (got != want && bad++ < 5) {
                ::sh::test::fail(__FILE__, __LINE__, "best gain of player " + std::to_string(p) + ": " + std::to_string(got) + " instead of " + std::to_string(want));
            }
            capped += want == LinearEvaluator::kGainCap;
            zero += want == 0;
        }
        std::array<double, 13> f13{};
        LinearEvaluator::features(pos, f13, LinearEvaluator::GainMode::Both);
        const int me = pos.side_to_move();
        CHECK_EQ(f13[12], static_cast<double>(ref_best_gain(pos, me) - ref_best_gain(pos, 1 - me)));
        LinearEvaluator::features(pos, f13, LinearEvaluator::GainMode::Mover);
        CHECK_EQ(f13[12], static_cast<double>(ref_best_gain(pos, me)));
        ++positions;
    });
    CHECK(positions > 10000);
    CHECK_EQ(bad, 0);
    CHECK(capped > 100);  // the data exercises the cap and the zero case, so the comparison is not vacuous
    CHECK(zero > 100);
}

SH_TEST(score_rounds_like_java) {
    // weights 1, 0, ...: score = round(scale * feature 0); the sums below are exact in binary
    std::array<double, 12> w{};
    w[0] = 1.0;
    LinearEvaluator e = LinearEvaluator::create(w, 1.0).value();
    std::array<double, 12> f{};
    f[0] = 2.5;
    CHECK_EQ(e.score(f, 0.0), 3);
    f[0] = -2.5;
    CHECK_EQ(e.score(f, 0.0), -2);
    f[0] = -0.5;
    CHECK_EQ(e.score(f, 0.0), 0);
    f[0] = 0.49999999999999994;
    CHECK_EQ(e.score(f, 0.0), 0);
    f[0] = std::nan("");
    CHECK_EQ(e.score(f, 0.0), 0);
    f[0] = 4294967296.0 + 5.0;  // (int) of the long 2^32 + 5 keeps the low 32 bits in Java
    CHECK_EQ(e.score(f, 0.0), 5);
}

int main() { return sh::test::run_all(); }
