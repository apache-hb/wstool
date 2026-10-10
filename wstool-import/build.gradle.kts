import java.security.MessageDigest
import java.util.HexFormat

plugins {
  application
  `java-test-fixtures`
}

group = rootProject.group

version = rootProject.version

java {
  sourceCompatibility = JavaVersion.VERSION_25
  targetCompatibility = JavaVersion.VERSION_25
}

dependencies {
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

tasks.named<ProcessResources>("processResources") {
  from(rootProject.file("packages/npm-wstool/wstool/bin/npm-wstool"))
}

tasks.register<Zip>("packageMavenWstool") {
  group = "distribution"
  description = "Packages the reusable maven-wstool build tool."
  from(rootProject.file("packages/maven-wstool")) { include("wstool.pkl", "bin/maven-wstool") }
  archiveBaseName.set("maven-wstool")
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
        name = "maven-wstool"
        version = "1.0.0"
        uri = "${archive.toURI()}"
        checksum = "$checksum"
      }
      """
          .trimIndent() + "\n"
      )
  }
}

tasks.register<Zip>("packageNpmWstool") {
  group = "distribution"
  description = "Packages the npm-wstool offline build tool."
  from(rootProject.file("packages/npm-wstool")) {
    include("wstool.pkl", "wstool-npm.json", "wstool/bin/**")
  }
  archiveBaseName.set("npm-wstool")
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
        name = "npm-wstool"
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
