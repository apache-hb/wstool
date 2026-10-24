import java.nio.file.Files
import java.nio.file.Path

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

tasks.register<Exec>("packageDeb") {
  group = "distribution"
  description = "Builds a Debian package containing wstool and its Java runtime."
  dependsOn(tasks.named("installDist"))

  val installDirectory = layout.buildDirectory.dir("install/wstool")
  val distributionDirectory = layout.buildDirectory.dir("distributions")
  val jpackageDirectory = layout.buildDirectory.dir("jpackage-deb")

  doFirst {
    jpackageDirectory.get().asFile.deleteRecursively()
    jpackageDirectory.get().asFile.mkdirs()
    distributionDirectory.get().asFile.mkdirs()
    commandLine(
      "jpackage",
      "--type",
      "deb",
      "--name",
      "wstool",
      "--app-version",
      project.version.toString(),
      "--vendor",
      "wstool",
      "--input",
      installDirectory.get().file("lib").asFile.absolutePath,
      "--main-jar",
      tasks.jar.get().archiveFileName.get(),
      "--main-class",
      "wstool.cli.Main",
      "--java-options",
      "--enable-native-access=ALL-UNNAMED",
      "--java-options",
      "--sun-misc-unsafe-memory-access=allow",
      "--dest",
      jpackageDirectory.get().asFile.absolutePath,
    )
  }

  doLast {
    fun runPackageCommand(vararg arguments: String) {
      val exitCode = ProcessBuilder(*arguments).inheritIO().start().waitFor()
      check(exitCode == 0) { "Command failed: ${arguments.joinToString(" ")}" }
    }

    val jpackageFile = jpackageDirectory.get().asFile.listFiles()!!.single { it.extension == "deb" }
    val packageRoot = layout.buildDirectory.dir("deb-package-root").get().asFile
    val controlRoot = layout.buildDirectory.dir("deb-package-control").get().asFile
    packageRoot.deleteRecursively()
    controlRoot.deleteRecursively()
    packageRoot.mkdirs()
    controlRoot.mkdirs()

    runPackageCommand("dpkg-deb", "--extract", jpackageFile.absolutePath, packageRoot.absolutePath)
    runPackageCommand("dpkg-deb", "--control", jpackageFile.absolutePath, controlRoot.absolutePath)

    val systemBin = packageRoot.resolve("usr/bin")
    systemBin.mkdirs()
    Files.createSymbolicLink(
      systemBin.resolve("wstool").toPath(),
      Path.of("/opt/wstool/bin/wstool"),
    )
    controlRoot.copyRecursively(packageRoot.resolve("DEBIAN"), overwrite = true)
    listOf("preinst", "postinst", "prerm", "postrm").forEach { script ->
      check(packageRoot.resolve("DEBIAN/$script").setExecutable(true, false))
    }

    runPackageCommand(
      "dpkg-deb",
      "--build",
      "--root-owner-group",
      packageRoot.absolutePath,
      distributionDirectory.get().file(jpackageFile.name).asFile.absolutePath,
    )
  }

  inputs.dir(installDirectory)
  inputs.property(
    "jpackageOptions",
    listOf(
      "deb",
      "wstool",
      project.version.toString(),
      "wstool.cli.Main",
      "--enable-native-access=ALL-UNNAMED",
      "--sun-misc-unsafe-memory-access=allow",
    ),
  )
  outputs.dir(jpackageDirectory)
  outputs.dir(distributionDirectory)
}

tasks.test {
  useJUnitPlatform()
  systemProperty("wstool.cli.classpath", sourceSets.main.get().runtimeClasspath.asPath)
}
