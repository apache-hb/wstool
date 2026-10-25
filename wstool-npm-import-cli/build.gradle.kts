plugins {
  application
  id("wstool.deb-package")
}

dependencies {
  implementation(platform(project(":")))
  implementation(project(":wstool-npm-import"))
  implementation("commons-io:commons-io:2.20.0")
  implementation("info.picocli:picocli:4.7.6")
  implementation("org.apache.logging.log4j:log4j-api")
  runtimeOnly("org.apache.logging.log4j:log4j-core")

  testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
  testImplementation(testFixtures(project(":wstool-test-components")))
  testImplementation("org.testcontainers:testcontainers")
  testImplementation("org.testcontainers:testcontainers-junit-jupiter")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.12.2")
}

application {
  applicationName = "wstool-npm-import"
  mainClass = "wstool.npm.importer.cli.NpmImportMain"
}

tasks.test {
  useJUnitPlatform()
  systemProperty("wstool.npm-import-cli.classpath", sourceSets.main.get().runtimeClasspath.asPath)
}
