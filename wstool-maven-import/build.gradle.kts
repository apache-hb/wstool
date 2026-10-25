import org.gradle.api.tasks.bundling.Compression
import org.gradle.api.tasks.bundling.Tar

plugins { `java-library` }

dependencies {
  implementation(platform(project(":")))
  implementation("org.apache.velocity:velocity-engine-core:2.4.1")
  api("org.apache.maven.resolver:maven-resolver-api:1.9.22")
  implementation("org.apache.maven.resolver:maven-resolver-util:1.9.22")
  implementation("org.apache.maven:maven-resolver-provider:3.9.9")
  api("org.apache.maven.resolver:maven-resolver-supplier:1.9.22")
  implementation("org.apache.maven.resolver:maven-resolver-connector-basic:1.9.22")
  implementation("org.apache.maven.resolver:maven-resolver-transport-http:1.9.22")
  implementation("javax.inject:javax.inject:1")
  implementation("org.apache.logging.log4j:log4j-api")
  runtimeOnly("org.apache.logging.log4j:log4j-core")

  testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
  testImplementation(testFixtures(project(":wstool-test-components")))
  testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.12.2")
}

tasks.test { useJUnitPlatform() }

tasks.register<Tar>("packageWstoolMavenImport") {
  group = "distribution"
  description = "Packages the wstool-maven-import wstool package as a tarball."
  from(rootProject.file("packages/wstool-maven-import")) {
    exclude("wstool/bin/maven-wstool-import")
  }
  from(rootProject.file("packages/wstool-maven-import/wstool/bin/maven-wstool-import")) {
    into("wstool/bin")
    filePermissions { unix("rwxr-xr-x") }
  }
  archiveBaseName.set("wstool-maven-import")
  archiveVersion.set("1.0.0")
  archiveExtension.set("tar.gz")
  compression = Compression.GZIP
  destinationDirectory.set(layout.buildDirectory.dir("distributions"))
  isPreserveFileTimestamps = false
  isReproducibleFileOrder = true
}
