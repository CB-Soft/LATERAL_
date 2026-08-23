cmake_minimum_required(VERSION 3.22.1)
project(lateral_beast_control)

# The VITURE SDK is already provisioned locally for uxspace.  Reuse its proprietary
# binary at build/package time rather than copying it into this source tree.
set(VITURE_SDK_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../uxspace/Android/glasses/src/main")

add_library(glasses SHARED IMPORTED)
set_target_properties(glasses PROPERTIES
    IMPORTED_LOCATION "${VITURE_SDK_ROOT}/jniLibs/${ANDROID_ABI}/libglasses.so")

add_library(lateral_beast_control SHARED beast_display_control.cpp)
target_include_directories(lateral_beast_control PRIVATE "${VITURE_SDK_ROOT}/cpp/include")
target_link_libraries(lateral_beast_control glasses android log)
