plugins { application }

dependencies {
  testImplementation(project(":wstool-test-components"))
  implementation(platform(project(":")))
  implementation(project(":wstool-common"))
  implementation(project(":wstool-sysroot"))
  implementation("de.tu-dresden.inf.lat.jsexp:jsexp:0.2.2")
  implementation(project(":wstool-git"))
  implementation("info.picocli:picocli:4.7.6")
  implementation("com.google.dagger:dagger")
  annotationProcessor("com.google.dagger:dagger-compiler:2.60.1")
  implementation("software.amazon.awssdk:s3")
  implementation("software.amazon.awssdk:sts")
  runtimeOnly("org.apache.logging.log4j:log4j-core")
  runtimeOnly("org.apache.logging.log4j:log4j-slf4j2-impl")

  testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
  testImplementation("info.picocli:picocli:4.7.6")
  testImplementation("com.tngtech.archunit:archunit-junit5:1.5.0")
  testImplementation("org.testcontainers:testcontainers")
  testImplementation("org.testcontainers:testcontainers-junit-jupiter")
  testImplementation("io.github.le-shi:java-gitea-api:1.27.3")
  testImplementation("dev.parsick.testcontainers.gitserver:testcontainers-gitea:0.16.0")
  testImplementation("software.amazon.awssdk:s3")
  testImplementation("software.amazon.awssdk:sts")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.12.2")
}

application {
  applicationName = "wstool"
  mainClass = "wstool.cli.Main"
}

tasks.jar {
  manifest {
    attributes(
      mapOf(
        "Class-Path" to
          configurations.runtimeClasspath
            .get()
            .files
            .filterNot { it.name == archiveFileName.get() }
            .joinToString(" ") { it.name },
        "Implementation-Version" to project.version.toString(),
      )
    )
  }
}

tasks.named<JavaExec>("run") { systemProperty("wstool.version", project.version.toString()) }

tasks.test {
  useJUnitPlatform()
  systemProperty("wstool.cli.classpath", sourceSets.main.get().runtimeClasspath.asPath)
}
