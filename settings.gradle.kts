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
  ":cli",
  ":common",
  ":config",
  ":wstool-import",
  ":wstool-git",
  ":wstool-gradle-plugin",
  ":wstool-test-components",
  ":integration-tests",
)
