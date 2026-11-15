// Reads the network golden files written by the Java exporter :nn:goldenExport (see README, "Golden files of the network").
// All files are safetensors with F32 tensors. A file is checked against the SHA-256 of cpp/golden/MANIFEST.tsv before it is
// parsed (binary files have the line count 0 there, so the line reader of golden.hpp cannot be used). Missing files and
// corrupt files are told apart as in golden.hpp (golden::ErrorKind), so that tests skip or fail like the classical ones.
#pragma once

#include <expected>
#include <filesystem>
#include <map>
#include <optional>
#include <span>
#include <string>
#include <string_view>
#include <vector>

#include "golden.hpp"
#include "parameters.hpp"
#include "safetensors.hpp"

namespace sh::nn {

// The preset names of the golden files: "small", "large", "gen11".
struct Preset {
    std::string name;    // small | large | gen11
    std::string key;     // small-u5 | large-u5 | large-gen11 (file names and manifest keys)
    NetworkConfig config;
};
std::optional<Preset> preset_by_name(std::string_view name);

// A golden file of the manifest, verified and parsed.
std::expected<Contents, golden::Error> load_golden_contents(const std::filesystem::path& dir, std::string_view name);

// "#key<TAB>value" lines of the manifest (the key without '#').
std::expected<std::map<std::string, std::string>, golden::Error> load_figures(const std::filesystem::path& dir);

// The extra term of the bound, as a fraction of the largest |logit| of the position: figure
// "extra_rel_of_max_logit.<impl>.<preset key>" or 0. impl is "vector" or "tornado" in the manifest; the C++ implementations
// use their own key and fall back to "vector" (the Java implementation with the same kind of arithmetic) if they have none.
double extra_rel(const std::map<std::string, std::string>& figures, std::string_view impl, const Preset& preset);

struct GoldenData {
    int positions = 0;
    std::vector<float> inputs;    // [positions][122][13]
    std::vector<float> logits;    // [positions][242] of the scalar Java reference
    std::vector<float> values;    // [positions][players]
    std::vector<float> vlogits;   // VectorNetwork of Java
    std::vector<float> vvalues;
};

// nn-inputs and nn-ref-<key>: the inputs and the references of a preset.
std::expected<GoldenData, golden::Error> load_golden_data(const std::filesystem::path& dir, const Preset& preset);

// The weights of a preset: nn-weights-<key> for small and large; for gen11 the real weights from
// $STERNHALMA_DATA_DIR/loop4/gen-0011/candidate.safetensors (Missing if the variable is not set or the file is absent).
std::expected<Parameters, golden::Error> load_golden_weights(const std::filesystem::path& dir, const Preset& preset);

// The bound of docs/network.md, "Numeric conventions": |fast - ref| <= 1e-4 + 1e-3 |ref| (+ extra * largest |logit|).
struct Comparison {
    double logits_fraction = 0;   // largest error as a fraction of the bound, policy logits
    double values_fraction = 0;
    bool logits_bit_identical = true;
    double values_max_ulps = 0;   // largest distance in units of the last place (float); infinity for a NaN mismatch
    double needed_extra = 0;      // the extra term that would make the policy pass (as in the manifest)
    double fraction() const { return logits_fraction > values_fraction ? logits_fraction : values_fraction; }
};

// Compares positions [first, first + n) of the references with outputs for n positions. NaN equals NaN, NaN against a number is infinite.
Comparison compare_outputs(std::span<const float> logits, std::span<const float> values, std::span<const float> ref_logits,
                           std::span<const float> ref_values, int first, int n, int players, double extra);

}  // namespace sh::nn
