plugins {
    id("java-platform")
}

group = "com.apachehb.wstool"
version = "0.1.0"

javaPlatform {
    allowDependencies()
}

dependencies {
    api(platform("software.amazon.awssdk:bom:2.31.74"))
    api(platform("org.apache.logging.log4j:log4j-bom:2.25.1"))
    api(platform("io.opentelemetry:opentelemetry-bom:1.51.0"))
    api(platform("org.pkl-lang:pkl-bom:0.32.1"))

    api("com.google.dagger:dagger:2.60.1")
    api("com.google.dagger:dagger-compiler:2.60.1")
}
