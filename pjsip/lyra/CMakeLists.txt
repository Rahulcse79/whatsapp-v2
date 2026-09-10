# The Lyra codec and its closure, built from third_party/ for one Android ABI (ADR-008).
#
# Invoked by pjsip/build-native.sh once per ABI with the NDK's toolchain file, against a
# STAGED COPY of the vendored trees — never third_party/ itself. Two of these trees write
# into their own source directory at configure time (TensorFlow Lite's eigen.cmake
# `file(WRITE ...)`s into Eigen; XNNPACK generates into its own tree), and third_party/ is
# read-only by architecture rule 12. build-native.sh's `sync_tree` is what makes the copy.
#
# What comes out, in the layout pjproject's `configure-android --with-lyra=DIR` expects
# (third_party/pjproject/aconfigure.ac:2582-2640), installed by build-native.sh into the
# per-ABI prefix:
#
#   lib/liblyra.a                       one archive that resolves LyraDecoder::Create on
#                                       its own: Lyra, audio_dsp, glog, TFLite, XNNPACK
#                                       and every transitive static library, merged
#   lyra_encoder.h, lyra_decoder.h      at the prefix root — lyra.cpp includes them bare
#   lyra/*.h                            the headers those two include
#   include/com_google_absl/            absl headers
#   include/gulrak_filesystem/          `include/ghc/filesystem.hpp`
#   include/com_google_glog/src/        glog headers (built ones, generated logging.h)
#   model_coeffs/                       the four weight files, so the path pjproject bakes
#                                       in as its default exists
#
# Nothing here downloads. Every FetchContent the sub-builds would perform is pointed at a
# vendored tree, and FETCHCONTENT_FULLY_DISCONNECTED makes any override this file forgot a
# configure ERROR rather than a silent fetch (N-2 fails closed, not open).

