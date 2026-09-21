plugins {
  id("java-platform")
  id("com.diffplug.spotless") version "8.10.2"
}

group = "com.apachehb.wstool"

version = "0.1.0"

javaPlatform { allowDependencies() }

dependencies {
  api(platform("software.amazon.awssdk:bom:2.31.74"))
  api(platform("org.apache.logging.log4j:log4j-bom:2.25.1"))
  api(platform("io.opentelemetry:opentelemetry-bom:1.51.0"))
  api(platform("org.pkl-lang:pkl-bom:0.32.1"))

  api("com.google.inject:guice:7.0.0")
  api("org.apache.logging.log4j:log4j-api")
  api("org.apache.logging.log4j:log4j-core")
}

val pklTools = configurations.create("pklTools")

dependencies { pklTools("org.pkl-lang:pkl-tools:0.32.1") }

val pklSourceFiles =
  fileTree(rootDir) {
    include("**/*.pkl", "**/PklProject")
    exclude("**/build/**", ".gradle/**")
  }

tasks.register<JavaExec>("pklFormat") {
  group = "formatting"
  description = "Formats Pkl source files with the native Pkl formatter."
  classpath = pklTools
  mainClass.set("org.pkl.cli.Main")
  jvmArgs("--enable-native-access=ALL-UNNAMED")
  args("format", "--write")
  args(pklSourceFiles.files.map(File::getAbsolutePath))
  inputs.files(pklSourceFiles)
}

tasks.register<JavaExec>("pklFormatCheck") {
  group = "verification"
  description = "Checks Pkl source files with the native Pkl formatter."
  classpath = pklTools
  mainClass.set("org.pkl.cli.Main")
  jvmArgs("--enable-native-access=ALL-UNNAMED")
  args("format", "--diff-name-only")
  args(pklSourceFiles.files.map(File::getAbsolutePath))
  inputs.files(pklSourceFiles)
}

tasks.register("formatGradle") {
  group = "formatting"
  description = "Formats Gradle build files with Spotless."
  dependsOn("spotlessKotlinGradleApply")
}

tasks.register("format") {
  group = "formatting"
  description = "Formats all project source files with their native formatters."
  dependsOn("spotlessApply", "pklFormat")
}

spotless {
  java {
    target("**/src/**/*.java")
    googleJavaFormat("1.30.0").aosp()
    removeUnusedImports()
    endWithNewline()
  }

  kotlinGradle {
    target("*.gradle.kts", "**/*.gradle.kts")
    ktfmt("0.61").googleStyle()
    endWithNewline()
  }

  cpp {
    target("integration/**/*.cc", "integration/**/*.h")
    clangFormat("*").style("LLVM")
    endWithNewline()
  }

  format("text") {
    target("**/*.md", "**/*.xml", ".gitattributes", ".gitignore")
    targetExclude("**/build/**", ".gradle/**")
    trimTrailingWhitespace()
    endWithNewline()
  }
}

tasks.named("check") { dependsOn("spotlessCheck", "pklFormatCheck") }
