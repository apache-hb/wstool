#include "message.h"

#include <absl/strings/str_cat.h>
#include <gnu/libc-version.h>

std::string message() {
  return absl::StrCat("wstool/", gnu_get_libc_version());
}
