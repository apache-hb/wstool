import java.nio.file.Files

plugins {
  id("java-platform")
  id("com.diffplug.spotless") version "8.10.2"
}

group = "com.apachehb.wstool"

version = providers.gradleProperty("releaseVersion").orElse("0.1.0").get()

javaPlatform { allowDependencies() }

dependencies {
  api(platform("software.amazon.awssdk:bom:2.31.74"))
  api(platform("org.apache.logging.log4j:log4j-bom:2.25.1"))
  api(platform("io.opentelemetry:opentelemetry-bom:1.51.0"))
  api(platform("org.pkl-lang:pkl-bom:0.32.1"))
  api(platform("org.testcontainers:testcontainers-bom:2.0.5"))

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

val textSourceFiles =
  rootDir
    .walkTopDown()
    .onEnter { file ->
      !Files.isSymbolicLink(file.toPath()) && file.name !in setOf("build", ".gradle")
    }
    .filter { file ->
      file.isFile &&
        (file.extension in setOf("md", "xml") || file.name in setOf(".gitattributes", ".gitignore"))
    }
    .toList()

tasks.register<JavaExec>("pklFormat") {
  group = "formatting"
  classpath = pklTools
  mainClass.set("org.pkl.cli.Main")
  jvmArgs("--enable-native-access=ALL-UNNAMED")
  args("format", "--write")
  args(pklSourceFiles.files.map(File::getAbsolutePath))
  inputs.files(pklSourceFiles)
}

tasks.register<JavaExec>("pklFormatCheck") {
  group = "verification"
  classpath = pklTools
  mainClass.set("org.pkl.cli.Main")
  jvmArgs("--enable-native-access=ALL-UNNAMED")
  args("format", "--diff-name-only")
  args(pklSourceFiles.files.map(File::getAbsolutePath))
  inputs.files(pklSourceFiles)
}

tasks.register("formatGradle") {
  group = "formatting"
  dependsOn("spotlessKotlinGradleApply")
}

tasks.register("format") {
  group = "formatting"
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

  format("text") {
    target(project.files(textSourceFiles))
    trimTrailingWhitespace()
    endWithNewline()
  }
}

tasks.named("check") { dependsOn("spotlessCheck", "pklFormatCheck") }
