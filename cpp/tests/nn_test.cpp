// Tests of src/nn (WP9): json_min, the safetensors reader and its rejection rules, NetworkConfig and Parameters, the gather
// table, the scalar reference against the Java golden outputs (logits bit-identical, values within 1 ulp), the hand-checkable
// cases of nn-cases.safetensors, the priors, the buckets and the comparison helper. Malformed files are built in the test.
#include <algorithm>
#include <array>
#include <bit>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <limits>
#include <map>
#include <string>
#include <vector>

#include "alloc_counter.hpp"
#include "buckets.hpp"
#include "check.hpp"
#include "config.hpp"
#include "gather.hpp"
#include "golden.hpp"
#include "golden_io.hpp"
#include "golden_test_support.hpp"
#include "json_min.hpp"
#include "parameters.hpp"
#include "priors.hpp"
#include "reference_net.hpp"
#include "safetensors.hpp"
#include "simd_net.hpp"
#include "splittable_random.hpp"

using namespace sh;
using namespace sh::nn;

namespace {

// ---- helpers: building safetensors files by hand ----

void put_le(std::vector<std::byte>& out, std::uint64_t v, int bytes) {
    for (int i = 0; i < bytes; ++i) out.push_back(static_cast<std::byte>((v >> (8 * i)) & 0xFF));
}

// A file with the given header text (padded with spaces to a multiple of 8), `floats` data values 0, 0.5, 1, ...
// and, if declared_length is set, a different length in the length field.
std::vector<std::byte> make_file(std::string header, std::size_t floats, std::optional<std::uint64_t> declared_length = std::nullopt) {
    while (header.size() % 8 != 0) header += ' ';
    std::vector<std::byte> out;
    put_le(out, declared_length.value_or(header.size()), 8);
    for (const char c : header) out.push_back(static_cast<std::byte>(c));
    for (std::size_t i = 0; i < floats; ++i) put_le(out, std::bit_cast<std::uint32_t>(static_cast<float>(i) * 0.5f), 4);
    return out;
}

std::string tensor_json(const std::string& name, const std::string& shape, std::uint64_t begin, std::uint64_t end, const std::string& dtype = "F32") {
    return "\"" + name + "\":{\"dtype\":\"" + dtype + "\",\"shape\":" + shape + ",\"data_offsets\":[" + std::to_string(begin) + "," + std::to_string(end) + "]}";
}

bool rejected(const std::expected<Contents, Error>& r, const std::string& part) {
    if (r) {
        std::cerr << "  expected a rejection mentioning '" << part << "', but the file was accepted\n";
        return false;
    }
    if (r.error().message.find(part) == std::string::npos) {
        std::cerr << "  expected '" << part << "' in: " << r.error().message << '\n';
        return false;
    }
    return true;
}

std::expected<Contents, Error> parse_text(const std::string& header, std::size_t floats) { return parse_safetensors(make_file(header, floats)); }

// ---- helpers: a TINY classic1 network with deterministic weights ----

std::vector<Tensor> random_tensors(const NetworkConfig& config, std::uint64_t seed) {
    SplittableRandom rng(seed);
    std::vector<Tensor> tensors;
    for (const auto& [name, shape] : parameter_shapes(config)) {
        Tensor t;
        t.name = name;
        t.shape = shape;
        t.data.resize(static_cast<std::size_t>(*element_count(shape)));
        for (float& v : t.data) v = static_cast<float>(static_cast<double>(rng.next_long() >> 40) / static_cast<double>(1 << 24)) - 0.5f;
        tensors.push_back(std::move(t));
    }
    return tensors;
}

std::map<std::string, std::string> metadata_of(const NetworkConfig& c) {
    std::map<std::string, std::string> m;
    m["format"] = "sternhalma-nn";
    m["format_version"] = c.planes == PlaneSet::Plain ? "1" : "2";
    m["channels"] = std::to_string(c.channels);
    m["blocks"] = std::to_string(c.blocks);
    m["players"] = std::to_string(c.players);
    m["value_hidden"] = std::to_string(c.value_hidden);
    m["input_planes"] = std::to_string(c.input_planes());
    if (c.planes != PlaneSet::Plain) m["planes"] = std::string(plane_set_id(c.planes));
    return m;
}

// Serialises tensors in the given order, contiguously, as the Java writer does.
std::vector<std::byte> to_file(const std::vector<Tensor>& tensors, const std::map<std::string, std::string>& metadata) {
    std::string header = "{";
    if (!metadata.empty()) {
        header += "\"__metadata__\":{";
        bool first = true;
        for (const auto& [k, v] : metadata) {
            header += (first ? "" : ",") + ("\"" + k + "\":\"" + v + "\"");
            first = false;
        }
        header += "}";
    }
    std::uint64_t offset = 0;
    for (const Tensor& t : tensors) {
        if (header.size() > 1) header += ",";
        std::string shape = "[";
        for (std::size_t i = 0; i < t.shape.size(); ++i) {
            if (i > 0) shape += ",";   // not (i > 0 ? "," : "") + std::to_string(..): GCC 12 reports -Wrestrict there (false positive)
            shape += std::to_string(t.shape[i]);
        }
        shape += "]";
        const std::uint64_t end = offset + t.data.size() * 4;
        header += tensor_json(t.name, shape, offset, end);
        offset = end;
    }
    header += "}";
    while (header.size() % 8 != 0) header += ' ';
    std::vector<std::byte> out;
    put_le(out, header.size(), 8);
    for (const char c : header) out.push_back(static_cast<std::byte>(c));
    for (const Tensor& t : tensors) {
        for (const float v : t.data) put_le(out, std::bit_cast<std::uint32_t>(v), 4);
    }
    return out;
}

const NetworkConfig kTiny = tiny_config(2, PlaneSet::Classic1);

std::vector<float> random_input(const NetworkConfig& c, std::uint64_t seed) {
    SplittableRandom rng(seed);
    std::vector<float> in(static_cast<std::size_t>(c.input_size()));
    const auto planes = static_cast<std::size_t>(c.input_planes());
    for (std::size_t i = 0; i < in.size(); ++i) {
        const bool padding = i >= static_cast<std::size_t>(kCells) * planes;
        in[i] = padding ? 0.0f : static_cast<float>(static_cast<double>(rng.next_long() >> 40) / static_cast<double>(1 << 24));
    }
    return in;
}

}  // namespace

// ---------------------------------------------------------------- json_min

SH_TEST(json_accepts_what_the_header_needs) {
    auto v = json::parse(R"( {"a": [1, 22, 0], "b": {"c": "x\n\u00e9\ud83d\ude00\\\"\/"}, "e": {}, "f": []} )");
    CHECK(v.has_value());
    if (!v) return;
    const auto& obj = std::get<json::Object>(v->v);
    CHECK_EQ(obj.size(), std::size_t{4});
    CHECK_EQ(std::get<json::Array>(obj[0].second.v).size(), std::size_t{3});
    CHECK_EQ(std::get<std::uint64_t>(std::get<json::Array>(obj[0].second.v)[1].v), std::uint64_t{22});
    const auto& s = std::get<std::string>(std::get<json::Object>(obj[1].second.v)[0].second.v);
    CHECK_EQ(s, std::string("x\n\xC3\xA9\xF0\x9F\x98\x80\\\"/"));
    CHECK_EQ(std::get<std::uint64_t>(json::parse("9223372036854775807")->v), std::uint64_t{9223372036854775807ULL});
}

