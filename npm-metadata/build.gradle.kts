plugins { `java-library` }

dependencies {
  implementation(platform(project(":")))
  implementation("org.apache.fory:fory-json:1.7.7")
  annotationProcessor("org.apache.fory:fory-annotation-processor:1.7.7")
  api("com.github.zafarkhaja:java-semver:0.10.2")

  testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.12.2")
}

tasks.test { useJUnitPlatform() }
