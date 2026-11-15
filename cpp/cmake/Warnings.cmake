# Warning set of docs/cpp-comparison-plan.md ("Memory-safety rules"): all warnings are errors.
# -Wunsafe-buffer-usage (and the libc-call variant) only exist in clang.
add_library(sh_warnings INTERFACE)
target_compile_options(sh_warnings INTERFACE
  -Wall -Wextra -Wpedantic -Wshadow -Wconversion -Wno-sign-conversion -Wnon-virtual-dtor
  -Wold-style-cast -Wcast-align -Wnull-dereference -Wimplicit-fallthrough -Werror)
if(CMAKE_CXX_COMPILER_ID MATCHES "Clang")
  target_compile_options(sh_warnings INTERFACE -Wunsafe-buffer-usage -Wunsafe-buffer-usage-in-libc-call)
endif()
