plugins {
  `java-library`
  `java-test-fixtures`
}

dependencies {
  implementation(platform(project(":")))
  implementation(project(":wstool-git"))

  implementation("io.github.classgraph:classgraph:4.8.198")

  implementation("io.github.le-shi:java-gitea-api:1.27.3")
  implementation("dev.parsick.testcontainers.gitserver:testcontainers-gitea:0.16.0")
  implementation("org.testcontainers:testcontainers")
  implementation("org.apache.logging.log4j:log4j-api")
  implementation("org.apache.fory:fory-json:1.7.7")
  implementation("software.amazon.awssdk:s3")

  testRuntimeOnly("org.apache.logging.log4j:log4j-core")
  testImplementation("org.testcontainers:testcontainers")
  testImplementation("org.testcontainers:testcontainers-junit-jupiter")
  testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.12.2")
}

tasks.test { useJUnitPlatform() }