SH_TEST(json_rejects_everything_else) {
    const std::vector<std::string> bad = {
        "", "{", "}", "[1,]", "{\"a\":1,}", "{\"a\"}", "{a:1}", "true", "false", "null", "-1", "1.5", "1e3", "01", "[01]",
        "9223372036854775808", "99999999999999999999", "\"abc", "\"a\\x\"", "\"\\u12\"", "\"\\ud800\"", "\"\\udc00\"", "\"\\ud800\\u0041\"",
        "\"a\nb\"", "{} x", "{}{}", "[[[[[[[[[[[[[[[[[[[1]]]]]]]]]]]]]]]]]]]", "\"\xC0\x80\"", "\"\xED\xA0\x80\"", "\"\xF4\x90\x80\x80\"", "\"\xFF\"", "\"\xE2\x82\"",
    };
    for (const std::string& text : bad) {
        const auto v = json::parse(text);
        if (v) std::cerr << "  accepted: " << text << '\n';
        CHECK(!v.has_value());
    }
    CHECK(json::is_valid_utf8("\xE2\x82\xAC \xF0\x9F\x98\x80"));
    CHECK(!json::is_valid_utf8("\xC3"));
}

// ---------------------------------------------------------------- safetensors

SH_TEST(safetensors_reads_a_valid_file) {
    const std::string h = "{\"__metadata__\":{\"k\":\"v\"}," + tensor_json("b", "[3]", 16, 28) + "," + tensor_json("a", "[2,2]", 0, 16) + "," + tensor_json("s", "[]", 28, 32) + "}";
    auto c = parse_text(h, 8);
    CHECK(c.has_value());
    if (!c) return;
    CHECK_EQ(c->metadata.at("k"), std::string("v"));
    CHECK_EQ(c->tensors.size(), std::size_t{3});
    CHECK_EQ(c->tensors[0].name, std::string("a"));  // in order of the begin offset
    CHECK_EQ(c->tensors[1].name, std::string("b"));
    CHECK_EQ(c->tensors[2].name, std::string("s"));
    CHECK_EQ(c->tensors[2].shape.size(), std::size_t{0});
    CHECK_EQ(c->tensors[1].data[2], 3.0f);  // data[4 + 2] = 6 * 0.5
    CHECK_EQ(c->tensors[2].data[0], 3.5f);
    CHECK_EQ(parse_text("{}", 0)->tensors.size(), std::size_t{0});
}

SH_TEST(safetensors_keeps_nan_bits) {
    std::vector<std::byte> f = make_file("{" + tensor_json("n", "[1]", 0, 4) + "}", 0);
    put_le(f, 0x7fc12345u, 4);
    auto c = parse_safetensors(f);
    CHECK(c.has_value());
    if (c) CHECK_EQ(std::bit_cast<std::uint32_t>(c->tensors[0].data[0]), std::uint32_t{0x7fc12345u});
}

SH_TEST(safetensors_empty_tensors_and_shape_limits) {
    // A dimension of 0 gives an empty tensor, whatever the other dimensions are.
    CHECK(parse_text("{" + tensor_json("z", "[2147483647,2,0]", 0, 0) + "}", 0).has_value());
    CHECK(parse_text("{" + tensor_json("z", "[0]", 0, 0) + "}", 0).has_value());
    // Without a 0, a product above 2^31 - 1 is rejected.
    CHECK(rejected(parse_text("{" + tensor_json("big", "[65536,65536]", 0, 17179869184ULL) + "}", 0), "more than"));
    CHECK(rejected(parse_text("{" + tensor_json("big", "[2147483648]", 0, 4) + "}", 1), "too large"));
    CHECK(parse_text("{" + tensor_json("ok", "[2147483647]", 0, 8589934588ULL) + "}", 0).has_value() == false);  // sizes do not fit the file, but not a crash
}

SH_TEST(safetensors_rejection_rules) {
    // header length larger than the file; above the limit; file shorter than the length field
    CHECK(rejected(parse_safetensors(make_file("{}", 0, 1000)), "larger than"));
    CHECK(rejected(parse_safetensors(make_file("{}", 0, 1ULL << 40)), "exceeds the limit"));
    CHECK(rejected(parse_safetensors(make_file("{}", 0, 0xFFFFFFFFFFFFFFFFULL)), "exceeds the limit"));
    CHECK(rejected(parse_safetensors(std::vector<std::byte>(7)), "shorter than"));
    CHECK(rejected(parse_safetensors({}), "shorter than"));
    // a header that is not a JSON object
    CHECK(rejected(parse_text("[1]", 0), "object"));
    CHECK(rejected(parse_text("\"x\"", 0), "object"));
    CHECK(rejected(parse_text("5", 0), "object"));
    CHECK(rejected(parse_text("{", 0), "malformed"));
    // a header that is not valid UTF-8
    {
        std::vector<std::byte> f = make_file("{\"a\":{}}", 0);
        f[8 + 2] = static_cast<std::byte>(0xFF);
        CHECK(rejected(parse_safetensors(f), "UTF-8"));
    }
    // duplicate tensor name, duplicate metadata, metadata that is not a string map
    CHECK(rejected(parse_text("{" + tensor_json("a", "[1]", 0, 4) + "," + tensor_json("a", "[1]", 4, 8) + "}", 2), "duplicate tensor name"));
    CHECK(rejected(parse_text("{\"__metadata__\":{\"k\":\"1\",\"k\":\"2\"}}", 0), "duplicate metadata key"));
    CHECK(rejected(parse_text("{\"__metadata__\":{},\"__metadata__\":{}}", 0), "duplicate __metadata__"));
    CHECK(rejected(parse_text("{\"__metadata__\":{\"k\":1}}", 0), "must be a string"));
    CHECK(rejected(parse_text("{\"__metadata__\":[]}", 0), "must be a JSON object"));
    // tensor entries
    CHECK(rejected(parse_text("{" + tensor_json("a", "[1]", 0, 4, "F16") + "}", 1), "dtype F16"));
    CHECK(rejected(parse_text("{" + tensor_json("a", "[1]", 0, 4, "F64") + "}", 2), "dtype F64"));
    CHECK(rejected(parse_text("{\"a\":{\"dtype\":\"F32\",\"shape\":[1]}}", 1), "needs dtype, shape and data_offsets"));
    CHECK(rejected(parse_text("{\"a\":{\"dtype\":\"F32\",\"shape\":[1],\"data_offsets\":[0,4],\"x\":1}}", 1), "unknown key"));
    CHECK(rejected(parse_text("{\"a\":{\"dtype\":\"F32\",\"dtype\":\"F32\",\"shape\":[1],\"data_offsets\":[0,4]}}", 1), "duplicate key dtype"));
    CHECK(rejected(parse_text("{\"a\":{\"dtype\":\"F32\",\"shape\":[1],\"data_offsets\":[0,4,8]}}", 2), "2 elements"));
    CHECK(rejected(parse_text("{\"a\":{\"dtype\":\"F32\",\"shape\":[-1],\"data_offsets\":[0,4]}}", 1), "malformed"));
    CHECK(rejected(parse_text("{\"a\":{\"dtype\":\"F32\",\"shape\":[1.5],\"data_offsets\":[0,4]}}", 1), "malformed"));
    CHECK(rejected(parse_text("{\"a\":{\"dtype\":\"F32\",\"shape\":\"x\",\"data_offsets\":[0,4]}}", 1), "must be an array"));
    CHECK(rejected(parse_text("{\"a\":[]}", 0), "must be a JSON object"));
    // offsets that do not match the shape; end before begin
    CHECK(rejected(parse_text("{" + tensor_json("a", "[2,2]", 0, 12) + "}", 3), "needs 16"));
    CHECK(rejected(parse_text("{" + tensor_json("a", "[1]", 8, 4) + "}", 3), "end before"));
    // overlapping tensors, a gap, bytes after the last tensor, data that is too short
    CHECK(rejected(parse_text("{" + tensor_json("a", "[2]", 0, 8) + "," + tensor_json("b", "[2]", 4, 12) + "}", 3), "overlap"));
    CHECK(rejected(parse_text("{" + tensor_json("a", "[1]", 0, 4) + "," + tensor_json("b", "[1]", 8, 12) + "}", 3), "gap"));
    CHECK(rejected(parse_text("{" + tensor_json("a", "[1]", 4, 8) + "}", 2), "gap"));  // the first tensor must start at 0
    CHECK(rejected(parse_text("{" + tensor_json("a", "[1]", 0, 4) + "}", 2), "after the last tensor"));
    CHECK(rejected(parse_text("{}", 1), "after the last tensor"));
    CHECK(rejected(parse_text("{" + tensor_json("a", "[2]", 0, 8) + "}", 1), "beyond the data section"));
}

