// Prints the fingerprint lines of Fingerprint.main for a player spec. Stub created by WP0; WP5 fills it.
#include <iostream>

#include "args.hpp"

int main(int argc, char** argv) {
    const auto args = sh::args_from_main(argc, argv);
    std::cerr << "fingerprint_main: not implemented yet (skeleton, " << args.size() << " argument(s))\n";
    return 64;
}
