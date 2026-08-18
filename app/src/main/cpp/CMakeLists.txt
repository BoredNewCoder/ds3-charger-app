cmake_minimum_required(VERSION 3.22.1)
project("ds3trigger")

add_library(ds3trigger SHARED
    ds3_trigger_uinput.c
)

target_link_libraries(ds3trigger
    log
)
