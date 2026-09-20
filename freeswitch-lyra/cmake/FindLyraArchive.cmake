# Locate the merged Lyra static library (liblyra.a) and the headers laid out by
# pjsip/lyra/CMakeLists.txt — the same closure the Android app links, built for the
# host by scripts/build-lyra.sh. Defines the imported target Lyra::Lyra.
#
#   LYRA_PREFIX   directory holding lib/liblyra.a, lyra_encoder.h, lyra/, include/…
#                 (default: freeswitch-lyra/build/lyra-prefix, then env LYRA_PREFIX)
#
# The one archive carries Lyra + TFLite + XNNPACK + absl + glog + audio_dsp merged,
# so nothing else needs to be found.

include(FindPackageHandleStandardArgs)

set(_lyra_hint "${LYRA_PREFIX}")
if(NOT _lyra_hint AND DEFINED ENV{LYRA_PREFIX})
  set(_lyra_hint "$ENV{LYRA_PREFIX}")
endif()

find_library(LYRA_LIBRARY
  NAMES lyra liblyra.a
  HINTS "${_lyra_hint}/lib"
        "${CMAKE_CURRENT_LIST_DIR}/../build/lyra-prefix/lib")

find_path(LYRA_INCLUDE_DIR
  NAMES lyra_encoder.h
  HINTS "${_lyra_hint}"
        "${CMAKE_CURRENT_LIST_DIR}/../build/lyra-prefix")

find_package_handle_standard_args(LyraArchive
  REQUIRED_VARS LYRA_LIBRARY LYRA_INCLUDE_DIR)

if(LyraArchive_FOUND AND NOT TARGET Lyra::Lyra)
  add_library(Lyra::Lyra STATIC IMPORTED)
  set_target_properties(Lyra::Lyra PROPERTIES IMPORTED_LOCATION "${LYRA_LIBRARY}")
  # The include layout lyra.cpp (and lyra_adapter.cpp) rely on: prefix root has the two
  # public headers and lyra/, include/com_google_absl holds absl, gulrak has ghc.
  # The include layout scripts/build-lyra.sh installs. lyra_config.h includes
  # "glog/logging.h" and "absl/...": glog headers live under com_google_glog/src, absl
  # under com_google_absl, ghc::filesystem under gulrak_filesystem/include.
  target_include_directories(Lyra::Lyra INTERFACE
    "${LYRA_INCLUDE_DIR}"
    "${LYRA_INCLUDE_DIR}/include/com_google_absl"
    "${LYRA_INCLUDE_DIR}/include/com_google_glog/src"
    "${LYRA_INCLUDE_DIR}/include/gulrak_filesystem")
  # glog symbols are inside the archive. On macOS, absl's cctz timezone code calls
  # CoreFoundation, so that framework must be linked. On Linux, pthread and dl are
  # pulled by TFLite/XNNPACK.
  if(APPLE)
    set_property(TARGET Lyra::Lyra APPEND PROPERTY INTERFACE_LINK_LIBRARIES
      "-framework CoreFoundation")
  elseif(UNIX)
    set_property(TARGET Lyra::Lyra APPEND PROPERTY INTERFACE_LINK_LIBRARIES pthread dl)
  endif()
endif()

mark_as_advanced(LYRA_LIBRARY LYRA_INCLUDE_DIR)
