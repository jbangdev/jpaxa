# jpaxa

Turn an application directory plus a startup command into a **single self-extracting executable**.

`jpaxa` packages your app as-is, wraps it in a small launcher, extracts it on first run, and executes your command. Works for **JBang apps, Java jars, Node tools, Python CLIs, and other runtime-based applications**.

## Install

### Download a binary

Grab the binary for your platform from the [early-access release](https://github.com/jbangdev/jpaxa/releases/tag/early-access):

| Platform | Binary |
|---|---|
| Linux x64 | [`jpaxa-linux-x86_64`](https://github.com/jbangdev/jpaxa/releases/download/early-access/jpaxa-linux-x86_64) |
| Linux ARM64 | [`jpaxa-linux-aarch_64`](https://github.com/jbangdev/jpaxa/releases/download/early-access/jpaxa-linux-aarch_64) |
| Linux ARM 32-bit | [`jpaxa-linux-arm_32`](https://github.com/jbangdev/jpaxa/releases/download/early-access/jpaxa-linux-arm_32) |
| macOS x64 | [`jpaxa-osx-x86_64`](https://github.com/jbangdev/jpaxa/releases/download/early-access/jpaxa-osx-x86_64) |
| macOS ARM64 | [`jpaxa-osx-aarch_64`](https://github.com/jbangdev/jpaxa/releases/download/early-access/jpaxa-osx-aarch_64) |
| Windows x64 | [`jpaxa-windows-x86_64.exe`](https://github.com/jbangdev/jpaxa/releases/download/early-access/jpaxa-windows-x86_64.exe) |

### Using JBang

```bash
jbang app install --name jpaxa jpaxa-earlyaccess@jbangdev/jpaxa
```

Or run directly without installing:

```bash
jbang jpaxa-earlyaccess@jbangdev/jpaxa build ...
```

## Quick start

### Package a JBang app

```bash
mkdir app
jbang wrapper install -d app
jpaxa build app -- "{{app}}/jbang" "env@jbangdev"
```

This produces a self-extracting executable that carries the JBang wrapper and runs the app on the target machine.

### Package a Java jar

```bash
jpaxa build my-java-app -- "java" "-jar" "{{app}}/app.jar"
```

### Package a Node CLI

```bash
jpaxa build my-node-app -- "node" "{{app}}/index.js"
```

## Why jpaxa

Sometimes you do not want a full installer, container packaging, GraalVM native-image complexity, or users manually unpacking archives. You just want **one file** that **runs on the target platform** while keeping the app's normal runtime and file layout.

### Good fit

- Ship a **JBang app** as a single executable
- Distribute an internal **CLI tool** as one file
- Package a **Node**, **Python**, or **Java** app without building an installer
- Wrap an existing app with minimal restructuring

### Not the right tool

- Not a native-image replacement (no ahead-of-time compilation)
- Not a jpackage replacement (no native installers)
- Not optimized for fastest possible first startup
- Java apps are still extracted to disk before launch

## Commands

### `jpaxa build`

Package an application directory into a self-extracting executable.

```bash
jpaxa build [OPTIONS] INPUT [-- COMMAND [ARGS...]]
```

**Arguments:**

| Argument | Description |
|---|---|
| `INPUT` | The input directory to package |
| `COMMAND [ARGS...]` | The command to run when the executable is launched |

**Options:**

| Option | Description |
|---|---|
| `-d, --directory DIR` | Directory where the executable will be produced |
| `-o, --output OUTPUT` | Name of the executable (relative to build directory) |
| `-s, --stub PATH` | Path to platform-specific stubs |
| `-e, --exclude PATH` | Paths to exclude from the build (repeatable) |
| `-F, --no-force` | Do not overwrite output if it exists |
| `-p, --prepare-command CMD` | Command to run on build directory before packaging |
| `--identifier ID` | Build identifier for the extraction cache path |
| `-m, --message TEXT` | Message shown during extraction |
| `--variants VARIANT` | Variants to build (`all` for all platforms) |
| `-B, --no-remove-build-directory` | Keep the build directory after the build |
| `--verbose` | Verbose output |

### `jpaxa inspect`

Inspect a jpaxa-created binary and optionally split it into its parts.

```bash
jpaxa inspect [-x] BINARY
```

| Option | Description |
|---|---|
| `-x, --explode` | Split into `<binary>.stub`, `<binary>.tar.gz`, and `<binary>.json` |

### `jpaxa verify`

Verify that all platforms listed in `platforms.txt` have available stubs.

```bash
jpaxa verify
```

## The `{{app}}` placeholder

Inside the runtime command, `{{app}}` is replaced with the extracted application directory at runtime.

```bash
jpaxa build my-app -- "java" "-jar" "{{app}}/app.jar"
#                                    ^^^^^^^ replaced at runtime
```

## How it works

A jpaxa executable contains:

1. A launcher stub (small Go binary)
2. A compressed archive of your application directory
3. Metadata describing what command to run

When launched, it extracts the archive to a cache location, replaces `{{app}}` in the command with the extracted path, and runs your command. Subsequent runs skip extraction.

## Cross-platform building

jpaxa can cross-compile for all supported platforms from a single machine:

```bash
# Build for all platforms at once
jpaxa build my-app --variants all -- "{{app}}/run.sh"
```

The Go stubs are cross-compiled for all six platform variants. The Java packager runs anywhere with Java 17+.

**Limitation:** When building Unix-targeted binaries on Windows, POSIX executable bits may not be preserved. Build Unix targets on a Unix runner for best results.

## Comparison

| | jpaxa | jpackage | native-image | zip/tarball |
|---|---|---|---|---|
| Single file | ✅ | ✅ (installer) | ✅ | ❌ |
| No recompilation | ✅ | ✅ | ❌ | ✅ |
| Runtime-agnostic | ✅ | Java only | Java only | ✅ |
| Fast cold start | ❌ | ❌ | ✅ | N/A |
| Native installers | ❌ | ✅ | ❌ | ❌ |
| No extraction step | ❌ | ❌ | ✅ | ❌ |

## Stubs

The stub is a Go binary that handles extraction and execution. Pre-compiled stubs ship inside the jpaxa jar for all supported platforms:

- `linux-x86_64`, `linux-aarch_64`, `linux-arm_32`
- `osx-x86_64`, `osx-aarch_64`
- `windows-x86_64`

You can also compile stubs yourself from `stub.go` using Go cross-compilation and pass them via `--stub`.

## Examples

See the [`examples/`](./examples) directory:

- [`examples/jbang-wrap`](./examples/jbang-wrap) — package a JBang wrapper into a single executable
- [`examples/simple-java`](./examples/simple-java) — package a small Java application

## Platform notes

- Windows output should end in `.exe`
- macOS can also produce `.app` bundles
- First run pays the extraction cost; subsequent runs use the cache

## License

MIT

## Acknowledgments

Inspired by [caxa](https://github.com/leafac/caxa/tree/63f28fb7a1e62e9f08edd1a9f697e0ac5b7ecb85) by Leandro Facchinetti.
