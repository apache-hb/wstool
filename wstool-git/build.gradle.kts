import org.gradle.plugins.ide.eclipse.model.Classpath
import org.gradle.plugins.ide.eclipse.model.SourceFolder

plugins {
  `java-library`
  eclipse
}

dependencies {
  api("org.jspecify:jspecify:1.0.0")

  compileOnly("org.immutables:value-annotations:2.12.2")
  annotationProcessor("org.immutables:value:2.12.2")
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