cmake_minimum_required(VERSION 3.22)
project(lyra_closure LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Where the staged copies of the vendored trees are. build-native.sh passes it.
set(LYRA_VENDOR "" CACHE PATH "Directory holding a copy of every tree in the Lyra closure")
if(NOT LYRA_VENDOR)
  message(FATAL_ERROR "LYRA_VENDOR must point at the staged third_party/ copy")
endif()
set(TF_ROOT   "${LYRA_VENDOR}/tensorflow")
set(LYRA_ROOT "${LYRA_VENDOR}/lyra")
set(ADSP_ROOT "${LYRA_VENDOR}/audio_dsp")

# ------------------------------------------------------------------ no network, ever
set(FETCHCONTENT_FULLY_DISCONNECTED ON CACHE BOOL "" FORCE)
set(FETCHCONTENT_QUIET OFF CACHE BOOL "" FORCE)
# TFLite's OverridableFetchContent wraps FetchContent, so the standard per-content source
# override is honoured. The names are TFLite's content names, upper-cased.
set(FETCHCONTENT_SOURCE_DIR_ABSEIL-CPP   "${LYRA_VENDOR}/abseil-cpp"  CACHE PATH "" FORCE)
set(FETCHCONTENT_SOURCE_DIR_CPUINFO      "${LYRA_VENDOR}/cpuinfo"     CACHE PATH "" FORCE)
set(FETCHCONTENT_SOURCE_DIR_EIGEN        "${LYRA_VENDOR}/eigen"       CACHE PATH "" FORCE)
set(FETCHCONTENT_SOURCE_DIR_FARMHASH     "${LYRA_VENDOR}/farmhash"    CACHE PATH "" FORCE)
set(FETCHCONTENT_SOURCE_DIR_FFT2D        "${LYRA_VENDOR}/fft2d"       CACHE PATH "" FORCE)
set(FETCHCONTENT_SOURCE_DIR_FLATBUFFERS  "${LYRA_VENDOR}/flatbuffers" CACHE PATH "" FORCE)
set(FETCHCONTENT_SOURCE_DIR_FP16_HEADERS "${LYRA_VENDOR}/FP16"        CACHE PATH "" FORCE)
set(FETCHCONTENT_SOURCE_DIR_GEMMLOWP     "${LYRA_VENDOR}/gemmlowp"    CACHE PATH "" FORCE)
set(FETCHCONTENT_SOURCE_DIR_NEON2SSE     "${LYRA_VENDOR}/neon2sse"    CACHE PATH "" FORCE)
set(FETCHCONTENT_SOURCE_DIR_RUY          "${LYRA_VENDOR}/ruy"         CACHE PATH "" FORCE)
set(FETCHCONTENT_SOURCE_DIR_XNNPACK      "${LYRA_VENDOR}/xnnpack"     CACHE PATH "" FORCE)
# XNNPACK and FP16 fetch their own sub-dependencies with ExternalProject unless told where
# they are. clog lives inside cpuinfo (deps/clog). The two consumers read CLOG_SOURCE_DIR
# differently — cpuinfo adds it as the clog directory itself, XNNPACK appends /deps/clog —
# but TFLite adds cpuinfo first (lite/CMakeLists.txt:149), which defines the `clog`
# target, and XNNPACK's use is behind IF(NOT TARGET clog). So the value cpuinfo wants is
# the one to set; XNNPACK only needs the variable defined to skip its download.
set(CLOG_SOURCE_DIR        "${LYRA_VENDOR}/cpuinfo/deps/clog" CACHE PATH "" FORCE)
set(CPUINFO_SOURCE_DIR     "${LYRA_VENDOR}/cpuinfo"     CACHE PATH "" FORCE)
set(FP16_SOURCE_DIR        "${LYRA_VENDOR}/FP16"        CACHE PATH "" FORCE)
set(FXDIV_SOURCE_DIR       "${LYRA_VENDOR}/FXdiv"       CACHE PATH "" FORCE)
set(PSIMD_SOURCE_DIR       "${LYRA_VENDOR}/psimd"       CACHE PATH "" FORCE)
set(PTHREADPOOL_SOURCE_DIR "${LYRA_VENDOR}/pthreadpool" CACHE PATH "" FORCE)

# ------------------------------------------------------------------ TensorFlow Lite
# What Lyra's tflite_model_wrapper.cc needs and nothing more: the interpreter, the built-in
# kernels and the XNNPACK delegate. GPU and NNAPI delegates are off — Lyra never asks for
# them, each is a library of its own, and NNAPI is deprecated from Android 15.
set(TFLITE_ENABLE_XNNPACK ON  CACHE BOOL "" FORCE)
set(TFLITE_ENABLE_RUY     ON  CACHE BOOL "" FORCE)
set(TFLITE_ENABLE_GPU     OFF CACHE BOOL "" FORCE)
set(TFLITE_ENABLE_NNAPI   OFF CACHE BOOL "" FORCE)
set(TFLITE_ENABLE_MMAP    ON  CACHE BOOL "" FORCE)
# The guards behind the guarded prunes in tools/vendor/pins.sh. verify-guarded-prunes.sh
# greps this file for these exact lines; keep them on one line each.
set(XNNPACK_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(XNNPACK_BUILD_BENCHMARKS OFF CACHE BOOL "" FORCE)
set(CPUINFO_BUILD_UNIT_TESTS OFF CACHE BOOL "" FORCE)
set(CPUINFO_BUILD_MOCK_TESTS OFF CACHE BOOL "" FORCE)
set(CPUINFO_BUILD_BENCHMARKS OFF CACHE BOOL "" FORCE)
set(CPUINFO_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
# Eigen is MPL-2.0 with a handful of LGPL files that TensorFlow excludes with this define;
# the same exclusion applies here so the licence table stays a single line.
add_compile_definitions(EIGEN_MPL2_ONLY)

add_subdirectory("${TF_ROOT}/tensorflow/lite" tflite EXCLUDE_FROM_ALL)

# ------------------------------------------------------------------ glog
# Lyra's WORKSPACE floats glog on master and pulls gflags through it; v0.6.0 is pinned and
# gflags is not needed — nothing in Lyra parses flags.
set(WITH_GFLAGS      OFF CACHE BOOL "" FORCE)
set(WITH_GTEST       OFF CACHE BOOL "" FORCE)
set(WITH_UNWIND      OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING    OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
add_subdirectory("${LYRA_VENDOR}/glog" glog EXCLUDE_FROM_ALL)

# ------------------------------------------------------------------ audio_dsp
# google/multichannel-audio-tools has no CMake build. This is the transitive closure of
# the six targets Lyra depends on, read off its Bazel BUILD files on 2026-09-10 — twelve
# C++ targets and five C ones from audio/dsp/portable. Eigen and fft2d come in through
# TensorFlow's shim headers (`third_party/eigen3/Eigen/Core`, `third_party/fft2d/fft.h`),
# by the exact paths audio_dsp includes.
add_library(audio_dsp STATIC
  "${ADSP_ROOT}/audio/dsp/bessel_functions.cc"
  "${ADSP_ROOT}/audio/dsp/mfcc/dct.cc"
  "${ADSP_ROOT}/audio/dsp/mfcc/mel_filterbank.cc"
  "${ADSP_ROOT}/audio/dsp/mfcc/mfcc.cc"
  "${ADSP_ROOT}/audio/dsp/number_util.cc"
  "${ADSP_ROOT}/audio/dsp/porting.cc"
  "${ADSP_ROOT}/audio/dsp/resampler_q.cc"
  "${ADSP_ROOT}/audio/dsp/spectrogram/inverse_spectrogram.cc"
  "${ADSP_ROOT}/audio/dsp/spectrogram/spectrogram.cc"
  "${ADSP_ROOT}/audio/dsp/window_functions.cc"
  "${ADSP_ROOT}/audio/dsp/portable/number_util.c"
  "${ADSP_ROOT}/audio/dsp/portable/rational_factor_resampler_kernel.c"
  "${ADSP_ROOT}/audio/dsp/portable/read_wav_file.c"
  "${ADSP_ROOT}/audio/dsp/portable/read_wav_file_generic.c"
  "${ADSP_ROOT}/audio/dsp/portable/write_wav_file.c"
  "${ADSP_ROOT}/audio/dsp/portable/write_wav_file_generic.c"
)
target_include_directories(audio_dsp PUBLIC
  "${ADSP_ROOT}"
  "${TF_ROOT}"
  "${LYRA_VENDOR}/eigen"
  "${LYRA_VENDOR}/abseil-cpp"
  "${LYRA_VENDOR}/fft2d"
)
target_link_libraries(audio_dsp PUBLIC glog::glog fft2d_fftsg absl::span absl::random_random)

# ------------------------------------------------------------------ Lyra
# The seventeen non-test sources under lyra/. lyra_config.pb.h is the patched-in parser
# (pjsip/patches/0001-lyra-config-without-protobuf.patch); no protoc runs here.
add_library(lyra_core STATIC
  "${LYRA_ROOT}/lyra/buffered_resampler.cc"
  "${LYRA_ROOT}/lyra/comfort_noise_generator.cc"
  "${LYRA_ROOT}/lyra/dsp_utils.cc"
  "${LYRA_ROOT}/lyra/fixed_packet_loss_model.cc"
  "${LYRA_ROOT}/lyra/gilbert_model.cc"
  "${LYRA_ROOT}/lyra/log_mel_spectrogram_extractor_impl.cc"
  "${LYRA_ROOT}/lyra/lyra_components.cc"
  "${LYRA_ROOT}/lyra/lyra_config.cc"
  "${LYRA_ROOT}/lyra/lyra_decoder.cc"
  "${LYRA_ROOT}/lyra/lyra_encoder.cc"
  "${LYRA_ROOT}/lyra/lyra_gan_model.cc"
  "${LYRA_ROOT}/lyra/noise_estimator.cc"
  "${LYRA_ROOT}/lyra/resampler.cc"
  "${LYRA_ROOT}/lyra/residual_vector_quantizer.cc"
  "${LYRA_ROOT}/lyra/soundstream_encoder.cc"
  "${LYRA_ROOT}/lyra/tflite_model_wrapper.cc"
  "${LYRA_ROOT}/lyra/wav_utils.cc"
)
target_include_directories(lyra_core PUBLIC
  "${LYRA_ROOT}"
  "${LYRA_VENDOR}/gulrak-filesystem"
  "${TF_ROOT}"
  "${LYRA_VENDOR}/abseil-cpp"
  "${LYRA_VENDOR}/flatbuffers/include"
  "${LYRA_VENDOR}/xnnpack/include"
)
target_link_libraries(lyra_core PUBLIC
  audio_dsp
  tensorflow-lite
  glog::glog
  absl::status absl::statusor absl::strings absl::str_format absl::span absl::optional
  absl::memory absl::random_random absl::base
)

# ------------------------------------------------------------------ the one archive
# pjproject's configure links `-llyra` and nothing else, so liblyra.a has to carry the
# whole closure. Every static library the targets above pull in is merged into one with
# an ar MRI script, which is the one way to do that on both macOS and Linux with the
# NDK's llvm-ar. The list is computed from the link closure at generate time, so a new
# absl target Lyra starts using is picked up rather than left for the link to miss.
# Every static-library TARGET reachable from lyra_core. Target names rather than files,
# because a file path is only knowable at generate time ($<TARGET_FILE:...>) and the MRI
# script is written by file(GENERATE), which evaluates that. Private dependencies appear
# in INTERFACE_LINK_LIBRARIES wrapped as $<LINK_ONLY:...>; those are unwrapped, not skipped.
function(collect_static_targets target out_var visited_var)
  if(target MATCHES "^\\$<LINK_ONLY:(.*)>$")
    set(target "${CMAKE_MATCH_1}")
  endif()
  if(NOT TARGET "${target}")
    return()
  endif()
  get_target_property(aliased "${target}" ALIASED_TARGET)
  if(aliased)
    set(target "${aliased}")
  endif()
  list(FIND ${visited_var} "${target}" seen)
  if(NOT seen EQUAL -1)
    return()
  endif()
  list(APPEND ${visited_var} "${target}")
  get_target_property(type "${target}" TYPE)
  if(type STREQUAL "STATIC_LIBRARY")
    list(APPEND ${out_var} "${target}")
  endif()
  set(deps)
  if(type STREQUAL "INTERFACE_LIBRARY")
    get_target_property(deps "${target}" INTERFACE_LINK_LIBRARIES)
  else()
    get_target_property(d1 "${target}" LINK_LIBRARIES)
    get_target_property(d2 "${target}" INTERFACE_LINK_LIBRARIES)
    set(deps ${d1} ${d2})
  endif()
  foreach(dep IN LISTS deps)
    if(dep AND NOT dep MATCHES "NOTFOUND")
      collect_static_targets("${dep}" ${out_var} ${visited_var})
    endif()
  endforeach()
  set(${out_var} "${${out_var}}" PARENT_SCOPE)
  set(${visited_var} "${${visited_var}}" PARENT_SCOPE)
endfunction()

set(LYRA_TARGETS)
set(LYRA_VISITED)
collect_static_targets(lyra_core LYRA_TARGETS LYRA_VISITED)
list(REMOVE_DUPLICATES LYRA_TARGETS)
list(LENGTH LYRA_TARGETS LYRA_ARCHIVE_COUNT)
message(STATUS "liblyra.a will merge ${LYRA_ARCHIVE_COUNT} static libraries: ${LYRA_TARGETS}")

# The MRI script is written at generate time with the real archive paths, then run by ar.
set(LYRA_MRI "${CMAKE_BINARY_DIR}/liblyra.mri")
set(LYRA_FAT "${CMAKE_BINARY_DIR}/liblyra.a")
set(LYRA_MRI_CONTENT "create ${LYRA_FAT}\n")
set(LYRA_ARCHIVE_FILES)
foreach(t IN LISTS LYRA_TARGETS)
  string(APPEND LYRA_MRI_CONTENT "addlib $<TARGET_FILE:${t}>\n")
  list(APPEND LYRA_ARCHIVE_FILES "$<TARGET_FILE:${t}>")
endforeach()
string(APPEND LYRA_MRI_CONTENT "save\nend\n")
file(GENERATE OUTPUT "${LYRA_MRI}" CONTENT "${LYRA_MRI_CONTENT}")

add_custom_command(
  OUTPUT "${LYRA_FAT}"
  COMMAND "${CMAKE_COMMAND}" -E remove -f "${LYRA_FAT}"
  COMMAND "${CMAKE_AR}" -M < "${LYRA_MRI}"
  DEPENDS ${LYRA_TARGETS} ${LYRA_ARCHIVE_FILES} "${LYRA_MRI}"
  COMMENT "Merging ${LYRA_ARCHIVE_COUNT} archives into liblyra.a"
  VERBATIM
)
add_custom_target(liblyra_fat ALL DEPENDS "${LYRA_FAT}")

# ------------------------------------------------------------------ the prefix layout
# `cmake --install` writes the layout aconfigure.ac reads. Headers are copied whole rather
# than listed: lyra_decoder.h pulls in a dozen sibling headers, and absl's include graph
# is not something to enumerate by hand.
install(FILES "${LYRA_FAT}" DESTINATION lib)
install(FILES "${LYRA_ROOT}/lyra/lyra_encoder.h" "${LYRA_ROOT}/lyra/lyra_decoder.h" DESTINATION .)
install(DIRECTORY "${LYRA_ROOT}/lyra/" DESTINATION lyra FILES_MATCHING PATTERN "*.h")
install(DIRECTORY "${LYRA_ROOT}/lyra/model_coeffs/" DESTINATION model_coeffs PATTERN "*.wav" EXCLUDE)
install(DIRECTORY "${LYRA_VENDOR}/abseil-cpp/absl" DESTINATION include/com_google_absl
        FILES_MATCHING PATTERN "*.h" PATTERN "*.inc")
install(DIRECTORY "${LYRA_VENDOR}/gulrak-filesystem/include" DESTINATION include/gulrak_filesystem)
install(DIRECTORY "${LYRA_VENDOR}/glog/src/" DESTINATION include/com_google_glog/src FILES_MATCHING PATTERN "*.h")
install(DIRECTORY "${CMAKE_BINARY_DIR}/glog/glog" DESTINATION include/com_google_glog/src FILES_MATCHING PATTERN "*.h")
