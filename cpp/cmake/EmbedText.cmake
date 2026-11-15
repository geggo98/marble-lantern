# Embeds a text file as one raw string literal, for example the MSL kernels.
# Script mode:  cmake -DINPUT=<file> -DOUTPUT=<file.inc> -P EmbedText.cmake
# The output is the text  R"MSL(<content>)MSL"  and is included inside a constexpr std::string_view initializer.
file(READ "${INPUT}" content)
string(FIND "${content}" ")MSL\"" clash)
if(NOT clash EQUAL -1)
  message(FATAL_ERROR "EmbedText: ${INPUT} contains the raw-string terminator")
endif()
file(WRITE "${OUTPUT}" "R\"MSL(${content})MSL\"\n")
