cmake_minimum_required(VERSION 3.20)
project(abseil LANGUAGES CXX)

add_library(absl_strings src/str_cat.cc)
target_compile_features(absl_strings PUBLIC cxx_std_20)
target_include_directories(absl_strings PUBLIC
    $<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}/include>
    $<INSTALL_INTERFACE:include>)

install(TARGETS absl_strings ARCHIVE DESTINATION lib)
install(DIRECTORY include/ DESTINATION include)
