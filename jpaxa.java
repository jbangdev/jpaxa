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
import java.nio.charset.StandardCharsets;
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

@Command(
    name = "jpaxa",
    description = "Package applications into executable binaries",
    mixinStandardHelpOptions = true
)
public class jpaxa implements Runnable {
    
    private static final Pattern APP_PLACEHOLDER = Pattern.compile("\\{\\{\\s*app\\s*\\}\\}");
    private static final byte[] ARCHIVE_SEPARATOR = ("\n" + "CAXA".repeat(3) + "\n").getBytes(StandardCharsets.UTF_8);
    private static final byte[] FOOTER_SEPARATOR = "\n".getBytes(StandardCharsets.UTF_8);
    
    public static void main(String[] args) {
        int exitCode = new CommandLine(new jpaxa()).execute(args);
        System.exit(exitCode);
    }
    
    @Override
    public void run() {
        new CommandLine(this).usage(System.err);
    }
    
    // Configuration object used during a single build invocation
    @Command(
        name = "build",
        description = "Package an application directory into a self-extracting executable",
        mixinStandardHelpOptions = true
    )
    int build(
        @Parameters(index = "0", paramLabel = "INPUT", description = "The input directory to package")
        Path input,
        @Option(names = {"-o", "--output"}, paramLabel = "OUTPUT",
                description = "The name of the executable to be produced, if relative will be relative to the build directory")
        Path output,
        @Option(names = {"-d", "--directory"}, paramLabel = "DIR",
                description = "The path where the executable will be produced",
                defaultValue = "build/jpaxa-output")
        Path buildDir,
        @Option(names = {"-F", "--force"}, negatable = true,
                description = "Overwrite output if it exists. True by default.",
                defaultValue = "true", fallbackValue = "true")
        boolean force,
        @Option(names = {"-e", "--exclude"}, paramLabel = "PATH",
                description = "Paths to exclude from the build")
        List<String> exclude,
        @Option(names = {"-p", "--prepare-command"}, paramLabel = "COMMAND",
                description = "Command to run on the build directory before packaging")
        String prepareCommand,
        @Option(names = {"-s", "--stub"}, paramLabel = "PATH",
                description = "Path to the platform specific stubs, if not provided will look up in classpath under /stubs")
        Path stub,
        @Option(names = {"--identifier"}, paramLabel = "ID",
                description = "Build identifier, which is part of the path in which the application will be unpacked")
        String identifier,
        @Option(names = {"-B", "--no-remove-build-directory"},
                description = "Don't remove the build directory after the build")
        boolean noRemoveBuildDirectory,
        @Option(names = {"-m", "--message"}, paramLabel = "TEXT",
                description = "A message to show when uncompressing")
        String uncompressionMessage,
        @Option(names = {"--variants"}, paramLabel = "VARIANT",
                description = "Variants to build; defaults to current platform and architecture if not provided. Use `all` to build all known variants.")
        List<String> variants,
        @Option(names = {"--verbose"}, description = "Verbose output")
        boolean verbose,
        @Parameters(index = "1..*", paramLabel = "COMMAND", description = "The command to run and optional arguments")
        List<String> command
    ) {
        try {
            List<String> effectiveVariants = variants;
            if (effectiveVariants == null || effectiveVariants.isEmpty()) {  // If no variants were provided, build the current platform and architecture
                String stubName = getPlatform() + "-" + getArchitecture();
                effectiveVariants = Arrays.asList(stubName);
            } else if (effectiveVariants.contains("all")) { // If --variants was provided with values, build all known variants
                effectiveVariants = new ArrayList<>(effectiveVariants);
                effectiveVariants.remove("all");
                effectiveVariants.addAll(getKnownVariants().keySet());
            }

            // Validate input
            if (!exists(input) || !isDirectory(input)) {
                throw new IllegalArgumentException("Input isn't a directory: " + input);
            }

            // Validate output
            String osName = System.getProperty("os.name").toLowerCase();
            boolean isWindows = osName.contains("win");

            Path effectiveOutput = output;
            if (effectiveOutput == null) {
                effectiveOutput = Path.of(input.getFileName().toString());
            }

            effectiveOutput = buildDir.resolve(effectiveOutput);

            if (exists(effectiveOutput) && !force) {
                throw new IllegalArgumentException("Output already exists: %s, use --force to overwrite".formatted(effectiveOutput));
            } else if (!exists(effectiveOutput)) {
                createDirectories(effectiveOutput.getParent());
            }

            // Create build directory
            Path tmpBuildDir = Files.createTempDirectory("jpaxa-");
            try {
                // Copy input to build directory
                copyDirectory(input, tmpBuildDir, exclude != null ? exclude : List.of());

                // Run prepare command if specified
                if (prepareCommand != null && !prepareCommand.isEmpty()) {
                    ProcessBuilder pb = new ProcessBuilder();
                    if (isWindows) {
                        pb.command("cmd", "/c", prepareCommand);
                    } else {
                        pb.command("sh", "-c", prepareCommand);
                    }
                    pb.directory(tmpBuildDir.toFile());
                    Process process = pb.start();
                    int exitCode = process.waitFor();
                    if (exitCode != 0) {
                        throw new RuntimeException("Prepare command failed with exit code: " + exitCode);
                    }
                }

                // Handle .app bundle for macOS
                if (endsWith(effectiveOutput, ".app")) {
                    if (!osName.contains("mac")) {
                        throw new IllegalArgumentException("macOS Application Bundles (.app) are supported in macOS only.");
                    }
                    createMacAppBundle(effectiveOutput, tmpBuildDir, command != null ? command : List.of(), verbose);
                    if (verbose) {
                        System.out.println("Created macOS Application Bundle: " + effectiveOutput);
                    }
                    return ExitCode.OK;
                }

                // Handle .sh shell stub
                if (endsWith(effectiveOutput, ".sh")) {
                    if (isWindows) {
                        throw new IllegalArgumentException("The Shell Stub (.sh) isn't supported in Windows.");
                    }
                    createShellStub(effectiveOutput, tmpBuildDir, identifier, uncompressionMessage, command != null ? command : List.of());
                    if (verbose) {
                        System.out.println("Created Shell Stub: " + effectiveOutput);
                    }
                    return ExitCode.OK;
                }

                // Binary stubs for variants
                List<String> cmd = command != null ? command : List.of();
                for (String variant : effectiveVariants) {
                    createBinaryStub(
                        effectiveOutput,
                        tmpBuildDir,
                        isWindows,
                        variant,
                        stub,
                        identifier,
                        force,
                        verbose,
                        uncompressionMessage,
                        cmd
                    );
                }

            } finally {
                if (!noRemoveBuildDirectory) {
                    deleteDirectory(tmpBuildDir);
                } else {
                    System.out.println("Build directory not removed: " + tmpBuildDir);
                }
            }
            return ExitCode.OK;
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            return ExitCode.SOFTWARE;
        }
    }

