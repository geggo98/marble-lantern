// Safetensors reader (read only) with every rejection rule of docs/network.md, "Safetensors file":
// header length larger than the file or above 100,000,000 bytes, a header that is not valid UTF-8 or not a JSON object, a
// duplicate tensor or metadata name, dtype other than F32, offsets that do not match the shape, overlapping tensors, gaps,
// bytes after the last tensor, a product of the dimensions above 2^31 - 1 (a dimension 0 gives an empty tensor whatever
// the others are). Tensors come back in the order of their begin offset; NaN bits are kept as they are.
#pragma once

#include <cstddef>
#include <cstdint>
#include <expected>
#include <filesystem>
#include <map>
#include <span>
#include <string>
#include <vector>

#include "config.hpp"

namespace sh::nn {

struct Tensor {
    std::string name;
    std::vector<int> shape;
    std::vector<float> data;
};

struct Contents {
    std::vector<Tensor> tensors;                  // in order of their begin offset
    std::map<std::string, std::string> metadata;  // empty if the file has no __metadata__
};

inline constexpr std::uint64_t kMaxHeaderBytes = 100'000'000;
inline constexpr const char* kMetadataKey = "__metadata__";

// Number of elements: 1 for an empty shape, 0 if any dimension is 0, else the product (values above 2^31 - 1 are returned as
// some value above that limit, not necessarily the exact product). Negative dimensions cannot occur (the reader takes
// unsigned integers); a negative one makes this return an error.
std::expected<std::int64_t, Error> element_count(std::span<const int> shape);

std::expected<Contents, Error> parse_safetensors(std::span<const std::byte> file);
std::expected<Contents, Error> read_safetensors(const std::filesystem::path& file);

}  // namespace sh::nn