SH_TEST(safetensors_every_truncation_is_rejected_and_header_flips_are_safe) {
    auto tensors = random_tensors(kTiny, 7);
    const std::vector<std::byte> file = to_file(tensors, metadata_of(kTiny));
    CHECK(parse_safetensors(file).has_value());
    // every strictly shorter prefix fails (sampled in the data section, every length in the header)
    std::uint64_t header_length = 0;
    for (int i = 0; i < 8; ++i) header_length |= static_cast<std::uint64_t>(file[static_cast<std::size_t>(i)]) << (8 * i);
    int checked = 0;
    for (std::size_t len = 0; len < file.size(); len += (len < header_length + 16 ? 1 : 97)) {
        CHECK(!parse_safetensors(std::span<const std::byte>(file).first(len)).has_value());
        ++checked;
    }
    CHECK(checked > 100);
    // flipping any bit of the header never crashes (ASan and the hardened library catch what the checks miss)
    for (std::size_t i = 8; i < 8 + header_length; ++i) {
        for (const unsigned mask : {1u, 0x20u, 0x80u}) {
            std::vector<std::byte> copy = file;
            copy[i] = static_cast<std::byte>(static_cast<unsigned>(copy[i]) ^ mask);
            (void)parse_safetensors(copy);
        }
    }
}

// ---------------------------------------------------------------- config and parameters

SH_TEST(config_parameter_counts) {
    // docs/network.md: TINY 2,020 / 2,022, SMALL 61,732 / 61,734 (classic1-planes / classic1, two players)
    CHECK_EQ(tiny_config(2, PlaneSet::Classic1Planes).parameter_count(), 2020L);
    CHECK_EQ(tiny_config(2, PlaneSet::Classic1).parameter_count(), 2022L);
    CHECK_EQ(small_config(2, PlaneSet::Classic1Planes).parameter_count(), 61732L);
    CHECK_EQ(small_config(2, PlaneSet::Classic1).parameter_count(), 61734L);
    long sum = 0;
    for (const auto& [name, shape] : parameter_shapes(small_config(2, PlaneSet::Classic1))) sum += *element_count(shape);
    CHECK_EQ(sum, 61734L);
    CHECK_EQ(parameter_shapes(large_config(3, PlaneSet::Plain)).size(), std::size_t{2 + 4 * 6 + 6});
    CHECK_EQ(parameter_shapes(NetworkConfig{1024, 64, 2, 1024, PlaneSet::Classic1}).size(), std::size_t{2 + 4 * 64 + 6 + 2});
}

SH_TEST(config_limits) {
    CHECK(make_config(64, 6, 2, 32, PlaneSet::Classic1).has_value());
    CHECK(!make_config(0, 6, 2, 32, PlaneSet::Plain));
    CHECK(!make_config(1025, 6, 2, 32, PlaneSet::Plain));
    CHECK(!make_config(8, -1, 2, 32, PlaneSet::Plain));
    CHECK(!make_config(8, 65, 2, 32, PlaneSet::Plain));
    CHECK(!make_config(8, 1, 1, 32, PlaneSet::Plain));
    CHECK(!make_config(8, 1, 4, 32, PlaneSet::Plain));
    CHECK(!make_config(8, 1, 2, 0, PlaneSet::Plain));
    CHECK(!make_config(8, 1, 2, 1025, PlaneSet::Plain));
    CHECK(make_config(1024, 64, 3, 1024, PlaneSet::Plain).has_value());
}

SH_TEST(config_metadata_rules) {
    auto good = metadata_of(kTiny);
    CHECK(config_from_metadata(good).has_value() && *config_from_metadata(good) == kTiny);
    auto plain = metadata_of(tiny_config(3, PlaneSet::Plain));
    CHECK(config_from_metadata(plain).has_value() && config_from_metadata(plain)->planes == PlaneSet::Plain);
    const auto without = [&](const std::string& key) { auto m = good; m.erase(key); return m; };
    const auto with = [&](const std::string& key, const std::string& v) { auto m = good; m[key] = v; return m; };
    for (const char* key : {"format", "format_version", "channels", "blocks", "players", "value_hidden", "input_planes", "planes"}) {
        CHECK(!config_from_metadata(without(key)).has_value());
    }
    CHECK(!config_from_metadata(with("format", "other")));
    CHECK(!config_from_metadata(with("format_version", "3")));
    CHECK(!config_from_metadata(with("format_version", "0")));
    CHECK(!config_from_metadata(with("planes", "classic2")));
    CHECK(!config_from_metadata(with("input_planes", "7")));        // 13 belongs to classic1
    CHECK(!config_from_metadata(with("channels", "+8")));           // only plain decimal digits
    CHECK(!config_from_metadata(with("channels", "8.0")));
    CHECK(!config_from_metadata(with("channels", "")));
    CHECK(!config_from_metadata(with("channels", "1234567890")));   // longer than nine digits
    CHECK(!config_from_metadata(with("blocks", "100000000")));      // out of range, rejected without allocating
    CHECK(!config_from_metadata(with("channels", "\xD9\xA8")));     // a non-ASCII digit
    CHECK(!config_from_metadata(with("players", "4")));
    auto v1_with_planes = metadata_of(tiny_config(2, PlaneSet::Plain));
    v1_with_planes["planes"] = "plain";
    CHECK(!config_from_metadata(v1_with_planes));                   // version 1 has no key planes
    auto v1_wrong_width = metadata_of(tiny_config(2, PlaneSet::Plain));
    v1_wrong_width["input_planes"] = "13";
    CHECK(!config_from_metadata(v1_wrong_width));
}

