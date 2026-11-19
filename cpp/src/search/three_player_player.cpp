#include "three_player_player.hpp"

#include <map>
#include <stdexcept>
#include <vector>

#include "linear_weights.hpp"
#include "parse_number.hpp"

namespace sh {

namespace {

std::string_view trim(std::string_view s) {
    while (!s.empty() && (s.front() == ' ' || s.front() == '\t')) s.remove_prefix(1);
    while (!s.empty() && (s.back() == ' ' || s.back() == '\t')) s.remove_suffix(1);
    return s;
}

// Java String.split(","): trailing empty strings are dropped.
std::vector<std::string_view> split_commas(std::string_view s) {
    std::vector<std::string_view> out;
    std::size_t start = 0;
    while (true) {
        const std::size_t at = s.find(',', start);
        if (at == std::string_view::npos) {
            out.push_back(s.substr(start));
            break;
        }
        out.push_back(s.substr(start, at - start));
        start = at + 1;
    }
    while (!out.empty() && out.back().empty()) out.pop_back();
    return out;
}

template <class T>
std::expected<T, std::string> to_number(std::string_view key, std::string_view text) {
    const std::optional<T> value = parse_number<T>(text);
    if (!value) return std::unexpected(std::string(key) + " must be a number, got '" + std::string(text) + "'");
    return *value;
}

// The 12 weights of an evaluator name, or empty if the name is not one of the three-player evaluators.
std::optional<std::span<const double>> weights_of(const std::string& evaluator) {
    if (evaluator == "linear-r2") return std::span<const double>(linear_weights::kRound2);
    if (evaluator == "linear-r1" || evaluator == "linear") return std::span<const double>(linear_weights::kR1);
    return std::nullopt;
}

}  // namespace

ThreePlayerPlayer::ThreePlayerPlayer(std::span<const double> weights, const std::string& evaluator_name, int max_depth,
                                     std::int64_t time_limit_millis, std::int64_t max_nodes, int table_bits, RuleSet::JumpRule jump_rule)
    : weights_(weights.begin(), weights.end()),
      table_bits_(table_bits),
      search_(weights, table_bits),
      name_("ab3[eval=" + evaluator_name + ", depth<=" + std::to_string(max_depth) +
            (time_limit_millis == ThreePlayerSearch::kNoLimit ? "" : ", " + std::to_string(time_limit_millis) + " ms") +
            (max_nodes == ThreePlayerSearch::kNoLimit ? "" : ", " + std::to_string(max_nodes) + " nodes") +
            (jump_rule == RuleSet::JumpRule::Long ? ", jump=long" : "") + "]"),
      jump_rule_(jump_rule),
      max_depth_(max_depth),
      time_limit_millis_(time_limit_millis),
      max_nodes_(max_nodes) {}

int ThreePlayerPlayer::choose(const Position& position) {
    if (position.rules().players() != 3) throw std::invalid_argument("player " + name_ + " plays three players");
    if (!plays(position)) {
        throw std::invalid_argument("player " + name_ + " plays jump=" + (jump_rule_ == RuleSet::JumpRule::Long ? "long" : "adjacent") +
                                    " but the position has jump=" + (position.rules().jump_rule() == RuleSet::JumpRule::Long ? "long" : "adjacent"));
    }
    const auto t0 = std::chrono::steady_clock::now();
    const SearchResult result = search_.search(position, max_depth_, time_limit_millis_, max_nodes_);
    total_time_ += std::chrono::steady_clock::now() - t0;
    total_nodes_ += result.nodes;
    ++moves_;
    last_result_ = result;
    return result.move;
}

void ThreePlayerPlayer::clear_search() {
    search_ = ThreePlayerSearch(weights_, table_bits_);
    last_result_.reset();
}

std::expected<ThreePlayerSpec, std::string> ThreePlayerSpec::parse(std::string_view text) {
    const std::size_t colon = text.find(':');
    const std::string_view kind = text.substr(0, colon);
    if (kind != "ab3") return std::unexpected("unknown player kind '" + std::string(kind) + "', expected 'ab3'");
    std::map<std::string, std::string> options;
    if (colon != std::string_view::npos && !trim(text.substr(colon + 1)).empty()) {
        for (const std::string_view pair : split_commas(text.substr(colon + 1))) {
            const std::size_t eq = pair.find('=');
            if (eq == std::string_view::npos) return std::unexpected("expected key=value, got '" + std::string(pair) + "'");
            options[std::string(trim(pair.substr(0, eq)))] = std::string(trim(pair.substr(eq + 1)));
        }
    }
    const auto take = [&options](const char* key) -> std::optional<std::string> {
        const auto it = options.find(key);
        if (it == options.end()) return std::nullopt;
        std::string value = std::move(it->second);
        options.erase(it);
        return value;
    };
    ThreePlayerSpec spec;
    spec.text = std::string(text);
    if (const auto eval = take("eval")) spec.evaluator = *eval;
    if (const auto nodes = take("nodes")) {
        const auto v = to_number<std::int64_t>("nodes", *nodes);
        if (!v) return std::unexpected(v.error());
        spec.nodes = *v;
    }
    if (const auto depth = take("depth")) {
        const auto v = to_number<int>("depth", *depth);
        if (!v) return std::unexpected(v.error());
        spec.depth = *v;
    }
    if (const auto ms = take("ms")) {
        const auto v = to_number<std::int64_t>("ms", *ms);
        if (!v) return std::unexpected(v.error());
        spec.millis = *v;
    }
    if (const auto tt = take("tt")) {
        const auto v = to_number<int>("tt", *tt);
        if (!v) return std::unexpected(v.error());
        spec.table_bits = *v;
    }
    if (const auto jump = take("jump")) {
        if (*jump == "long") {
            spec.jump_rule = RuleSet::JumpRule::Long;
        } else if (*jump != "adjacent") {
            return std::unexpected("jump must be adjacent or long, got '" + *jump + "'");
        }
    }
    if (!options.empty()) {
        std::string names;
        for (const auto& [key, value] : options) names += (names.empty() ? "" : ", ") + key;
        return std::unexpected("unknown options [" + names + "]");
    }
    if (!weights_of(spec.evaluator)) {
        return std::unexpected("three-player evaluator must be linear-r2, linear-r1 or linear, got '" + spec.evaluator + "'");
    }
    if (spec.table_bits < 0 || spec.table_bits > 30) return std::unexpected("tt must be in 0..30, got " + std::to_string(spec.table_bits));
    if (spec.depth < 1) return std::unexpected("depth must be at least 1, got " + std::to_string(spec.depth));
    return spec;
}

std::expected<std::unique_ptr<ThreePlayerPlayer>, std::string> ThreePlayerSpec::create() const {
    const auto weights = weights_of(evaluator);
    if (!weights) return std::unexpected("three-player evaluator must be linear-r2, linear-r1 or linear, got '" + evaluator + "'");
    return std::make_unique<ThreePlayerPlayer>(*weights, evaluator, depth, millis, nodes, table_bits, jump_rule);
}

}  // namespace sh
