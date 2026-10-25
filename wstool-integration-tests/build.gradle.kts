import org.gradle.api.tasks.SourceSetContainer

plugins { `java` }

dependencies {
  testImplementation(platform(project(":")))
  testImplementation(project(":wstool-cli"))
  testImplementation(project(":wstool-npm-import-cli"))
  testImplementation(project(":wstool-maven-import-cli"))
  testImplementation(testFixtures(project(":wstool-test-components")))
  testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
  testImplementation("org.apache.fory:fory-json:1.7.7")
  testImplementation("org.testcontainers:testcontainers")
  testImplementation("org.testcontainers:testcontainers-junit-jupiter")
  testImplementation("dev.parsick.testcontainers.gitserver:testcontainers-gitea:0.16.0")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.12.2")
}

tasks.test {
  useJUnitPlatform()
  inputs.dir(rootProject.file("packages/npm-wstool"))
  inputs.dir(rootProject.file("packages/wstool-npm-import"))
  inputs.dir(rootProject.file("packages/wstool-maven-import"))
  systemProperty("wstool.project-root", rootProject.projectDir.absolutePath)
  systemProperty(
    "wstool.integration-tests.output-directory",
    layout.buildDirectory.dir("integration-test-output").get().asFile.absolutePath,
  )
  systemProperty(
    "wstool.cli.classpath",
    project(":wstool-cli")
      .extensions
      .getByType<SourceSetContainer>()
      .named("main")
      .get()
      .runtimeClasspath
      .asPath,
  )
  systemProperty(
    "wstool.npm-import-cli.classpath",
    project(":wstool-npm-import-cli")
      .extensions
      .getByType<SourceSetContainer>()
      .named("main")
      .get()
      .runtimeClasspath
      .asPath,
  )
  systemProperty(
    "wstool.maven-import-cli.classpath",
    project(":wstool-maven-import-cli")
      .extensions
      .getByType<SourceSetContainer>()
      .named("main")
      .get()
      .runtimeClasspath
      .asPath,
  )
}