SH_TEST(parameters_load_and_mismatches) {
    auto tensors = random_tensors(kTiny, 11);
    // load through the file, with extra metadata that is kept
    auto meta = metadata_of(kTiny);
    meta["training_step"] = "42";
    auto contents = parse_safetensors(to_file(tensors, meta));
    CHECK(contents.has_value());
    if (!contents) return;
    auto p = Parameters::from_contents(std::move(*contents));
    CHECK(p.has_value());
    if (!p) return;
    CHECK(p->config() == kTiny);
    CHECK_EQ(p->extra_metadata().size(), std::size_t{1});
    CHECK_EQ(p->extra_metadata().at("training_step"), std::string("42"));
    CHECK_EQ(p->data("conv_in.weight").size(), std::size_t{8 * 7 * 13});
    CHECK_EQ(p->data("conv_in.weight")[5], tensors[0].data[5]);
    CHECK_EQ(p->data(kPriorBeta).size(), std::size_t{1});
    // a missing tensor, a wrong shape, an unexpected tensor, tensors in another order (accepted)
    {
        auto t = tensors;
        t.erase(t.begin() + 3);
        CHECK(!Parameters::from_tensors(kTiny, t).has_value());
    }
    {
        auto t = tensors;
        t[1].shape = {8, 1};
        CHECK(!Parameters::from_tensors(kTiny, t).has_value());
    }
    {
        auto t = tensors;
        t.push_back(Tensor{"extra", {1}, {0.0f}});
        CHECK(!Parameters::from_tensors(kTiny, t).has_value());
    }
    {
        auto t = tensors;
        std::ranges::reverse(t);
        CHECK(Parameters::from_tensors(kTiny, t).has_value());
    }
    {
        // a classic1 network needs prior.beta and prior.k, a plain one must not have them
        auto contents2 = parse_safetensors(to_file(tensors, metadata_of(tiny_config(2, PlaneSet::Classic1Planes))));
        CHECK(contents2.has_value() && !Parameters::from_contents(std::move(*contents2)).has_value());
    }
    // a file without tensors but with the metadata of a huge network: rejected by the limits, nothing is allocated
    auto huge = metadata_of(kTiny);
    huge["blocks"] = "100000000";
    auto contents3 = parse_safetensors(to_file({}, huge));
    CHECK(contents3.has_value() && !Parameters::from_contents(std::move(*contents3)).has_value());
    CHECK(!Parameters::load("/nonexistent/network.safetensors").has_value());
}

// ---------------------------------------------------------------- gather table

SH_TEST(gather_table_counts_and_identities) {
    const GatherTable& table = gather_table();
    int self = 0;
    int pad = 0;
    int neigh = 0;
    std::map<int, int> by_degree;
    for (int c = 0; c < kCells; ++c) {
        CHECK_EQ(*gather(c, 0), c);
        self += 1;
        int degree = 0;
        for (int k = 1; k < kStencil; ++k) {
            const int g = *gather(c, k);
            CHECK_EQ(g, table[static_cast<std::size_t>(c * kStencil + k)]);
            if (g == kPadding) {
                ++pad;
            } else {
                ++neigh;
                ++degree;
                // adjacency is symmetric: the neighbour in direction d has this cell in direction d ^ 1
                CHECK_EQ(*neighbor(g, (k - 1) ^ 1), c);
            }
        }
        ++by_degree[degree];
    }
    CHECK_EQ(self, 121);
    CHECK_EQ(neigh, 624);  // the star has 312 adjacent pairs, each twice
    CHECK_EQ(pad, 102);
    CHECK_EQ(by_degree[6], 73);
    CHECK_EQ(by_degree[5], 6);
    CHECK_EQ(by_degree[4], 36);
    CHECK_EQ(by_degree[2], 6);
    CHECK_EQ(by_degree.size(), std::size_t{4});
    CHECK(!gather(-1, 0).has_value());
    CHECK(!gather(121, 0).has_value());
    CHECK(!gather(0, 7).has_value());   // not (cell 1, k 0)
    CHECK(!gather(0, -1).has_value());
    // cell 0 is the top tip (q, r) = (4, -8); row r = -7 holds cells 1 = (3, -7) and 2 = (4, -7)
    CHECK_EQ(*neighbor(0, 0), -1);
    CHECK_EQ(*neighbor(0, 2), 2);
    CHECK_EQ(*neighbor(0, 5), 1);
}

// ---------------------------------------------------------------- priors

SH_TEST(priors_by_hand) {
    CHECK_EQ(value_sign(0), 1);
    CHECK_EQ(value_sign(1), -1);
    CHECK_EQ(value_sign(2), 0);
    std::vector<float> input(static_cast<std::size_t>(kRows) * kClassicPlanes, 0.0f);
    for (int c = 0; c < kCells; ++c) input[static_cast<std::size_t>(c * kClassicPlanes + kDistOwn)] = static_cast<float>(c) / 16.0f;
    input[static_cast<std::size_t>(kEval)] = 0.75f;
    std::vector<float> policy(kPolicySize, 1.0f);
    add_policy_prior(2.0f, input, kClassicPlanes, policy);
    for (int c = 0; c < kCells; ++c) {
        CHECK_EQ(policy[static_cast<std::size_t>(c * 2)], 1.0f + 2.0f * static_cast<float>(c));
        CHECK_EQ(policy[static_cast<std::size_t>(c * 2 + 1)], 1.0f - 2.0f * static_cast<float>(c));
    }
    std::vector<float> values = {0.25f, 0.5f, 1.0f};
    add_value_prior(0.5f, input, kClassicPlanes, values);
    CHECK_EQ(values[0], 0.25f + 0.375f);
    CHECK_EQ(values[1], 0.5f - 0.375f);
    CHECK_EQ(values[2], 1.0f);
    // the host step: raw outputs of a device pass plus priors plus tanh
    std::vector<float> two_inputs = input;
    two_inputs.insert(two_inputs.end(), input.begin(), input.end());
    std::vector<float> logits(2 * kPolicySize, 0.0f);
    std::vector<float> raw = {0.1f, -0.2f, 0.3f, 0.4f};
    apply_host_priors(1.0f, 0.5f, two_inputs, kClassicPlanes, 2, 2, logits, raw);
    CHECK_EQ(logits[2], 16.0f * (1.0f / 16.0f));
    CHECK_EQ(raw[0], static_cast<float>(std::tanh(static_cast<double>(0.1f + 0.5f * 0.75f))));
    CHECK_EQ(raw[1], static_cast<float>(std::tanh(static_cast<double>(-0.2f + 0.5f * -0.75f))));
    CHECK_EQ(raw[3], static_cast<float>(std::tanh(static_cast<double>(0.4f + 0.5f * -0.75f))));
}

// ---------------------------------------------------------------- reference ops and network

SH_TEST(hex_conv_by_hand) {
    // one input channel, one output channel, weight = 1 for every stencil entry, bias 0.5: the sum of the cell and its neighbours
    std::vector<float> in(static_cast<std::size_t>(kRows), 0.0f);
    for (int c = 0; c < kCells; ++c) in[static_cast<std::size_t>(c)] = static_cast<float>(c + 1);
    const std::vector<float> weight(7, 1.0f);
    const std::vector<float> bias = {0.5f};
    std::vector<float> out(static_cast<std::size_t>(kRows), std::nanf(""));
    CHECK(hex_conv(in, 1, weight, bias, 1, out).has_value());
    for (int c = 0; c < kCells; ++c) {
        float expected = 0.5f;
        for (int k = 0; k < kStencil; ++k) {
            const int row = *gather(c, k);
            expected += in[static_cast<std::size_t>(row)];
        }
        CHECK_EQ(out[static_cast<std::size_t>(c)], expected);
    }
    CHECK_EQ(out[static_cast<std::size_t>(kPadding)], 0.0f);
    // dirty padding row, wrong sizes
    in[static_cast<std::size_t>(kPadding)] = 0.5f;
    CHECK(!hex_conv(in, 1, weight, bias, 1, out).has_value());
    in[static_cast<std::size_t>(kPadding)] = std::nanf("");
    CHECK(!hex_conv(in, 1, weight, bias, 1, out).has_value());
    in[static_cast<std::size_t>(kPadding)] = -0.0f;
    CHECK(hex_conv(in, 1, weight, bias, 1, out).has_value());
    CHECK(!hex_conv(std::span<const float>(in).first(10), 1, weight, bias, 1, out).has_value());
    std::vector<float> small_out(5);
    CHECK(!hex_conv(in, 1, weight, bias, 1, small_out).has_value());
}