    private static boolean endsWith(Path path, String suffix) {
        return path.getFileName().toString().endsWith(suffix);
    }

    private static boolean isWindowsVariantStub(String variant) {
        // variant is like "windows-x86_64"
        return variant.startsWith("windows-");
    }

    private static String stripExeSuffix(String path) {
        return path.endsWith(".exe") ? path.substring(0, path.length() - 4) : path;
    }

    private void createBinaryStub(
        Path outputBase,
        Path buildDir,
        boolean isWindows,
        String variant,
        Path explicitStub,
        String explicitIdentifier,
        boolean force,
        boolean verbose,
        String uncompressionMessage,
        List<String> command
    ) throws Exception {

        String baseOutputPath = outputBase.toString();
        // For Windows variants, ensure the produced filename ends with ".exe" so it is directly runnable on Windows.
        // We put ".exe" at the end (after the variant suffix), because Windows requires the extension at the end.
        String outputPath;
        if (isWindowsVariantStub(variant)) {
            baseOutputPath = stripExeSuffix(baseOutputPath);
            outputPath = baseOutputPath + "-" + variant + ".exe";
        } else {
            outputPath = baseOutputPath + "-" + variant;
        }
        Path stubPath = explicitStub;
        if (stubPath == null) {
            stubPath = findStub(variant);
            if (stubPath == null) {
                throw new IllegalArgumentException(
                    "Stub not found (your operating system / architecture may be unsupported): " + variant +
                    "\nYou can compile stubs with: go build -o " + variant + " <path-to-stub.go>"
                );
            }
        }
        
        // Generate identifier if not provided
        String identifier = explicitIdentifier;
        if (identifier == null || identifier.isEmpty()) {
            String baseName = outputBase.getFileName().toString();
            baseName = baseName.replaceAll("\\.exe$", "").replaceAll("\\.app$", "").replaceAll("\\.sh$", "");
            identifier = baseName + "/" + generateRandomString(10);
        }
        
        if (Files.exists(Path.of(outputPath)) && !force) {
            throw new IllegalArgumentException("Output already exists: %s, use --force to overwrite".formatted(outputPath));
        }
        if (verbose) {
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
        if (verbose) {
            System.out.println("Footer JSON: " + footerJson);
        }
        writeString(Path.of(outputPath), "\n" + footerJson, StandardOpenOption.APPEND);

        System.out.println("Created binary: " + outputPath);
    }
    
    private void createShellStub(Path output, Path buildDir, String explicitIdentifier, String uncompressionMessage, List<String> command) throws Exception {
        // Generate identifier if not provided
        String identifier = explicitIdentifier;
        if (identifier == null || identifier.isEmpty()) {
            String baseName = output.getFileName().toString().replaceAll("\\.sh$", "");
            identifier = baseName + "/" + generateRandomString(10);
        }
        
        // Generate shell stub script
        String stubScript = """
            #!/usr/bin/env sh
            export jpaxa_TEMPORARY_DIRECTORY="$(dirname $(mktemp))/jpaxa"
            export jpaxa_EXTRACTION_ATTEMPT=-1
            while true
            do
              export jpaxa_EXTRACTION_ATTEMPT=$(( jpaxa_EXTRACTION_ATTEMPT + 1 ))
              export jpaxa_LOCK="$jpaxa_TEMPORARY_DIRECTORY/locks/%s/$jpaxa_EXTRACTION_ATTEMPT"
              export jpaxa_APPLICATION_DIRECTORY="$jpaxa_TEMPORARY_DIRECTORY/applications/%s/$jpaxa_EXTRACTION_ATTEMPT"
              if [ -d "$jpaxa_APPLICATION_DIRECTORY" ] 
              then
                if [ -d "$jpaxa_LOCK" ] 
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
            mkdir -p "$jpaxa_LOCK"
            mkdir -p "$jpaxa_APPLICATION_DIRECTORY"
            tail -n+__STUB_LINES__ "$0" | tar -xz -C "$jpaxa_APPLICATION_DIRECTORY"
            rmdir "$jpaxa_LOCK"
            break
          fi
        done
        exec""";

        for (String cmdPart : command) {
            String expanded = APP_PLACEHOLDER.matcher(cmdPart).replaceAll("\"\\$jpaxa_APPLICATION_DIRECTORY\"");
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
    
    private void createMacAppBundle(Path output, Path buildDir, List<String> command, boolean verbose) throws Exception {
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
                            // Preserve executability: if the source file is executable, mark it as such
                            if (Files.isExecutable(source)) {
                                entry.setMode(0755);
                            } else {
                                entry.setMode(TarArchiveEntry.DEFAULT_FILE_MODE);
                            }
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
    
    /** 
     * Searches for stubs in the following sequence:
     * 
     * 1. Current directory
     * 2. stubs directory
     * 3. build/stubs directory
     * 4. resources (if packaged as JAR)
     */
    private Path findStub(String stubName) {
        if(!stubName.startsWith("stub-")) {
            stubName = "stub-" + stubName;
        }
        // Check a list of possible stub locations in order and return the first one that exists
        List<Path> candidatePaths = Arrays.asList(
            Paths.get(".", stubName),
            Paths.get("stubs", stubName),
            Paths.get("build/stubs", stubName)
        );
        for (Path path : candidatePaths) {
            if (exists(path)) {
                return path;
            }
        }
        
        // Try in resources (if packaged as JAR)
        try {
            InputStream is = getClass().getResourceAsStream("/stubs/" + stubName);
            if (is != null) {
                Path tempStub = Files.createTempFile("jpaxa-stub-", "");
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
        if (osName.contains("win")) return "windows";
        if (osName.contains("mac")) return "osx";
        if (osName.contains("nix") || osName.contains("nux")) return "linux";
        return "unknown";
    }
    
    private String getArchitecture() {
        String arch = System.getProperty("os.arch").toLowerCase();
        if (arch.contains("amd64") || arch.contains("x86_64")) return "x86_64";
        if (arch.contains("aarch64") || arch.contains("arm64")) return "aarch_64";
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
    
    private Map<String, String> getKnownVariants() {
        Map<String, String> variants = new LinkedHashMap<>();
        try {
            InputStream is = getClass().getResourceAsStream("/stubs/platforms.txt");
            if(is == null) {
                System.err.println("WARN: Platforms file not found, defaults to current platform and architecture");
                return Collections.singletonMap(getPlatform() + "-" + getArchitecture(), getPlatform() + "-" + getArchitecture());
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 3) {
                        String suffix = parts[2];
                        String description = suffix;
                        variants.put(suffix, description);
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("Error reading platforms file: " + e.getMessage());
            return new HashMap<>();
        }
        return variants;
    }
    
    private static class FooterInfo {
        String identifier;
        List<String> command;
        String uncompressionMessage;
    }

    @Command(
        name = "inspect",
        description = "Inspect a jpaxa-created binary and optionally split it into stub, archive, and footer",
        mixinStandardHelpOptions = true
    )
    int inspect(
        @Option(names = {"-x", "--explode"}, description = "Split into <binary>.stub, <binary>.tar.gz, and <binary>.json")
        boolean explode,
        @Parameters(index = "0", paramLabel = "BINARY", description = "The jpaxa binary to inspect")
        Path binary
    ) {
        try {
            inspectBinary(binary, explode);
            return ExitCode.OK;
        } catch (Exception e) {
            System.err.println("Error inspecting binary: " + e.getMessage());
            if (Boolean.getBoolean("jpaxa.inspect.stacktrace")) {
                e.printStackTrace();
            }
            return ExitCode.SOFTWARE;
        }
    }

    @Command(
        name = "verify",
        description = "Verify that all platforms listed in platforms.txt have available stubs",
        mixinStandardHelpOptions = true
    )
    int verify() {
        try {
            verifyStubs();
            return ExitCode.OK;
        } catch (Exception e) {
            System.err.println("Error verifying stubs: " + e.getMessage());
            if (Boolean.getBoolean("jpaxa.verify.stacktrace")) {
                e.printStackTrace();
            }
            return ExitCode.SOFTWARE;
        }
    }

    private static void verifyStubs() throws IOException {
        jpaxa tool = new jpaxa();
        Map<String, String> variants = tool.getKnownVariants();

        if (variants.isEmpty()) {
            throw new IllegalStateException("No platforms found in /stubs/platforms.txt");
        }

        // Detect fallback case where platforms file was missing
        if (variants.size() == 1) {
            String only = variants.keySet().iterator().next();
            String expected = tool.getPlatform() + "-" + tool.getArchitecture();
            if (only.equals(expected)) {
                throw new IllegalStateException("Platforms file '/stubs/platforms.txt' not found on classpath; cannot verify platforms.");
            }
        }

        boolean allOk = true;
        System.out.println("Verifying stubs for " + variants.size() + " platform(s)...");
        for (String platform : variants.keySet()) {
            Path stub = tool.findStub(platform);
            if (stub == null) {
                System.out.println("[MISSING] " + platform + " -> no stub found");
                allOk = false;
            } else {
                System.out.println("[OK]      " + platform + " -> " + stub.toAbsolutePath());
            }
        }

        if (!allOk) {
            throw new IllegalStateException("One or more platforms are missing stubs. See [MISSING] entries above.");
        } else {
            System.out.println("All referenced platforms have available stubs.");
        }
    }

    private static void inspectBinary(Path binary, boolean explode) throws IOException {
        if (!Files.exists(binary)) {
            throw new IllegalArgumentException("File not found: " + binary);
        }

        byte[] bytes = Files.readAllBytes(binary);
        if (bytes.length == 0) {
            throw new IllegalArgumentException("File is empty: " + binary);
        }

        int footerIndex = lastIndexOf(bytes, FOOTER_SEPARATOR);
        if (footerIndex == -1 || footerIndex + FOOTER_SEPARATOR.length >= bytes.length) {
            throw new IllegalArgumentException("Not a valid jpaxa binary (footer separator not found)");
        }
        int footerStart = footerIndex + FOOTER_SEPARATOR.length;
        String footerJson = new String(bytes, footerStart, bytes.length - footerStart, StandardCharsets.UTF_8);

        FooterInfo footer = new Gson().fromJson(footerJson, FooterInfo.class);

        int archiveIndex = lastIndexOf(bytes, ARCHIVE_SEPARATOR);
        if (archiveIndex == -1 || archiveIndex >= footerIndex) {
            throw new IllegalArgumentException("Not a valid jpaxa binary (archive separator not found)");
        }

        int stubEnd = archiveIndex;
        int archiveStart = archiveIndex + ARCHIVE_SEPARATOR.length;
        int archiveLength = footerIndex - archiveStart;
        if (archiveLength < 0) {
            throw new IllegalArgumentException("Not a valid jpaxa binary (inconsistent archive/footer positions)");
        }

        long totalSize = bytes.length;
        System.out.println("File: " + binary.toAbsolutePath());
        System.out.println("Total size:   " + totalSize + " bytes");
        System.out.println("Stub size:    " + stubEnd + " bytes");
        System.out.println("Archive size: " + archiveLength + " bytes");
        System.out.println("Footer size:  " + (bytes.length - footerStart) + " bytes");

        if (footer != null) {
            System.out.println();
            System.out.println("Identifier: " + footer.identifier);
            System.out.println("Command:   " + (footer.command != null ? footer.command : Collections.emptyList()));
            if (footer.uncompressionMessage != null) {
                System.out.println("Message:   " + footer.uncompressionMessage);
            }
        }

        if (explode) {
            Path dir = binary.getParent() != null ? binary.getParent() : Paths.get(".");
            String baseName = binary.getFileName().toString();

            Path stubOut = dir.resolve(baseName + ".stub");
            Path archiveOut = dir.resolve(baseName + ".tar.gz");
            Path footerOut = dir.resolve(baseName + ".json");

            Files.write(stubOut, Arrays.copyOfRange(bytes, 0, stubEnd));
            Files.write(archiveOut, Arrays.copyOfRange(bytes, archiveStart, archiveStart + archiveLength));
            Files.write(footerOut, footerJson.getBytes(StandardCharsets.UTF_8));

            System.out.println();
            System.out.println("Exploded into:");
            System.out.println("  " + stubOut.toAbsolutePath());
            System.out.println("  " + archiveOut.toAbsolutePath());
            System.out.println("  " + footerOut.toAbsolutePath());
        }
    }

    private static int lastIndexOf(byte[] data, byte[] pattern) {
        if (pattern.length == 0 || data.length < pattern.length) {
            return -1;
        }
        outer:
        for (int i = data.length - pattern.length; i >= 0; i--) {
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
