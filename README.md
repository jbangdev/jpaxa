# jpaxa

A port of [jpaxa](https://github.com/leafac/jpaxa) to Java - a tool to package any runtime or script dependent applications (Node.js, Java, Python, jbang, etc.) into self-extracting executable binaries/bundles.

## Overview

`jpaxa` creates self-extracting executables that contain:

1. A **stub** (Go platform specific binary or shell script) that extracts and runs the application
2. A **tarball** containing your application
3. A **JSON footer** with metadata (command, identifier, etc.)

When the executable runs, it extracts the tarball to a temporary directory and executes your command.

This approach gives a lot of freedom and enables it to work with any kind of application, not just Java.


## Features

- :white_check_mark: Works on Windows, macOS (Intel & ARM), and Linux (Intel, ARM, ARM64)
- :white_check_mark: Simple to use - no need to declare which files to include
- :white_check_mark: Supports any kind of application (Node.js, Java, Python, jbang, etc.)
- :white_check_mark: Works with native modules/dependencies (*)
- :white_check_mark: Fast packaging (seconds)
- :white_check_mark: Relatively small binaries
- :white_check_mark: Produces .exe's for Windows, simple binaries for macOS/Linux, and macOS Application Bundles (.app).
- :white_check_mark: Based on a simple but powerful idea. Implemented in ~200 lines of code.
- :white_check_mark: No magic - just extracts and runs

(*) - if your application contains platform specific pieces you might need to
build that on the specific platform - but `jpaxa` does not care; it will take whatever
you point it to and package/unpack it.

## What is this NOT

- :x: self-contained executable for Java - current JDK's require files on disk. OpenJDK has a proposal for [Hermetic Java](https://cr.openjdk.org/~jiangli/hermetic_java.pdf) that does this which at time of writing is not yet available.
- :x: native-image replacement - no, native-image is a different approach that is more focused on performance and security. 
- :x: jpackage replacement - no, jpackage is a different approach that is more focused on creating installers for various Operating systems, only Windows for standalone executables plus jpackage must be run on each target platform. In other words its great for creating installers for your application, but not for creating self-extracting executables. jpaxa works uniformly across all platforms and works with any kind of application, not just Java.
- :x: packaging for Java applications - `jpaxa` is agnostic to the runtime of the application, it will package the application as is. 
- :x: high-performant - on first run there is cost of extracting the archive and running the command. This is not a problem for most applications, but if you need high performance on first call, you should use a different approach.

## Differences from Original jpaxa

- **Written in Java** instead of TypeScript/Node.js; more portable and easier to maintain (for me :)
- **No runtime bundling** - jpaxa had special node support to bundle the application, instead `jpaxa` expect users to existing tools to bundle your application, `jpaxa` for now will just package the exectuble archive. (note: I'm open to adding runtime bundling support if there is demand)
- **Cross-platform Java** - can run on any platform with Java installed
- **jbang-compatible** - can be run directly with jbang

## Install/Run

Install binaries from [releases](https://github.com/jpaxa/jpaxa/releases) or install directly with jbang:

```bash
jbang app install jpaxa@jbangdev
```

## Usage

### Basic Examples

```bash
# Package a jbang application, wraps jbang so nothing required on target machine - jbang will handle the heavy lifting
mkdir app 
jbang wrapper install -d=app
jpaxa app -- "{{apppack}}/jbang" "env@jbangdev"

# Package a Java application, assuming you have node installed in node_modules/.bin/node
jpaxa \
  --input "my-java-app" \
  --output "my-java-app" \
  -- "java" "-jar" "{{jpaxa}}/app.jar"

# Package a Node.js application, assuming you have node installed in node_modules/.bin/node
jpaxa \
  --input "my-app" \
  --output "my-app" \
  -- "{{jpaxa}}/node_modules/.bin/node" "{{jpaxa}}/index.js"

# Package a Python application, assume python3 is in the path of user running the command
jpaxa \
  --input "my-python-app" \
  --output "my-python-app" \
  -- "python3" "{{jpaxa}}/main.py"
```

### Windows

On Windows, the output must end in `.exe`:

```bash
jbang JPAXA.java --input "my-app" --output "my-app.exe" -- "{{jpaxa}}/node_modules/.bin/node" "{{jpaxa}}/index.js"
```

### macOS Application Bundle

On macOS, you can create a `.app` bundle:

```bash
jbang JPAXA.java --input "my-app" --output "MyApp.app" -- "{{jpaxa}}/node_modules/.bin/node" "{{jpaxa}}/index.js"
```

### Shell Stub (macOS/Linux only)

For a smaller binary that depends on system tools (tar, tail):

```bash
jbang JPAXA.java --input "my-app" --output "my-app.sh" -- "{{jpaxa}}/node_modules/.bin/node" "{{jpaxa}}/index.js"
```

## Command-Line Options

```
-i, --input <input>                    [Required] The input directory to package
-o, --output <output>                  [Required] The path where the executable will be produced
-F, --no-force                         Don't overwrite output if it exists
-e, --exclude <path...>                Paths to exclude from the build
-D, --no-dedupe                        Don't run npm dedupe (ignored in Java port)
-p, --prepare-command <command>        Command to run on the build directory before packaging
-N, --no-include-node                  Don't copy Node.js executable (ignored in Java port)
-s, --stub <path>                      Path to the stub
--identifier <identifier>               Build identifier for the extraction path
-B, --no-remove-build-directory        Don't remove the build directory after the build
-m, --uncompression-message <message>  Message to show when uncompressing
```

## The `{{jpaxa}}` Placeholder

The `{{jpaxa}}` placeholder in your command is replaced with the directory where the application is extracted at runtime. For example:

- `{{jpaxa}}/index.js` → `/tmp/jpaxa/applications/my-app-abc123/0/index.js`
- `{{jpaxa}}/node_modules/.bin/node` → `/tmp/jpaxa/applications/my-app-abc123/0/node_modules/.bin/node`

## Stubs

The stub is a Go binary that handles extraction and execution. Pre-compiled stubs are available for:
- Windows (x64)
- macOS (x64, ARM64)
- Linux (x64, ARM64, ARM)

### Using Pre-compiled Stubs

The Java version will look for stubs in:
1. Current directory: `stub-<platform>`
2. `stubs/` directory: `stubs/stub-<platform>`
3. JAR resources: `/stubs/stub-<platform>`

You can copy the stubs from the original jpaxa project or compile them yourself.

### Compiling Stubs

To compile stubs for all platforms:

```bash
# Then compile for each platform using the new platform IDs:
#
#   windows-x86_64
#   osx-x86_64
#   osx-aarch_64
#   linux-x86_64
#   linux-aarch_64
#   linux-arm_32

# Windows x64
GOOS=windows GOARCH=amd64 CGO_ENABLED=0 go build -o stubs/stub-windows-x86_64 stub.go
echo "" >> stubs/stub-windows-x86_64
echo "JPAXAJPAXAJPAXA" >> stubs/stub-windows-x86_64

# macOS x64
GOOS=darwin GOARCH=amd64 CGO_ENABLED=0 go build -o stubs/stub-osx-x86_64 stub.go
echo "" >> stubs/stub-osx-x86_64
echo "JPAXAJPAXAJPAXA" >> stubs/stub-osx-x86_64

# macOS ARM64
GOOS=darwin GOARCH=arm64 CGO_ENABLED=0 go build -o stubs/stub-osx-aarch_64 stub.go
echo "" >> stubs/stub-osx-aarch_64
echo "JPAXAJPAXAJPAXA" >> stubs/stub-osx-aarch_64

# Linux x64
GOOS=linux GOARCH=amd64 CGO_ENABLED=0 go build -o stubs/stub-linux-x86_64 stub.go
echo "" >> stubs/stub-linux-x86_64
echo "JPAXAJPAXAJPAXA" >> stubs/stub-linux-x86_64

# Linux ARM64
GOOS=linux GOARCH=arm64 CGO_ENABLED=0 go build -o stubs/stub-linux-aarch_64 stub.go
echo "" >> stubs/stub-linux-aarch_64
echo "JPAXAJPAXAJPAXA" >> stubs/stub-linux-aarch_64

# Linux ARM (32-bit)
GOOS=linux GOARCH=arm CGO_ENABLED=0 go build -o stubs/stub-linux-arm_32 stub.go
echo "" >> stubs/stub-linux-arm_32
echo "JPAXAJPAXAJPAXA" >> stubs/stub-linux-arm_32
```

## Cross-Platform Building

### Can I build for other platforms?

**Yes, with limitations:**

1. **Java code**: The Java packager can run on any platform with Java installed. You can build packages for any platform from any platform.

2. **Go stubs**: The Go stubs can be cross-compiled using Go's built-in cross-compilation:
   ```bash
   GOOS=windows GOARCH=amd64 go build -o stub.exe stub.go
   ```
   This means you can compile stubs for all platforms from a single machine (if you have Go installed).

3. **Limitation**: To create a complete executable for a platform, you need:
   - The Java packager (works cross-platform)
   - A stub compiled for the target platform (can be cross-compiled with Go)
   - The application files (platform-specific if they contain native binaries)

4. **Executable bits when building on Windows**: When you build jpaxa binaries on Windows that target Unix-like platforms (Linux/macOS), the packager cannot reliably infer POSIX executable bits from the Windows filesystem. In practice this means that inner files that need to be executable on Linux/macOS (for example the `jbang` wrapper) may lose their `+x` bit when extracted. For best results, build Unix-targeted jpaxa binaries on a Unix runner (Linux/macOS) so executable flags are preserved correctly.

### Recommended Approach

1. **Build stubs once** for all platforms (using Go cross-compilation)
2. **Package on each platform** OR package cross-platform if your application doesn't have platform-specific native dependencies
3. **Use CI/CD** (GitHub Actions, etc.) to build for all platforms automatically

### Example: Building for Multiple Platforms

```bash
# 1. Compile all stubs (do this once, or get them from the original jpaxa project)
# (See "Compiling Stubs" section above)

# 2. Package for current platform
jbang JPAXA.java --input "my-app" --output "my-app" -- "{{jpaxa}}/app"

# 3. For other platforms, you can:
#    - Run on that platform, OR
#    - Use the appropriate stub with --stub option
#    - Note: If your app has native dependencies, you must build on the target platform
```

## How It Works

1. **Packaging**:
   - Copies your application directory to a temporary build directory
   - Creates a tarball (gzip-compressed) of the build directory
   - Appends the tarball to a stub binary
   - Appends a JSON footer with metadata

2. **Execution**:
   - The stub reads itself to find the tarball and footer
   - Extracts the tarball to a temporary directory (cached for subsequent runs)
   - Replaces `{{jpaxa}}` placeholders in the command
   - Executes the command with the extracted files

## Examples

See the `examples/` directory for sample applications.

## License

MIT (same as original jpaxa)

## Acknowledgments

This is a Java port of [jpaxa](https://github.com/leafac/jpaxa) by Leandro Facchinetti. The Go stub code is from the original jpaxa project.
