plugins { `java-library` }

dependencies {
  implementation(platform(project(":")))
  implementation("org.apache.fory:fory-json:1.7.7")
  implementation("org.apache.logging.log4j:log4j-api")
  runtimeOnly("org.apache.logging.log4j:log4j-core")

  testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
  testImplementation(project(":wstool-test-components"))
  testImplementation("org.testcontainers:testcontainers")
  testImplementation("org.testcontainers:testcontainers-junit-jupiter")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.12.2")
}

tasks.test { useJUnitPlatform() }

sourceSets { test { resources.srcDir("../wstool-test-components/src/test/resources") } }
