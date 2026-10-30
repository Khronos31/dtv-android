cmake_minimum_required(VERSION 3.22.1)
project(mirakc_usb_process)

add_library(usb_process SHARED
    usb_process.cpp
)
target_include_directories(usb_process PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/arib25 ${CMAKE_CURRENT_SOURCE_DIR})
target_compile_features(usb_process PRIVATE cxx_std_17)
target_compile_options(usb_process PRIVATE -fvisibility=hidden)
target_link_options(usb_process PRIVATE
    -Wl,-z,max-page-size=16384
    -Wl,-z,common-page-size=16384
    -Wl,-z,relro
    -Wl,-z,now
)
target_link_libraries(usb_process log)

option(MIRAKC_BUILD_B25_FILTER "Build the standalone mirakc B25 filter" OFF)
if(MIRAKC_BUILD_B25_FILTER)
    add_executable(b25_filter
        b25_filter_main.cpp
        b25_filter.cpp
        ccid_reader.c
        t1_state_machine.c
        b_cas_card_ccid.c
        arib25/arib_std_b25.c
        arib25/ts_section_parser.c
        arib25/multi2.cc
    )
    target_include_directories(b25_filter PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/arib25
        ${CMAKE_CURRENT_SOURCE_DIR}
    )
    target_compile_features(b25_filter PRIVATE cxx_std_17)
    target_compile_options(b25_filter PRIVATE -fvisibility=hidden)
    target_link_options(b25_filter PRIVATE
        -Wl,-z,max-page-size=16384
        -Wl,-z,common-page-size=16384
        -Wl,-z,relro
        -Wl,-z,now
    )
    set_target_properties(b25_filter PROPERTIES
        OUTPUT_NAME "libmirakc-b25-filter.so"
    )
    target_link_libraries(b25_filter log)
endif()

option(MIRAKC_BUILD_SIANO_ADAPTER "Build the standalone mirakc tuner adapter" OFF)
if(MIRAKC_BUILD_SIANO_ADAPTER)
    add_executable(siano_adapter siano_adapter.cpp)
    target_compile_features(siano_adapter PRIVATE cxx_std_17)
    target_link_options(siano_adapter PRIVATE
        -Wl,-z,max-page-size=16384
        -Wl,-z,common-page-size=16384
        -Wl,-z,relro
        -Wl,-z,now
    )
    set_target_properties(siano_adapter PROPERTIES
        OUTPUT_NAME "libmirakc-siano-adapter.so"
    )
    target_link_libraries(siano_adapter log)
endif()

option(MIRAKC_BUILD_PX4_ADAPTER "Build the standalone mirakc PX4 tuner adapter" OFF)
option(MIRAKC_BUILD_ASICEN_ADAPTER "Build the standalone mirakc ASICEN tuner adapter" OFF)
option(MIRAKC_BUILD_PIX_ADAPTER "Build the standalone mirakc PIX tuner adapter" OFF)
option(MIRAKC_BUILD_PX4_RUNTIME_SOCKET_PATH_TESTS "Build host Core runtime socket path proof" OFF)
option(MIRAKC_BUILD_ADAPTER_CLI_TESTS "Build host adapter CLI/Core resolution tests" OFF)
option(MIRAKC_BUILD_USERLAND_CONCURRENCY_TESTS "Build Core receiver concurrency proof" OFF)
option(MIRAKC_BUILD_NATIVE_CARD_FIXTURE_TESTS "Build synthetic persistent IFD client fixtures" OFF)
set(DTV_USERLAND_CORE_DIR "" CACHE PATH "Pinned dtv-userland-core checkout")
set(PX4_USERLAND_DIR "" CACHE PATH "Pinned px4-userland checkout")
set(ASICEN_USERLAND_DIR "" CACHE PATH "Pinned asicen-userland checkout")

if(MIRAKC_BUILD_PX4_ADAPTER OR MIRAKC_BUILD_ASICEN_ADAPTER OR
   MIRAKC_BUILD_PIX_ADAPTER OR MIRAKC_BUILD_PX4_RUNTIME_SOCKET_PATH_TESTS OR
   MIRAKC_BUILD_ADAPTER_CLI_TESTS OR MIRAKC_BUILD_USERLAND_CONCURRENCY_TESTS OR
   MIRAKC_BUILD_NATIVE_CARD_FIXTURE_TESTS)
    if(NOT IS_DIRECTORY "${DTV_USERLAND_CORE_DIR}/include/dtv")
        message(FATAL_ERROR "DTV_USERLAND_CORE_DIR must point to a dtv-userland-core checkout")
    endif()
    if(MIRAKC_BUILD_USERLAND_CONCURRENCY_TESTS OR MIRAKC_BUILD_NATIVE_CARD_FIXTURE_TESTS)
        set(DTV_USERLAND_BUILD_TESTING ON CACHE BOOL "" FORCE)
    else()
        set(DTV_USERLAND_BUILD_TESTING OFF CACHE BOOL "" FORCE)
    endif()
    set(DTV_USERLAND_BUILD_CONTROL ON CACHE BOOL "" FORCE)
    set(DTV_USERLAND_BUILD_HOST_RUNTIME ON CACHE BOOL "" FORCE)
    foreach(component IN ITEMS RECEIVER CARD AUTHORITY STATUS_PROJECTION HOST_TUNER
            HOST_AUTHORITY_OWNER HOST_WORKERS HOST_AUTHORITY_BRIDGE HOST_RUNTIME
            HOST_LISTENER_CONTEXT)
        set(DTV_USERLAND_BUILD_${component} ON CACHE BOOL "" FORCE)
    endforeach()
    add_subdirectory("${DTV_USERLAND_CORE_DIR}" dtv-userland-core)