SH_TEST(reference_net_on_a_synthetic_network) {
    auto p = Parameters::from_tensors(kTiny, random_tensors(kTiny, 3));
    CHECK(p.has_value());
    if (!p) return;
    const ReferenceNet net(*p);
    const int batch = 5;
    std::vector<float> input;
    for (int b = 0; b < batch; ++b) {
        const auto one = random_input(kTiny, 100 + static_cast<std::uint64_t>(b));
        input.insert(input.end(), one.begin(), one.end());
    }
    std::vector<float> logits(static_cast<std::size_t>(batch) * kPolicySize);
    std::vector<float> values(static_cast<std::size_t>(batch) * 2);
    CHECK(net.forward(input, batch, logits, values).has_value());
    // batch results equal single results, bit for bit
    for (int b = 0; b < batch; ++b) {
        std::vector<float> l1(kPolicySize);
        std::vector<float> v1(2);
        const auto in1 = std::span<const float>(input).subspan(static_cast<std::size_t>(b) * static_cast<std::size_t>(kTiny.input_size()), static_cast<std::size_t>(kTiny.input_size()));
        CHECK(net.forward(in1, 1, l1, v1).has_value());
        for (int i = 0; i < kPolicySize; ++i) CHECK_EQ(std::bit_cast<std::uint32_t>(l1[static_cast<std::size_t>(i)]), std::bit_cast<std::uint32_t>(logits[static_cast<std::size_t>(b * kPolicySize + i)]));
        for (int i = 0; i < 2; ++i) CHECK_EQ(std::bit_cast<std::uint32_t>(v1[static_cast<std::size_t>(i)]), std::bit_cast<std::uint32_t>(values[static_cast<std::size_t>(b * 2 + i)]));
    }
    for (const float v : values) CHECK(v >= -1.0f && v <= 1.0f);
    // errors: batch 0, short spans, a dirty padding row
    std::vector<float> l1(kPolicySize);
    std::vector<float> v1(2);
    CHECK(!net.forward(input, 0, l1, v1).has_value());
    CHECK(!net.forward(std::span<const float>(input).first(100), 1, l1, v1).has_value());
    CHECK(!net.forward(input, 1, std::span<float>(l1).first(10), v1).has_value());
    CHECK(!net.forward(input, 1, l1, std::span<float>(v1).first(1)).has_value());
    auto dirty = std::vector<float>(input.begin(), input.begin() + kTiny.input_size());
    dirty[static_cast<std::size_t>(kPadding * kClassicPlanes + kProgress)] = 0.5f;
    CHECK(!net.forward(dirty, 1, l1, v1).has_value());
}

SH_TEST(reference_net_zero_network_is_the_classical_prior) {
    // docs/network.md: a network with zero weights and biases gives the prior alone: policy = +-beta*gain, value = tanh(k*sign*eval)
    auto tensors = random_tensors(kTiny, 3);
    for (Tensor& t : tensors) std::ranges::fill(t.data, 0.0f);
    tensors.back().data[0] = 0.5f;                      // prior.k
    tensors[tensors.size() - 2].data[0] = 1.0f;         // prior.beta
    auto p = Parameters::from_tensors(kTiny, tensors);
    CHECK(p.has_value());
    if (!p) return;
    const auto input = random_input(kTiny, 9);
    std::vector<float> logits(kPolicySize);
    std::vector<float> values(2);
    CHECK(ReferenceNet(*p).forward(input, 1, logits, values).has_value());
    for (int c = 0; c < kCells; ++c) {
        const float term = 16.0f * input[static_cast<std::size_t>(c * kClassicPlanes + kDistOwn)];
        CHECK_EQ(logits[static_cast<std::size_t>(c * 2)], term);
        CHECK_EQ(logits[static_cast<std::size_t>(c * 2 + 1)], -term);
    }
    CHECK_EQ(values[0], static_cast<float>(std::tanh(static_cast<double>(0.5f * input[kEval]))));
    CHECK_EQ(values[1], static_cast<float>(std::tanh(static_cast<double>(0.5f * -input[kEval]))));
}

// ---------------------------------------------------------------- golden: reference against Java

namespace {

// Positions spread over the 1,024 golden positions, each run alone (so this also covers batch = 1).
std::vector<int> spread(int count, int positions) {
    std::vector<int> out;
    for (int i = 0; i < count; ++i) out.push_back(static_cast<int>((static_cast<long>(i) * positions) / count + (i % 5)) % positions);
    return out;
}

void check_reference_against_java(const std::string& name, int count) {
    const auto preset = preset_by_name(name);
    const auto dir = golden::directory();
    auto data = load_golden_data(dir, *preset);
    if (!data) {
        const bool optional_file = name == "gen11" && data.error().kind == golden::ErrorKind::Missing;
        if (optional_file || (data.error().kind == golden::ErrorKind::Missing && !golden::required())) {
            test::skip(name + ": " + data.error().message);
        } else {
            test::count_check();
            test::fail("golden", 0, name + ": " + data.error().message);
        }
        return;
    }
    auto weights = load_golden_weights(dir, *preset);
    if (!weights) {
        if (weights.error().kind == golden::ErrorKind::Missing && (name == "gen11" || !golden::required())) {
            test::skip(name + ": " + weights.error().message);
        } else {
            test::count_check();
            test::fail("golden", 0, name + ": " + weights.error().message);
        }
        return;
    }
    CHECK(weights->config() == preset->config);
    const NetworkConfig& cfg = weights->config();
    const ReferenceNet net(*weights);
    const auto size = static_cast<std::size_t>(cfg.input_size());
    double max_ulps = 0;
    int identical = 0;
    for (const int pos : spread(count, data->positions)) {
        std::vector<float> logits(kPolicySize);
        std::vector<float> values(static_cast<std::size_t>(cfg.players));
        const auto in = std::span<const float>(data->inputs).subspan(static_cast<std::size_t>(pos) * size, size);
        CHECK(net.forward(in, 1, logits, values).has_value());
        const Comparison c = compare_outputs(logits, values, data->logits, data->values, pos, 1, cfg.players, 0.0);
        CHECK(c.logits_bit_identical);
        CHECK(c.fraction() <= 1.0);
        max_ulps = std::max(max_ulps, c.values_max_ulps);
        identical += c.logits_bit_identical ? 1 : 0;
    }
    CHECK(max_ulps <= 1.0);
    std::cout << "  " << name << ": " << identical << "/" << count << " positions with bit-identical logits, values within " << max_ulps << " ulp\n";
}

}  // namespace

SH_TEST(reference_matches_java_small) { check_reference_against_java("small", 48); }
SH_TEST(reference_matches_java_large) { check_reference_against_java("large", 8); }
SH_TEST(reference_matches_java_gen11) { check_reference_against_java("gen11", 4); }

SH_TEST(reference_batch_equals_single_on_golden_inputs) {
    const auto preset = preset_by_name("small");
    auto data = load_golden_data(golden::directory(), *preset);
    auto weights = load_golden_weights(golden::directory(), *preset);
    if (!data || !weights) {
        test::count_check();
        if (data.error().kind == golden::ErrorKind::Missing && !golden::required()) {
            test::skip("small golden files missing");
        } else {
            test::fail("golden", 0, "small golden files unreadable");
        }
        return;
    }
    const ReferenceNet net(*weights);
    const auto size = static_cast<std::size_t>(preset->config.input_size());
    const int batch = 67;
    std::vector<float> logits(static_cast<std::size_t>(batch) * kPolicySize);
    std::vector<float> values(static_cast<std::size_t>(batch) * 2);
    CHECK(net.forward(std::span<const float>(data->inputs).first(static_cast<std::size_t>(batch) * size), batch, logits, values).has_value());
    const Comparison c = compare_outputs(logits, values, data->logits, data->values, 0, batch, 2, 0.0);
    CHECK(c.logits_bit_identical);
    CHECK(c.values_max_ulps <= 1.0);
}

