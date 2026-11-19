#include "config.hpp"

#include <algorithm>
#include <cstddef>

namespace sh::nn {
namespace {

std::expected<int, Error> int_value(const std::map<std::string, std::string>& metadata, const std::string& key) {
    const auto it = metadata.find(key);
    if (it == metadata.end()) return std::unexpected(Error{"metadata key " + key + " is missing"});
    const std::string& text = it->second;
    // Only plain ASCII decimal digits, at most nine.
    const bool digits = std::ranges::all_of(text, [](char c) { return c >= '0' && c <= '9'; });
    if (text.empty() || text.size() > 9 || !digits) {
        return std::unexpected(Error{"metadata key " + key + " is not a decimal integer: " + text});
    }
    return std::stoi(text);
}

}  // namespace

std::string_view plane_set_id(PlaneSet set) {
    switch (set) {
        case PlaneSet::Plain: return "plain";
        case PlaneSet::Classic1: return "classic1";
        case PlaneSet::Classic1Planes: return "classic1-planes";
        case PlaneSet::Classic1V: return "classic1v";
    }
    return "?";
}

int plane_count(PlaneSet set) {
    switch (set) {
        case PlaneSet::Plain: return kPlainPlanes;
        case PlaneSet::Classic1V: return kVariantPlanes;
        default: return kClassicPlanes;
    }
}

bool has_residual_priors(PlaneSet set) { return set == PlaneSet::Classic1 || set == PlaneSet::Classic1V; }

long NetworkConfig::parameter_count() const {
    const long c = channels;
    const long h = value_hidden;
    const long conv_in = static_cast<long>(kStencil) * input_planes() * c + c;
    const long block = 2 * (static_cast<long>(kStencil) * c * c + c);
    const long policy = static_cast<long>(kPolicyChannels) * c + kPolicyChannels;
    const long value = (h * c + h) + (static_cast<long>(players) * h + players);
    const long priors = has_residual_priors(planes) ? 2 : 0;
    return conv_in + blocks * block + policy + value + priors;
}

NetworkConfig tiny_config(int players, PlaneSet planes) { return {8, 1, players, kValueHidden, planes}; }
NetworkConfig small_config(int players, PlaneSet planes) { return {32, 4, players, kValueHidden, planes}; }
NetworkConfig large_config(int players, PlaneSet planes) { return {64, 6, players, kValueHidden, planes}; }

std::expected<NetworkConfig, Error> make_config(int channels, int blocks, int players, int value_hidden, PlaneSet planes) {
    if (channels < 1 || channels > kMaxChannels) {
        return std::unexpected(Error{"channels must be in [1, " + std::to_string(kMaxChannels) + "], got " + std::to_string(channels)});
    }
    if (blocks < 0 || blocks > kMaxBlocks) {
        return std::unexpected(Error{"blocks must be in [0, " + std::to_string(kMaxBlocks) + "], got " + std::to_string(blocks)});
    }
    if (players != 2 && players != 3) {
        return std::unexpected(Error{"players must be 2 or 3, got " + std::to_string(players)});
    }
    if (planes == PlaneSet::Classic1V && players != 3) {
        return std::unexpected(Error{"planes classic1v needs players 3 (the value head serves 2 and 3), got " + std::to_string(players)});
    }
    if (value_hidden < 1 || value_hidden > kMaxValueHidden) {
        return std::unexpected(Error{"value_hidden must be in [1, " + std::to_string(kMaxValueHidden) + "], got " + std::to_string(value_hidden)});
    }
    return NetworkConfig{channels, blocks, players, value_hidden, planes};
}

std::expected<NetworkConfig, Error> config_from_metadata(const std::map<std::string, std::string>& metadata) {
    const auto format = metadata.find("format");
    if (format == metadata.end() || format->second != kFormat) {
        return std::unexpected(Error{"unknown format: " + (format == metadata.end() ? std::string("(none)") : format->second)});
    }
    const auto version = int_value(metadata, "format_version");
    if (!version) return std::unexpected(version.error());
    PlaneSet planes = PlaneSet::Plain;
    if (*version == kFormatVersion) {
        if (const auto p = metadata.find("planes"); p != metadata.end()) {
            return std::unexpected(Error{"format_version 1 has no key planes, got " + p->second});
        }
    } else if (*version == kFormatVersionPlanes) {
        const auto p = metadata.find("planes");
        if (p == metadata.end()) return std::unexpected(Error{"metadata key planes is missing"});
        if (p->second == "plain") {
            planes = PlaneSet::Plain;
        } else if (p->second == "classic1") {
            planes = PlaneSet::Classic1;
        } else if (p->second == "classic1-planes") {
            planes = PlaneSet::Classic1Planes;
        } else if (p->second == "classic1v") {
            return std::unexpected(Error{"planes classic1v needs format_version 3, got 2"});
        } else {
            return std::unexpected(Error{"unknown planes: " + p->second + " (expected plain, classic1 or classic1-planes)"});
        }
    } else if (*version == kFormatVersionVariants) {
        const auto p = metadata.find("planes");
        if (p == metadata.end()) return std::unexpected(Error{"metadata key planes is missing"});
        if (p->second != "classic1v") {
            return std::unexpected(Error{"format_version 3 needs planes classic1v, got " + p->second});
        }
        planes = PlaneSet::Classic1V;
    } else {
        return std::unexpected(Error{"unknown format_version: " + metadata.at("format_version")});
    }
    const auto input_planes = int_value(metadata, "input_planes");
    if (!input_planes) return std::unexpected(input_planes.error());
    if (*input_planes != plane_count(planes)) {
        return std::unexpected(Error{"input_planes must be " + std::to_string(plane_count(planes)) + " for planes " +
                                     std::string(plane_set_id(planes)) + ", got " + metadata.at("input_planes")});
    }
    const auto channels = int_value(metadata, "channels");
    if (!channels) return std::unexpected(channels.error());
    const auto blocks = int_value(metadata, "blocks");
    if (!blocks) return std::unexpected(blocks.error());
    const auto players = int_value(metadata, "players");
    if (!players) return std::unexpected(players.error());
    if (planes == PlaneSet::Classic1V && *players != 3) {
        return std::unexpected(Error{"format_version 3 needs players 3, got " + metadata.at("players")});
    }
    const auto hidden = int_value(metadata, "value_hidden");
    if (!hidden) return std::unexpected(hidden.error());
    return make_config(*channels, *blocks, *players, *hidden, planes);
}

}  // namespace sh::nn
