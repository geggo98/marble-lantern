#include "fingerprint.hpp"

#include <algorithm>
#include <format>
#include <memory>
#include <optional>
#include <span>

#include "alpha_beta.hpp"
#include "golden.hpp"
#include "move.hpp"
#include "parse_number.hpp"
#include "player_spec.hpp"
#include "scores.hpp"

namespace sh::fingerprint {

namespace {

std::vector<std::string_view> split(std::string_view text, char sep) {
    std::vector<std::string_view> parts;
    std::size_t start = 0;
    while (true) {
        const std::size_t at = text.find(sep, start);
        if (at == std::string_view::npos) {
            parts.push_back(text.substr(start));
            return parts;
        }
        parts.push_back(text.substr(start, at - start));
        start = at + 1;
    }
}

template <class T>
std::optional<T> number(std::string_view text, int base = 10) { return parse_number<T>(text, base); }

// Java: h = h * 31 + v with long arithmetic (wrapping); an int is sign-extended first.
constexpr std::uint64_t mix(std::uint64_t h, std::int64_t v) { return h * 31 + static_cast<std::uint64_t>(v); }

struct Entry {
    int index = 0;
    int ply = 0;
    SearchResult result;
    SearchResult fixed;

    std::string line() const {
        return std::format("{:2d} ply={:3d} move={:<15} score={:8d} depth={:2d} nodes={:6d} | depth {}: move={:<15} score={:8d} nodes={}", index,
                           ply, Move::to_string(result.move), result.score, result.depth, result.nodes, kFixedDepth,
                           Move::to_string(fixed.move), fixed.score, fixed.nodes);
    }
};

}  // namespace

std::string Part::checksum_text() const { return std::to_string(static_cast<std::int64_t>(checksum)) + "L"; }

std::expected<Position, std::string> replay(const std::vector<int>& moves) {
    Position pos = Position::initial(RuleSet::defaults());
    std::array<int, kMaxMoves> buffer{};
    for (const int m : moves) {
        const int n = pos.generate(buffer);
        if (!std::ranges::contains(std::span<const int>(buffer).first(static_cast<std::size_t>(n)), m)) {
            return std::unexpected("illegal move " + std::to_string(m) + " at ply " + std::to_string(pos.ply()));
        }
        pos.make(m);
    }
    return pos;
}

std::expected<ReferenceData, std::string> load_reference() {
    const auto lines = golden::load_lines("reference.tsv");
    if (!lines) return std::unexpected("reference.tsv: " + lines.error().message);
    ReferenceData data;
    for (const std::string& line : *lines) {
        if (line.empty() || line.front() == '#') continue;
        const auto row = split(line, '\t');
        if (row.size() != 5) return std::unexpected("reference.tsv: expected 5 columns, got " + std::to_string(row.size()));
        std::vector<int> moves;
        for (const std::string_view part : split(row[4], ' ')) {
            const auto m = number<int>(part);
            if (!m) return std::unexpected("reference.tsv: bad move '" + std::string(part) + "'");
            moves.push_back(*m);
        }
        const auto replayed = replay(moves);
        if (!replayed) return std::unexpected("reference.tsv row " + std::string(row[0]) + " " + std::string(row[1]) + ": " + replayed.error());
        if (replayed->ply() != number<int>(row[2]) || replayed->hash() != number<std::uint64_t>(row[3], 16)) {
            return std::unexpected("reference.tsv row " + std::string(row[0]) + " " + std::string(row[1]) + ": ply or hash differs from the replay");
        }
        if (row[0] == "position") {
            data.positions.push_back(std::move(moves));
        } else if (row[0] == "opening") {
            data.openings.push_back(std::move(moves));
        } else {
            return std::unexpected("reference.tsv: unknown kind '" + std::string(row[0]) + "'");
        }
    }
    if (data.positions.size() != 64 || data.openings.size() != 2) return std::unexpected("reference.tsv: expected 64 positions and 2 openings");
    return data;
}

std::expected<Part, std::string> positions(const ReferenceData& reference, std::string_view spec) {
    const auto parsed = PlayerSpec::parse(spec);
    if (!parsed) return std::unexpected(parsed.error());
    std::uint64_t h = 17;
    for (const auto& list : reference.positions) {
        for (const int m : list) h = mix(h, m);
        h = mix(h, static_cast<std::int64_t>(list.size()));
    }
    Part part;
    for (std::size_t i = 0; i < reference.positions.size(); ++i) {
        const auto pos = replay(reference.positions[i]);
        if (!pos) return std::unexpected(pos.error());
        Entry e;
        e.index = static_cast<int>(i);
        e.ply = pos->ply();
        {
            auto searcher = parsed->make_searcher();
            if (!searcher) return std::unexpected(searcher.error());
            e.result = (*searcher)->search(*pos, parsed->depth, AlphaBeta::kNoLimit, parsed->nodes);
        }
        {
            auto searcher = parsed->make_searcher();
            if (!searcher) return std::unexpected(searcher.error());
            e.fixed = (*searcher)->search(*pos, std::min(kFixedDepth, parsed->depth), AlphaBeta::kNoLimit, AlphaBeta::kNoLimit);
        }
        h = mix(h, e.result.move);
        h = mix(h, e.result.score);
        h = mix(h, e.result.depth);
        h = mix(h, e.result.nodes);
        h = mix(h, e.fixed.move);
        h = mix(h, e.fixed.score);
        h = mix(h, e.fixed.nodes);
        part.lines.push_back(e.line() + " | kinds " + scores::kind_name(scores::kind(e.result.score)) + "/" +
                             scores::kind_name(scores::kind(e.fixed.score)));
    }
    part.checksum = h;
    return part;
}

std::string with_nodes(std::string_view spec, std::int64_t nodes) {
    const std::size_t colon = spec.find(':');
    std::string out(spec.substr(0, colon));
    out += ':';
    if (colon != std::string_view::npos) {
        for (std::string_view pair : split(spec.substr(colon + 1), ',')) {
            while (!pair.empty() && pair.front() == ' ') pair.remove_prefix(1);
            while (!pair.empty() && pair.back() == ' ') pair.remove_suffix(1);
            if (!pair.empty() && !pair.starts_with("nodes=")) out += std::string(pair) + ',';
        }
    }
    return out + "nodes=" + std::to_string(nodes);
}

std::expected<Part, std::string> game(const ReferenceData& reference, std::string_view spec, std::int64_t nodes) {
    const auto parsed = PlayerSpec::parse(with_nodes(spec, nodes));
    if (!parsed) return std::unexpected(parsed.error());
    auto pos = replay(reference.openings.at(0));
    if (!pos) return std::unexpected(pos.error());
    std::array<std::unique_ptr<AlphaBetaPlayer>, 2> players;
    for (auto& player : players) {
        auto created = parsed->create();
        if (!created) return std::unexpected(created.error());
        player = std::move(*created);
    }
    std::uint64_t h = 17;
    std::string line;
    std::string kinds;
    std::array<int, kMaxMoves> buffer{};
    while (!pos->outcome().has_value() && pos->generate(buffer) > 0) {
        AlphaBetaPlayer& player = *players[static_cast<std::size_t>(pos->side_to_move())];
        const int m = player.choose(*pos);
        h = mix(h, m);
        const auto kind = player.last_score_kind();
        kinds += kind ? scores::kind_name(*kind)[0] : 'r';
        line += Move::to_string(m) + ' ';
        pos->make(m);
    }
    h = h * 31 + static_cast<std::uint64_t>(players[0]->total_nodes()) * 7 + static_cast<std::uint64_t>(players[1]->total_nodes());
    Part part;
    if (!line.empty()) line.pop_back();
    part.lines.push_back("moves " + line);
    part.lines.push_back("plies=" + std::to_string(pos->ply()) + " nodes=" + std::to_string(players[0]->total_nodes()) + "/" +
                         std::to_string(players[1]->total_nodes()));
    part.lines.push_back("kinds " + kinds + " (O ordinary, R race band, M mate, r race move) race-moves=" +
                         std::to_string(players[0]->race_moves()) + "/" + std::to_string(players[1]->race_moves()));
    part.checksum = h;
    return part;
}

}  // namespace sh::fingerprint
