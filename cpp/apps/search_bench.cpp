// Search benchmark: nodes per CPU-second and CPU milliseconds per move of a player spec on the 64 reference positions (and, with
// --games, full self-play games), single thread, thread CPU time. The C++ counterpart of the timing part of the Java
// sternhalma.search.Bench; the output lines use the same wording, so one parser reads both.
//
//   search_bench <specA> [<specB>] [--nodes N] [--reps R] [--positions K] [--games G] [--golden DIR] [--max-load L] [--ignore-load] [--warm-games]
//
// Sides: with one spec only A is measured (Java would compare against a frozen build, which C++ does not have); with two specs A and B run
// in this process, interleaved (the self-check of the tool: a spec against itself must give a ratio of 1.00).
// Method (as Bench): one untimed warm-up pass over the positions of every side, then R repetitions (default 3); in each repetition every
// item (a position search with a fresh searcher, a full game with two fresh players) runs on both sides directly after each other, in the
// order A,B when item + repetition is even, else B,A. CPU time is CLOCK_THREAD_CPUTIME_ID around each search() or choose() call, never
// per node; searcher construction and position replay are outside the timed region. The ratio of a repetition is nps(A)/nps(B) over all its
// items; the reported ratio is the median.
//
// Equality: in every run, the move, score, depth and node count of each position search is compared with the per-position line of the
// golden fingerprint file whose "spec" line equals the side's spec (the Java Fingerprint output). On a difference the item and both lines
// are printed, the exit code is 2 and no RESULT line is written. A spec without a golden file (for example after --nodes) is searched
// without the check, and a "golden:" line says so.
//
// Exit codes: 0 result, 2 usage error, golden difference or a failed search, 3 load too high (no RESULT line).
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <ctime>
#include <expected>
#include <format>
#include <iostream>
#include <memory>
#include <optional>
#include <sstream>
#include <string>
#include <string_view>
#include <thread>
#include <vector>

#include "alpha_beta.hpp"
#include "alpha_beta_player.hpp"
#include "args.hpp"
#include "fingerprint.hpp"
#include "geometry.hpp"
#include "golden.hpp"
#include "move.hpp"
#include "parse_number.hpp"
#include "player_spec.hpp"

namespace {

using Nanos = std::int64_t;

Nanos thread_cpu_ns() {
    timespec ts{};
    clock_gettime(CLOCK_THREAD_CPUTIME_ID, &ts);
    return static_cast<Nanos>(ts.tv_sec) * 1'000'000'000 + ts.tv_nsec;
}

Nanos wall_ns() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<Nanos>(ts.tv_sec) * 1'000'000'000 + ts.tv_nsec;
}

double load_average() {
    double load = -1;
    if (getloadavg(&load, 1) != 1) return -1;
    return load;
}

struct Totals {
    std::int64_t nodes = 0;
    Nanos cpu = 0;
    Nanos wall = 0;
    std::int64_t moves = 0;

    Totals plus(const Totals& o) const { return {nodes + o.nodes, cpu + o.cpu, wall + o.wall, moves + o.moves}; }
    double nps() const { return static_cast<double>(nodes) / (static_cast<double>(cpu) / 1e9); }
    double cpu_ms_per_move() const { return static_cast<double>(cpu) / 1e6 / static_cast<double>(moves); }
    double wall_ms_per_move() const { return static_cast<double>(wall) / 1e6 / static_cast<double>(moves); }
    // Java prints NaN where nothing was measured (0 nodes in 0 CPU-s); std::format prints nan or -nan.
    std::string text() const {
        std::string s = std::format("{} nodes, {} moves, {:.0f} CPU-ms, {:.0f} wall-ms: {:.0f} nodes/CPU-s, {:.3f} CPU-ms/move, {:.3f} wall-ms/move", nodes,
                           moves, static_cast<double>(cpu) / 1e6, static_cast<double>(wall) / 1e6, nps(), cpu_ms_per_move(), wall_ms_per_move());
        for (std::size_t at = s.find("nan"); at != std::string::npos; at = s.find("nan", at)) {
            const std::size_t from = at > 0 && s[at - 1] == '-' ? at - 1 : at;
            s.replace(from, at + 3 - from, "NaN");
            at = from + 3;
        }
        return s;
    }
};

