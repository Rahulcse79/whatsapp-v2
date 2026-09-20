# freeswitch-lyra — two FreeSWITCH modules (mod_lyra, mod_lyra_record) plus their
# unit tests. Built against the INSTALLED FreeSWITCH (pkg-config freeswitch) and the
# host Lyra archive (scripts/build-lyra.sh → build/lyra-prefix).
#
#   cmake -S . -B build -DCMAKE_BUILD_TYPE=Release
#   cmake --build build
#   ctest --test-dir build            # unit tests, no FreeSWITCH or network needed
#   cmake --install build             # copies the .so into FreeSWITCH's mod dir
#
# See docs/06-build-and-deploy.md.
cmake_minimum_required(VERSION 3.16)
project(freeswitch_lyra LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)          # the Lyra closure is C++17
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
if(NOT CMAKE_BUILD_TYPE AND NOT CMAKE_CONFIGURATION_TYPES)
  set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)
endif()

list(APPEND CMAKE_MODULE_PATH "${CMAKE_CURRENT_SOURCE_DIR}/cmake")

option(FSLYRA_BUILD_MODULES "Build the FreeSWITCH modules (needs an installed FreeSWITCH and the Lyra archive)" ON)
option(FSLYRA_BUILD_TESTS   "Build unit tests" ON)

# Warnings the request asked for, plus a couple that catch real bugs. Applied to OUR
# code only (the Lyra archive is already built); FreeSWITCH's own headers are noisy, so
# they are included as SYSTEM headers below.
add_library(fslyra_warnings INTERFACE)
target_compile_options(fslyra_warnings INTERFACE
  -Wall -Wextra -Wpedantic -Wshadow -Wnon-virtual-dtor -Wunused)

# Security/hardening flags where the toolchain accepts them (see docs/06 for lld).
include(CheckCXXCompilerFlag)
add_library(fslyra_hardening INTERFACE)
foreach(flag -fstack-protector-strong -D_FORTIFY_SOURCE=2 -fno-strict-aliasing)
  string(MAKE_C_IDENTIFIER "HAS${flag}" _var)
  check_cxx_compiler_flag("${flag}" ${_var})
  if(${_var})
    target_compile_options(fslyra_hardening INTERFACE ${flag})
  endif()
endforeach()

# Pure, FreeSWITCH-free helpers shared by the modules and the tests.
add_library(fslyra_common INTERFACE)
target_include_directories(fslyra_common INTERFACE "${CMAKE_CURRENT_SOURCE_DIR}/src")

# The config/path parsers as an object library so tests link them without FreeSWITCH.
add_library(fslyra_logic STATIC
  src/mod_lyra/lyra_codec_config.cpp
  src/mod_lyra_record/record_config.cpp
  src/mod_lyra_record/record_path.cpp)
target_link_libraries(fslyra_logic PUBLIC fslyra_common)
target_include_directories(fslyra_logic PUBLIC
  "${CMAKE_CURRENT_SOURCE_DIR}/src/mod_lyra"
  "${CMAKE_CURRENT_SOURCE_DIR}/src/mod_lyra_record")

if(FSLYRA_BUILD_MODULES)
  find_package(FreeSWITCH REQUIRED)
  find_package(LyraArchive REQUIRED)
  message(STATUS "FreeSWITCH headers: ${FREESWITCH_INCLUDE_DIR} (${FREESWITCH_VERSION})")
  message(STATUS "FreeSWITCH modules: ${FREESWITCH_MOD_DIR}")
  message(STATUS "Lyra archive:       ${LYRA_LIBRARY}")

  # A module is a shared object with no lib prefix (mod_lyra.so, not libmod_lyra.so).
  function(add_fs_module name)
    add_library(${name} MODULE ${ARGN})
    set_target_properties(${name} PROPERTIES PREFIX "" OUTPUT_NAME "${name}")
    target_link_libraries(${name} PRIVATE FreeSWITCH::FreeSWITCH fslyra_common fslyra_warnings fslyra_hardening)
    # FreeSWITCH headers as SYSTEM so -Wpedantic does not drown in them.
    target_include_directories(${name} SYSTEM PRIVATE "${FREESWITCH_INCLUDE_DIR}")
    target_include_directories(${name} PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/src")
    if(APPLE)
      set_property(TARGET ${name} PROPERTY SUFFIX ".so")  # not .dylib — FreeSWITCH loads mod_*.so
    endif()
    install(TARGETS ${name} LIBRARY DESTINATION "${FREESWITCH_MOD_DIR}")
  endfunction()

  add_fs_module(mod_lyra
    src/mod_lyra/mod_lyra.cpp
    src/mod_lyra/lyra_adapter.cpp
    src/mod_lyra/lyra_codec_config.cpp)
  target_include_directories(mod_lyra PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/src/mod_lyra")
  target_link_libraries(mod_lyra PRIVATE Lyra::Lyra)

  add_fs_module(mod_lyra_record
    src/mod_lyra_record/mod_lyra_record.cpp
    src/mod_lyra_record/record_config.cpp
    src/mod_lyra_record/record_path.cpp)
  target_include_directories(mod_lyra_record PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/src/mod_lyra_record")

  # The sample configs are installed by scripts/install.sh (which refuses to clobber an
  # operator's edited file); CMake install() has no "only if absent" mode, so it is not
  # done here.
endif()

if(FSLYRA_BUILD_TESTS)
  enable_testing()
  add_subdirectory(tests/unit)
endif()
