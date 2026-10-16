plugins {
    `java-library`
}

dependencies {
    implementation(platform(project(":")))
    implementation(project(":common"))
    implementation("de.tu-dresden.inf.lat.jsexp:jsexp:0.2.2")
    api(project(":config"))
    api("org.jspecify:jspecify:1.0.0")

    runtimeOnly("org.apache.logging.log4j:log4j-core")
    runtimeOnly("org.apache.logging.log4j:log4j-slf4j2-impl")

    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.12.2")
}

tasks.test { useJUnitPlatform() }
