import org.gradle.plugins.ide.eclipse.model.Classpath
import org.gradle.plugins.ide.eclipse.model.SourceFolder

plugins {
  `java-library`
  eclipse
}

dependencies {
  testImplementation(project(":wstool-test-components"))
  implementation(platform(project(":")))
  api(project(":config"))
  api("org.jspecify:jspecify:1.0.0")

  compileOnly("org.immutables:value-annotations:2.12.2")
  annotationProcessor("org.immutables:value:2.12.2")

  api("jakarta.inject:jakarta.inject-api:2.0.1")
  implementation("org.apache.commons:commons-compress:1.28.0")
  implementation("org.tukaani:xz:1.12")
  implementation("org.apache.logging.log4j:log4j-api")
  runtimeOnly("org.apache.logging.log4j:log4j-core")
  runtimeOnly("org.apache.logging.log4j:log4j-slf4j2-impl")
  implementation("io.opentelemetry:opentelemetry-api")
  implementation("software.amazon.awssdk:s3")
  implementation("software.amazon.awssdk:sts")
  implementation("org.xerial:sqlite-jdbc:3.53.4.0")
  implementation("org.pkl-lang:pkl-core")
  implementation("org.pkl-lang:pkl-config-java")

  testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
  testImplementation("io.github.le-shi:java-gitea-api:1.27.3")
  testImplementation("dev.parsick.testcontainers.gitserver:testcontainers-gitea:0.16.0")
  testImplementation("org.testcontainers:testcontainers")
  testImplementation("org.testcontainers:testcontainers-junit-jupiter")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.12.2")
}

tasks.test { useJUnitPlatform() }

eclipse {
  classpath {
    file {
      whenMerged {
        // Make annotation-processor output visible to Buildship and JDT.
        (this as Classpath)
          .entries
          .add(SourceFolder("build/generated/sources/annotationProcessor/java/main", null))
      }
    }
  }
}
