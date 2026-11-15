// Network size and plane sets (Java: NetworkConfig, PlaneSet, HexGrid and Encoder constants; docs/network.md, "Layers").
// Errors of the readers are std::expected values, never exceptions.
#pragma once

#include <cstdint>
#include <expected>
#include <map>
#include <string>
#include <string_view>

namespace sh::nn {

struct Error {
    std::string message;
};

inline constexpr int kCells = 121;                       // Geometry.CELLS
inline constexpr int kPadding = kCells;                  // HexGrid.PADDING: the all-zero row of every activation
inline constexpr int kRows = kCells + 1;                 // HexGrid.ROWS
inline constexpr int kDirections = 6;                    // Geometry.DIRECTIONS
inline constexpr int kStencil = 1 + kDirections;         // HexGrid.STENCIL
inline constexpr int kPlainPlanes = 7;                   // Encoder.PLANES
inline constexpr int kClassicPlanes = 13;                // Encoder.CLASSIC_PLANES
inline constexpr int kDistOwn = 7;                       // Encoder.DIST_OWN
inline constexpr int kEval = 12;                         // Encoder.EVAL
inline constexpr int kProgress = 6;                      // Encoder.PROGRESS
inline constexpr int kDistScale = 16;                    // ClassicalPlanes.DIST_SCALE
inline constexpr int kPolicyChannels = 2;                // Encoder.POLICY_CHANNELS
inline constexpr int kFromChannel = 0;                   // Encoder.FROM
inline constexpr int kToChannel = 1;                     // Encoder.TO
inline constexpr int kPolicySize = kCells * kPolicyChannels;  // Encoder.POLICY_SIZE

inline constexpr int kMaxChannels = 1024;
inline constexpr int kMaxBlocks = 64;
inline constexpr int kMaxValueHidden = 1024;
inline constexpr int kValueHidden = 32;                  // NetworkConfig.VALUE_HIDDEN, every preset

inline constexpr std::string_view kFormat = "sternhalma-nn";
inline constexpr int kFormatVersion = 1;
inline constexpr int kFormatVersionPlanes = 2;

enum class PlaneSet : std::uint8_t { Plain, Classic1, Classic1Planes };

std::string_view plane_set_id(PlaneSet set);
int plane_count(PlaneSet set);            // 7 for Plain, 13 for the classical sets
bool has_residual_priors(PlaneSet set);   // only Classic1

struct NetworkConfig {
    int channels = 0;
    int blocks = 0;
    int players = 2;
    int value_hidden = kValueHidden;
    PlaneSet planes = PlaneSet::Plain;

    int input_planes() const { return plane_count(planes); }
    int input_size() const { return kRows * input_planes(); }  // floats of one encoded position
    long parameter_count() const;
    friend bool operator==(const NetworkConfig&, const NetworkConfig&) = default;
};

// NetworkConfig.tiny/small/large(2 players) with the given plane set.
NetworkConfig tiny_config(int players, PlaneSet planes);
NetworkConfig small_config(int players, PlaneSet planes);
NetworkConfig large_config(int players, PlaneSet planes);

// The constructor check of NetworkConfig: ranges of every field.
std::expected<NetworkConfig, Error> make_config(int channels, int blocks, int players, int value_hidden, PlaneSet planes);

// NetworkConfig.fromMetadata: format, format_version, the five sizes, planes; every rule of docs/network.md, "Metadata keys".
std::expected<NetworkConfig, Error> config_from_metadata(const std::map<std::string, std::string>& metadata);

}  // namespace sh::nn
