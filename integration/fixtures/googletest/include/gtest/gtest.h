#pragma once

#include <cstdlib>
#include <iostream>
#include <string>
#include <string_view>
#include <vector>

namespace testing {
using TestFunction = void (*)();
void RegisterTest(std::string_view name, TestFunction function);
int RunAllTests();
} // namespace testing

#define WSTOOL_JOIN_IMPL(a, b) a##b
#define WSTOOL_JOIN(a, b) WSTOOL_JOIN_IMPL(a, b)
#define TEST(suite, name)                                                      \
  static void WSTOOL_JOIN(wstool_test_, __LINE__)();                           \
  namespace {                                                                  \
  const bool WSTOOL_JOIN(wstool_registered_, __LINE__) =                       \
      (testing::RegisterTest(#suite "." #name,                                 \
                             &WSTOOL_JOIN(wstool_test_, __LINE__)),            \
       true);                                                                  \
  }                                                                            \
  static void WSTOOL_JOIN(wstool_test_, __LINE__)()

#define EXPECT_EQ(expected, actual)                                            \
  do {                                                                         \
    if ((expected) != (actual)) {                                              \
      std::cerr << "EXPECT_EQ failed at " << __FILE__ << ":" << __LINE__       \
                << "\n";                                                       \
      std::exit(1);                                                            \
    }                                                                          \
  } while (false)
