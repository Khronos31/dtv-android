cmake_minimum_required(VERSION 3.22.1)
project(mirakc_usb_process)

add_library(usb_process SHARED
    usb_process.cpp
    b25_filter.cpp
    ccid_reader.c
    b_cas_card_ccid.c
    arib25/arib_std_b25.c
    arib25/ts_section_parser.c
    arib25/multi2.cc
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
endif()