endif()

function(mirakc_add_card_adapter target output product_root)
    set(PRODUCT_USERLAND_SRC "${product_root}/userland")
    if(NOT IS_DIRECTORY "${PRODUCT_USERLAND_SRC}/include/px4")
        message(FATAL_ERROR "Invalid userland product source: ${product_root}")
    endif()
    add_executable(${target}
        px4_adapter.cpp
        px4_tune_plan.cpp
        b25_filter.cpp
        ccid_reader.c
        t1_state_machine.c
        b_cas_card_ccid.c
        arib25/arib_std_b25.c
        arib25/ts_section_parser.c
        arib25/multi2.cc
        ${PRODUCT_USERLAND_SRC}/src/pcsc_ifd_adapter.cpp
    )
    target_include_directories(${target} PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/arib25
        ${CMAKE_CURRENT_SOURCE_DIR}
        ${PRODUCT_USERLAND_SRC}/include
    )
    target_compile_features(${target} PRIVATE cxx_std_17)
    target_compile_options(${target} PRIVATE -fvisibility=hidden)
    target_link_options(${target} PRIVATE
        -Wl,-z,max-page-size=16384
        -Wl,-z,common-page-size=16384
        -Wl,-z,relro
        -Wl,-z,now
        -static-libstdc++
    )
    set_target_properties(${target} PROPERTIES OUTPUT_NAME "${output}")
    target_link_libraries(${target} dtv::control dtv::host_runtime log)
endfunction()

if(MIRAKC_BUILD_PX4_ADAPTER)
    if(NOT IS_DIRECTORY "${PX4_USERLAND_DIR}/userland/include")
        message(FATAL_ERROR "PX4_USERLAND_DIR must point to a px4-userland checkout")
    endif()
    mirakc_add_card_adapter(px4_adapter libmirakc-px4-adapter.so
        "${PX4_USERLAND_DIR}")
endif()

if(MIRAKC_BUILD_ASICEN_ADAPTER)
    if(NOT IS_DIRECTORY "${ASICEN_USERLAND_DIR}/third_party/px4-userland/userland/include")
        message(FATAL_ERROR "ASICEN_USERLAND_DIR must point to an asicen-userland checkout")
    endif()
    mirakc_add_card_adapter(asicen_adapter libmirakc-asicen-adapter.so
        "${ASICEN_USERLAND_DIR}/third_party/px4-userland")
    target_sources(asicen_adapter PRIVATE
        "${ASICEN_USERLAND_DIR}/third_party/px4-userland/userland/src/error.cpp")
    target_compile_definitions(asicen_adapter PRIVATE
        ASICEN_USE_DTV_CORE=1 ASICEN_PROFILE_ASICEN=1
        ASICEN_PCSC_DEVICE_PREFIX="asicen-userland:")
endif()

