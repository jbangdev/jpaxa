# JBang wrapper example

This example shows how to package a JBang wrapper with `jpaxa` so you can ship a single executable that can run a JBang app on the target machine.

## What it demonstrates

- wrapping an app directory that already contains the JBang wrapper scripts
- using `{{jpaxa}}` to locate the extracted wrapper at runtime
- producing a single self-extracting executable instead of asking users to unpack files manually

## Prepare the wrapper

From this directory, create the JBang wrapper files:

```bash
jbang wrapper install -d .
```

That gives you files such as `jbang`, `jbang.cmd`, and `jbang.ps1` inside this example directory.

## Build the executable

### macOS / Linux

```bash
jpaxa \
  --input . \
  --output jbang-wrap \
  -- "{{jpaxa}}/jbang" "env@jbangdev"
```

### Windows

```bash
jpaxa \
  --input . \
  --output jbang-wrap.exe \
  -- "{{jpaxa}}/jbang.cmd" "env@jbangdev"
```

## Run it

```bash
./jbang-wrap
```

You should see the output from `env@jbangdev`, executed through the extracted JBang wrapper bundled in the executable.

## Why this example matters

This is one of the nicest `jpaxa` use cases:

- author a tool with JBang
- bundle the wrapper once
- ship a single file to users

No installer, no manual unzip step, and no need to explain the internal file layout to the user.