SH_TEST(nn_cases_of_the_java_exporter) {
    auto cases = load_golden_contents(golden::directory(), "nn-cases.safetensors");
    if (!cases) {
        test::count_check();
        if (cases.error().kind == golden::ErrorKind::Missing && !golden::required()) {
            test::skip("nn-cases: " + cases.error().message);
        } else {
            test::fail("golden", 0, "nn-cases: " + cases.error().message);
        }
        return;
    }
    const auto find = [&](const std::string& name) -> const std::vector<float>& {
        for (const Tensor& t : cases->tensors) {
            if (t.name == name) return t.data;
        }
        test::count_check();
        test::fail("golden", 0, "nn-cases lacks " + name);
        static const std::vector<float> none;
        return none;
    };
    // the base network: tensors "base.<name>"
    std::vector<Tensor> base;
    for (const Tensor& t : cases->tensors) {
        if (t.name.starts_with("base.") && t.name != "base.logits" && t.name != "base.values") {
            base.push_back(Tensor{t.name.substr(5), t.shape, t.data});
        }
    }
    auto params = Parameters::from_tensors(kTiny, base);
    CHECK(params.has_value());
    if (!params) return;
    const std::vector<float>& input = find("input");
    const std::vector<float>& dirty = find("dirty_input");
    CHECK_EQ(input.size(), static_cast<std::size_t>(kTiny.input_size()));
    std::vector<float> logits(kPolicySize);
    std::vector<float> values(2);
    CHECK(ReferenceNet(*params).forward(input, 1, logits, values).has_value());
    const Comparison base_cmp = compare_outputs(logits, values, find("base.logits"), find("base.values"), 0, 1, 2, 0.0);
    CHECK(base_cmp.logits_bit_identical);
    CHECK(base_cmp.values_max_ulps <= 1.0);

    // a dirty padding row is refused
    CHECK(!ReferenceNet(*params).forward(dirty, 1, logits, values).has_value());

    // NaN in conv_in.weight: the same NaN pattern as Java (NaN where Java has NaN, equal bits elsewhere)
    {
        std::vector<Tensor> t = base;
        for (Tensor& x : t) {
            if (x.name == "conv_in.weight") x.data = find("nan.conv_in.weight");
        }
        auto p = Parameters::from_tensors(kTiny, t);
        CHECK(p.has_value());
        if (p) {
            std::vector<float> nl(kPolicySize);
            std::vector<float> nv(2);
            CHECK(ReferenceNet(*p).forward(input, 1, nl, nv).has_value());
            const std::vector<float>& el = find("nan.logits");
            const std::vector<float>& ev = find("nan.values");
            int nan_logits = 0;
            for (std::size_t i = 0; i < nl.size(); ++i) {
                CHECK_EQ(std::isnan(nl[i]), std::isnan(el[i]));
                if (std::isnan(nl[i])) ++nan_logits; else CHECK_EQ(std::bit_cast<std::uint32_t>(nl[i]), std::bit_cast<std::uint32_t>(el[i]));
            }
            CHECK(nan_logits > 0);
            CHECK(std::isnan(nv[0]) && std::isnan(nv[1]));
            CHECK(std::isnan(ev[0]) && std::isnan(ev[1]));
        }
    }
    // saturation: the dense2 biases 44.3, 100 and -1000, +inf give exactly {1, 1} and {-1, 1}
    for (const std::string tag : {"sat_a", "sat_b"}) {
        std::vector<Tensor> t = base;
        for (Tensor& x : t) {
            if (x.name == "head_value.dense2.bias") x.data = find(tag + ".head_value.dense2.bias");
        }
        auto p = Parameters::from_tensors(kTiny, t);
        CHECK(p.has_value());
        if (!p) continue;
        std::vector<float> sl(kPolicySize);
        std::vector<float> sv(2);
        CHECK(ReferenceNet(*p).forward(input, 1, sl, sv).has_value());
        const std::vector<float>& ev = find(tag + ".values");
        CHECK_EQ(sv[0], ev[0]);
        CHECK_EQ(sv[1], ev[1]);
        CHECK_EQ(sv[0], tag == "sat_a" ? 1.0f : -1.0f);
        CHECK_EQ(sv[1], 1.0f);
    }
}

// ---------------------------------------------------------------- comparison, golden_io

SH_TEST(compare_outputs_rules) {
    std::vector<float> ref(kPolicySize, 1.0f);
    std::vector<float> got = ref;
    std::vector<float> rv = {0.5f, -0.5f};
    std::vector<float> gv = rv;
    Comparison c = compare_outputs(got, gv, ref, rv, 0, 1, 2, 0.0);
    CHECK(c.logits_bit_identical);
    CHECK_EQ(c.fraction(), 0.0);
    // an error of exactly half the bound: 0.5 * (1e-4 + 1e-3 * 1)
    got[3] = 1.0f + 0.5f * static_cast<float>(1e-4 + 1e-3);
    c = compare_outputs(got, gv, ref, rv, 0, 1, 2, 0.0);
    CHECK(!c.logits_bit_identical);
    CHECK(std::abs(c.logits_fraction - 0.5) < 1e-3);
    // the extra term widens the bound: largest |logit| is 1
    got[3] = 1.0f + 2.2e-3f;
    c = compare_outputs(got, gv, ref, rv, 0, 1, 2, 0.0);
    CHECK(c.logits_fraction > 1.0);
    CHECK(c.needed_extra > 0.0);
    c = compare_outputs(got, gv, ref, rv, 0, 1, 2, c.needed_extra * 1.5);
    CHECK(c.logits_fraction < 1.0);
    // NaN equals NaN, NaN against a number is infinite
    got = ref;
    got[0] = std::nanf("");
    c = compare_outputs(got, gv, ref, rv, 0, 1, 2, 0.0);
    CHECK(std::isinf(c.logits_fraction));
    ref[0] = std::nanf("");
    c = compare_outputs(got, gv, ref, rv, 0, 1, 2, 0.0);
    CHECK_EQ(c.logits_fraction, 0.0);
    // values: one ulp
    gv[0] = std::nextafter(0.5f, 1.0f);
    c = compare_outputs(got, gv, ref, rv, 0, 1, 2, 0.0);
    CHECK_EQ(c.values_max_ulps, 1.0);
    // the window [first, first + n): positions after the first read the right reference rows
    std::vector<float> ref2(2 * kPolicySize, 0.0f);
    std::vector<float> rv2 = {1.0f, 2.0f, 3.0f, 4.0f};
    std::vector<float> got1(kPolicySize, 0.0f);
    std::vector<float> gv1 = {3.0f, 4.0f};
    c = compare_outputs(got1, gv1, ref2, rv2, 1, 1, 2, 0.0);
    CHECK(c.logits_bit_identical);
    CHECK_EQ(c.values_fraction, 0.0);
}

