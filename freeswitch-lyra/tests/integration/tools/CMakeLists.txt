# Integration-test helper tools. wav_check is pure; lyra_gen_frames links the archive.
cmake_minimum_required(VERSION 3.16)
project(fslyra_integration_tools LANGUAGES CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

list(APPEND CMAKE_MODULE_PATH "${CMAKE_CURRENT_LIST_DIR}/../../../cmake")

add_executable(wav_check wav_check.cpp)

find_package(LyraArchive REQUIRED)
add_executable(lyra_gen_frames lyra_gen_frames.cpp
  "${CMAKE_CURRENT_LIST_DIR}/../../../src/mod_lyra/lyra_adapter.cpp")
target_include_directories(lyra_gen_frames PRIVATE "${CMAKE_CURRENT_LIST_DIR}/../../../src")
target_link_libraries(lyra_gen_frames PRIVATE Lyra::Lyra)
