# Locate an installed FreeSWITCH via pkg-config (the freeswitch.pc the install ships),
# falling back to a manual prefix. Defines the imported target FreeSWITCH::FreeSWITCH
# with the include dir and the module install directory.
#
#   FREESWITCH_INCLUDE_DIRS   -I<prefix>/include/freeswitch
#   FREESWITCH_MOD_DIR        <prefix>/lib/freeswitch/mod  (where .so modules install)
#   FREESWITCH_CONF_DIR       <prefix>/etc/freeswitch      (autoload_configs live here)
#   FREESWITCH_VERSION
#
# Point it at a non-default install with -DFREESWITCH_PREFIX=/path or
# PKG_CONFIG_PATH=/path/lib/pkgconfig.

include(FindPackageHandleStandardArgs)

find_package(PkgConfig QUIET)
if(PKG_CONFIG_FOUND)
  pkg_check_modules(PC_FREESWITCH QUIET freeswitch)
endif()

set(_fs_hint "${FREESWITCH_PREFIX}")
if(NOT _fs_hint AND DEFINED ENV{FREESWITCH_PREFIX})
  set(_fs_hint "$ENV{FREESWITCH_PREFIX}")
endif()

find_path(FREESWITCH_INCLUDE_DIR
  NAMES switch.h
  HINTS ${PC_FREESWITCH_INCLUDEDIR} ${PC_FREESWITCH_INCLUDE_DIRS}
        "${_fs_hint}/include/freeswitch"
  PATHS /usr/local/freeswitch/include/freeswitch
        /usr/include/freeswitch
        /usr/local/include/freeswitch)

# The module and config directories come from the .pc file's own variables when present.
if(PC_FREESWITCH_FOUND)
  pkg_get_variable(FREESWITCH_MOD_DIR freeswitch modulesdir)
  pkg_get_variable(FREESWITCH_CONF_DIR freeswitch confdir)
  pkg_get_variable(FREESWITCH_PREFIX_VAR freeswitch prefix)
endif()

if(NOT FREESWITCH_MOD_DIR AND FREESWITCH_INCLUDE_DIR)
  get_filename_component(_prefix "${FREESWITCH_INCLUDE_DIR}/../.." ABSOLUTE)
  set(FREESWITCH_MOD_DIR "${_prefix}/lib/freeswitch/mod")
  set(FREESWITCH_CONF_DIR "${_prefix}/etc/freeswitch")
endif()

set(FREESWITCH_VERSION "${PC_FREESWITCH_VERSION}")

find_package_handle_standard_args(FreeSWITCH
  REQUIRED_VARS FREESWITCH_INCLUDE_DIR FREESWITCH_MOD_DIR
  VERSION_VAR FREESWITCH_VERSION)

if(FreeSWITCH_FOUND)
  set(FREESWITCH_INCLUDE_DIRS "${FREESWITCH_INCLUDE_DIR}")
  if(NOT TARGET FreeSWITCH::FreeSWITCH)
    add_library(FreeSWITCH::FreeSWITCH INTERFACE IMPORTED)
    set_target_properties(FreeSWITCH::FreeSWITCH PROPERTIES
      INTERFACE_INCLUDE_DIRECTORIES "${FREESWITCH_INCLUDE_DIR}")
    # FreeSWITCH modules resolve switch_* symbols from the running freeswitch binary at
    # load time; on macOS the linker must be told those symbols are supplied later.
    if(APPLE)
      set_target_properties(FreeSWITCH::FreeSWITCH PROPERTIES
        INTERFACE_LINK_OPTIONS "-undefined;dynamic_lookup")
    endif()
  endif()
endif()

mark_as_advanced(FREESWITCH_INCLUDE_DIR FREESWITCH_MOD_DIR FREESWITCH_CONF_DIR)
