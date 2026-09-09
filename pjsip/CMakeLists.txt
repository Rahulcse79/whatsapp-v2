# Stage 2 of the native build: the single CMake entry point (N-4).
#
# Invoked once per ABI by `:pjsip:buildPjsua2Native`, as
#
#   cmake -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake -DANDROID_ABI=<abi>
#
# which is the command line master prompt §2.3 option (a) specifies. ONE `./gradlew`
# invocation produces every `.so` from vendored source with no human in the loop (N-4, N-5).
#
# NOT reached through `externalNativeBuild`, and that was tried first. AGP enumerates a
# CMake project's TARGETS and packages the libraries they declare; this project declares
# none, because the `.so` is produced by upstream's autotools and CMake never sees it. AGP's
# answer was `configureCMakeDebug[arm64-v8a] > java.lang.NullPointerException` — no message,
# no target named. See com.whatsappv2.buildlogic.BuildPjsua2Native for the full trade.
#
# ## Why this drives upstream's build systems rather than replacing them
#
# Master prompt §2.3 offers three shapes. This is (b): CMake as the single entry point,
# driving each vendored tree with its own build system. (a) — a native CMake port — is the
# better destination, but pjproject 2.17's root CMakeLists.txt contains NO Android branch
# at all (its only CMAKE_SYSTEM_NAME test is `rtems`), so it is an upstream port with a real
# budget, not a configuration exercise. (c) — hand-authoring CMake over pjproject's sources
# — is permanent divergence, and divergence is not ownership.
#
# The autotools path is the one Android is documented and tested on, and it is the one this
# repository already has a GREEN run of: 2m46s-3m23s per ABI, all three ABIs, an AAR and an
# APK in 7m18s (docs/reconciliation.md B-7). Every flag below is ported from that run
# verbatim, including three failure modes already paid for once. Getting (b) green from
# vendored source first means a failure here is a failure you can read.
#
# ## Nothing is written into third_party/
#
# The vendored trees are read-only inputs: architecture rule 12 hashes them and N-7 forbids
# editing them without a patch. autotools writes config.log, .depend, build.mak and object
# files all over a source tree, so each tree is COPIED into the build directory first and
# built there. That copy is also what makes a per-ABI build possible at all — three ABIs
# cannot share one configured tree.

cmake_minimum_required(VERSION 3.22)
# One language, not NONE: enabling C is what makes the toolchain file resolve
# CMAKE_ANDROID_NDK and the sysroot, which everything below reads. The NDK ships a working
# C compiler, so the compiler check it triggers costs a second and buys the variables.
project(pjsip_native C)

if(NOT ANDROID)
    message(FATAL_ERROR
        "This build is Android-only. Invoke it with the NDK toolchain file:\n"
        "  cmake -DCMAKE_TOOLCHAIN_FILE=$ENV{ANDROID_NDK_ROOT}/build/cmake/android.toolchain.cmake "
        "-DANDROID_ABI=arm64-v8a\n"
        "or, more usefully, `./gradlew :pjsip:buildPjsua2Native`, which does that for every ABI.")
endif()

set(VENDOR_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../third_party")
set(CONFIG_SITE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/config")
set(NATIVE_STAGE "${CMAKE_CURRENT_BINARY_DIR}/native")
set(PREFIX "${NATIVE_STAGE}/prefix")

foreach(tree pjproject openssl opus libvpx)
    if(NOT EXISTS "${VENDOR_ROOT}/${tree}")
        message(FATAL_ERROR
            "third_party/${tree} is missing. The native stack is built from vendored "
            "source (N-2); there is no download step and no prebuilt fallback (N-14). "
            "Run tools/vendor/vendor.sh, or check out the repository completely.")
    endif()
endforeach()

# The ABI is the axis everything below is keyed on. AGP sets ANDROID_ABI; the other three
# are the names each upstream build system happens to use for the same architecture, and
# they are NOT interchangeable — `sysroot-triple` differs from `host` on armeabi-v7a, and
# using one for the other finds nothing and drops the C++ runtime silently.
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(HOST_TRIPLE    "aarch64-linux-android")
    set(SYSROOT_TRIPLE "aarch64-linux-android")
    set(OPENSSL_TARGET "android-arm64")
    set(VPX_TARGET     "arm64-android-gcc")
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
    set(HOST_TRIPLE    "armv7a-linux-androideabi")
    # NOT the same as HOST_TRIPLE. The compiler driver is armv7a-…-androideabi26-clang, but
    # the sysroot library directory is arm-linux-androideabi. Using HOST_TRIPLE here finds
    # nothing and libc++_shared.so goes missing from the packaged output.
    set(SYSROOT_TRIPLE "arm-linux-androideabi")
    set(OPENSSL_TARGET "android-arm")
    set(VPX_TARGET     "armv7-android-gcc")
elseif(ANDROID_ABI STREQUAL "x86_64")
    set(HOST_TRIPLE    "x86_64-linux-android")
    set(SYSROOT_TRIPLE "x86_64-linux-android")
    set(OPENSSL_TARGET "android-x86_64")
    set(VPX_TARGET     "x86_64-android-gcc")
else()
    message(FATAL_ERROR
        "Unsupported ABI '${ANDROID_ABI}'. The supported set is arm64-v8a, armeabi-v7a and "
        "x86_64 — the three app/build.gradle.kts packages (N-6).")
endif()

# minSdk. Must match app/build.gradle.kts: an ABI built for a different API level than the
# one packaged is a runtime failure on the devices in between.
set(ANDROID_API_LEVEL 26)

# The NDK CMake resolved, never the host's. `CMAKE_ANDROID_NDK` is set by the toolchain
# file, so this is the NDK that is actually compiling — asking the environment instead is
# how a cached-NDK-with-no-compiler gets used four steps later.
if(NOT DEFINED CMAKE_ANDROID_NDK)
    message(FATAL_ERROR "CMAKE_ANDROID_NDK is unset — this must run under the NDK toolchain file")
endif()

add_custom_target(pjsua2_native ALL
    COMMAND "${CMAKE_COMMAND}" -E env
            "ANDROID_NDK_ROOT=${CMAKE_ANDROID_NDK}"
            "ANDROID_API=${ANDROID_API_LEVEL}"
            "ABI=${ANDROID_ABI}"
            "HOST_TRIPLE=${HOST_TRIPLE}"
            "SYSROOT_TRIPLE=${SYSROOT_TRIPLE}"
            "OPENSSL_TARGET=${OPENSSL_TARGET}"
            "VPX_TARGET=${VPX_TARGET}"
            "VENDOR_ROOT=${VENDOR_ROOT}"
            "CONFIG_SITE_DIR=${CONFIG_SITE_DIR}"
            "STAGE=${NATIVE_STAGE}"
            "PREFIX=${PREFIX}"
            "OUT_DIR=${CMAKE_LIBRARY_OUTPUT_DIRECTORY}"
            bash "${CMAKE_CURRENT_SOURCE_DIR}/build-native.sh"
    COMMENT "Building pjsua2 for ${ANDROID_ABI} from vendored source"
    VERBATIM
    USES_TERMINAL)
