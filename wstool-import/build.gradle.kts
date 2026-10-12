import java.security.MessageDigest
import java.util.HexFormat

plugins {
  application
  `java-test-fixtures`
}

dependencies {
  testImplementation(project(":wstool-test-components"))
  implementation(platform(project(":")))
  implementation("info.picocli:picocli:4.7.6")
  implementation("com.fasterxml.jackson.core:jackson-databind:2.19.2")
  implementation("org.apache.maven:maven-resolver-provider:3.9.9")
  implementation("org.apache.maven.resolver:maven-resolver-connector-basic:1.9.22")
  implementation("org.apache.maven.resolver:maven-resolver-transport-file:1.9.22")
  implementation("org.apache.maven.resolver:maven-resolver-transport-http:1.9.22")
  implementation("org.apache.maven.resolver:maven-resolver-supplier:1.9.22")
  implementation("org.apache.maven.resolver:maven-resolver-impl:1.9.22")
  runtimeOnly("org.apache.logging.log4j:log4j-core")
  runtimeOnly("org.apache.logging.log4j:log4j-slf4j2-impl")

  testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
  testImplementation(project(":common"))
  testImplementation("org.pkl-lang:pkl-config-java:0.32.1")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.12.2")
}

application {
  applicationName = "wstool-import"
  mainClass = "wstool.importer.Main"
}

tasks.named<Jar>("jar") {
  manifest {
    attributes["Class-Path"] =
      configurations.runtimeClasspath
        .get()
        .files
        .filterNot { it.name == archiveFileName.get() }
        .joinToString(" ") { it.name }
    attributes["Implementation-Version"] = project.version.toString()
  }
}

tasks.named<JavaExec>("run") { systemProperty("wstool.version", project.version.toString()) }

tasks.named<ProcessResources>("processTestResources") {
  from(rootProject.file("packages/wstool-npm-import/wstool/bin/npm-wstool"))
}

tasks.register<Zip>("packageMavenWstoolImport") {
  group = "distribution"
  description = "Packages the shared maven-wstool-import build tool."
  from(rootProject.file("packages/maven-wstool-import")) { include("wstool.pkl") }
  from(rootProject.file("packages/maven-wstool-import/wstool/bin/maven-wstool-import")) {
    into("bin")
  }
  archiveBaseName.set("maven-wstool-import")
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
        name = "maven-wstool-import"
        version = "1.0.0"
        uri = "${archive.toURI()}"
        checksum = "$checksum"
      }
      """
          .trimIndent() + "\n"
      )
  }
}

tasks.register<Zip>("packageWstoolNpmImport") {
  group = "distribution"
  description = "Packages the shared wstool-npm-import build tool."
  from(rootProject.file("packages/wstool-npm-import")) {
    include("wstool.pkl", "wstool-npm.json", "wstool/bin/**")
  }
  archiveBaseName.set("wstool-npm-import")
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
        name = "wstool-npm-import"
        version = "1.0.0"
        uri = "${archive.toURI()}"
        checksum = "$checksum"
      }
      """
          .trimIndent() + "\n"
      )
  }
}

tasks.test { useJUnitPlatform() }
