import java.security.MessageDigest
import java.util.HexFormat

plugins { `java-gradle-plugin` }

gradlePlugin {
  plugins {
    create("wstoolRepositories") {
      id = "wstool.repositories"
      implementationClass = "wstool.gradle.RepositoriesPlugin"
      displayName = "wstool local Maven repositories"
      description = "Resolves Gradle dependencies from the wstool Maven sysroot."
    }
  }
}

dependencies {
  testImplementation(project(":wstool-test-components"))
  testImplementation(gradleTestKit())
  testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.12.2")
}

tasks.test { useJUnitPlatform() }

val bundledGradle = requireNotNull(gradle.gradleHomeDir)

tasks.register<Zip>("packageGradle") {
  group = "distribution"
  description = "Packages the Gradle distribution as a separate wstool package."
  from(rootProject.file("packages/gradle/wstool.pkl"))
  from(bundledGradle) {
    include("bin/**", "lib/**", "LICENSE", "NOTICE")
    into("share/gradle")
  }
  archiveBaseName.set("gradle")
  archiveVersion.set(gradle.gradleVersion)
  destinationDirectory.set(layout.buildDirectory.dir("distributions"))
  doLast {
    val archive = archiveFile.get().asFile
    val digest = MessageDigest.getInstance("SHA-256")
    val checksum = HexFormat.of().formatHex(digest.digest(archive.readBytes()))
    archive.resolveSibling("${archive.name}.sha256").writeText("$checksum\n")
    archive
      .resolveSibling("${archive.name}.pkl")
      .writeText(
        """
      new Archive {
        name = "gradle"
        version = "${gradle.gradleVersion}"
        uri = "${archive.toURI()}"
        checksum = "$checksum"
      }
      """
          .trimIndent() + "\n"
      )
  }
}

tasks.register<Zip>("packageWstoolGradle") {
  group = "distribution"
  description = "Packages the wstool-gradle build tool and repository plugin."
  dependsOn(tasks.named("jar"))
  from(rootProject.file("packages/wstool-gradle")) { include("wstool.pkl", "bin/wstool-gradle") }
  from(rootProject.file("packages/wstool-gradle/init.gradle")) { into("share/wstool-gradle") }
  from(tasks.named<Jar>("jar")) {
    into("share/wstool-gradle")
    rename { "wstool-gradle-plugin.jar" }
  }
  archiveBaseName.set("wstool-gradle")
  archiveVersion.set("1.0.0")
  destinationDirectory.set(layout.buildDirectory.dir("distributions"))
  doLast {
    val archive = archiveFile.get().asFile
    val digest = MessageDigest.getInstance("SHA-256")
    val checksum = HexFormat.of().formatHex(digest.digest(archive.readBytes()))
    archive.resolveSibling("${archive.name}.sha256").writeText("$checksum\n")
    archive
      .resolveSibling("${archive.name}.pkl")
      .writeText(
        """
      new Archive {
        name = "wstool-gradle"
        version = "1.0.0"
        uri = "${archive.toURI()}"
        checksum = "$checksum"
      }
      """
          .trimIndent() + "\n"
      )
  }
}
