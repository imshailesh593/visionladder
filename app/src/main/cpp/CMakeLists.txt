cmake_minimum_required(VERSION 3.22.1)

project("laddervision")

add_library(
    laddervision
    SHARED
    ladder_vision.cpp
)

find_library(
    log-lib
    log
)

find_library(
    android-lib
    android
)

target_link_libraries(
    laddervision
    ${log-lib}
    ${android-lib}
)
