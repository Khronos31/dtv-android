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
target_link_libraries(usb_process log)
