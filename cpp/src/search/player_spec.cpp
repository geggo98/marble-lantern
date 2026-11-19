#include "player_spec.hpp"

#include <map>
#include <optional>
#include <vector>

#include "linear_eval.hpp"
#include "parse_number.hpp"
#include "race_eval.hpp"

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

class Options {
public:
    explicit Options(std::map<std::string, std::string> values) : values_(std::move(values)) {}

    std::optional<std::string> take(const std::string& key) {
        const auto it = values_.find(key);
        if (it == values_.end()) return std::nullopt;
        std::string value = std::move(it->second);
        values_.erase(it);
        return value;
    }
    const std::map<std::string, std::string>& rest() const { return values_; }

private:
    std::map<std::string, std::string> values_;
};

std::expected<bool, std::string> flag(const std::optional<std::string>& value, const char* key) {
    if (!value || *value == "0") return false;
    if (*value == "1") return true;
    return std::unexpected(std::string(key) + " must be 0 or 1, got '" + *value + "'");
}

std::expected<int, std::string> int_option(const std::optional<std::string>& value, const char* key) {
    if (!value) return 0;
    return to_number<int>(key, *value);
}

}  // namespace

std::expected<PlayerSpec, std::string> PlayerSpec::parse(std::string_view text) {
    const std::size_t colon = text.find(':');
    const std::string_view kind = text.substr(0, colon);
    if (kind != "ab") return std::unexpected("unknown player kind '" + std::string(kind) + "', expected 'ab'");
    std::map<std::string, std::string> pairs;
    if (colon != std::string_view::npos && !trim(text.substr(colon + 1)).empty()) {
        for (const std::string_view pair : split_commas(text.substr(colon + 1))) {
            const std::size_t eq = pair.find('=');
            if (eq == std::string_view::npos) return std::unexpected("expected key=value, got '" + std::string(pair) + "'");
            pairs[std::string(trim(pair.substr(0, eq)))] = std::string(trim(pair.substr(eq + 1)));
        }
    }
    Options in(std::move(pairs));
    PlayerSpec spec;
    spec.text = std::string(text);
    spec.evaluator = in.take("eval").value_or("distance");
    if (const auto nodes = in.take("nodes")) {
        const auto v = to_number<std::int64_t>("nodes", *nodes);
        if (!v) return std::unexpected(v.error());
        spec.nodes = *v;
    }
    if (const auto depth = in.take("depth")) {
        const auto v = to_number<int>("depth", *depth);
        if (!v) return std::unexpected(v.error());
        spec.depth = *v;
    }
    if (const auto tt = in.take("tt")) {
        const auto v = to_number<int>("tt", *tt);
        if (!v) return std::unexpected(v.error());
        spec.table_bits = *v;
    }
    SearchOptions search;
    const auto read_flag = [&](const char* key, bool& into) -> std::optional<std::string> {
        const auto v = flag(in.take(key), key);
        if (!v) return v.error();
        into = *v;
        return std::nullopt;
    };
    const auto read_int = [&](const char* key, int& into) -> std::optional<std::string> {
        const auto v = int_option(in.take(key), key);
        if (!v) return v.error();
        into = *v;
        return std::nullopt;
    };
    int asp = 0;
    int futk = SearchOptions::kDefaultFutilityGain;
    const auto futk_text = in.take("futk");
    if (futk_text) {
        const auto v = to_number<int>("futk", *futk_text);
        if (!v) return std::unexpected(v.error());
        futk = *v;
    }
    for (const auto& error : {read_int("asp", asp), read_int("fut", search.futility), read_int("fut2", search.futility2),
                              read_flag("pvs", search.pvs), read_flag("killers", search.killers), read_flag("history", search.history),
                              read_int("lmr", search.lmr), read_flag("ttrep", search.depth_preferred), read_flag("mate", search.mate_fix),
                              read_flag("partial", search.partial)}) {
        if (error) return std::unexpected(*error);
    }
    search.aspiration = asp == 1 ? SearchOptions::kDefaultAspiration : asp;
    search.futility_gain = futk;
    const auto checked = SearchOptions::create(search);
    if (!checked) return std::unexpected(checked.error());
    spec.options = *checked;
    int race = 0;
    if (const auto error = read_int("race", race)) return std::unexpected(*error);
    if (race < 0 || race > 2) return std::unexpected("race must be 0, 1 or 2, got " + std::to_string(race));
    spec.race = race;
    if (const auto rows = in.take("racerows")) {
        if (race == 0) return std::unexpected("racerows needs race=1 or race=2 with the table enabled");
        const auto v = to_number<int>("racerows", *rows);
        if (!v) return std::unexpected(v.error());
        if (*v != 1 && *v != 2) return std::unexpected("racerows must be 1 or 2, got " + std::to_string(*v));
        spec.race_rows = *v;
    }
    if (const auto jump = in.take("jump")) {
        if (*jump == "long") {
            spec.jump_rule = RuleSet::JumpRule::Long;
        } else if (*jump != "adjacent") {
            return std::unexpected("jump must be adjacent or long, got '" + *jump + "'");
        }
    }
    if (!in.rest().empty()) {
        std::string names;
        for (const auto& [key, value] : in.rest()) names += (names.empty() ? "" : ", ") + key;
        return std::unexpected("unknown options [" + names + "]");
    }
    if (spec.table_bits < 0 || spec.table_bits > 30) return std::unexpected("tt must be in 0..30, got " + std::to_string(spec.table_bits));
    if (spec.depth < 1 || spec.depth > AlphaBeta::kMaxDepth) {
        return std::unexpected("depth must be in 1.." + std::to_string(AlphaBeta::kMaxDepth) + ", got " + std::to_string(spec.depth));
    }
    // fail early on an evaluator that is not ported, not only at the first search
    if (spec.evaluator == "distance" || spec.evaluator == "linear-r2") {
        return std::unexpected("evaluator '" + spec.evaluator + "' is not ported");
    }
    if (spec.evaluator != "linear-r1" && spec.evaluator != "linear-r3" && spec.evaluator != "racelin1-r3" &&
        spec.evaluator != "racelin-r3") {
        return std::unexpected("unknown evaluator '" + spec.evaluator + "', known: linear-r1, linear-r3, racelin1-r3, racelin-r3");
    }
    if (spec.jump_rule == RuleSet::JumpRule::Long) {
        if (spec.race != 0) {
            return std::unexpected("jump=long cannot be combined with race=" + std::to_string(spec.race) +
                                   ": the race table and the solitaire beam are computed for single jumps");
        }
        if (spec.evaluator == "racelin1-r3" || spec.evaluator == "racelin-r3") {
            return std::unexpected("jump=long cannot be combined with eval=" + spec.evaluator +
                                   ": it reads the race table, which is computed for single jumps (use its base evaluator)");
        }
    }
    return spec;
}

