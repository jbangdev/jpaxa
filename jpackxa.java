///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS org.apache.commons:commons-compress:1.21
//DEPS info.picocli:picocli:4.7.5
//DEPS com.google.code.gson:gson:2.10.1
//JAVA 17+

import static java.nio.file.Files.copy;
import static java.nio.file.Files.createDirectories;
import static java.nio.file.Files.exists;
import static java.nio.file.Files.isDirectory;
import static java.nio.file.Files.write;
import static java.nio.file.Files.writeString;
import static java.nio.file.attribute.PosixFilePermission.*;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.apache.commons.compress.archivers.tar.*;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.ExitCode;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import com.google.gson.Gson;

@Command(name = "jpackxa", description = "Package applications into executable binaries", mixinStandardHelpOptions = true)
public class jpackxa implements Runnable {
    
    @Parameters(index = "0", arity = "1", description = "The input directory to package")
    private Path input;
    
    @Option(names = {"-o", "--output"}, required = true, description = "The path where the executable will be produced, if relative will be relative to the build directory")
    private Path output;

    @Option(names = {"-d", "--directory"}, description = "The path where the executable will be produced", defaultValue = "jpackxa-output")
    private Path build;

    
    @Option(names = {"-F", "--force"}, negatable = true, description = "Overwrite output if they exists. True by default.", defaultValue = "true", fallbackValue = "true")
    private boolean force = true;
    
    @Option(names = {"-e", "--exclude"}, description = "Paths to exclude from the build")
    private List<String> exclude = new ArrayList<>();
    
    @Option(names = {"-p", "--prepare-command"}, description = "Command to run on the build directory before packaging")
    private String prepareCommand;
    
    @Option(names = {"-s", "--stub"}, description = "Path to the platform specific stubs, if not provided will look up in classpath under /stubs")
    private Path stub;
    
    @Option(names = {"--identifier"}, description = "Build identifier, which is part of the path in which the application will be unpacked")
    private String identifier;
    
    @Option(names = {"-B", "--no-remove-build-directory"}, description = "Don't remove the build directory after the build")
    private boolean noRemoveBuildDirectory = false;
    
    @Option(names = {"-m", "--message"}, description = "A message to show when uncompressing")
    private String uncompressionMessage;

    @Option(names = {"--variants"}, description = "Variants to build, will default to current platform and architecture if not provided")
    private List<String> variants = new ArrayList<>();
    
    @Option(names = {"--verbose"}, description = "Verbose output")
    private boolean verbose = false;

    @Parameters(index = "1..*", description = "The command to run and optional arguments")
    private List<String> command = new ArrayList<>();
    
    private static final Pattern APP_PLACEHOLDER = Pattern.compile("\\{\\{\\s*app\\s*\\}\\}");
    
    public static void main(String[] args) {
        System.exit(new CommandLine(new jpackxa()).execute(args));
    }
    
    @Override
    public void run() {
        try {
            packageApplication();
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(ExitCode.SOFTWARE);
        }
    }
    
    boolean endsWith(Path path, String suffix) {
        return path.getFileName().toString().endsWith(suffix);
    }

    private boolean isWindowsVariantStub(String stubName) {
        // stubName is like "stub--win32--x64"
        return stubName.startsWith("stub--win32--");
    }

    private String stripExeSuffix(String path) {
        return path.endsWith(".exe") ? path.substring(0, path.length() - 4) : path;
    }

