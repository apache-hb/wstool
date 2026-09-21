#include "message.h"

#include <gtest/gtest.h>

TEST(SampleApp, UsesAbseilAndGlibc) {
  EXPECT_EQ("wstool/", message().substr(0, 7));
}
