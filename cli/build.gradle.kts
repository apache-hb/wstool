plugins {
    application
}

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

    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testImplementation("info.picocli:picocli:4.7.6")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.12.2")
}

application {
    applicationName = "wstool"
    mainClass = "wstool.cli.RootCommand"
}

tasks.test {
    useJUnitPlatform()
}
