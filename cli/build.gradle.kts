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
  runtimeOnly("org.apache.logging.log4j:log4j-core")
  runtimeOnly("org.apache.logging.log4j:log4j-slf4j2-impl")

  testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
  testImplementation("info.picocli:picocli:4.7.6")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.12.2")
}

application {
  applicationName = "wstool"
  mainClass = "wstool.cli.RootCommand"
}

tasks.test { useJUnitPlatform() }

val integrationTestSourceSet = sourceSets.create("integrationTest")

integrationTestSourceSet.compileClasspath += sourceSets.main.get().output

integrationTestSourceSet.runtimeClasspath += sourceSets.main.get().output

configurations[integrationTestSourceSet.implementationConfigurationName].extendsFrom(
  configurations.testImplementation.get()
)

configurations[integrationTestSourceSet.runtimeOnlyConfigurationName].extendsFrom(
  configurations.testRuntimeOnly.get()
)

dependencies {
  add(integrationTestSourceSet.implementationConfigurationName, project(":common"))
  add(
    integrationTestSourceSet.implementationConfigurationName,
    "org.junit.jupiter:junit-jupiter:5.12.2",
  )
  add(
    integrationTestSourceSet.runtimeOnlyConfigurationName,
    "org.junit.platform:junit-platform-launcher:1.12.2",
  )
}

tasks.register<Test>("integrationTest") {
  description = "Runs the local Garage-backed end-to-end test."
  group = "verification"
  testClassesDirs = integrationTestSourceSet.output.classesDirs
  classpath = integrationTestSourceSet.runtimeClasspath
  useJUnitPlatform()
  shouldRunAfter(tasks.test)
}
