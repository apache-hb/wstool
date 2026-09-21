#include "absl/strings/str_cat.h"

namespace absl {
std::string StrCat(std::string_view first, std::string_view second) {
  return std::string(first) + std::string(second);
}
} // namespace absl
