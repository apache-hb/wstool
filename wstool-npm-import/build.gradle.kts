import org.gradle.api.tasks.bundling.Compression
import org.gradle.api.tasks.bundling.Tar

plugins { `java-library` }

dependencies {
  implementation(platform(project(":")))
  implementation("org.apache.velocity:velocity-engine-core:2.4.1")
  implementation("org.apache.fory:fory-json:1.7.7")
  implementation("org.apache.logging.log4j:log4j-api")
  runtimeOnly("org.apache.logging.log4j:log4j-core")

  testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
  testImplementation(testFixtures(project(":wstool-test-components")))
  testImplementation("org.testcontainers:testcontainers")
  testImplementation("org.testcontainers:testcontainers-junit-jupiter")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.12.2")
}

tasks.test { useJUnitPlatform() }

tasks.register<Tar>("packageNpmWstool") {
  group = "distribution"
  description = "Packages the npm-wstool source builder as a tarball."
  from(rootProject.file("packages/npm-wstool")) { exclude("wstool/bin/npm-wstool") }
  from(rootProject.file("packages/npm-wstool/wstool/bin/npm-wstool")) {
    into("wstool/bin")
    filePermissions { unix("rwxr-xr-x") }
  }
  archiveBaseName.set("npm-wstool")
  archiveVersion.set("1.0.0")
  archiveExtension.set("tar.gz")
  compression = Compression.GZIP
  destinationDirectory.set(layout.buildDirectory.dir("distributions"))
  isPreserveFileTimestamps = false
  isReproducibleFileOrder = true
}

tasks.register<Tar>("packageWstoolNpmImport") {
  group = "distribution"
  description = "Packages the wstool-npm-import imported-package installer as a tarball."
  from(rootProject.file("packages/wstool-npm-import")) { exclude("wstool/bin/wstool-npm-import") }
  from(rootProject.file("packages/wstool-npm-import/wstool/bin/wstool-npm-import")) {
    into("wstool/bin")
    filePermissions { unix("rwxr-xr-x") }
  }
  archiveBaseName.set("wstool-npm-import")
  archiveVersion.set("1.0.0")
  archiveExtension.set("tar.gz")
  compression = Compression.GZIP
  destinationDirectory.set(layout.buildDirectory.dir("distributions"))
  isPreserveFileTimestamps = false
  isReproducibleFileOrder = true
}
