# Simple Java Example

This example shows how to package a simple Java application with `jpaxa`.

## Steps

1. Compile the Java application:
   ```bash
   javac Hello.java
   ```

2. Package it with `jpaxa`:
   ```bash
   jpaxa \
     --input . \
     --output hello \
     -- "java" "-cp" "{{jpaxa}}" "Hello" "some" "arguments"
   ```

3. Run the packaged executable:
   ```bash
   ./hello additional args
   ```

Note: On Windows, use `hello.exe` as the output name.
