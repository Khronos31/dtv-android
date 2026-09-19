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
if(MIRAKC_BUILD_PX4_ADAPTER)
    set(PX4_USERLAND_DIR "" CACHE PATH "Pinned px4-userland checkout")
    if(NOT IS_DIRECTORY "${PX4_USERLAND_DIR}/userland/include")
        message(FATAL_ERROR "PX4_USERLAND_DIR must point to a px4-userland checkout")
    endif()
    set(PX4_USERLAND_SRC "${PX4_USERLAND_DIR}/userland")
    add_executable(px4_adapter
        px4_adapter.cpp
        px4_tune_plan.cpp
        b25_filter.cpp
        ccid_reader.c
        b_cas_card_ccid.c
        arib25/arib_std_b25.c
        arib25/ts_section_parser.c
        arib25/multi2.cc
        ${PX4_USERLAND_SRC}/src/control_client.cpp
        ${PX4_USERLAND_SRC}/src/error.cpp
        ${PX4_USERLAND_SRC}/src/ipc.cpp
        ${PX4_USERLAND_SRC}/src/pcsc_ifd_adapter.cpp
        ${PX4_USERLAND_SRC}/src/posix_ipc.cpp
    )
    target_include_directories(px4_adapter PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/arib25
        ${CMAKE_CURRENT_SOURCE_DIR}
        ${PX4_USERLAND_SRC}/include
    )
    target_compile_features(px4_adapter PRIVATE cxx_std_17)
    target_compile_options(px4_adapter PRIVATE -fvisibility=hidden)
    target_link_options(px4_adapter PRIVATE
        -Wl,-z,max-page-size=16384
        -Wl,-z,common-page-size=16384
        -Wl,-z,relro
        -Wl,-z,now
    )
    set_target_properties(px4_adapter PROPERTIES
        OUTPUT_NAME "libmirakc-px4-adapter.so"
    )
    target_link_libraries(px4_adapter log)
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

option(MIRAKC_BUILD_PX4_FWTOOL "Build the pinned PX4 firmware generator" OFF)
if(MIRAKC_BUILD_PX4_FWTOOL)
    set(PX4_DRV_DIR "" CACHE PATH "Pinned px4_drv checkout")
    if(NOT IS_DIRECTORY "${PX4_DRV_DIR}/fwtool")
        message(FATAL_ERROR "PX4_DRV_DIR must point to a px4_drv checkout")
    endif()
    add_executable(px4_fwtool
        ${PX4_DRV_DIR}/fwtool/fwtool.c
        ${PX4_DRV_DIR}/fwtool/tsv.c
        ${PX4_DRV_DIR}/fwtool/crc32.c
    )
    target_include_directories(px4_fwtool PRIVATE ${PX4_DRV_DIR}/fwtool)
    target_compile_options(px4_fwtool PRIVATE -fPIE)
    target_link_options(px4_fwtool PRIVATE
        -fPIE
        -pie
        -Wl,-z,max-page-size=16384
        -Wl,-z,common-page-size=16384
        -Wl,-z,relro
        -Wl,-z,now
    )
    set_target_properties(px4_fwtool PROPERTIES
        OUTPUT_NAME "libmirakc-px4-fwtool.so"
    )
endif()