SH_TEST(golden_io_error_kinds) {
    const std::filesystem::path none = "/nonexistent-golden-directory";
    auto missing = load_golden_contents(none, "nn-inputs.safetensors");
    CHECK(!missing.has_value() && missing.error().kind == golden::ErrorKind::Missing);
    CHECK(!load_figures(none).has_value());
    CHECK(preset_by_name("small").has_value());
    CHECK(!preset_by_name("huge").has_value());
    CHECK(preset_by_name("gen11")->key == "large-gen11");
    std::map<std::string, std::string> figures = {{"extra_rel_of_max_logit.vector.small-u5", "0.25"}};
    CHECK_EQ(extra_rel(figures, "vector", *preset_by_name("small")), 0.25);
    CHECK_EQ(extra_rel(figures, "simd", *preset_by_name("small")), 0.25);   // falls back to the Java vector figure
    CHECK_EQ(extra_rel(figures, "vector", *preset_by_name("large")), 0.0);
    // a golden file that does not match its manifest entry is corrupt, not missing
    auto real = load_golden_contents(golden::directory(), "nn-inputs.safetensors");
    if (!real && real.error().kind == golden::ErrorKind::Missing) {
        test::count_check();
        if (golden::required()) test::fail("golden", 0, real.error().message); else test::skip(real.error().message);
    } else {
        CHECK(real.has_value());
        if (real) CHECK_EQ(real->tensors[0].name, std::string("inputs"));
    }
}

// ---------------------------------------------------------------- buckets

SH_TEST(buckets_and_variant_rule) {
    const std::vector<std::array<int, 3>> table = {{1, 1024, 1}, {2, 1024, 2}, {3, 1024, 4}, {4, 1024, 4}, {5, 1024, 8}, {67, 1024, 128},
                                                   {256, 1024, 256}, {257, 1024, 512}, {1000, 1024, 1024}, {1024, 1024, 1024},
                                                   {1025, 1024, 1024}, {5, 6, 6}, {3, 3, 3}, {6, 6, 6}, {1, 1, 1}, {100, 1, 1}};
    for (const auto& [n, max, expected] : table) CHECK_EQ(*bucket_for(n, max), expected);
    CHECK(!bucket_for(0, 1024).has_value());
    CHECK(!bucket_for(5, 0).has_value());
    CHECK(!bucket_for(-3, 1024).has_value());
    CHECK_EQ(bucket_slot(1), 0);
    CHECK_EQ(bucket_slot(2), 1);
    CHECK_EQ(bucket_slot(4), 2);
    CHECK_EQ(bucket_slot(1024), 10);
    CHECK_EQ(bucket_slot(6), 3);  // a capped bucket that is no power of two goes after the slot of 4
    // variant rule: naive while bucket * channels <= 128; SMALL (32): bucket 4 naive, 8 not; LARGE (64): bucket 2 naive, 4 not
    CHECK(variant_for(4, 32, true) == ConvVariant::Naive);
    CHECK(variant_for(8, 32, true) == ConvVariant::Simdgroup);
    CHECK(variant_for(2, 64, true) == ConvVariant::Naive);
    CHECK(variant_for(4, 64, true) == ConvVariant::Simdgroup);
    CHECK(variant_for(4, 64, false) == ConvVariant::Tiled);
    CHECK(variant_for(1, 64, false) == ConvVariant::Naive);
    // chunks: batches above max_batch are split
    const auto chunks = chunks_of(2500, 1024);
    CHECK_EQ(chunks.size(), std::size_t{3});
    CHECK_EQ(chunks[0].first, 0);
    CHECK_EQ(chunks[0].n, 1024);
    CHECK_EQ(chunks[1].first, 1024);
    CHECK_EQ(chunks[2].first, 2048);
    CHECK_EQ(chunks[2].n, 452);
    CHECK_EQ(chunks[2].bucket, 512);
    CHECK_EQ(chunks_of(1, 1024).size(), std::size_t{1});
    CHECK_EQ(chunks_of(1, 1024)[0].bucket, 1);
    CHECK(chunks_of(0, 1024).empty());
}

// ---------------------------------------------------------------- SIMD forward pass (WP10)

namespace {

constexpr SimdVariant kVariants[] = {SimdVariant::Fma, SimdVariant::NoFma};

const char* variant_name(SimdVariant v) { return v == SimdVariant::Fma ? "simd" : "simd-nofma"; }

// Runs a SimdNet over `n` positions in calls of `batch` positions; false if a call failed.
bool run_simd(SimdNet& net, std::span<const float> input, int n, int batch, std::span<float> logits, std::span<float> values) {
    const NetworkConfig& cfg = net.config();
    const auto size = static_cast<std::size_t>(cfg.input_size());
    const auto players = static_cast<std::size_t>(cfg.players);
    for (int first = 0; first < n; first += batch) {
        const int m = std::min(batch, n - first);
        const auto f = static_cast<std::size_t>(first);
        const auto c = static_cast<std::size_t>(m);
        if (!net.forward(input.subspan(f * size, c * size), m, logits.subspan(f * static_cast<std::size_t>(kPolicySize), c * static_cast<std::size_t>(kPolicySize)),
                         values.subspan(f * players, c * players)))
            return false;
    }
    return true;
}

// Synthetic networks, also with channel counts that are no multiple of the tile width (tail lanes) and without priors:
// SimdNet against ReferenceNet within the bound, both variants, batches 1, 3 and 7.
void check_simd_on_synthetic(const NetworkConfig& cfg, std::uint64_t seed) {
    auto p = Parameters::from_tensors(cfg, random_tensors(cfg, seed));
    CHECK(p.has_value());
    if (!p) return;
    const ReferenceNet ref(*p);
    const int n = 7;
    std::vector<float> input;
    for (int b = 0; b < n; ++b) {
        const auto one = random_input(cfg, 500 + static_cast<std::uint64_t>(b));
        input.insert(input.end(), one.begin(), one.end());
    }
    const auto players = static_cast<std::size_t>(cfg.players);
    std::vector<float> ref_logits(static_cast<std::size_t>(n) * kPolicySize);
    std::vector<float> ref_values(static_cast<std::size_t>(n) * players);
    CHECK(ref.forward(input, n, ref_logits, ref_values).has_value());
    for (const SimdVariant variant : kVariants) {
        SimdNet net(*p, variant);
        for (const int batch : {1, 3, 7}) {
            std::vector<float> logits(ref_logits.size(), std::numeric_limits<float>::quiet_NaN());
            std::vector<float> values(ref_values.size(), std::numeric_limits<float>::quiet_NaN());
            CHECK(run_simd(net, input, n, batch, logits, values));
            const Comparison c = compare_outputs(logits, values, ref_logits, ref_values, 0, n, cfg.players, 0.0);
            std::cout << "  channels=" << cfg.channels << " blocks=" << cfg.blocks << " " << variant_name(variant) << " batch=" << batch
                      << " logits_err_frac=" << c.logits_fraction << " values_err_frac=" << c.values_fraction << '\n';
            CHECK(c.fraction() <= 1.0);
            for (const float v : logits) CHECK(!std::isnan(v));  // every element was written
        }
    }
}

}  // namespace

SH_TEST(simd_on_synthetic_networks) {
    check_simd_on_synthetic(kTiny, 11);                                           // 8 channels, classic1
    check_simd_on_synthetic(*make_config(5, 2, 2, 32, PlaneSet::Classic1), 12);   // tail lanes, two blocks
    check_simd_on_synthetic(*make_config(13, 1, 3, 32, PlaneSet::Classic1), 13);  // tail lanes, three players
    check_simd_on_synthetic(*make_config(21, 2, 2, 32, PlaneSet::Plain), 14);     // no residual priors, 7 planes
    check_simd_on_synthetic(*make_config(40, 1, 2, 17, PlaneSet::Classic1), 15);  // value_hidden that is no multiple of the lanes
}

