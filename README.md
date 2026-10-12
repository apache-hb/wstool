# wstool

A polyrepo workspace management tool.

This is still quite experimental, and a fair bit is quite sloppy ai code. This is more of a proof of concept than something you should or that I am proud of.

## prerequesites

* An s3 compatible storage service
* java25
* a set of repositories

## usage

First create a workspace with `wstool workspace create --name "MyWorkspace"`. Then enter the workspace `cd MyWorkspace`, set a distribution with `wstool workspace setup --distro MyDistroName --from "<url>"`. url can be either a git repo, or a path on the local filesystem. Add source packages with `wstool workspace clone --package MyFirstPackage --package MySecondPackage`. Any packages that the distribution requires that are not part of the current workspace have their build artifacts taken from object storage instead.

To build a package enter the folder its cloned to, i.e. `MyWorkspace/src/MyFirstPackage` and run `wstool package release`, this will build the package, run any tests, and place its build artifacts into the build folder. To run other targets such as test use `wstool package <target>`. Target is a freeform string, and all cli arguments after it are forwarded to the build tool as is.

To create a distribution release run `wstool distro build`, this will package all updated dependencies and their dependants. The build is run locally in docker containers, the docker build containers by default contain nothing. Base packages can opt into a small installation of gcc, glibc, and shell. Containers also have access to the subset of packages it declares a dependency on that are in the current distribution. Once `wstool distro build` succeeds create a release using `wstool distro release`, this creates a concrete revision of the distro with all build artifacts finalized.

### distribution.pkl config file

To start using wstool you must first invent the universe. This means creating a "distribution". A distribution is defined using pkl and a file named `distribution.pkl` in the root of a repository. This config file defines all the packages the distribution requires to build.

```pkl
amends "package://<package-base-uri>@<version>#/distribution.pkl"

name = "my-wonderful-distribution"

storage {
    // You can optionally specify a custom s3 endpoint if you want to store artifacts elsewhere (such as locally)
    // endpoint = "my-custom.s3.provider.com"

    // An s3 bucket name where your distro is stored
    bucket = "my-distribution-bucket"
}

packages {
    // Packages can be sourced from git
    ["wstool-meson@1.x"] = new GitRepository { repo = "https://github.com/apache-hb/wstool-meson.git"; branch = "master" }

    // Or from an archive
    ["example@2.x"] = new Archive { uri = "file://example.zip"; checksum = "f77bd47cf8170c57365ae7bf86696c118adb3b120d3259c64c502d3dc1e2d926" }

    // Or from the local filesystem
    ["internal-package@1.x"] = new SourceCode { path = "./packages/internal-package" }

    // You may also specify multiple versions of the same package
    ["internal-package@0.x"] = new SourceCode { path = "./legacy-packages/internal" }
}
```

### wstool.pkl config file

In every package there is a file called wstool.pkl in the root directory, which contains information about the package.

```pkl
amends "package://<package-base-uri>@<version>#/package.pkl"

package {
    name = "my-cxx-package"
    version = "1.x"
}

buildTool = "wstool-meson"

buildDependencies {
    ["wstool-meson"] = "1.x"
    ["clang"] = "22.x"
}

dependencies {
    ["libstdcxx-devel"] = "22.x"
    ["glibc-devel"] = "2.38"
}

testDependencies {
    ["google-test"] = "1.18.0"
}
```

### wstool sysroot command

The main command for configuring and aggregating packages during builds is `wstool sysroot <selector>`, this allows creation of a fake sysroot with installation files from other packages in it. This is most commonly used for creating a build root with all required headers/libraries/binaries in it during build. This can also be used to create a final aggregate package with `wstool aggregate <selector>`. `wstool sysroot <selector>` can accept any of the following arguments as its selector: `build`, `development`, `runtime`, and `<packagename>@<version>/<selector>`. `build` collects all the build time dependencies and their transitive dependents. `development` collects all buildDependencies, dependencies, testDependencies, and runtimeDependencies. And runtime collects only dependencies and runtimeDependencies. Specifying a package name version overrides the package to use as the root for graph traversal as this defaults to the current package.

## writing your own build tools

Build tools are simply scripts in the `bin` folder of your install, to create a new build tool create a package that installs an executable file to its `bin` output. The `buildTool` package key should be the name of the installed binary. If you want to create a build tool that only a single project is going to use, it can be placed in `wstool/bin` inside the packages root directory. Then specify it using `buildTool` like usual.

The executable receives these environment variables:

* `WST_SRCDIR` is the source directory.
* `WST_PRIVATE` is the private build directory, including any build-tool-owned sysroots.
* `WST_PREFIX` is the installation prefix for the package.

The release build invokes the executable without arguments. For `wstool package
<target>`, the target is passed as the first argument and any remaining arguments are
forwarded to the build tool. The build tool is responsible for constructing and using
any sysroots it needs. The standard packages `wstool-cmake` and `wstool-meson` provide
this interface for CMake and Meson projects.

### workspace layout

Workspaces have a fixed layout

```
Workspace - The root of the workspace
  distribution - The distribution git repo, containing distribution.pkl
  src/ - Any cloned source packages
    FirstPackage - Package source
  .wstool/ - wstool internal folder, all internal config is stored here
```

### CLI package for container builds

`./gradlew :cli:packageCli` creates a ZIP package containing the `wstool` launcher and its
runtime dependencies at `cli/build/distributions/wstool-cli-<version>.zip`. Add that archive to a
distribution as an `Archive` package, including its SHA-256 checksum. Packages whose build tools
call `wstool` inside a build container should declare `wstool-cli` in `buildDependencies`. The
container build adds every build dependency's `bin` directory to `PATH`.

