# wstool

A polyrepo workspace management tool.

## prerequesites

* An s3 compatible storage service
* java25
* a set of repositories

## usage

First create a workspace with `wstool workspace create --name "MyWorkspace"`. Then enter the workspace `cd MyWorkspace`, set a distribution with `wstool workspace setup --distro MyDistroName`, and add source packages with `wstool workspace clone --package MyFirstPackage --package MySecondPackage`. Any packages that the distribution requires that are not part of the current workspace have their build artifacts taken from object storage instead.

To build a package enter the folder its cloned to, i.e. `MyWorkspace/src/MyFirstPackage` and run `wstool package release`, this will build the package, run any tests, and place its build artifacts into the build folder. To run other targets such as test use `wstool package <target>`. Target is a freeform string, and all cli arguments after it are forwarded to the build tool as is.

To create a distribution release run `wstool distro build`, this will package all updated dependencies and their dependants. The build is run locally in docker containers, the docker build containers by default contain nothing. Base packages can opt into a small installation of gcc, glibc, and shell. Containers also have access to the subset of packages it declares a dependency on that are in the current distribution. Once `wstool distro build` succeeds create a release using `wstool distro release`, this creates a concrete revision of the distro with all build artifacts finalized.

### wstool.pkl config file

In every package there is a file called wstool.pkl in the root directory, which contains information about the package.

```pkl
amends "package://<url>/package.pkl"

package {
    name = "my-cxx-package"
    version = "1.x"
}

buildTool: "wstool-meson"

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

## Implementation notes

The current implementation uses CMake for packages whose `buildTool` is `cmake` or
`wstool-cmake`. Package artifacts are stored under
`artifacts/<name>/<version>/artifact.zip` in the configured S3 bucket. The S3 endpoint,
when using Garage or another local S3-compatible service, is selected with
`WSTOOL_S3_ENDPOINT`; credentials use `WSTOOL_S3_ACCESS_KEY`, `WSTOOL_S3_SECRET_KEY`,
`WSTOOL_S3_REGION`, and `WSTOOL_S3_BUCKET` (the default bucket is `storage`).

For a local repository checkout, use `wstool workspace clone --package name --from
/path/to/repository`. The `--from` value can also be an HTTP(S) or SSH git URL.
`wstool distro build --docker --build-image <image>` runs the CMake build in that image;
without `--docker` it uses the local toolchain, which is useful for a fast development
loop.

### Reproducible local integration demo

The JUnit integration test starts Garage in Docker, creates its single-node layout and
S3 credentials, builds the checked-in Abseil/GoogleTest/glibc/sample application fixtures,
and then builds the application again in a clean workspace using artifacts fetched from
Garage. Run it with:

```shell
./gradlew :cli:integrationTest
```

It requires Java 25 and a working Docker daemon. No repository-level shell script is used
to orchestrate the test; the test owns the Garage container and all CLI subprocesses.
