// SHA-256 over a byte span, as lower-case hexadecimal. On macOS sha256_hex uses CommonCrypto, elsewhere the portable
// implementation (sha256_hex_portable, span-only code). Both are always compiled, so a test can compare them.
// Deviation from the plan's layout: this lives in src/core (not src/tablebase), because the golden reader in src/core needs it.
#pragma once

#include <cstddef>
#include <span>
#include <string>

namespace sh {

std::string sha256_hex(std::span<const std::byte> data);
std::string sha256_hex_portable(std::span<const std::byte> data);

}  // namespace sh
