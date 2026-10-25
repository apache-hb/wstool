plugins {
  application
  id("wstool.deb-package")
}

dependencies {
  implementation(platform(project(":")))
  implementation(project(":wstool-maven-import"))
  implementation("info.picocli:picocli:4.7.6")
  implementation("com.google.dagger:dagger")
  annotationProcessor("com.google.dagger:dagger-compiler:2.60.1")
  implementation("org.apache.logging.log4j:log4j-api")
  runtimeOnly("org.apache.logging.log4j:log4j-core")

  testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
  testImplementation(testFixtures(project(":wstool-test-components")))
  testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.12.2")
}

application {
  applicationName = "wstool-maven-import"
  mainClass = "wstool.maven.importer.cli.MavenImportMain"
}

tasks.test {
  useJUnitPlatform()
  systemProperty("wstool.maven-import-cli.classpath", sourceSets.main.get().runtimeClasspath.asPath)
}
