#include "gtest/gtest.h"

namespace testing {
namespace {
struct RegisteredTest {
  std::string name;
  TestFunction function;
};
std::vector<RegisteredTest> &tests() {
  static std::vector<RegisteredTest> value;
  return value;
}
} // namespace

void RegisterTest(std::string_view name, TestFunction function) {
  tests().push_back({std::string(name), function});
}

int RunAllTests() {
  for (const auto &test : tests()) {
    std::cout << "[ RUN      ] " << test.name << "\n";
    test.function();
    std::cout << "[       OK ] " << test.name << "\n";
  }
  return 0;
}
} // namespace testing
