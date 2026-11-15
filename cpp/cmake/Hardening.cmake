# Bounds checks of the standard library: SH_HARDENING is NONE, FAST, EXTENSIVE or DEBUG.
# libc++ (clang): _LIBCPP_HARDENING_MODE_<mode>.  libstdc++ (gcc): -D_GLIBCXX_ASSERTIONS (any mode except NONE).
set(SH_HARDENING "NONE" CACHE STRING "Standard-library hardening: NONE, FAST, EXTENSIVE or DEBUG")
set_property(CACHE SH_HARDENING PROPERTY STRINGS NONE FAST EXTENSIVE DEBUG)
add_library(sh_hardening INTERFACE)
if(NOT SH_HARDENING STREQUAL "NONE")
  if(CMAKE_CXX_COMPILER_ID MATCHES "Clang")
    target_compile_definitions(sh_hardening INTERFACE
      _LIBCPP_HARDENING_MODE=_LIBCPP_HARDENING_MODE_${SH_HARDENING})
  else()
    target_compile_definitions(sh_hardening INTERFACE _GLIBCXX_ASSERTIONS)
  endif()
endif()