SH_TEST(simd_batch_equals_single_bit_for_bit) {
    auto p = Parameters::from_tensors(kTiny, random_tensors(kTiny, 21));
    CHECK(p.has_value());
    if (!p) return;
    const int n = 6;
    std::vector<float> input;
    for (int b = 0; b < n; ++b) {
        const auto one = random_input(kTiny, 900 + static_cast<std::uint64_t>(b));
        input.insert(input.end(), one.begin(), one.end());
    }
    for (const SimdVariant variant : kVariants) {
        SimdNet net(*p, variant);
        std::vector<float> all_l(static_cast<std::size_t>(n) * kPolicySize);
        std::vector<float> all_v(static_cast<std::size_t>(n) * 2);
        std::vector<float> one_l(all_l.size());
        std::vector<float> one_v(all_v.size());
        CHECK(run_simd(net, input, n, n, all_l, all_v));
        CHECK(run_simd(net, input, n, 1, one_l, one_v));
        for (std::size_t i = 0; i < all_l.size(); ++i) CHECK_EQ(std::bit_cast<std::uint32_t>(all_l[i]), std::bit_cast<std::uint32_t>(one_l[i]));
        for (std::size_t i = 0; i < all_v.size(); ++i) CHECK_EQ(std::bit_cast<std::uint32_t>(all_v[i]), std::bit_cast<std::uint32_t>(one_v[i]));
        // the same input twice in one call gives the same output twice (scratch buffers carry nothing over)
        std::vector<float> twice = random_input(kTiny, 77);
        const std::vector<float> first = twice;
        twice.insert(twice.end(), first.begin(), first.end());
        std::vector<float> tl(2 * kPolicySize);
        std::vector<float> tv(4);
        CHECK(net.forward(twice, 2, tl, tv).has_value());
        for (int i = 0; i < kPolicySize; ++i) CHECK_EQ(std::bit_cast<std::uint32_t>(tl[static_cast<std::size_t>(i)]), std::bit_cast<std::uint32_t>(tl[static_cast<std::size_t>(kPolicySize + i)]));
    }
}

SH_TEST(simd_errors_and_dirty_padding) {
    auto p = Parameters::from_tensors(kTiny, random_tensors(kTiny, 31));
    CHECK(p.has_value());
    if (!p) return;
    const auto one = random_input(kTiny, 5);
    for (const SimdVariant variant : kVariants) {
        SimdNet net(*p, variant);
        std::vector<float> l(kPolicySize, -7.0f);
        std::vector<float> v(2, -7.0f);
        CHECK(!net.forward(one, 0, l, v).has_value());
        CHECK(!net.forward(std::span<const float>(one).first(100), 1, l, v).has_value());
        CHECK(!net.forward(one, 1, std::span<float>(l).first(10), v).has_value());
        CHECK(!net.forward(one, 1, l, std::span<float>(v).first(1)).has_value());
        // a dirty padding row in the SECOND position: refused, and nothing is written, not even for the first position
        std::vector<float> two;
        for (int copy = 0; copy < 2; ++copy) two.insert(two.end(), one.begin(), one.end());
        two[static_cast<std::size_t>(kTiny.input_size() + kPadding * kClassicPlanes + kProgress)] = 0.5f;
        std::vector<float> l2(2 * kPolicySize, -7.0f);
        std::vector<float> v2(4, -7.0f);
        CHECK(!net.forward(two, 2, l2, v2).has_value());
        for (const float x : l2) CHECK_EQ(x, -7.0f);
        for (const float x : v2) CHECK_EQ(x, -7.0f);
        two[static_cast<std::size_t>(kTiny.input_size() + kPadding * kClassicPlanes + kProgress)] = std::numeric_limits<float>::quiet_NaN();
        CHECK(!net.forward(two, 2, l2, v2).has_value());  // a NaN is not zero
        // and the net still works afterwards
        CHECK(net.forward(one, 1, l, v).has_value());
    }
}

SH_TEST(simd_forward_allocates_nothing) {
    auto p = Parameters::from_tensors(kTiny, random_tensors(kTiny, 41));
    CHECK(p.has_value());
    if (!p) return;
    std::vector<float> input;
    for (int b = 0; b < 3; ++b) {
        const auto one = random_input(kTiny, 60 + static_cast<std::uint64_t>(b));
        input.insert(input.end(), one.begin(), one.end());
    }
    std::vector<float> l(3 * kPolicySize);
    std::vector<float> v(6);
    for (const SimdVariant variant : kVariants) {
        SimdNet net(*p, variant);
        CHECK(net.forward(input, 3, l, v).has_value());  // warm-up
        const std::size_t before = test::allocations();
        const bool ok = net.forward(input, 3, l, v).has_value();
        const bool ok1 = net.forward(std::span<const float>(input).first(static_cast<std::size_t>(kTiny.input_size())), 1, l, v).has_value();
        const std::size_t after = test::allocations();
        CHECK(ok);
        CHECK(ok1);
        CHECK_EQ(after - before, std::size_t{0});
    }
    // the counter does count: constructing a net allocates
    const std::size_t b0 = test::allocations();
    { SimdNet net(*p); }
    CHECK(test::allocations() > b0);
}

namespace {

// Golden: both variants against the Java scalar reference, within the bound, for the batches 1, 3, 64 and 67.
void check_simd_against_java(const std::string& name, int positions) {
    const auto preset = preset_by_name(name);
    const auto dir = golden::directory();
    auto data = load_golden_data(dir, *preset);
    auto weights = load_golden_weights(dir, *preset);
    if (!data || !weights) {
        const golden::Error& e = !data ? data.error() : weights.error();
        if (e.kind == golden::ErrorKind::Missing && (name == "gen11" || !golden::required())) {
            test::skip(name + ": " + e.message);
        } else {
            test::count_check();
            test::fail("golden", 0, name + ": " + e.message);
        }
        return;
    }
    const NetworkConfig& cfg = weights->config();
    auto figures = load_figures(dir);
    CHECK(figures.has_value());
    const double extra = figures ? extra_rel(*figures, "vector", *preset) : 0.0;
    const auto size = static_cast<std::size_t>(cfg.input_size());
    const int n = std::min(positions, data->positions);
    for (const SimdVariant variant : kVariants) {
        SimdNet net(*weights, variant);
        for (const int batch : {1, 3, 64, 67}) {
            std::vector<float> logits(static_cast<std::size_t>(n) * kPolicySize);
            std::vector<float> values(static_cast<std::size_t>(n) * static_cast<std::size_t>(cfg.players));
            CHECK(run_simd(net, std::span<const float>(data->inputs).first(static_cast<std::size_t>(n) * size), n, batch, logits, values));
            const Comparison c = compare_outputs(logits, values, data->logits, data->values, 0, n, cfg.players, extra);
            const Comparison java = compare_outputs(logits, values, data->vlogits, data->vvalues, 0, n, cfg.players, extra);
            std::cout << "  " << name << " " << variant_name(variant) << " lanes=" << SimdNet::lanes() << " batch=" << batch << " positions=" << n
                      << ": err_frac vs scalar reference " << c.fraction() << ", vs Java VectorNetwork " << java.fraction() << '\n';
            CHECK(c.fraction() <= 1.0);
            CHECK(java.fraction() <= 1.0);
        }
    }
}

}  // namespace

SH_TEST(simd_matches_java_small) { check_simd_against_java("small", 134); }
SH_TEST(simd_matches_java_large) { check_simd_against_java("large", 70); }
SH_TEST(simd_matches_java_gen11) { check_simd_against_java("gen11", 70); }

int main() { return sh::test::run_all(); }
