#include "safetensors.hpp"

#include <algorithm>
#include <fstream>
#include <limits>
#include <set>
#include <string_view>

#include "json_min.hpp"
#include "le_bytes.hpp"

namespace sh::nn {
namespace {

constexpr std::size_t kLengthFieldBytes = 8;
constexpr std::int64_t kIntMax = std::numeric_limits<std::int32_t>::max();

struct Entry {
    std::string name;
    std::vector<int> shape;
    std::uint64_t begin = 0;
    std::uint64_t end = 0;
};

std::unexpected<Error> fail(std::string message) { return std::unexpected(Error{std::move(message)}); }

std::string show(std::span<const int> shape) {
    std::string out = "[";
    for (std::size_t i = 0; i < shape.size(); ++i) {
        if (i > 0) out += ", ";
        out += std::to_string(shape[i]);
    }
    return out + "]";
}

std::expected<void, Error> check_header_length(std::uint64_t header_length, std::uint64_t file_size) {
    if (header_length > kMaxHeaderBytes) {
        return fail("header length " + std::to_string(header_length) + " exceeds the limit of " + std::to_string(kMaxHeaderBytes) + " bytes");
    }
    if (header_length > file_size - kLengthFieldBytes) {
        return fail("header length " + std::to_string(header_length) + " is larger than the " +
                    std::to_string(file_size - kLengthFieldBytes) + " bytes after the length field");
    }
    return {};
}

std::expected<std::vector<std::uint64_t>, Error> uint_array(const std::string& name, const char* key, const json::Value& value) {
    if (!value.is_array()) return fail("tensor " + name + ": " + key + " must be an array");
    std::vector<std::uint64_t> out;
    for (const json::Value& item : std::get<json::Array>(value.v)) {
        if (!item.is_uint()) return fail("tensor " + name + ": " + key + " must hold non-negative integers");
        out.push_back(std::get<std::uint64_t>(item.v));
    }
    return out;
}

std::expected<Entry, Error> parse_tensor(const std::string& name, const json::Value& value) {
    if (!value.is_object()) return fail("tensor " + name + ": entry must be a JSON object");
    const std::string* dtype = nullptr;
    std::optional<std::vector<std::uint64_t>> shape;
    std::optional<std::vector<std::uint64_t>> offsets;
    for (const auto& [key, member] : std::get<json::Object>(value.v)) {
        if (key == "dtype") {
            if (dtype != nullptr) return fail("tensor " + name + ": duplicate key dtype");
            if (!member.is_string()) return fail("tensor " + name + ": dtype must be a string");
            dtype = &std::get<std::string>(member.v);
        } else if (key == "shape") {
            if (shape) return fail("tensor " + name + ": duplicate key shape");
            auto a = uint_array(name, "shape", member);
            if (!a) return std::unexpected(a.error());
            shape = std::move(*a);
        } else if (key == "data_offsets") {
            if (offsets) return fail("tensor " + name + ": duplicate key data_offsets");
            auto a = uint_array(name, "data_offsets", member);
            if (!a) return std::unexpected(a.error());
            offsets = std::move(*a);
        } else {
            return fail("tensor " + name + ": unknown key " + key);
        }
    }
    if (dtype == nullptr || !shape || !offsets) return fail("tensor " + name + ": entry needs dtype, shape and data_offsets");
    if (*dtype != "F32") return fail("tensor " + name + ": dtype " + *dtype + " is not supported, only F32");
    if (offsets->size() != 2) return fail("tensor " + name + ": data_offsets must have 2 elements, not " + std::to_string(offsets->size()));
    Entry e;
    e.name = name;
    for (const std::uint64_t d : *shape) {
        if (d > static_cast<std::uint64_t>(kIntMax)) return fail("tensor " + name + ": dimension " + std::to_string(d) + " is too large");
        e.shape.push_back(static_cast<int>(d));
    }
    e.begin = (*offsets)[0];
    e.end = (*offsets)[1];
    return e;
}

bool data_order(const Entry& a, const Entry& b) { return a.begin != b.begin ? a.begin < b.begin : a.end < b.end; }

std::expected<void, Error> validate_layout(const std::vector<Entry>& entries, std::uint64_t data_length) {
    for (const Entry& e : entries) {
        if (e.end < e.begin) {
            return fail("tensor " + e.name + ": data_offsets [" + std::to_string(e.begin) + ", " + std::to_string(e.end) + "] end before they begin");
        }
        const auto count = element_count(e.shape);
        if (!count) return std::unexpected(count.error());
        if (*count > kIntMax) {
            return fail("tensor " + e.name + ": shape " + show(e.shape) + " has more than 2^31 - 1 elements");
        }
        const std::uint64_t bytes = static_cast<std::uint64_t>(*count) * sizeof(float);
        if (e.end - e.begin != bytes) {
            return fail("tensor " + e.name + ": data_offsets [" + std::to_string(e.begin) + ", " + std::to_string(e.end) + "] hold " +
                        std::to_string(e.end - e.begin) + " bytes, shape " + show(e.shape) + " needs " + std::to_string(bytes));
        }
    }
    std::vector<const Entry*> sorted;
    for (const Entry& e : entries) sorted.push_back(&e);
    std::ranges::stable_sort(sorted, [](const Entry* a, const Entry* b) { return data_order(*a, *b); });
    std::uint64_t expected = 0;
    const Entry* previous = nullptr;
    for (const Entry* e : sorted) {
        if (e->begin < expected) {
            return fail("tensor " + e->name + ": data_offsets [" + std::to_string(e->begin) + ", " + std::to_string(e->end) +
                        "] overlap tensor " + previous->name + ", which ends at " + std::to_string(expected));
        }
        if (e->begin > expected) {
            return fail("tensor " + e->name + ": data_offsets begin at " + std::to_string(e->begin) + ", leaving a gap after byte " + std::to_string(expected));
        }
        expected = e->end;
        previous = e;
    }
    if (expected > data_length) {
        return fail("tensor " + previous->name + ": data ends at " + std::to_string(expected) + ", beyond the data section of " +
                    std::to_string(data_length) + " bytes");
    }
    if (expected < data_length) {
        return fail("data section has " + std::to_string(data_length - expected) + " bytes after the last tensor");
    }
    return {};
}

std::expected<void, Error> parse_metadata(const json::Value& value, std::map<std::string, std::string>& metadata) {
    if (!value.is_object()) return fail(std::string(kMetadataKey) + " must be a JSON object");
    for (const auto& [key, member] : std::get<json::Object>(value.v)) {
        if (!member.is_string()) return fail("metadata value for " + key + " must be a string");
        if (!metadata.emplace(key, std::get<std::string>(member.v)).second) return fail("duplicate metadata key " + key);
    }
    return {};
}

}  // namespace

std::expected<std::int64_t, Error> element_count(std::span<const int> shape) {
    bool zero = false;
    for (const int d : shape) {
        if (d < 0) return fail("negative dimension in shape " + show(shape));
        zero = zero || d == 0;
    }
    if (zero) return 0;
    std::int64_t size = 1;
    for (const int d : shape) {
        size *= d;
        if (size > kIntMax) return size;  // cannot match any array; stop before an overflow of the product
    }
    return size;
}

std::expected<Contents, Error> parse_safetensors(std::span<const std::byte> file) {
    const auto length = read_le<std::uint64_t>(file, 0);
    if (!length) return fail("file has " + std::to_string(file.size()) + " bytes, shorter than the 8-byte header length");
    if (auto ok = check_header_length(*length, file.size()); !ok) return std::unexpected(ok.error());
    if (file.size() > static_cast<std::size_t>(kIntMax) - 8) {
        return fail("file has " + std::to_string(file.size()) + " bytes, more than this reader supports");
    }
    const std::size_t header_length = static_cast<std::size_t>(*length);
    const std::size_t data_start = kLengthFieldBytes + header_length;
    const std::span<const std::byte> header_bytes = file.subspan(kLengthFieldBytes, header_length);
    std::string header_text;
    header_text.reserve(header_bytes.size());
    for (const std::byte b : header_bytes) header_text += static_cast<char>(b);

    auto root = json::parse(header_text);
    if (!root) return fail("malformed header: " + root.error().message);
    if (!root->is_object()) return fail("malformed header: header must be a JSON object");

    std::vector<Entry> entries;
    std::map<std::string, std::string> metadata;
    std::set<std::string> names;
    bool saw_metadata = false;
    for (const auto& [key, member] : std::get<json::Object>(root->v)) {
        if (key == kMetadataKey) {
            if (saw_metadata) return fail(std::string("malformed header: duplicate ") + kMetadataKey);
            saw_metadata = true;
            if (auto ok = parse_metadata(member, metadata); !ok) return std::unexpected(ok.error());
        } else {
            if (!names.insert(key).second) return fail("malformed header: duplicate tensor name " + key);
            auto entry = parse_tensor(key, member);
            if (!entry) return std::unexpected(entry.error());
            entries.push_back(std::move(*entry));
        }
    }
    const std::uint64_t data_length = file.size() - data_start;
    if (auto ok = validate_layout(entries, data_length); !ok) return std::unexpected(ok.error());

    std::ranges::stable_sort(entries, data_order);
    Contents contents;
    contents.metadata = std::move(metadata);
    const std::span<const std::byte> data = file.subspan(data_start);
    for (Entry& e : entries) {
        Tensor t;
        const std::size_t count = static_cast<std::size_t>((e.end - e.begin) / sizeof(float));
        t.data.reserve(count);
        for (std::size_t i = 0; i < count; ++i) {
            const auto v = read_le<float>(data, static_cast<std::size_t>(e.begin) + i * sizeof(float));
            if (!v) return fail("internal error: tensor " + e.name + " reads past the data section");
            t.data.push_back(*v);
        }
        t.name = std::move(e.name);
        t.shape = std::move(e.shape);
        contents.tensors.push_back(std::move(t));
    }
    return contents;
}

std::expected<Contents, Error> read_safetensors(const std::filesystem::path& path) {
    std::error_code ec;
    const std::uintmax_t size = std::filesystem::file_size(path, ec);
    if (ec) return fail(path.string() + ": cannot read the file size: " + ec.message());
    if (size > static_cast<std::uintmax_t>(kIntMax) - 8) {
        return fail(path.string() + ": file has " + std::to_string(size) + " bytes, more than this reader supports");
    }
    std::ifstream in(path, std::ios::binary);
    if (!in) return fail(path.string() + ": cannot open the file");
    std::string text(static_cast<std::size_t>(size), '\0');
    in.read(text.data(), static_cast<std::streamsize>(text.size()));
    if (static_cast<std::size_t>(in.gcount()) != text.size()) return fail(path.string() + ": file shrank while it was read");
    auto contents = parse_safetensors(std::as_bytes(std::span<const char>(text)));
    if (!contents) return fail(path.string() + ": " + contents.error().message);
    return contents;
}

}  // namespace sh::nn
