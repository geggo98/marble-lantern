// Counting allocator for ONE test executable (nn_test): replaces the global operator new and delete and counts the calls of
// operator new. The replacement functions must exist exactly once per executable, so only nn_test.cpp includes this header.
// scripts/check-rules.sh knows this file (the rule against new, delete and malloc has this one exception: the allocator that
// proves that a forward pass allocates nothing has to be built from them).
#pragma once

#include <atomic>
#include <cstddef>
#include <cstdlib>
#include <new>

namespace sh::test {
inline std::atomic<std::size_t> g_allocations{0};
inline std::size_t allocations() { return g_allocations.load(); }
}  // namespace sh::test

// noinline: gcc would inline new and delete into the callers and warn about std::free on a pointer from operator new.
[[gnu::noinline]] void* operator new(std::size_t size) {
    sh::test::g_allocations.fetch_add(1);
    if (void* p = std::malloc(size == 0 ? 1 : size)) return p;
    throw std::bad_alloc();
}
[[gnu::noinline]] void operator delete(void* p) noexcept { std::free(p); }
[[gnu::noinline]] void operator delete(void* p, std::size_t) noexcept { std::free(p); }
