#include "parameters.hpp"

#include <algorithm>
#include <cstdlib>
#include <iostream>
#include <set>

namespace sh::nn {
namespace {

std::unexpected<Error> fail(std::string message) { return std::unexpected(Error{std::move(message)}); }

std::string show(const std::vector<int>& shape) {
    std::string out = "[";
    for (std::size_t i = 0; i < shape.size(); ++i) out += (i > 0 ? ", " : "") + std::to_string(shape[i]);
    return out + "]";
}

}  // namespace

std::vector<std::pair<std::string, std::vector<int>>> parameter_shapes(const NetworkConfig& config) {
    const int c = config.channels;
    const int h = config.value_hidden;
    const int p = config.players;
    const int conv = kStencil * c;
    std::vector<std::pair<std::string, std::vector<int>>> shapes;
    shapes.push_back({"conv_in.weight", {c, kStencil * config.input_planes()}});
    shapes.push_back({"conv_in.bias", {c}});
    for (int i = 0; i < config.blocks; ++i) {
        const std::string block = "block" + std::to_string(i);
        shapes.push_back({block + ".conv1.weight", {c, conv}});
        shapes.push_back({block + ".conv1.bias", {c}});
        shapes.push_back({block + ".conv2.weight", {c, conv}});
        shapes.push_back({block + ".conv2.bias", {c}});
    }
    shapes.push_back({"head_policy.weight", {kPolicyChannels, c}});
    shapes.push_back({"head_policy.bias", {kPolicyChannels}});
    shapes.push_back({"head_value.dense1.weight", {h, c}});
    shapes.push_back({"head_value.dense1.bias", {h}});
    shapes.push_back({"head_value.dense2.weight", {p, h}});
    shapes.push_back({"head_value.dense2.bias", {p}});
    if (has_residual_priors(config.planes)) {
        shapes.push_back({kPriorBeta, {1}});
        shapes.push_back({kPriorK, {1}});
    }
    return shapes;
}

std::expected<Parameters, Error> Parameters::from_tensors(const NetworkConfig& config, std::vector<Tensor> tensors) {
    // the ranges of make_config hold for every Parameters, not only the shapes (NetworkConfig is a plain aggregate)
    if (const auto checked = make_config(config.channels, config.blocks, config.players, config.value_hidden, config.planes); !checked) {
        return fail(checked.error().message);
    }
    const auto expected = parameter_shapes(config);
    std::map<std::string, Tensor*> by_name;
    for (Tensor& t : tensors) by_name[t.name] = &t;
    Parameters result;
    result.config_ = config;
    for (const auto& [name, shape] : expected) {
        const auto it = by_name.find(name);
        if (it == by_name.end()) return fail("tensor " + name + " is missing");
        if (it->second->shape != shape) {
            return fail("tensor " + name + " has shape " + show(it->second->shape) + ", expected " + show(shape));
        }
        const auto count = element_count(shape);
        if (!count || static_cast<std::size_t>(*count) != it->second->data.size()) {
            return fail("tensor " + name + " has " + std::to_string(it->second->data.size()) + " elements, its shape needs more or fewer");
        }
        result.index_[name] = result.tensors_.size();
        result.tensors_.push_back(std::move(*it->second));
    }
    std::set<std::string> known;
    for (const auto& [name, shape] : expected) known.insert(name);
    for (const auto& [name, ptr] : by_name) {
        if (!known.contains(name)) return fail("unexpected tensor " + name);
    }
    return result;
}

std::expected<Parameters, Error> Parameters::from_contents(Contents contents) {
    auto config = config_from_metadata(contents.metadata);
    if (!config) return std::unexpected(config.error());
    auto result = from_tensors(*config, std::move(contents.tensors));
    if (!result) return fail(result.error().message + " for " + std::string(plane_set_id(config->planes)) + " network");
    // Keys that are not config keys are kept (a training step, a seed).
    static const std::set<std::string> config_keys = {"format", "format_version", "channels", "blocks", "players", "value_hidden", "input_planes", "planes"};
    for (const auto& [key, value] : contents.metadata) {
        if (!config_keys.contains(key)) result->extra_[key] = value;
    }
    return result;
}

std::expected<Parameters, Error> Parameters::load(const std::filesystem::path& file) {
    auto contents = read_safetensors(file);
    if (!contents) return std::unexpected(contents.error());
    auto result = from_contents(std::move(*contents));
    if (!result) return fail(file.string() + ": " + result.error().message);
    return result;
}

std::span<const float> Parameters::data(std::string_view name) const {
    const auto it = index_.find(name);
    if (it == index_.end()) {
        std::cerr << "Parameters::data: no tensor named " << name << '\n';
        std::abort();
    }
    return tensors_[it->second].data;
}

}  // namespace sh::nn
