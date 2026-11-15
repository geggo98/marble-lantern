# AddressSanitizer and UndefinedBehaviorSanitizer, no recovery: every finding aborts the test.
option(SH_SANITIZE "Build with -fsanitize=address,undefined" OFF)
add_library(sh_sanitizers INTERFACE)
if(SH_SANITIZE)
  target_compile_options(sh_sanitizers INTERFACE -fsanitize=address,undefined -fno-sanitize-recover=all -fno-omit-frame-pointer)
  target_link_options(sh_sanitizers INTERFACE -fsanitize=address,undefined)
endif()
