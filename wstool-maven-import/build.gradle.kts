plugins { `java-library` }

dependencies {
  implementation(platform(project(":")))
  api("org.apache.maven.resolver:maven-resolver-api:1.9.22")
  implementation("org.apache.maven.resolver:maven-resolver-util:1.9.22")
  implementation("org.apache.maven:maven-resolver-provider:3.9.9")
  api("org.apache.maven.resolver:maven-resolver-supplier:1.9.22")
  implementation("org.apache.maven.resolver:maven-resolver-connector-basic:1.9.22")
  implementation("org.apache.maven.resolver:maven-resolver-transport-http:1.9.22")
  implementation("javax.inject:javax.inject:1")
  implementation("org.apache.logging.log4j:log4j-api")
  runtimeOnly("org.apache.logging.log4j:log4j-core")

  testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
  testImplementation(testFixtures(project(":wstool-test-components")))
  testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.12.2")
}

tasks.test { useJUnitPlatform() }
