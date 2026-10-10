plugins { application }

group = rootProject.group

version = rootProject.version

java {
  sourceCompatibility = JavaVersion.VERSION_25
  targetCompatibility = JavaVersion.VERSION_25
}

dependencies {
  implementation(platform(project(":")))
  implementation(project(":common"))
  implementation("info.picocli:picocli:4.7.6")
  implementation("com.google.inject:guice")
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
  val cliPackage = tasks.named<Zip>("packageCli")
  val gradlePackage = project(":wstool-gradle-plugin").tasks.named<Zip>("packageGradle")
  val gradleRunnerPackage = project(":wstool-gradle-plugin").tasks.named<Zip>("packageWstoolGradle")
  val mavenToolPackage = project(":wstool-import").tasks.named<Zip>("packageMavenWstool")
  dependsOn(cliPackage)
  dependsOn(gradlePackage, gradleRunnerPackage, mavenToolPackage)
  environment("WSTOOL_CLI_ARCHIVE", cliPackage.get().archiveFile.get().asFile.toURI().toString())
  environment("WSTOOL_GRADLE_PACKAGE", gradlePackage.get().archiveFile.get().asFile.absolutePath)
  environment(
    "WSTOOL_GRADLE_RUNNER_PACKAGE",
    gradleRunnerPackage.get().archiveFile.get().asFile.absolutePath,
  )
  environment(
    "WSTOOL_MAVEN_TOOL_PACKAGE",
    mavenToolPackage.get().archiveFile.get().asFile.absolutePath,
  )
}

tasks.register<Zip>("packageCli") {
  group = "distribution"
  description = "Packages the wstool launcher and runtime dependencies as a workspace package."
  dependsOn(tasks.named("installDist"))
  from(layout.buildDirectory.dir("install/wstool"))
  from("src/package")
  archiveBaseName.set("wstool-cli")
  archiveVersion.set(project.version.toString())
  destinationDirectory.set(layout.buildDirectory.dir("distributions"))
}

fun registerJpackageTask(taskName: String, packageType: String) =
  tasks.register<Exec>(taskName) {
    group = "distribution"
    description = "Builds the Linux $packageType package using jpackage."
    dependsOn(tasks.named("installDist"))
    val packageDirectory = layout.buildDirectory.dir("distributions")
    doFirst { packageDirectory.get().asFile.mkdirs() }
    commandLine(
      file("${System.getProperty("java.home")}/bin/jpackage"),
      "--type",
      packageType,
      "--name",
      "wstool",
      "--app-version",
      project.version.toString(),
      "--input",
      layout.buildDirectory.dir("install/wstool/lib").get().asFile.absolutePath,
      "--main-jar",
      tasks.jar.get().archiveFileName.get(),
      "--main-class",
      application.mainClass.get(),
      "--java-options",
      "-Dwstool.version=${project.version}",
      "--dest",
      packageDirectory.get().asFile.absolutePath,
      "--resource-dir",
      layout.projectDirectory.dir("src/jpackage").asFile.absolutePath,
      "--linux-package-name",
      "wstool",
      "--linux-app-release",
      "1",
      "--linux-menu-group",
      "Development",
    )
  }

registerJpackageTask("packageDeb", "deb")

registerJpackageTask("packageRpm", "rpm")

tasks.register("packageLinux") {
  group = "distribution"
  description = "Builds both Debian and RPM packages using jpackage."
  dependsOn("packageDeb", "packageRpm")
}

tasks.named("assemble") { dependsOn(tasks.named("packageCli")) }
