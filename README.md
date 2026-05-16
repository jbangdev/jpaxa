# jpaxa

Turn an application directory plus a startup command into a **single self-extracting executable**.

`jpaxa` packages your app as-is, wraps it in a small launcher, extracts it on first run, and executes your command. It works well for **JBang apps, Java jars, Node tools, Python CLIs, and other runtime-based applications**.

## Why jpaxa exists

Sometimes you do not want:

- a full installer
- container packaging
- GraalVM native-image complexity
- `jpackage`'s platform-specific packaging workflow
- users unpacking zip or tar files manually

Sometimes you just want:

- **one file**
- that **runs on the target platform**
- while still using the app's normal runtime and file layout

That is what `jpaxa` is for.

## Best fit

Use `jpaxa` when you want to:

- ship a **JBang app** as a single executable
- distribute an internal **CLI tool** as one file
- package a **Node**, **Python**, or **Java** app without building an installer
- wrap an existing app with minimal restructuring
- keep the original runtime model instead of recompiling to native

## Not the right tool

`jpaxa` is **not**:

- a **native-image** replacement
- a **jpackage** replacement
- an installer builder
- optimized for the absolute fastest first startup
- a way to run Java fully in-memory without unpacking files

For Java specifically, today's JDKs still expect files on disk.

## 30-second examples

### Package a JBang app

```bash
mkdir app
jbang wrapper install -d app
jpaxa app -- "{{jpaxa}}/jbang" "env@jbangdev"
```

This produces a self-extracting executable that carries the JBang wrapper and runs the app on the target machine.

### Package a Java jar

```bash
jpaxa \
  --input my-java-app \
  --output my-java-app \
  -- "java" "-jar" "{{jpaxa}}/app.jar"
```

### Package a Node CLI

```bash
jpaxa \
  --input my-node-app \
  --output my-node-app \
  -- "{{jpaxa}}/node_modules/.bin/node" "{{jpaxa}}/index.js"
```

### Package a Python app

```bash
jpaxa \
  --input my-python-app \
  --output my-python-app \
  -- "python3" "{{jpaxa}}/main.py"
```

