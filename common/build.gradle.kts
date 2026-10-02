plugins { `java-library` }

group = rootProject.group

version = rootProject.version

java {
  sourceCompatibility = JavaVersion.VERSION_25
  targetCompatibility = JavaVersion.VERSION_25
}

dependencies {
  implementation(platform(project(":")))
  api(project(":config"))

  implementation("com.google.dagger:dagger:2.60.1")
  annotationProcessor("com.google.dagger:dagger-compiler:2.60.1")

  compileOnly("org.immutables:value-annotations:2.12.2")
  annotationProcessor("org.immutables:value:2.12.2")

  implementation("com.google.inject:guice")
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
  testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.12.2")
}

tasks.test { useJUnitPlatform() }
