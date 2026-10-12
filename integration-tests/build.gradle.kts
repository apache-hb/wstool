plugins { java }

dependencies {
  testImplementation(project(":wstool-test-components"))
  testImplementation(platform(project(":")))
  testImplementation(project(":cli"))
  testImplementation(project(":common"))
  testImplementation(testFixtures(project(":wstool-import")))
  testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
  testImplementation("info.picocli:picocli:4.7.6")
  testImplementation("org.testcontainers:testcontainers")
  testImplementation("org.testcontainers:testcontainers-junit-jupiter")
  testImplementation("software.amazon.awssdk:s3")
  testImplementation("io.github.le-shi:java-gitea-api:1.27.3")
  testImplementation("dev.parsick.testcontainers.gitserver:testcontainers-gitea:0.16.0")
  testImplementation("org.pkl-lang:pkl-config-java:0.32.1")
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
  val npmImporter = project(":wstool-import")
  val npmImporterLauncher =
    npmImporter.layout.buildDirectory.file("install/wstool-import/bin/wstool-import")
  dependsOn(cli.tasks.named("installDist"), cliPackage)
  dependsOn(npmImporter.tasks.named("installDist"))
  dependsOn(gradlePackage, gradleRunnerPackage, mavenToolPackage, npmImportPackage)
  environment("WSTOOL_CLI_ARCHIVE", cliPackage.get().archiveFile.get().asFile.toURI().toString())
  environment(
    "WSTOOL_CLI_LAUNCHER",
    cli.layout.buildDirectory.file("install/wstool/bin/wstool").get().asFile.absolutePath,
  )
  environment("WSTOOL_IMPORT_LAUNCHER", npmImporterLauncher.get().asFile.absolutePath)
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
