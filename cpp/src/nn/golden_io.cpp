#include "golden_io.hpp"

#include <algorithm>
#include <array>
#include <bit>
#include <sstream>
#include <cerrno>
#include <cmath>
#include <cstdlib>
#include <fstream>
#include <iterator>
#include <limits>

#include "sha256.hpp"

namespace sh::nn {
namespace {

std::unexpected<golden::Error> missing(std::string message) { return std::unexpected(golden::Error{golden::ErrorKind::Missing, std::move(message)}); }
std::unexpected<golden::Error> corrupt(std::string message) { return std::unexpected(golden::Error{golden::ErrorKind::Corrupt, std::move(message)}); }

std::expected<std::string, golden::Error> read_text(const std::filesystem::path& path) {
    std::ifstream in(path, std::ios::binary);
    if (!in) return missing("cannot open " + path.filename().string());
    std::ostringstream text;
    text << in.rdbuf();
    return text.str();
}

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

const std::vector<float>* find_data(const Contents& c, std::string_view name) {
    for (const Tensor& t : c.tensors) {
        if (t.name == name) return &t.data;
    }
    return nullptr;
}

std::int64_t ordered_bits(float f) {  // monotonic integer image of a finite float, for the distance in ulps
    const auto bits = static_cast<std::int32_t>(std::bit_cast<std::uint32_t>(f));
    return bits < 0 ? static_cast<std::int64_t>(std::numeric_limits<std::int32_t>::min()) - bits : bits;
}

double error_of(float fast, double ref) {
    if (std::isnan(fast) || std::isnan(ref)) return std::isnan(fast) && std::isnan(ref) ? 0.0 : std::numeric_limits<double>::infinity();
    return std::abs(static_cast<double>(fast) - ref);
}

}  // namespace

std::optional<Preset> preset_by_name(std::string_view name) {
    if (name == "small") return Preset{"small", "small-u5", small_config(2, PlaneSet::Classic1)};
    if (name == "large") return Preset{"large", "large-u5", large_config(2, PlaneSet::Classic1)};
    if (name == "gen11") return Preset{"gen11", "large-gen11", large_config(2, PlaneSet::Classic1)};
    return std::nullopt;
}

std::expected<Contents, golden::Error> load_golden_contents(const std::filesystem::path& dir, std::string_view name) {
    auto manifest_text = read_text(dir / "MANIFEST.tsv");
    if (!manifest_text) return missing("no MANIFEST.tsv in the golden directory (run :nn:goldenExport)");
    auto manifest = golden::parse_manifest(*manifest_text);
    if (!manifest) return std::unexpected(manifest.error());
    const golden::ManifestEntry* entry = nullptr;
    for (const golden::ManifestEntry& e : *manifest) {
        if (e.name == name) entry = &e;
    }
    if (entry == nullptr) return missing(std::string(name) + " is not listed in MANIFEST.tsv (run :nn:goldenExport)");
    auto bytes = read_text(dir / std::string(name));
    if (!bytes) return corrupt(std::string(name) + " is listed in MANIFEST.tsv but cannot be read");
    const std::string actual = sha256_hex(std::as_bytes(std::span<const char>(*bytes)));
    if (actual != entry->sha256) {
        return corrupt(std::string(name) + ": SHA-256 " + actual + " differs from MANIFEST.tsv " + entry->sha256);
    }
    auto contents = parse_safetensors(std::as_bytes(std::span<const char>(*bytes)));
    if (!contents) return corrupt(std::string(name) + ": " + contents.error().message);
    return std::move(*contents);
}

std::expected<std::map<std::string, std::string>, golden::Error> load_figures(const std::filesystem::path& dir) {
    auto text = read_text(dir / "MANIFEST.tsv");
    if (!text) return missing("no MANIFEST.tsv in the golden directory (run :nn:goldenExport)");
    std::map<std::string, std::string> figures;
    for (std::string_view line : split(*text, '\n')) {
        if (!line.empty() && line.back() == '\r') line.remove_suffix(1);
        if (line.size() < 2 || line.front() != '#' || line[1] == ' ') continue;  // "# ..." is a plain comment
        const std::vector<std::string_view> f = split(line.substr(1), '\t');
        figures[std::string(f[0])] = f.size() > 1 ? std::string(f[1]) : std::string();
    }
    return figures;
}

std::expected<double, golden::Error> extra_rel(const std::map<std::string, std::string>& figures, std::string_view impl, const Preset& preset) {
    const std::array<std::string, 2> keys = {"extra_rel_of_max_logit." + std::string(impl) + "." + preset.key, "extra_rel_of_max_logit.vector." + preset.key};
    for (const std::string& key : keys) {
        const auto it = figures.find(key);
        if (it == figures.end()) continue;
        const std::string& text = it->second;
        // the whole text is one finite number in [0, kMaxExtraRel]; "0.25x", "", "nan", "-1" and "1e9" are a corrupt manifest
        double v = 0;
        std::istringstream in(text);
        in >> std::noskipws >> v;
        if (text.empty() || !in || in.peek() != std::char_traits<char>::eof() || !std::isfinite(v) || v < 0 || v > kMaxExtraRel) {
            return corrupt("figure " + key + " in MANIFEST.tsv is not a number in [0, " + std::to_string(kMaxExtraRel) + "]: '" + text + "'");
        }
        return v;
    }
    return 0.0;
}

std::expected<GoldenData, golden::Error> load_golden_data(const std::filesystem::path& dir, const Preset& preset) {
    auto inputs = load_golden_contents(dir, "nn-inputs.safetensors");
    if (!inputs) return std::unexpected(inputs.error());
    auto ref = load_golden_contents(dir, "nn-ref-" + preset.key + ".safetensors");
    if (!ref) return std::unexpected(ref.error());
    GoldenData d;
    const auto* in = find_data(*inputs, "inputs");
    const auto* logits = find_data(*ref, "logits");
    const auto* values = find_data(*ref, "values");
    const auto* vlogits = find_data(*ref, "vlogits");
    const auto* vvalues = find_data(*ref, "vvalues");
    if (in == nullptr || logits == nullptr || values == nullptr || vlogits == nullptr || vvalues == nullptr) {
        return corrupt("nn-inputs or nn-ref-" + preset.key + " lacks a tensor (inputs, logits, values, vlogits, vvalues)");
    }
    const auto size = static_cast<std::size_t>(preset.config.input_size());
    if (in->size() % size != 0 || in->empty()) return corrupt("nn-inputs: size is not a multiple of one position");
    d.positions = static_cast<int>(in->size() / size);
    const auto n = static_cast<std::size_t>(d.positions);
    if (logits->size() != n * static_cast<std::size_t>(kPolicySize) || values->size() != n * static_cast<std::size_t>(preset.config.players) ||
        vlogits->size() != logits->size() || vvalues->size() != values->size()) {
        return corrupt("nn-ref-" + preset.key + ": output sizes do not match the number of positions");
    }
    d.inputs = *in;
    d.logits = *logits;
    d.values = *values;
    d.vlogits = *vlogits;
    d.vvalues = *vvalues;
    return d;
}

std::expected<Parameters, golden::Error> load_golden_weights(const std::filesystem::path& dir, const Preset& preset) {
    if (preset.name == "gen11") {
        const char* data = std::getenv("STERNHALMA_DATA_DIR");
        if (data == nullptr || *data == '\0') return missing("STERNHALMA_DATA_DIR is not set (real weights of gen11)");
        const std::filesystem::path file = std::filesystem::path(data) / "loop4" / "gen-0011" / "candidate.safetensors";
        if (!std::filesystem::exists(file)) return missing(file.filename().string() + " not found under STERNHALMA_DATA_DIR/loop4/gen-0011");
        auto p = Parameters::load(file);
        if (!p) return corrupt(p.error().message);
        return std::move(*p);
    }
    auto contents = load_golden_contents(dir, "nn-weights-" + preset.key + ".safetensors");
    if (!contents) return std::unexpected(contents.error());
    auto p = Parameters::from_contents(std::move(*contents));
    if (!p) return corrupt(p.error().message);
    return std::move(*p);
}

Comparison compare_outputs(std::span<const float> logits, std::span<const float> values, std::span<const float> ref_logits,
                           std::span<const float> ref_values, int first, int n, int players, double extra) {
    constexpr double kAbs = 1e-4;
    constexpr double kRel = 1e-3;
    Comparison out;
    const auto policy = static_cast<std::size_t>(kPolicySize);
    const auto p = static_cast<std::size_t>(players);
    const std::size_t rows = p == 0 ? 0 : ref_values.size() / p;   // the reference positions wrap around, as the callers build their inputs
    for (std::size_t b = 0; b < static_cast<std::size_t>(n); ++b) {
        const std::size_t rb = rows == 0 ? static_cast<std::size_t>(first) + b : (static_cast<std::size_t>(first) + b) % rows;
        double largest = 0;
        for (std::size_t i = 0; i < policy; ++i) largest = std::max(largest, std::abs(static_cast<double>(ref_logits[rb * policy + i])));
        for (std::size_t i = 0; i < policy; ++i) {
            const float ref = ref_logits[rb * policy + i];
            const float got = logits[b * policy + i];
            const double err = error_of(got, ref);
            const double base = kAbs + kRel * std::abs(static_cast<double>(ref));
            out.logits_fraction = std::max(out.logits_fraction, err / (base + extra * largest));
            if (largest > 0) out.needed_extra = std::max(out.needed_extra, std::max(0.0, err - base) / largest);
            if (std::bit_cast<std::uint32_t>(got) != std::bit_cast<std::uint32_t>(ref)) out.logits_bit_identical = false;
        }
        for (std::size_t i = 0; i < p; ++i) {
            const float ref = ref_values[rb * p + i];
            const float got = values[b * p + i];
            const double bound = kAbs + kRel * std::abs(static_cast<double>(ref));
            out.values_fraction = std::max(out.values_fraction, error_of(got, ref) / bound);
            if (std::isnan(got) || std::isnan(ref)) {
                if (std::isnan(got) != std::isnan(ref)) out.values_max_ulps = std::numeric_limits<double>::infinity();
            } else {
                out.values_max_ulps = std::max(out.values_max_ulps, static_cast<double>(std::abs(ordered_bits(got) - ordered_bits(ref))));
            }
        }
    }
    return out;
}

}  // namespace sh::nn
