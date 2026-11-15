#include "options.hpp"

#include <utility>

namespace sh {

std::expected<SearchOptions, std::string> SearchOptions::create(SearchOptions options) {
    if (options.lmr < 0 || options.lmr > 3) return std::unexpected("lmr must be 0, 1, 2 or 3");
    if (options.aspiration < 0) return std::unexpected("aspiration must be >= 0");
    if (options.futility < 0 || options.futility2 < 0 || options.futility_gain < 0) {
        return std::unexpected("fut, fut2 and futk must be >= 0");
    }
    if (options.futility == 0 && options.futility2 == 0) options.futility_gain = kDefaultFutilityGain;
    return options;
}

SearchOptions SearchOptions::exact() const {
    SearchOptions out = *this;
    out.lmr = 0;
    out.futility = 0;
    out.futility2 = 0;
    out.futility_gain = kDefaultFutilityGain;
    return out;
}

std::string SearchOptions::describe() const {
    std::string b;
    const auto add = [&b](const std::string& part) {
        if (!b.empty()) b += ',';
        b += part;
    };
    if (pvs) add("pvs");
    if (killers) add("killers");
    if (history) add("history");
    if (lmr > 0) add("lmr=" + std::to_string(lmr));
    if (aspiration > 0) add("asp=" + std::to_string(aspiration));
    if (depth_preferred) add("ttrep");
    if (mate_fix) add("mate");
    if (partial) add("partial");
    if (futility > 0) add("fut=" + std::to_string(futility));
    if (futility2 > 0) add("fut2=" + std::to_string(futility2));
    if (futility_on()) add("futk=" + std::to_string(futility_gain));
    return b;
}

}  // namespace sh