    private void packageApplication() throws Exception {
        // Validate input
        if (!exists(input) || !isDirectory(input)) {
            throw new IllegalArgumentException("Input isn't a directory: " + input);
        }
        
        // Validate output
        String osName = System.getProperty("os.name").toLowerCase();
        boolean isWindows = osName.contains("win");
        
        output = build.resolve(output);
        
        if (exists(output) && !force) {
            throw new IllegalArgumentException("Output already exists: %s, use --force to overwrite".formatted(output));
        } else if (!exists(output)) {
            createDirectories(output.getParent());
        }
        
        // Create build directory
        Path buildDir = Files.createTempDirectory("jpackxa-");
        try {
            // Copy input to build directory
            copyDirectory(input, buildDir, exclude);
            
            // Run prepare command if specified
            if (prepareCommand != null && !prepareCommand.isEmpty()) {
                ProcessBuilder pb = new ProcessBuilder();
                if (isWindows) {
                    pb.command("cmd", "/c", prepareCommand);
                } else {
                    pb.command("sh", "-c", prepareCommand);
                }
                pb.directory(buildDir.toFile());
                Process process = pb.start();
                int exitCode = process.waitFor();
                if (exitCode != 0) {
                    throw new RuntimeException("Prepare command failed with exit code: " + exitCode);
                }
            }
            
            // Handle .app bundle for macOS
            if (endsWith(output, ".app")) {
                if (!osName.contains("mac")) {
                    throw new IllegalArgumentException("macOS Application Bundles (.app) are supported in macOS only.");
                }
                createMacAppBundle(buildDir);
                if(verbose) {
                    System.out.println("Created macOS Application Bundle: " + output);
                }
                return;
            }
            
            // Handle .sh shell stub
            if (endsWith(output, ".sh")) {
                if (isWindows) {
                    throw new IllegalArgumentException("The Shell Stub (.sh) isn't supported in Windows.");
                }
                createShellStub(buildDir);
                if(verbose) {
                    System.out.println("Created Shell Stub: " + output);
                }
                return;
            }
            
            if(variants.isEmpty()) {
                String stubName = "stub--" + getPlatform() + "--" + getArchitecture();
                // Handle regular binary stub
                createBinaryStub(buildDir, isWindows, stubName);
            } else {
                for (String variant : variants) {
                    createBinaryStub(buildDir, isWindows, "stub--" + variant);
                }
            }
            
        } finally {
            if (!noRemoveBuildDirectory) {
                deleteDirectory(buildDir);
            } else {
                System.out.println("Build directory not removed: " + buildDir);
            }
        }
    }
    
    private void createBinaryStub(Path buildDir, boolean isWindows, String stubName) throws Exception {
       
        String baseOutputPath = output.toString();
        // For Windows variants, ensure the produced filename ends with ".exe" so it is directly runnable on Windows.
        // We put ".exe" at the end (after the variant suffix), because Windows requires the extension at the end.
        String outputPath;
        if (isWindowsVariantStub(stubName)) {
            baseOutputPath = stripExeSuffix(baseOutputPath);
            outputPath = baseOutputPath + "--" + stubName + ".exe";
        } else {
            outputPath = baseOutputPath + "--" + stubName;
        }
        Path stubPath;
        if (stub != null) {
            stubPath = stub;
        } else {
            // Try to find stub in resources or current directory
            stubPath = findStub(stubName);
            if (stubPath == null) {
                throw new IllegalArgumentException(
                    "Stub not found (your operating system / architecture may be unsupported): " + stubName +
                    "\nYou can compile stubs with: go build -o " + stubName + " <path-to-stub.go>"
                );
            }
        }
        
        // Generate identifier if not provided
        if (identifier == null || identifier.isEmpty()) {
            String baseName = output.getFileName().toString();
            baseName = baseName.replaceAll("\\.exe$", "").replaceAll("\\.app$", "").replaceAll("\\.sh$", "");
            identifier = baseName + "/" + generateRandomString(10);
        }
        
        if(Files.exists(Path.of(outputPath)) && !force) {
            throw new IllegalArgumentException("Output already exists: %s, use --force to overwrite".formatted(outputPath));
        }
        if(verbose) {
            System.out.println("Copying stub to output: " + stubPath + " -> " + outputPath);
        }
        // Copy stub to output
        copy(stubPath, new File(outputPath).toPath(), StandardCopyOption.REPLACE_EXISTING);
        
        // Make executable on Unix
        if (!isWindows) {
            try {
                Set<PosixFilePermission> perms = Files.getPosixFilePermissions(new File(outputPath).toPath());
                perms.add(PosixFilePermission.OWNER_EXECUTE);
                perms.add(PosixFilePermission.GROUP_EXECUTE);
                perms.add(PosixFilePermission.OTHERS_EXECUTE);
                Files.setPosixFilePermissions(new File(outputPath).toPath(), perms);
            } catch (UnsupportedOperationException e) {
                // Windows doesn't support PosixFilePermission
            }
        }
        
        // Append tarball
        appendTarball(buildDir, new File(outputPath).toPath());
        
        // Append footer
        Map<String, Object> footer = new HashMap<>();
        footer.put("identifier", identifier);
        footer.put("command", command);
        if (uncompressionMessage != null) {
            footer.put("uncompressionMessage", uncompressionMessage);
        }   
        
        String footerJson = new Gson().toJson(footer);
        if(verbose) {
            System.out.println("Footer JSON: " + footerJson);
        }
        writeString(Path.of(outputPath), "\n" + footerJson, StandardOpenOption.APPEND);
    }
    
