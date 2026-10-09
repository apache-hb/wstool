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

tasks.test {
  useJUnitPlatform()
  val cliPackage = tasks.named<Zip>("packageCli")
  dependsOn(cliPackage)
  environment("WSTOOL_CLI_ARCHIVE", cliPackage.get().archiveFile.get().asFile.toURI().toString())
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

tasks.named("assemble") { dependsOn(tasks.named("packageCli")) }