struct Measurement {
    Totals totals;
    std::uint64_t signature = 0;
};

// One side of the bench: a spec, its parsed form and, if a golden fingerprint file exists for it, the per-position lines.
struct Side {
    std::string name;
    std::string spec;
    sh::PlayerSpec parsed;
    bool has_golden = false;
    std::string golden_file;
    std::vector<std::string> golden;  // the 64 position lines up to " | depth 4", without leading blanks
};

std::string_view trim_left(std::string_view s) {
    while (!s.empty() && s.front() == ' ') s.remove_prefix(1);
    return s;
}

// The golden lines of the side's spec from the first fingerprint file whose "spec" line equals it.
void find_golden(Side& side, const std::string& dir) {
    for (const std::string_view name : {"fingerprint-r2.txt", "fingerprint-r3.txt", "fingerprint-best.txt"}) {
        const auto lines = dir.empty() ? sh::golden::load_lines(name) : sh::golden::load_lines(dir, name);
        if (!lines || lines->empty() || lines->front() != "spec " + side.spec) continue;
        std::vector<std::string> positions;
        for (std::size_t i = 1; i < lines->size(); ++i) {
            const std::string_view line = trim_left((*lines)[i]);
            if (line.starts_with("positions checksum")) break;
            const std::size_t bar = line.find(" | ");
            if (bar == std::string_view::npos) break;
            positions.emplace_back(line.substr(0, bar));
        }
        if (positions.size() != 64) continue;
        side.has_golden = true;
        side.golden_file = std::string(name);
        side.golden = std::move(positions);
        return;
    }
}

// The part of a Java Fingerprint position line before " | depth 4".
std::string position_line(int index, int ply, const sh::SearchResult& r) {
    return std::format("{:2d} ply={:3d} move={:<15} score={:8d} depth={:2d} nodes={:6d}", index, ply, sh::Move::to_string(r.move), r.score, r.depth,
                       r.nodes);
}

std::uint64_t mix(std::uint64_t h, std::int64_t v) { return h * 31 + static_cast<std::uint64_t>(v); }

struct Bench {
    const sh::fingerprint::ReferenceData& reference;
    std::string failure;  // empty while all is well

    void fail(std::string message) {
        if (failure.empty()) failure = std::move(message);
    }

    // Position search with a fresh searcher; the timed region is the search call only.
    Measurement search_position(const Side& side, int k) {
        const auto pos = sh::fingerprint::replay(reference.positions.at(static_cast<std::size_t>(k)));
        auto searcher = side.parsed.make_searcher();
        if (!pos || !searcher) {
            fail("search setup failed: " + (!pos ? pos.error() : searcher.error()));
            return {};
        }
        const Nanos c0 = thread_cpu_ns();
        const Nanos w0 = wall_ns();
        const sh::SearchResult r = (*searcher)->search(*pos, side.parsed.depth, sh::AlphaBeta::kNoLimit, side.parsed.nodes);
        const Nanos wall = wall_ns() - w0;
        const Nanos cpu = thread_cpu_ns() - c0;
        if (side.has_golden) {
            const std::string mine = std::string(trim_left(position_line(k, pos->ply(), r)));
            const std::string& golden = side.golden.at(static_cast<std::size_t>(k));
            if (mine != golden) {
                fail(std::format("side {} ({}): position {} differs from {}\n  golden: {}\n  C++:    {}", side.name, side.spec, k, side.golden_file,
                                 golden, mine));
            }
        }
        const std::uint64_t h = mix(mix(mix(mix(0, r.move), r.score), r.depth), r.nodes);
        return {{r.nodes, cpu, wall, 1}, h};
    }

