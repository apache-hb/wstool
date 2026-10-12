import java.nio.file.Files
import java.nio.file.StandardCopyOption

plugins { application }

dependencies {
  testImplementation(project(":wstool-test-components"))
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
  val mavenToolPackage = project(":wstool-import").tasks.named<Zip>("packageMavenWstoolImport")
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

val packageInput = layout.buildDirectory.dir("jpackage-input")
val stagePackageInput =
  tasks.register<Sync>("stagePackageInput") {
    dependsOn(tasks.named("installDist"), project(":wstool-import").tasks.named("installDist"))
    from(layout.buildDirectory.dir("install/wstool/lib"))
    from(project(":wstool-import").layout.buildDirectory.dir("install/wstool-import")) {
      into("wstool-import")
    }
    into(packageInput)
  }
val importerLauncherProperties = layout.buildDirectory.file("jpackage/wstool-import.properties")
val writeImporterLauncherProperties =
  tasks.register("writeImporterLauncherProperties") {
    val propertiesFile = importerLauncherProperties
    inputs.property("releaseVersion", project.version.toString())
    outputs.file(propertiesFile)
    doLast {
      propertiesFile.get().asFile.apply {
        parentFile.mkdirs()
        writeText(
          "main-jar=wstool-import/lib/wstool-import-${project.version}.jar\n" +
            "main-class=wstool.importer.Main\n" +
            "java-options=-Dwstool.version=${project.version}\n"
        )
      }
    }
  }

fun registerJpackageTask(taskName: String, packageType: String) =
  tasks.register<Exec>(taskName) {
    group = "distribution"
    description = "Builds the Linux $packageType package using jpackage."
    dependsOn(stagePackageInput, writeImporterLauncherProperties)
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
      packageInput.get().asFile.absolutePath,
      "--main-jar",
      tasks.jar.get().archiveFileName.get(),
      "--main-class",
      application.mainClass.get(),
      "--java-options",
      "-Dwstool.version=${project.version}",
      "--add-launcher",
      "wstool-import=${importerLauncherProperties.get().asFile.absolutePath}",
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

    if (packageType == "deb") {
      doLast {
        val packageFile =
          packageDirectory.get().file("wstool_${project.version}-1_amd64.deb").asFile
        val packageRoot = temporaryDir.resolve("deb-package")
        packageRoot.deleteRecursively()

        fun runPackagingCommand(vararg arguments: String) {
          val process = ProcessBuilder(*arguments).redirectErrorStream(true).start()
          val output = process.inputStream.bufferedReader().use { it.readText() }
          val exitCode = process.waitFor()
          check(exitCode == 0) {
            "Command ${arguments.joinToString(" ")} failed with exit code $exitCode:\n$output"
          }
        }

        runPackagingCommand(
          "dpkg-deb",
          "--raw-extract",
          packageFile.absolutePath,
          packageRoot.absolutePath,
        )

        val controlFile = packageRoot.resolve("DEBIAN/control")
        val control = controlFile.readText()
        val dependsField = Regex("(?m)^Depends: ([^\\n]*)$")
        val dependsMatch =
          dependsField.find(control)
            ?: error("Generated Debian package has no Depends field: $controlFile")
        val alsaDependency = Regex("(?<![A-Za-z0-9+.-])libasound2(?:t64)?(?![A-Za-z0-9+.-])")
        check(alsaDependency.containsMatchIn(dependsMatch.groupValues[1])) {
          "Generated Debian package does not declare an ALSA library dependency: $controlFile"
        }
        val compatibleDepends =
          dependsMatch.groupValues[1].replace(alsaDependency, "libasound2 | libasound2t64")
        controlFile.writeText(
          control.replaceRange(dependsMatch.range, "Depends: $compatibleDepends")
        )

        val rebuiltPackage = packageFile.resolveSibling("${packageFile.name}.tmp")
        runPackagingCommand(
          "dpkg-deb",
          "--build",
          "--root-owner-group",
          packageRoot.absolutePath,
          rebuiltPackage.absolutePath,
        )
        Files.move(
          rebuiltPackage.toPath(),
          packageFile.toPath(),
          StandardCopyOption.REPLACE_EXISTING,
        )
      }
    }
  }

registerJpackageTask("packageDeb", "deb")

registerJpackageTask("packageRpm", "rpm")

tasks.register("packageLinux") {
  group = "distribution"
  description = "Builds both Debian and RPM packages using jpackage."
  dependsOn("packageDeb", "packageRpm")
}

tasks.named("assemble") { dependsOn(tasks.named("packageCli")) }