    private void createShellStub(Path buildDir) throws Exception {
        // Generate identifier if not provided
        if (identifier == null || identifier.isEmpty()) {
            String baseName = output.getFileName().toString().replaceAll("\\.sh$", "");
            identifier = baseName + "/" + generateRandomString(10);
        }
        
        // Generate shell stub script
        String stubScript = """
            #!/usr/bin/env sh
            export JPACKXA_TEMPORARY_DIRECTORY="$(dirname $(mktemp))/jpackxa"
            export JPACKXA_EXTRACTION_ATTEMPT=-1
            while true
            do
              export JPACKXA_EXTRACTION_ATTEMPT=$(( JPACKXA_EXTRACTION_ATTEMPT + 1 ))
              export JPACKXA_LOCK="$JPACKXA_TEMPORARY_DIRECTORY/locks/%s/$JPACKXA_EXTRACTION_ATTEMPT"
              export JPACKXA_APPLICATION_DIRECTORY="$JPACKXA_TEMPORARY_DIRECTORY/applications/%s/$JPACKXA_EXTRACTION_ATTEMPT"
              if [ -d "$JPACKXA_APPLICATION_DIRECTORY" ] 
              then
                if [ -d "$JPACKXA_LOCK" ] 
                then
                  continue
                else
                  break
                fi
              else
            """.formatted(identifier, identifier);
        if (uncompressionMessage != null) {
            stubScript += "    echo \"%s\" >&2\n".formatted(uncompressionMessage);
        }
        stubScript += """
            mkdir -p "$JPACKXA_LOCK"
            mkdir -p "$JPACKXA_APPLICATION_DIRECTORY"
            tail -n+__STUB_LINES__ "$0" | tar -xz -C "$JPACKXA_APPLICATION_DIRECTORY"
            rmdir "$JPACKXA_LOCK"
            break
          fi
        done
        exec""";

        for (String cmdPart : command) {
            String expanded = APP_PLACEHOLDER.matcher(cmdPart).replaceAll("\"\\$JPACKXA_APPLICATION_DIRECTORY\"");
            stubScript += " \"" + expanded + "\"";
        }
        stubScript += " \"$@\"\n";

        // Use split(..., -1) so trailing empty string is kept (Java drops it by default; caxa/JS does not)
        stubScript = stubScript.replace("__STUB_LINES__", String.valueOf(stubScript.split("\n", -1).length));

        writeString(output, stubScript);
        
        // Make executable
        try {
            var perms = EnumSet.of(
                OWNER_READ, OWNER_WRITE, OWNER_EXECUTE,
                GROUP_READ, GROUP_EXECUTE,
                OTHERS_READ, OTHERS_EXECUTE
            );
            Files.setPosixFilePermissions(output, perms);
        } catch (UnsupportedOperationException e) {
            // Windows doesn't support PosixFilePermission
        }
        
        // Append tarball
        appendTarball(buildDir, output);
    }
    
    private void createMacAppBundle(Path buildDir) throws Exception {
        String name = output.getFileName().toString().replaceAll("\\.app$", "");
        Path appPath = output;
        Path contentsPath = appPath.resolve("Contents");
        Path macosPath = contentsPath.resolve("MacOS");
        Path resourcesPath = contentsPath.resolve("Resources");
        
        createDirectories(macosPath);
        createDirectories(resourcesPath);
        
        // Create MacOS executable
        String macosScript = "#!/usr/bin/env sh\n" +
            "open \"$(dirname \"$0\")/../Resources/" + name + "\"\n";
        Path macosExecutable = macosPath.resolve(name);
        write(macosExecutable, macosScript.getBytes());
        try {
            Set<PosixFilePermission> perms = EnumSet.of(
                OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE
            );
            Files.setPosixFilePermissions(macosExecutable, perms);
        } catch (Exception e) {
            // Ignore
        }
        
        // Create Resources executable
        StringBuilder resourcesScript = new StringBuilder();
        resourcesScript.append("#!/usr/bin/env sh\n");
        for (int i = 0; i < command.size(); i++) {
            String cmdPart = command.get(i);
            String expanded = APP_PLACEHOLDER.matcher(cmdPart).replaceAll("\\$(dirname \"\\$0\")/application");
            if (i > 0) resourcesScript.append(" ");
            resourcesScript.append("\"").append(expanded).append("\"");
        }
        resourcesScript.append("\n");
        
        Path resourcesExecutable = resourcesPath.resolve(name);
        write(resourcesExecutable, resourcesScript.toString().getBytes());
        try {
            Set<PosixFilePermission> perms = EnumSet.of(
                OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE
            );
            Files.setPosixFilePermissions(resourcesExecutable, perms);
        } catch (Exception e) {
            // Ignore
        }
        
        // Move build directory to Resources/application
        Path applicationPath = resourcesPath.resolve("application");
        moveDirectory(buildDir, applicationPath);
    }
    
