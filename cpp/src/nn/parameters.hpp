// The named parameter tensors of one network (Java: Parameters, load only; docs/network.md, "Tensor names").
// Names and shapes follow shapes() exactly, in that order; a Parameters object is always consistent with its config.
#pragma once

#include <expected>
#include <filesystem>
#include <map>
#include <span>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

#include "config.hpp"
#include "safetensors.hpp"

namespace sh::nn {

inline constexpr const char* kPriorBeta = "prior.beta";
inline constexpr const char* kPriorK = "prior.k";

// Names and shapes of all tensors of a config in file order (Parameters.shapes).
std::vector<std::pair<std::string, std::vector<int>>> parameter_shapes(const NetworkConfig& config);

class Parameters {
public:
    // The metadata keys of the file give the config; the tensors must match parameter_shapes exactly.
    static std::expected<Parameters, Error> from_contents(Contents contents);
    static std::expected<Parameters, Error> load(const std::filesystem::path& file);
    // For tests and golden cases: tensors with exactly the names and shapes of the config, in any order.
    static std::expected<Parameters, Error> from_tensors(const NetworkConfig& config, std::vector<Tensor> tensors);

    const NetworkConfig& config() const { return config_; }
    const std::map<std::string, std::string>& extra_metadata() const { return extra_; }
    const std::vector<Tensor>& tensors() const { return tensors_; }  // in file order of parameter_shapes
    // The data of a tensor of this network. The name must be one of parameter_shapes; anything else is a bug (aborts).
    std::span<const float> data(std::string_view name) const;

private:
    Parameters() = default;
    NetworkConfig config_;
    std::vector<Tensor> tensors_;
    std::map<std::string, std::size_t, std::less<>> index_;
    std::map<std::string, std::string> extra_;
};

}  // namespace sh::nn
