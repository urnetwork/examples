# Builds the C++ embed example and its self-test, which links neither the SDK
# library nor libcurl, with the SDK package's urnetwork::sdk target,
# nlohmann/json and CURL.
cmake_minimum_required(VERSION 3.24)
project(urnetwork_cpp_embed LANGUAGES CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
find_package(urnetwork-sdk CONFIG REQUIRED)
find_package(nlohmann_json 3.11 CONFIG REQUIRED)
find_package(CURL REQUIRED)
find_package(Threads REQUIRED)
add_executable(embed main.cpp)
target_link_libraries(embed PRIVATE urnetwork::sdk nlohmann_json::nlohmann_json CURL::libcurl Threads::Threads)
# the self-test without the sdk library or libcurl: only the sdk header's data
# types and constants
add_executable(embed-self-test selftest_main.cpp)
target_include_directories(embed-self-test SYSTEM PRIVATE $<TARGET_PROPERTY:urnetwork::sdk,INTERFACE_INCLUDE_DIRECTORIES>)
target_link_libraries(embed-self-test PRIVATE nlohmann_json::nlohmann_json Threads::Threads)
enable_testing()
add_test(NAME embed-self-test COMMAND embed-self-test)
if(MSVC)
  target_compile_options(embed PRIVATE /utf-8 /bigobj)
  target_compile_options(embed-self-test PRIVATE /utf-8 /bigobj)
endif()
if(WIN32)
  add_custom_command(TARGET embed POST_BUILD COMMAND ${CMAKE_COMMAND} -E copy_if_different $<TARGET_FILE:urnetwork::sdk> $<TARGET_FILE_DIR:embed>)
endif()
