# Built only when the FMOD Engine SDK is unpacked into third_party/fmod (see docs/AUDIO.md).
cmake_minimum_required(VERSION 3.22.1)
project(ghostsignal_fmod CXX)

set(FMOD_DIR "" CACHE PATH "Root of the FMOD Engine Android SDK (contains api/core)")

add_library(fmod SHARED IMPORTED)
set_target_properties(fmod PROPERTIES IMPORTED_LOCATION ${FMOD_DIR}/api/core/lib/${ANDROID_ABI}/libfmod.so)

add_library(ghostsignal_fmod SHARED fmod_bridge.cpp)
target_include_directories(ghostsignal_fmod PRIVATE ${FMOD_DIR}/api/core/inc)
target_compile_features(ghostsignal_fmod PRIVATE cxx_std_17)
target_link_libraries(ghostsignal_fmod fmod log)