    Measurement play_game(const Side& side, int g) {
        auto pos = sh::fingerprint::replay(reference.openings.at(static_cast<std::size_t>(g)));
        if (!pos) {
            fail("game opening: " + pos.error());
            return {};
        }
        std::array<std::unique_ptr<sh::AlphaBetaPlayer>, 2> players;
        for (auto& player : players) {
            auto created = side.parsed.create();
            if (!created) {
                fail("game player: " + created.error());
                return {};
            }
            player = std::move(*created);
        }
        Nanos cpu = 0;
        Nanos wall = 0;
        std::int64_t moves = 0;
        std::uint64_t h = 17;
        std::array<int, sh::kMaxMoves> buffer{};
        while (!pos->outcome().has_value() && pos->generate(buffer) > 0) {
            sh::AlphaBetaPlayer& player = *players[static_cast<std::size_t>(pos->side_to_move())];
            const Nanos c0 = thread_cpu_ns();
            const Nanos w0 = wall_ns();
            const int m = player.choose(*pos);
            wall += wall_ns() - w0;
            cpu += thread_cpu_ns() - c0;
            ++moves;
            h = mix(h, m);
            pos->make(m);
        }
        const std::int64_t nodes = players[0]->total_nodes() + players[1]->total_nodes();
        return {{nodes, cpu, wall, moves}, mix(h, nodes)};
    }
};

double median(std::vector<double> v) {
    std::ranges::sort(v);
    const std::size_t n = v.size();
    if (n == 0) return std::nan("");
    return n % 2 == 1 ? v[n / 2] : (v[n / 2 - 1] + v[n / 2]) / 2;
}

struct Options {
    std::vector<std::string> specs;
    std::optional<std::int64_t> nodes;
    int reps = 3;
    int positions = 64;
    int games = 2;
    std::string golden_dir;
    std::optional<double> max_load;
    bool ignore_load = false;
    bool warm_games = false;  // the untimed warm-up also plays the full games (as Bench --warm-games)
};

std::expected<Options, std::string> parse_args(const std::vector<std::string_view>& args) {
    Options o;
    auto value = [&](std::size_t& i) -> std::expected<std::string_view, std::string> {
        if (i + 1 >= args.size()) return std::unexpected("missing value for " + std::string(args[i]));
        return args[++i];
    };
    auto integer = [&](std::size_t& i) -> std::expected<std::int64_t, std::string> {
        const auto v = value(i);
        if (!v) return std::unexpected(v.error());
        const auto n = sh::parse_number<std::int64_t>(*v);
        if (!n) return std::unexpected("not an integer: " + std::string(*v));
        return *n;
    };
    for (std::size_t i = 1; i < args.size(); ++i) {
        const std::string_view a = args[i];
        if (a == "--nodes" || a == "--reps" || a == "--positions" || a == "--games") {
            const auto n = integer(i);
            if (!n) return std::unexpected(n.error());
            if (a == "--nodes") o.nodes = *n;
            if (a == "--reps") o.reps = static_cast<int>(*n);
            if (a == "--positions") o.positions = static_cast<int>(*n);
            if (a == "--games") o.games = static_cast<int>(*n);
        } else if (a == "--max-load") {
            const auto v = value(i);
            if (!v) return std::unexpected(v.error());
            double load = 0;   // a decimal such as 4.0 (the load limit is a real number)
            std::istringstream in{std::string(*v)};
            in >> load;
            if (in.fail() || !in.eof() || !(load > 0)) return std::unexpected("not a positive number: " + std::string(*v));
            o.max_load = load;
        } else if (a == "--golden") {
            const auto v = value(i);
            if (!v) return std::unexpected(v.error());
            o.golden_dir = std::string(*v);
        } else if (a == "--ignore-load") {
            o.ignore_load = true;
        } else if (a == "--warm-games") {
            o.warm_games = true;
        } else if (a.starts_with("--")) {
            return std::unexpected("unknown option " + std::string(a));
        } else {
            o.specs.emplace_back(a);
        }
    }
    if (o.specs.empty() || o.specs.size() > 2 || o.reps < 1 || o.positions < 0 || o.positions > 64 || o.games < 0 || o.games > 2 ||
        o.positions + o.games == 0) {
        return std::unexpected(
            "usage: search_bench <specA> [<specB>] [--nodes N] [--reps R] [--positions 0..64] [--games 0..2] [--golden DIR] [--max-load L] "
            "[--ignore-load] [--warm-games]");
    }
    return o;
}

}  // namespace

