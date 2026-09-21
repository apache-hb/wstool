cmake_minimum_required(VERSION 3.20)
project(sample_app LANGUAGES CXX)
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(absl CONFIG REQUIRED)
find_package(GTest CONFIG REQUIRED)

add_library(sample_core src/message.cc)
target_include_directories(sample_core PUBLIC src)
target_link_libraries(sample_core PUBLIC absl::strings)

add_executable(sample-app src/main.cc)
target_link_libraries(sample-app PRIVATE sample_core)

add_executable(sample-app-test tests/message_test.cc)
target_link_libraries(sample-app-test PRIVATE sample_core GTest::gtest_main GTest::gtest)

enable_testing()
add_test(NAME sample-app-test COMMAND sample-app-test)

install(TARGETS sample-app RUNTIME DESTINATION bin)