if(MIRAKC_BUILD_PIX_ADAPTER)
    add_executable(pix_adapter pix_adapter.cpp)
    target_include_directories(pix_adapter PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
    target_compile_features(pix_adapter PRIVATE cxx_std_17)
    target_compile_options(pix_adapter PRIVATE -fvisibility=hidden)
    target_link_options(pix_adapter PRIVATE
        -Wl,-z,max-page-size=16384
        -Wl,-z,common-page-size=16384
        -Wl,-z,relro
        -Wl,-z,now
        -static-libstdc++
    )
    set_target_properties(pix_adapter PROPERTIES
        OUTPUT_NAME "libmirakc-pix-adapter.so")
endif()

option(MIRAKC_BUILD_PX4_TUNE_PLAN_TESTS "Build host PX4 tune-plan tests" OFF)
if(MIRAKC_BUILD_PX4_TUNE_PLAN_TESTS)
    add_executable(px4_tune_plan_test
        px4_tune_plan.cpp
        tests/px4_tune_plan_test.cpp
    )
    target_compile_features(px4_tune_plan_test PRIVATE cxx_std_17)
    target_include_directories(px4_tune_plan_test PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
    add_executable(px4_card_retry_test tests/px4_card_retry_test.cpp)
    target_compile_features(px4_card_retry_test PRIVATE cxx_std_17)
    target_include_directories(px4_card_retry_test PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
endif()

if(MIRAKC_BUILD_PX4_RUNTIME_SOCKET_PATH_TESTS)
    add_executable(px4_runtime_socket_path_test
        tests/px4_runtime_socket_path_test.cpp)
    target_compile_features(px4_runtime_socket_path_test PRIVATE cxx_std_17)
    target_link_libraries(px4_runtime_socket_path_test PRIVATE dtv::host_runtime)
    if(EXISTS "${ASICEN_USERLAND_DIR}/third_party/px4-userland/userland/src/posix_ipc.cpp")
        target_sources(px4_runtime_socket_path_test PRIVATE
            "${ASICEN_USERLAND_DIR}/third_party/px4-userland/userland/src/posix_ipc.cpp"
            "${ASICEN_USERLAND_DIR}/third_party/px4-userland/userland/src/ipc.cpp"
            "${ASICEN_USERLAND_DIR}/third_party/px4-userland/userland/src/error.cpp")
        target_include_directories(px4_runtime_socket_path_test PRIVATE
            "${ASICEN_USERLAND_DIR}/third_party/px4-userland/userland/include"
            "${ASICEN_USERLAND_DIR}/userland/include")
        target_compile_definitions(px4_runtime_socket_path_test PRIVATE
            MIRAKC_HAS_ASICEN_LEGACY_PATH=1 ASICEN_PROFILE_ASICEN=1)
        target_link_libraries(px4_runtime_socket_path_test PRIVATE dtv::core dtv::wire)
    endif()
endif()

if(MIRAKC_BUILD_ADAPTER_CLI_TESTS)
    add_executable(adapter_cli_test tests/adapter_cli_test.cpp)
    target_compile_features(adapter_cli_test PRIVATE cxx_std_17)
    target_include_directories(adapter_cli_test PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
    target_link_libraries(adapter_cli_test PRIVATE dtv::core dtv::tuning)
endif()

if(MIRAKC_BUILD_USERLAND_CONCURRENCY_TESTS)
    add_executable(userland_concurrency_test
        tests/userland_concurrency_test.cpp)
    target_compile_features(userland_concurrency_test PRIVATE cxx_std_17)
    target_link_libraries(userland_concurrency_test PRIVATE
        dtv::host_workers dtv::host_tuner dtv::host_authority_owner)
    add_executable(userland_worker_resource_test
        tests/userland_worker_resource_test.cpp)
    target_compile_features(userland_worker_resource_test PRIVATE cxx_std_17)
    target_compile_definitions(userland_worker_resource_test PRIVATE
        PX4_CONTROL_WORKERS_TEST_ACCESS=1)
    target_link_libraries(userland_worker_resource_test PRIVATE
        dtv_userland_host_workers_test)
endif()

if(MIRAKC_BUILD_NATIVE_CARD_FIXTURE_TESTS)
    if(WIN32)
        message(FATAL_ERROR "Synthetic native card fixtures currently require POSIX sockets")
    endif()
    add_library(px4_card_fixture_client OBJECT
        tests/ifd_card_fixture_client.cpp
        "${PX4_USERLAND_DIR}/userland/src/pcsc_ifd_adapter.cpp")
    target_include_directories(px4_card_fixture_client PRIVATE
        "${PX4_USERLAND_DIR}/userland/include"
        "${DTV_USERLAND_CORE_DIR}/adapters/host_runtime/include"
        "${DTV_USERLAND_CORE_DIR}/adapters/host_daemon/include"
        "${DTV_USERLAND_CORE_DIR}/include")
    target_compile_features(px4_card_fixture_client PRIVATE cxx_std_17)
    add_executable(px4_card_fixture_test
        tests/ifd_card_fixture_test.cpp
        $<TARGET_OBJECTS:px4_card_fixture_client>)
    target_compile_features(px4_card_fixture_test PRIVATE cxx_std_17)
    target_link_libraries(px4_card_fixture_test PRIVATE dtv::host_listener_context)
    add_library(asicen_card_fixture_client OBJECT
        tests/ifd_card_fixture_client.cpp
        "${ASICEN_USERLAND_DIR}/third_party/px4-userland/userland/src/pcsc_ifd_adapter.cpp"
        "${ASICEN_USERLAND_DIR}/third_party/px4-userland/userland/src/error.cpp")
    target_include_directories(asicen_card_fixture_client PRIVATE
        "${ASICEN_USERLAND_DIR}/third_party/px4-userland/userland/include"
        "${DTV_USERLAND_CORE_DIR}/adapters/host_runtime/include"
        "${DTV_USERLAND_CORE_DIR}/adapters/host_daemon/include"
        "${DTV_USERLAND_CORE_DIR}/include")
    target_compile_features(asicen_card_fixture_client PRIVATE cxx_std_17)
    target_compile_definitions(asicen_card_fixture_client PRIVATE
        CARD_FIXTURE_ASICEN=1 ASICEN_USE_DTV_CORE=1 ASICEN_PROFILE_ASICEN=1
        ASICEN_PCSC_DEVICE_PREFIX="asicen-userland:")
    add_executable(asicen_card_fixture_test
        tests/ifd_card_fixture_test.cpp
        $<TARGET_OBJECTS:asicen_card_fixture_client>)
    target_compile_features(asicen_card_fixture_test PRIVATE cxx_std_17)
    target_compile_definitions(asicen_card_fixture_test PRIVATE CARD_FIXTURE_ASICEN=1)
    target_link_libraries(asicen_card_fixture_test PRIVATE dtv::host_listener_context)
endif()
