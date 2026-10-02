# wstool

A polyrepo workspace management tool.

## prerequesites

* An s3 compatible storage service
* java25
* a set of repositories

## usage

First create a workspace with `wstool workspace create --name "MyWorkspace"`. Then enter the workspace `cd MyWorkspace`, set a distribution with `wstool workspace setup --distro MyDistroName --from "<url>"`. url can be either a git repo, or a path on the local filesystem. Add source packages with `wstool workspace clone --package MyFirstPackage --package MySecondPackage`. Any packages that the distribution requires that are not part of the current workspace have their build artifacts taken from object storage instead.

To build a package enter the folder its cloned to, i.e. `MyWorkspace/src/MyFirstPackage` and run `wstool package release`, this will build the package, run any tests, and place its build artifacts into the build folder. To run other targets such as test use `wstool package <target>`. Target is a freeform string, and all cli arguments after it are forwarded to the build tool as is.

To create a distribution release run `wstool distro build`, this will package all updated dependencies and their dependants. The build is run locally in docker containers, the docker build containers by default contain nothing. Base packages can opt into a small installation of gcc, glibc, and shell. Containers also have access to the subset of packages it declares a dependency on that are in the current distribution. Once `wstool distro build` succeeds create a release using `wstool distro release`, this creates a concrete revision of the distro with all build artifacts finalized.

### world.pkl config file

To start using wstool you must first invent the universe. This means creating a "distribution". A distribution is defined using pkl and a file named `world.pkl` in the root of a repository. This config file defines all the packages the distribution requires to build.

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
  world.pkl - A symlink to distribution/world.pkl
  distribution - The distribution git repo, containing world.pkl
  src/ - Any cloned source packages
    FirstPackage - Package source
  .wstool/ - wstool internal folder, all internal config is stored here
```

## Implementation notes

The build process invokes the executable named by `buildTool` from the package's
build dependencies; it does not select a build system based on source files. Package
artifacts are stored under
`artifacts/<name>/<version>/artifact.zip` in the configured S3 bucket. The S3 endpoint,
when using Garage or another local S3-compatible service, is selected with
`WSTOOL_S3_ENDPOINT`; credentials use `WSTOOL_S3_ACCESS_KEY`, `WSTOOL_S3_SECRET_KEY`,
`WSTOOL_S3_REGION`, and `WSTOOL_S3_BUCKET` (the default bucket is `storage`).

For a local repository checkout, use `wstool workspace clone --package name --from
/path/to/repository`. The `--from` value can also be an HTTP(S) or SSH git URL.
`wstool distro build --docker` runs package builds in Docker. The image is selected by
each package's `buildOptions.dockerImage`; packages using `buildImage = "declared"`
always use the `scratch` image. Without `--docker`, packages use the local toolchain
unless `buildImage = "bootstrap"` requires Docker.

### Reproducible local integration demo

The JUnit integration test starts Garage in Docker, creates its single-node layout and
S3 credentials, builds the checked-in Abseil/GoogleTest/glibc/sample application fixtures,
and then builds the application again in a clean workspace using artifacts fetched from
Garage. Run it with:

```shell
./gradlew :cli:test --tests wstool.cli.EndToEndIntegrationTest
```

It requires Java 25 and a working Docker daemon. No repository-level shell script is used
to orchestrate the test; the test owns the Garage container and all CLI subprocesses.

## Current build tool configuration

Build tool selection is configured with `buildOptions.tool`. A tool is first resolved from the package-local
`wstool/bin/<tool>` path, which is useful for bespoke package-specific build tools, and
then from the package's build dependencies. `buildEnvironment` entries are passed to
the tool's environment, while `buildConfig` entries are exposed through
`WST_USER_OPTIONS` for the tool to interpret.

## Current world configuration behavior

`workspace setup` evaluates the selected `world.pkl` with Pkl and records the evaluated
distribution and storage settings in the workspace state:

```shell
wstool workspace setup --distro my-wonderful-distribution --from /path/to/distribution
```

The `--from` value may name a directory containing `world.pkl`, the `world.pkl` file itself,
or a Git URL. `workspace clone --package name` then resolves the package source from the
configured world. Relative `SourceCode.path` values are resolved relative to `world.pkl`.

## Current workspace discovery behavior

Workspace configuration is derived directly from `world.pkl`; Commands locate the world by walking from the workspace root through its parent directories until `world.pkl` is found. Setup therefore only validates the distribution name:

```shell
wstool workspace setup --distro my-wonderful-distribution
```

Build tools are resolved by name through `PATH`. The build process adds the `bin` directories
of build dependencies and the package's `wstool/bin` directory before the existing environment
`PATH`, so build tools can invoke tools supplied by their dependencies.
