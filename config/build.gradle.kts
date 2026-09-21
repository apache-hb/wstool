plugins {
  `java-library`
  id("org.pkl-lang") version "0.32.1"
}

group = rootProject.group

version = rootProject.version

java {
  sourceCompatibility = JavaVersion.VERSION_25
  targetCompatibility = JavaVersion.VERSION_25
}

dependencies {
  implementation(platform(project(":")))
  implementation("org.pkl-lang:pkl-config-java:0.32.1")
}

pkl {
  javaCodeGenerators {
    register("configClasses") {
      sourceModules.set(files("src/main/resources/package.pkl"))
      generateGetters.set(true)
    }
  }
}
