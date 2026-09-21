cmake_minimum_required(VERSION 3.20)
project(googletest LANGUAGES CXX)

add_library(gtest src/gtest.cc)
target_compile_features(gtest PUBLIC cxx_std_20)
target_include_directories(gtest PUBLIC
    $<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}/include>
    $<INSTALL_INTERFACE:include>)

add_library(gtest_main src/gtest_main.cc)
target_link_libraries(gtest_main PUBLIC gtest)
target_include_directories(gtest_main PUBLIC
    $<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}/include>
    $<INSTALL_INTERFACE:include>)

install(TARGETS gtest gtest_main ARCHIVE DESTINATION lib)
install(DIRECTORY include/ DESTINATION include)