int main(int argc, char** argv) {
    const std::vector<std::string_view> args = sh::args_from_main(argc, argv);
    const auto parsed_args = parse_args(args);
    if (!parsed_args) {
        std::cerr << "search_bench: " << parsed_args.error() << '\n';
        return 2;
    }
    Options opt = *parsed_args;
    if (opt.nodes) {
        for (std::string& s : opt.specs) s = sh::fingerprint::with_nodes(s, *opt.nodes);
    }
    const auto reference = sh::fingerprint::load_reference();
    if (!reference) {
        std::cerr << "search_bench: " << reference.error() << '\n';
        return 2;
    }
    std::vector<Side> sides;
    for (std::size_t i = 0; i < opt.specs.size(); ++i) {
        auto parsed = sh::PlayerSpec::parse(opt.specs[i]);
        if (!parsed) {
            std::cerr << "search_bench: " << parsed.error() << '\n';
            return 2;
        }
        Side side{.name = i == 0 ? "A" : "B", .spec = opt.specs[i], .parsed = std::move(*parsed)};
        find_golden(side, opt.golden_dir);
        sides.push_back(std::move(side));
    }
    const bool has_b = sides.size() == 2;
    const int positions = opt.positions;
    const int games = opt.games;
    const int items = positions + games;

    const unsigned cores = std::max(1U, std::thread::hardware_concurrency());
    const double max_load = opt.max_load.value_or(static_cast<double>(cores));
    const double load_before = load_average();
    std::cout << std::format("load before: {:.2f} (1-minute average, {} cores)\n", load_before, cores);
    if (load_before > max_load && !opt.ignore_load) {
        std::cout << std::format("REFUSED: load {:.2f} exceeds {:.2f}; not started\n", load_before, max_load);
        return 3;
    }
    for (const Side& s : sides) std::cout << s.name << " = " << s.spec << " on the C++ build\n";
    if (!has_b) std::cout << "one spec: measuring A only\n";
    for (const Side& s : sides) {
        std::cout << (s.has_golden ? std::format("golden: side {} checked against {} on every position search\n", s.name, s.golden_file)
                                   : std::format("golden: side {} has no golden file for its spec; positions are not checked\n", s.name));
    }
    std::cout << std::format("items: {} reference positions + {} full games, {} timed repetitions, single thread, thread CPU time\n", positions,
                             games, opt.reps);

    Bench bench{*reference, {}};
    auto failed = [&]() -> bool {
        if (bench.failure.empty()) return false;
        std::cout << "GOLDEN CHECK FAILED or search error: " << bench.failure << '\n';
        std::cerr << "search_bench: " << bench.failure << '\n';
        return true;
    };

    // Warm-up: untimed, every side over the positions.
    for (const Side& side : sides) {
        for (int k = 0; k < positions; ++k) bench.search_position(side, k);
        if (opt.warm_games) {
            for (int g = 0; g < games; ++g) bench.play_game(side, g);
        }
        if (failed()) return 2;
    }

    std::array<Totals, 2> pos_totals{};
    std::array<Totals, 2> game_totals{};
    std::array<std::vector<std::uint64_t>, 2> first_signature;
    for (auto& v : first_signature) v.assign(static_cast<std::size_t>(items), 0);
    std::vector<bool> identical(static_cast<std::size_t>(items), has_b);
    std::vector<double> ratios;
    for (int r = 0; r < opt.reps; ++r) {
        std::array<Totals, 2> rep{};
        for (int k = 0; k < items; ++k) {
            const std::size_t ik = static_cast<std::size_t>(k);
            const bool a_first = (k + r) % 2 == 0;
            for (std::size_t turn = 0; turn < sides.size(); ++turn) {
                const std::size_t s = sides.size() == 1 ? 0 : a_first ? turn : 1 - turn;
                const Measurement m = k < positions ? bench.search_position(sides[s], k) : bench.play_game(sides[s], k - positions);
                if (failed()) return 2;
                rep[s] = rep[s].plus(m.totals);
                auto& totals = k < positions ? pos_totals : game_totals;
                totals[s] = totals[s].plus(m.totals);
                if (r == 0) {
                    first_signature[s][ik] = m.signature;
                } else if (first_signature[s][ik] != m.signature) {
                    std::cout << std::format("side {} is not deterministic on item {}\n", sides[s].name, k);
                    std::cerr << "search_bench: nondeterministic search\n";
                    return 2;
                }
            }
            if (has_b && r == 0) identical[ik] = first_signature[0][ik] == first_signature[1][ik];
        }
        if (has_b) {
            ratios.push_back(rep[0].nps() / rep[1].nps());
            std::cout << std::format("rep {}: A {:.0f} nodes/CPU-s, B {:.0f} nodes/CPU-s, ratio {:.4f}\n", r + 1, rep[0].nps(), rep[1].nps(),
                                     ratios.back());
        } else {
            std::cout << std::format("rep {}: A {:.0f} nodes/CPU-s\n", r + 1, rep[0].nps());
        }
    }

    const double load_after = load_average();
    for (std::size_t s = 0; s < sides.size(); ++s) {
        const Totals all = pos_totals[s].plus(game_totals[s]);
        std::cout << sides[s].name << " positions: " << pos_totals[s].text() << '\n';
        std::cout << sides[s].name << " games:     " << game_totals[s].text() << '\n';
        std::cout << sides[s].name << " all:       " << all.text() << '\n';
    }
    int same_positions = 0;
    int same_games = 0;
    for (int k = 0; k < items; ++k) {
        if (identical[static_cast<std::size_t>(k)]) (k < positions ? same_positions : same_games)++;
    }
    const bool identical_trees = same_positions == positions && same_games == games;
    double ratio = 1.0;
    if (has_b) {
        ratio = median(ratios);
        std::cout << std::format("trees: identical on {}/{} positions and {}/{} games\n", same_positions, positions, same_games, games);
        std::string list;
        for (const double r : ratios) list += std::format(" {:.4f}", r);
        std::cout << std::format("ratio nps(A)/nps(B): median {:.4f} (repetitions:{})\n", ratio, list);
        const std::int64_t nodes_b = sides[1].parsed.nodes;
        std::cout << std::format("equal cost: nodes(A) = nodes(B) x ratio = {} x {:.4f} = {:.0f}\n", nodes_b, ratio,
                                 static_cast<double>(nodes_b) * ratio);
    }
    std::cout << std::format("load after: {:.2f}\n", load_after);
    const bool loaded = load_before > max_load || load_after > max_load;
    if (loaded && !opt.ignore_load) {
        std::cout << std::format("REFUSED: load {:.2f} exceeds {:.2f}; no result\n", load_after, max_load);
        return 3;
    }
    const Totals all_a = pos_totals[0].plus(game_totals[0]);
    std::cout << std::format("RESULT nps_a={:.0f} cpu_ms_a={:.3f}", all_a.nps(), all_a.cpu_ms_per_move());
    if (has_b) {
        const Totals all_b = pos_totals[1].plus(game_totals[1]);
        std::cout << std::format(" nps_b={:.0f} cpu_ms_b={:.3f} ratio={:.4f} identical={}", all_b.nps(), all_b.cpu_ms_per_move(), ratio,
                                 identical_trees);
    }
    std::cout << (loaded ? " UNDER_LOAD" : "") << '\n';
    return 0;
}
