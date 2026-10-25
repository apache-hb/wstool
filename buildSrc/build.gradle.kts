plugins { `kotlin-dsl` }

repositories { mavenCentral() }

gradlePlugin {
  plugins {
    create("debPackage") {
      id = "wstool.deb-package"
      implementationClass = "wstool.gradle.DebPackagePlugin"
    }
  }
}
