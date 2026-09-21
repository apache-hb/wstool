#pragma once

#include <string>
#include <string_view>

namespace absl {
std::string StrCat(std::string_view first, std::string_view second);
}
