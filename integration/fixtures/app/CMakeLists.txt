cmake_minimum_required(VERSION 3.20)
project(sample_app LANGUAGES CXX)
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_path(ABSEIL_INCLUDE_DIR absl/strings/str_cat.h PATHS "${WSTOOL_SYSROOT}/include" NO_DEFAULT_PATH REQUIRED)
find_library(ABSEIL_LIBRARY NAMES absl_strings PATHS "${WSTOOL_SYSROOT}/lib" NO_DEFAULT_PATH REQUIRED)
find_path(GTEST_INCLUDE_DIR gtest/gtest.h PATHS "${WSTOOL_SYSROOT}/include" NO_DEFAULT_PATH REQUIRED)
find_library(GTEST_MAIN_LIBRARY NAMES gtest_main PATHS "${WSTOOL_SYSROOT}/lib" NO_DEFAULT_PATH REQUIRED)
find_library(GTEST_LIBRARY NAMES gtest PATHS "${WSTOOL_SYSROOT}/lib" NO_DEFAULT_PATH REQUIRED)

add_library(sample_core src/message.cc)
target_include_directories(sample_core PUBLIC src "${ABSEIL_INCLUDE_DIR}")
target_link_libraries(sample_core PUBLIC "${ABSEIL_LIBRARY}")

add_executable(sample-app src/main.cc)
target_link_libraries(sample-app PRIVATE sample_core)

add_executable(sample-app-test tests/message_test.cc)
target_include_directories(sample-app-test PRIVATE "${GTEST_INCLUDE_DIR}")
target_link_libraries(sample-app-test PRIVATE sample_core "${GTEST_MAIN_LIBRARY}" "${GTEST_LIBRARY}")

enable_testing()
add_test(NAME sample-app-test COMMAND sample-app-test)

install(TARGETS sample-app RUNTIME DESTINATION bin)
