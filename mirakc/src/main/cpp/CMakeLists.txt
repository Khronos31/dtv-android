cmake_minimum_required(VERSION 3.22.1)
project(mirakc_usb_process)

add_library(usb_process SHARED usb_process.cpp)
target_compile_features(usb_process PRIVATE cxx_std_17)
target_link_libraries(usb_process log)
