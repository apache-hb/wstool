plugins { java }

group = rootProject.group

version = rootProject.version

java {
  sourceCompatibility = JavaVersion.VERSION_25
  targetCompatibility = JavaVersion.VERSION_25
}

dependencies {
  testImplementation(platform(project(":")))
  testImplementation(project(":cli"))
  testImplementation(project(":common"))
  testImplementation(testFixtures(project(":wstool-import")))
  testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
  testImplementation("info.picocli:picocli:4.7.6")
  testImplementation("org.testcontainers:testcontainers")
  testImplementation("org.testcontainers:testcontainers-junit-jupiter")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.12.2")
}

tasks.test {
  useJUnitPlatform()
  val cli = project(":cli")
  val cliPackage = cli.tasks.named<Zip>("packageCli")
  val gradlePackage = project(":wstool-gradle-plugin").tasks.named<Zip>("packageGradle")
  val gradleRunnerPackage = project(":wstool-gradle-plugin").tasks.named<Zip>("packageWstoolGradle")
  val mavenToolPackage = project(":wstool-import").tasks.named<Zip>("packageMavenWstoolImport")
  val npmImportPackage = project(":wstool-import").tasks.named<Zip>("packageWstoolNpmImport")
  dependsOn(cli.tasks.named("installDist"), cliPackage)
  dependsOn(gradlePackage, gradleRunnerPackage, mavenToolPackage, npmImportPackage)
  environment("WSTOOL_CLI_ARCHIVE", cliPackage.get().archiveFile.get().asFile.toURI().toString())
  environment(
    "WSTOOL_CLI_LAUNCHER",
    cli.layout.buildDirectory.file("install/wstool/bin/wstool").get().asFile.absolutePath,
  )
  environment("WSTOOL_GRADLE_PACKAGE", gradlePackage.get().archiveFile.get().asFile.absolutePath)
  environment(
    "WSTOOL_GRADLE_RUNNER_PACKAGE",
    gradleRunnerPackage.get().archiveFile.get().asFile.absolutePath,
  )
  environment(
    "WSTOOL_MAVEN_TOOL_PACKAGE",
    mavenToolPackage.get().archiveFile.get().asFile.absolutePath,
  )
  environment(
    "WSTOOL_NPM_IMPORT_PACKAGE",
    npmImportPackage.get().archiveFile.get().asFile.absolutePath,
  )
}