std::expected<std::unique_ptr<Evaluator>, std::string> PlayerSpec::make_evaluator() const {
    if (evaluator == "linear-r1") return LinearEvaluator::linear_r1();
    if (evaluator == "linear-r3") return LinearEvaluator::linear_r3();
    if (evaluator == "racelin1-r3") {
        const auto& table = RaceEvaluator::rows1_table();
        if (!table) return std::unexpected("race table rows1: " + table.error().message);
        return RaceEvaluator::racelin1_r3(*table);
    }
    if (evaluator == "racelin-r3") {
        const auto table = RaceMove::shared_table(2);
        if (!table) return std::unexpected(table.error());
        return RaceEvaluator::racelin_r3(*table);
    }
    return std::unexpected("evaluator '" + evaluator + "' is not ported");
}

std::expected<std::unique_ptr<AlphaBeta>, std::string> PlayerSpec::make_searcher() const {
    auto eval = make_evaluator();
    if (!eval) return std::unexpected(eval.error());
    return std::make_unique<AlphaBeta>(std::move(*eval), table_bits, true, options);
}

std::expected<std::unique_ptr<AlphaBetaPlayer>, std::string> PlayerSpec::create() const {
    std::unique_ptr<RaceMove> race_move;
    if (race != 0) {
        auto table = RaceMove::shared_table(race_rows);
        if (!table) return std::unexpected(table.error());
        race_move = std::make_unique<RaceMove>(std::move(*table), race);
    }
    auto eval = make_evaluator();
    if (!eval) return std::unexpected(eval.error());
    return std::make_unique<AlphaBetaPlayer>(std::move(*eval), depth, AlphaBeta::kNoLimit, nodes, table_bits, evaluator, options,
                                             std::move(race_move), jump_rule);
}

}  // namespace sh