    private void appendTarball(Path buildDir, Path outputPath) throws Exception {
        try (FileOutputStream fos = new FileOutputStream(outputPath.toFile(), true);
             BufferedOutputStream bos = new BufferedOutputStream(fos);
             GzipCompressorOutputStream gzos = new GzipCompressorOutputStream(bos);
             TarArchiveOutputStream tos = new TarArchiveOutputStream(gzos)) {
            
            tos.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            
            try (Stream<Path> paths = Files.walk(buildDir)) {
                paths.forEach(source -> {
                    try {
                        // Skip the root directory itself
                        if (source.equals(buildDir)) {
                            return;
                        }
                        
                        Path target = buildDir.relativize(source);
                        String targetPath = target.toString().replace('\\', '/');
                        
                        // Ensure path doesn't start with / and is not empty
                        if (targetPath.isEmpty() || targetPath.equals("/")) {
                            return;
                        }
                        
                        // Create entry with relative path (not absolute)
                        TarArchiveEntry entry = new TarArchiveEntry(source.toFile(), targetPath);
                        
                        if (isDirectory(source)) {
                            entry.setMode(TarArchiveEntry.DEFAULT_DIR_MODE);
                        } else {
                            entry.setMode(TarArchiveEntry.DEFAULT_FILE_MODE);
                            entry.setSize(Files.size(source));
                        }
                        
                        tos.putArchiveEntry(entry);
                        
                        if (Files.isRegularFile(source)) {
                            copy(source, tos);
                        }
                        
                        tos.closeArchiveEntry();
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
            }
        }
    }
    
    private void copyDirectory(Path source, Path target, List<String> excludes) throws IOException {
        Set<Path> excludePaths = new HashSet<>();
        for (String excludePattern : excludes) {
            // Simple glob matching - could be enhanced
            Path excludePath = source.resolve(excludePattern);
            if (exists(excludePath)) {
                excludePaths.add(excludePath);
            }
        }
        
        Files.walk(source).forEach(sourcePath -> {
            try {
                // Check if path should be excluded
                boolean shouldExclude = excludePaths.stream().anyMatch(exclude -> 
                    sourcePath.startsWith(exclude) || sourcePath.equals(exclude)
                );
                if (shouldExclude) {
                    return;
                }
                
                Path targetPath = target.resolve(source.relativize(sourcePath));
                if (isDirectory(sourcePath)) {
                    createDirectories(targetPath);
                } else {
                    createDirectories(targetPath.getParent());
                    copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }
    
    private void moveDirectory(Path source, Path target) throws IOException {
        if (exists(target)) {
            deleteDirectory(target);
        }
        Files.move(source, target);
    }
    
    private void deleteDirectory(Path directory) throws IOException {
        if (exists(directory)) {
            try (Stream<Path> paths = Files.walk(directory)) {
                paths.sorted(Comparator.reverseOrder())
                     .forEach(path -> {
                         try {
                             Files.delete(path);
                         } catch (IOException e) {
                             // Ignore
                         }
                     });
            }
        }
    }
    
    private Path findStub(String stubName) {
        // Try current directory
        Path currentDir = Paths.get(".").resolve(stubName);
        if (exists(currentDir)) {
            return currentDir;
        }
        
        // Try stubs directory
        Path stubsDir = Paths.get("stubs").resolve(stubName);
        if (exists(stubsDir)) {
            return stubsDir;
        }
        
        // Try in resources (if packaged as JAR)
        try {
            InputStream is = getClass().getResourceAsStream("/stubs/" + stubName);
            if (is != null) {
                Path tempStub = Files.createTempFile("jpackxa-stub-", "");
                copy(is, tempStub, StandardCopyOption.REPLACE_EXISTING);
                tempStub.toFile().deleteOnExit();
                return tempStub;
            }
        } catch (Exception e) {
            // Ignore
        }
        
        return null;
    }
    
    private String getPlatform() {
        String osName = System.getProperty("os.name").toLowerCase();
        if (osName.contains("win")) return "win32";
        if (osName.contains("mac")) return "darwin";
        if (osName.contains("nix") || osName.contains("nux")) return "linux";
        return "unknown";
    }
    
    private String getArchitecture() {
        String arch = System.getProperty("os.arch").toLowerCase();
        if (arch.contains("amd64") || arch.contains("x86_64")) return "x64";
        if (arch.contains("aarch64") || arch.contains("arm64")) return "arm64";
        if (arch.contains("arm")) return "arm";
        return "unknown";
    }
    
    private String generateRandomString(int length) {
        String chars = "abcdefghijklmnopqrstuvwxyz0123456789";
        Random random = new Random();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        return sb.toString();
    }
}