## TODO

* Build and distribution revisions for checking out specific versions of builds
* Getting java build tools integrated, i.e. gradle and maven using dependencies from inside the distribution rather than downloaded from mavencentral
    * also pkl
    * also npm
* get ides working with the semi-sandboxed packages
* maven auto-import tool to add maven packages as jars

### Installing wstool globally

Build native Linux packages with JDK 25's `jpackage`:

```sh
./gradlew :cli:packageLinux
```

This creates a Debian package (`wstool_<version>-1_amd64.deb`) and an RPM package (`wstool-<version>-1.x86_64.rpm`) under `cli/build/distributions`. The packages install a `wstool` command globally and bundle the Java runtime. Building requires `jpackage`, `dpkg-deb`, and `rpmbuild`; to build one format, use `:cli:packageDeb` or `:cli:packageRpm`.

Install the package for your distribution:

```sh
sudo apt install ./cli/build/distributions/wstool_0.1.0-1_amd64.deb
sudo dnf install ./cli/build/distributions/wstool-0.1.0-1.x86_64.rpm
```

The installed command is available from any project directory, for example `wstool workspace create --name MyWorkspace`.

### Building Gradle projects with wstool packages

The `wstool-import` CLI imports Maven artifacts and their transitive compile/runtime dependencies into a wstool package bundle. Build the importer with `./gradlew :wstool-import:installDist`, then run:

```sh
wstool-import --output imported-maven --base-url https://packages.example.com/maven \
  org.example:library:1.2.3
```

This writes a source bundle ZIP and `packages.pkl` entries. Publish the ZIP at the supplied base URL and add the generated entries to the distribution's package configuration. Each imported artifact is represented as a source package that builds from the bundled Maven repository.

Build the shared Maven support package and Gradle runner with:

```sh
./gradlew :wstool-import:packageMavenWstool
./gradlew :wstool-gradle-plugin:packageWstoolGradle
```

Both tasks produce a ZIP, SHA-256 file, and Pkl package entry under their module's `build/distributions` directory. Add the packages to the distribution using those generated entries. `maven-wstool` stages imported JARs and Javadocs in build outputs. `wstool-gradle` bundles Gradle and its repository plugin, and runs Gradle offline against the Maven repository provided by wstool.

For a Gradle project, set `buildOptions.tool = "wstool-gradle"`, add `wstool-gradle` and the required imported Maven packages to `buildDependencies`, and use a JDK-enabled build image. The bundled Gradle plugin rewrites project and plugin dependency repositories to the wstool-provided Maven repository.

Gradle is distributed separately from `wstool-gradle`. Build its package with `./gradlew :wstool-gradle-plugin:packageGradle` and add the generated `gradle` package entry to the distribution. The `wstool-gradle` package depends on that package and contains only the runner, init script, and repository plugin.

### Importing npm packages

The `wstool-import` command provides Maven and npm subcommands. Import npm dependencies and their transitive packages with:

```sh
wstool-import npm --output imported-npm --base-url https://packages.example.com/npm \
  is-number@7.0.0
```

The importer uses npm's registry and local cache while resolving packages. It writes a source bundle, `packages.pkl`, and `npm-dependencies.pkl`. Publish the bundle at the supplied base URL and add the generated package entries to the distribution. Import Maven artifacts with `wstool-import maven --output imported-maven GROUP:ARTIFACT:VERSION`.

Add the generated dependency mappings and the `npm-wstool` package to a project. The generated `dependencies` mapping records runtime packages; `buildDependencies` makes those packages available while the build runs:

```pkl
import "npm-dependencies.pkl" as importedNpmDependencies

buildDependencies {
  ["npm-wstool"] = "1.0.0"
  ...importedNpmDependencies.buildDependencies
}

dependencies {
  ...importedNpmDependencies.dependencies
}
```

Build `npm-wstool` with `./gradlew :wstool-import:packageNpmWstool` and add its generated package entry to the distribution. It assembles `node_modules` from wstool package prefixes and runs the project build with network isolation, so project builds do not contact npm.

### Creating a release

Push a version tag such as `v1.2.3` to create a GitHub release. The release workflow builds the `wstool-cli`, `maven-wstool`, `npm-wstool`, `gradle`, and `wstool-gradle` archives, plus Debian and RPM installers for wstool. The archive files and their SHA-256 checksums are attached to the release. The `wstool-cli` archive is the wstool runtime for use in build environments; install the Debian or RPM package to add the `wstool` and `wstool-import` commands locally.

Maven imports use the shared `maven-wstool-import` build-tool package. Each generated Maven artifact package depends on it and does not bundle its executable. Build and package it with `./gradlew :wstool-import:packageMavenWstoolImport`; its release archive is `maven-wstool-import-1.0.0.zip`.

npm imports use the shared `wstool-npm-import` build-tool package. Each generated npm package depends on it and does not bundle its own copy of `npm-wstool`. Build and package it with `./gradlew :wstool-import:packageWstoolNpmImport`; its release archive is `wstool-npm-import-1.0.0.zip`.

For npm packages, `buildOptions.tool` remains `npm-wstool`; the shared package that provides it is `wstool-npm-import`, which generated packages list under `buildDependencies`.

### External import sources

Maven import package configs use `ExternalFiles`, fetching each artifact from the configured Maven repository and verifying its SHA-256 checksum. npm package configs use `ExternalArchive` with the tarball URL and integrity recorded by npm. The npm importer writes only package metadata and does not copy package files into an `external-files/` directory.

Archive extraction uses Apache Commons Compress to read ZIP and TAR archives, including compressed TAR files. It uses the archive entry metadata to preserve file permissions and rejects paths and links that escape the extraction directory.
