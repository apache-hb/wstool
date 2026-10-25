package wstool.gradle

import java.nio.file.Files
import java.nio.file.Path
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaApplication
import org.gradle.api.tasks.Exec
import org.gradle.jvm.tasks.Jar

class DebPackagePlugin : Plugin<Project> {
  override fun apply(project: Project) {
    project.plugins.withId("org.gradle.application") {
      val application = project.extensions.getByType(JavaApplication::class.java)
      val installDirectory =
        project.layout.buildDirectory.dir(
          project.providers.provider { "install/${application.applicationName}" }
        )
      val jpackageDirectory = project.layout.buildDirectory.dir("jpackage-deb")
      val distributionDirectory = project.layout.buildDirectory.dir("distributions")
      val debFile =
        project.layout.buildDirectory.file(
          project.providers.provider {
            "distributions/${application.applicationName}_${project.version}_amd64.deb"
          }
        )

      project.tasks.register("packageDeb", Exec::class.java) {
        group = "distribution"
        description = "Builds a Debian package containing the application and its Java runtime."
        dependsOn(project.tasks.named("installDist"))
        doFirst {
          val applicationName = application.applicationName
          val mainClass = application.mainClass.get()
          val version = project.version.toString()
          jpackageDirectory.get().asFile.deleteRecursively()
          jpackageDirectory.get().asFile.mkdirs()
          distributionDirectory.get().asFile.mkdirs()
          commandLine(
            "jpackage",
            "--type",
            "deb",
            "--name",
            applicationName,
            "--app-version",
            version,
            "--vendor",
            "wstool",
            "--input",
            installDirectory.get().file("lib").asFile.absolutePath,
            "--main-jar",
            project.tasks.named("jar", Jar::class.java).get().archiveFileName.get(),
            "--main-class",
            mainClass,
            "--java-options",
            "--enable-native-access=ALL-UNNAMED",
            "--java-options",
            "--sun-misc-unsafe-memory-access=allow",
            "--dest",
            jpackageDirectory.get().asFile.absolutePath,
          )
        }
        doLast {
          val applicationName = application.applicationName
          fun runPackageCommand(vararg arguments: String) {
            val exitCode = ProcessBuilder(*arguments).inheritIO().start().waitFor()
            check(exitCode == 0) { "Command failed: ${arguments.joinToString(" ")}" }
          }

          val generatedDeb =
            jpackageDirectory.get().asFile.listFiles()!!.single { it.extension == "deb" }
          val packageRoot = project.layout.buildDirectory.dir("deb-package-root").get().asFile
          val controlRoot = project.layout.buildDirectory.dir("deb-package-control").get().asFile
          packageRoot.deleteRecursively()
          controlRoot.deleteRecursively()
          packageRoot.mkdirs()
          controlRoot.mkdirs()

          runPackageCommand("dpkg-deb", "--extract", generatedDeb.absolutePath, packageRoot.absolutePath)
          runPackageCommand("dpkg-deb", "--control", generatedDeb.absolutePath, controlRoot.absolutePath)

          val systemBin = packageRoot.resolve("usr/bin")
          systemBin.mkdirs()
          Files.createSymbolicLink(
            systemBin.resolve(applicationName).toPath(),
            Path.of("/opt/$applicationName/bin/$applicationName"),
          )
          controlRoot.copyRecursively(packageRoot.resolve("DEBIAN"), overwrite = true)
          listOf("preinst", "postinst", "prerm", "postrm").forEach { script ->
            val controlScript = packageRoot.resolve("DEBIAN/$script")
            if (controlScript.exists()) {
              check(controlScript.setExecutable(true, false))
            }
          }

          runPackageCommand(
            "dpkg-deb",
            "--build",
            "--root-owner-group",
            packageRoot.absolutePath,
            debFile.get().asFile.absolutePath,
          )
        }
        inputs.dir(installDirectory)
        inputs.property("jpackageOptions", application.mainClass)
        outputs.dir(jpackageDirectory)
        outputs.file(debFile)
      }
    }
  }
}
