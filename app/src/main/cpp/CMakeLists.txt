cmake_minimum_required(VERSION 3.22.1)
project(node_bridge)

set(CMAKE_CXX_STANDARD 17)

# libnode.so is placed into jniLibs/<abi>/ by the CI workflow before the build.
add_library(libnode SHARED IMPORTED)
set_target_properties(libnode PROPERTIES
    IMPORTED_LOCATION ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libnode.so)

add_library(node_bridge SHARED node_bridge.cpp)

find_library(log-lib log)
target_link_libraries(node_bridge libnode ${log-lib})
