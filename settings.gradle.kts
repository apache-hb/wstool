pluginManagement {
  repositories {
    gradlePluginPortal()
    mavenCentral()
  }
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories { mavenCentral() }
}

rootProject.name = "wstool-platform"

include(
  ":wstool-cli",
  ":wstool-npm-import",
  ":wstool-npm-import-cli",
  ":wstool-maven-import",
  ":wstool-maven-import-cli",
  ":wstool-integration-tests",
  ":wstool-common",
  ":wstool-config",
  ":wstool-sysroot",
  ":wstool-git",
  ":wstool-test-components",
)