If the target machine does not have Python installed, bundle a Python runtime inside the input directory and launch that bundled interpreter instead. See [Self-contained Python](#self-contained-python).

## Install

### Using JBang

```bash
jbang app install jpaxa@jbangdev
```

### Releases

Download binaries from [GitHub Releases](https://github.com/jbangdev/jpaxa/releases).

## Usage

```bash
jpaxa --input <dir> --output <file> -- <command> [args...]
```

Example:

```bash
jpaxa --input my-app --output my-app -- "python3" "{{jpaxa}}/main.py"
```

## Command-line options

```text
-i, --input <input>                    Input directory to package
-o, --output <output>                  Output executable path
-F, --no-force                         Do not overwrite output
-e, --exclude <path...>                Exclude paths from packaging
-D, --no-dedupe                        Don't run npm dedupe (ignored in Java port)
-p, --prepare-command <command>        Command to run before packaging
-N, --no-include-node                  Don't copy Node.js executable (ignored in Java port)
-s, --stub <path>                      Use a custom stub
--identifier <identifier>              Extraction/cache identifier
-B, --no-remove-build-directory        Keep build directory
-m, --uncompression-message <message>  Message shown during extraction
```

## The `{{jpaxa}}` placeholder

Inside the runtime command, `{{jpaxa}}` is replaced with the extracted app directory.

Examples:

- `{{jpaxa}}/app.jar`
- `{{jpaxa}}/index.js`
- `{{jpaxa}}/node_modules/.bin/node`

## How it works

A `jpaxa` executable contains:

1. a launcher stub
2. a compressed archive of your application directory
3. metadata describing what command to run

When launched, it:

1. extracts the archive to a cache or temporary location
2. replaces `{{jpaxa}}` in the command with the extracted path
3. runs your command

That keeps the model simple and runtime-agnostic.

## Comparison

### jpaxa vs jpackage

Use `jpaxa` when you want:

- a simple self-extracting executable
- one packaging model across runtimes
- fewer installer-specific conventions

Use `jpackage` when you want:

- native installers
- OS-specific app packaging conventions
- tighter desktop integration

### jpaxa vs native-image

Use `jpaxa` when you want:

- minimal app changes
- runtime-agnostic packaging
- simpler builds
- to keep using the original runtime

Use native-image when you want:

- fast startup
- lower runtime footprint
- actual ahead-of-time native compilation

### jpaxa vs zip or tarball

Use `jpaxa` when you want:

- one file
- no manual unpack step
- launch behavior baked in

## Java note

`jpaxa` can package Java applications well, but it does **not** make Java magically become a single in-memory executable. The packaged app is still extracted to disk before launch because current JDKs expect normal files and directories.

If you are interested in a future where Java apps can run from a fully self-contained packaged image without file extraction, see the OpenJDK [Hermetic Java proposal](https://cr.openjdk.org/~jiangli/hermetic_java.pdf).

## Self-contained Python

`jpaxa` can make a Python application feel self-contained to the end user, but only if you bundle the Python runtime inside the input directory before packaging.

In other words:

- `jpaxa` can package a Python interpreter plus your app into one executable
- `jpaxa` does **not** itself provide or build the Python runtime
- you must prepare a platform-specific Python bundle first

### What this means in practice

If the target machine already has Python installed, this is enough:

```bash
jpaxa \
  --input my-python-app \
  --output my-python-app \
  -- "python3" "{{jpaxa}}/main.py"
```

If the target machine does **not** have Python installed, package a directory that already contains:

- a Python interpreter
- your application code
- any required packages or virtual environment contents

Then launch the bundled interpreter.

### Example layout

```text
my-python-bundle/
  python/
    bin/python3
  app/
    main.py
```

### macOS / Linux example

```bash
jpaxa \
  --input my-python-bundle \
  --output my-tool \
  -- "{{jpaxa}}/python/bin/python3" "{{jpaxa}}/app/main.py"
```

### Windows example

```bash
jpaxa \
  --input my-python-bundle \
  --output my-tool.exe \
  -- "{{jpaxa}}/python/python.exe" "{{jpaxa}}/app/main.py"
```

### Important limitation

A self-contained Python bundle is still platform-specific.

That means you generally need separate bundled runtimes for:

- Windows
- macOS
- Linux

If your app uses native extensions or platform-specific wheels, those must also match the target platform.

## Platform notes

- **Windows** output should end in `.exe`
- **macOS** can also produce `.app` bundles
- **macOS/Linux** can use a shell stub for smaller outputs
- if your app includes native or platform-specific bits, package appropriately for that target platform

## Stubs

The stub is a Go binary that handles extraction and execution. Pre-compiled stubs are available for:

- Windows (x64)
- macOS (x64, ARM64)
- Linux (x64, ARM64, ARM)

### Using pre-compiled stubs

The Java version looks for stubs in:

1. Current directory: `stub-<platform>`
2. `stubs/` directory: `stubs/stub-<platform>`
3. JAR resources: `/stubs/stub-<platform>`

You can compile the stubs yourself or reuse compatible stubs from the original caxa lineage.

### Compiling stubs

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

## Cross-platform building

### Can I build for other platforms?

**Yes, with limitations:**

1. **Java code**: The Java packager can run on any platform with Java installed. You can build packages for any platform from any platform.

2. **Go stubs**: The Go stubs can be cross-compiled using Go's built-in cross-compilation:
   ```bash
   GOOS=windows GOARCH=amd64 go build -o stub.exe stub.go
   ```
   This means you can compile stubs for all platforms from a single machine if you have Go installed.

3. **Limitation**: To create a complete executable for a platform, you need:
   - the Java packager
   - a stub compiled for the target platform
   - the application files, if they contain native binaries for that platform

4. **Executable bits when building on Windows**: When you build jpaxa binaries on Windows that target Unix-like platforms, the packager cannot reliably infer POSIX executable bits from the Windows filesystem. In practice this means inner files that need to be executable on Linux or macOS, for example the `jbang` wrapper, may lose their `+x` bit when extracted. For best results, build Unix-targeted jpaxa binaries on a Unix runner so executable flags are preserved correctly.

### Recommended approach

1. **Build stubs once** for all platforms using Go cross-compilation
2. **Package on each platform** or package cross-platform if your application does not have platform-specific native dependencies
3. **Use CI/CD** to build for all platforms automatically

## Examples

See the `examples/` directory for sample applications.

- [`examples/jbang-wrap`](./examples/jbang-wrap): package a JBang wrapper and ship a single executable
- [`examples/simple-java`](./examples/simple-java): package a small Java application

## Limitations

- first run pays extraction cost
- Java still needs files on disk
- executable-bit preservation matters for Unix targets
- native dependencies still need target-appropriate builds

## License

MIT

## Acknowledgments

This project is inspired by [caxa](https://github.com/leafac/caxa/tree/63f28fb7a1e62e9f08edd1a9f697e0ac5b7ecb85) by Leandro Facchinetti. The Go stub approach comes from that same lineage.
