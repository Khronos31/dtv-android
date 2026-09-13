# Keep Android settings identical in the top-level and ExternalProject CMake
# invocations used by mirakc-arib.  The upstream CMakeLists passes only this
# toolchain file to its child projects, so the NDK defaults would otherwise
# silently select API 21 for those dependencies.
#
# The build harness sets these environment variables for every invocation.
if(NOT DEFINED ENV{MIRAKC_ARIB_NDK})
  message(FATAL_ERROR "MIRAKC_ARIB_NDK must point to the selected Android NDK")
endif()
if(NOT DEFINED ENV{MIRAKC_ARIB_ANDROID_ABI})
  message(FATAL_ERROR "MIRAKC_ARIB_ANDROID_ABI must be set (armeabi-v7a or arm64-v8a)")
endif()
if(NOT DEFINED ENV{MIRAKC_ARIB_ANDROID_PLATFORM})
  message(FATAL_ERROR "MIRAKC_ARIB_ANDROID_PLATFORM must be set (android-24 or newer)")
endif()

set(ANDROID_ABI "$ENV{MIRAKC_ARIB_ANDROID_ABI}" CACHE STRING "Android ABI" FORCE)
set(ANDROID_PLATFORM "$ENV{MIRAKC_ARIB_ANDROID_PLATFORM}" CACHE STRING "Android API" FORCE)
# Seed these before loading the NDK toolchain: it initializes package search
# to ONLY when the cache variable is not already set.
if(DEFINED ENV{CMAKE_PREFIX_PATH})
  set(CMAKE_PREFIX_PATH "$ENV{CMAKE_PREFIX_PATH}" CACHE STRING "Vendor package prefix" FORCE)
  set(CMAKE_FIND_ROOT_PATH_MODE_PACKAGE BOTH CACHE STRING "Package root search mode" FORCE)
endif()
include("$ENV{MIRAKC_ARIB_NDK}/build/cmake/android.toolchain.cmake")

# ExternalProject children use this toolchain too.  Their target packages are
# installed in the build prefix (outside the NDK sysroot), so allow the
# explicitly supplied CMAKE_PREFIX_PATH to be searched for package configs.
if(DEFINED ENV{CMAKE_PREFIX_PATH})
  set(CMAKE_PREFIX_PATH "$ENV{CMAKE_PREFIX_PATH}" CACHE STRING "Vendor package prefix" FORCE)
  set(CMAKE_FIND_ROOT_PATH_MODE_PACKAGE BOTH CACHE STRING "Package root search mode" FORCE)
endif()
