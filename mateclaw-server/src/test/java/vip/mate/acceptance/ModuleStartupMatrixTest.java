package vip.mate.acceptance;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ModuleStartupMatrixTest {
    @Test
    void allDisabled() throws Exception {
        runVariant("000");
    }

    @ParameterizedTest
    @ValueSource(strings = {"111", "100", "010", "001", "110", "101", "011"})
    void moduleCombinations(String variant) throws Exception {
        runVariant(variant);
    }

    @Test
    void presalesRecoveryAcrossProductionProcessRestarts() throws Exception {
        Path root = Files.createTempDirectory("mateclaw-ac21-restart-");
        var seed = runVariant("010", root, "restart-seed");
        var recovered = runVariant("010", root, "restart-recover");
        var repeated = runVariant("010", root, "restart-repeat");
        assertEquals(
                3,
                java.util.Set.of(
                                seed.path("pid").asLong(),
                                recovered.path("pid").asLong(),
                                repeated.path("pid").asLong())
                        .size());
        assertEquals(seed.path("database"), recovered.path("database"));
        assertEquals(recovered.path("database"), repeated.path("database"));
        cleanup(root);
    }

    private void runVariant(String variant) throws Exception {
        Path root = Files.createTempDirectory("mateclaw-ac05-" + variant + "-");
        runVariant(variant, root, "ordinary");
        cleanup(root);
    }

    private com.fasterxml.jackson.databind.JsonNode runVariant(
            String variant, Path root, String phase) throws Exception {
        Path home = Files.createDirectories(root.resolve("home"));
        Path tmp = Files.createDirectories(root.resolve("tmp"));
        Path classes = Files.createDirectories(root.resolve("probe-classes"));
        Path target =
                Path.of("target", "ac05-main-results", phase.equals("ordinary") ? variant : phase)
                        .toAbsolutePath();
        Files.createDirectories(target);
        Path log = target.resolve("startup.log");
        Files.deleteIfExists(target.resolve("result.json"));
        Files.deleteIfExists(root.resolve("result.json"));
        Files.writeString(target.resolve("sandbox-path.txt"), root.toString());
        Path ownClasses =
                Path.of(
                        ModuleStartupProbe.class
                                .getProtectionDomain()
                                .getCodeSource()
                                .getLocation()
                                .toURI());
        Path packagePath = Path.of("vip", "mate", "acceptance");
        Files.createDirectories(classes.resolve(packagePath));
        try (var files = Files.list(ownClasses.resolve(packagePath))) {
            for (Path source :
                    files.filter(
                                    p ->
                                            p.getFileName()
                                                    .toString()
                                                    .matches(
                                                            "(?:ModuleStartupProbe|OrdinaryChatProbe|PresalesRestartProbe)(?:\\$.*)?\\.class"))
                            .toList())
                Files.copy(
                        source,
                        classes.resolve(packagePath).resolve(source.getFileName()),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        Path factories = classes.resolve("META-INF/spring.factories");
        Files.createDirectories(factories.getParent());
        Files.writeString(
                factories,
                "org.springframework.context.ApplicationListener="
                        + ModuleStartupProbe.ReadyListener.class.getName()
                        + "\n");
        String sourceClasspath =
                System.getProperty(
                        "surefire.test.class.path", System.getProperty("java.class.path"));
        var classpath = new ArrayList<String>();
        classpath.add(classes.toString());
        Arrays.stream(sourceClasspath.split(java.util.regex.Pattern.quote(File.pathSeparator)))
                .map(p -> Path.of(p).toAbsolutePath().normalize())
                .filter(p -> !p.getFileName().toString().equals("test-classes"))
                .map(Path::toString)
                .forEach(classpath::add);
        var command = new ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-Xmx768m");
        command.add("-Duser.home=" + home);
        command.add("-Djava.io.tmpdir=" + tmp);
        command.add("-cp");
        command.add(String.join(File.pathSeparator, classpath));
        command.add(ModuleStartupProbe.class.getName());
        command.add(variant);
        command.add(root.toString());
        command.add(phase);
        var builder =
                new ProcessBuilder(command)
                        .directory(root.toFile())
                        .redirectErrorStream(true)
                        .redirectOutput(log.toFile());
        builder.environment().clear();
        builder.environment().put("LANG", "en_US.UTF-8");
        var process = builder.start();
        boolean exited;
        try {
            exited = process.waitFor(120, TimeUnit.SECONDS);
        } finally {
            if (process.isAlive()) {
                var descendants = process.descendants().toList();
                descendants.forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                assertTrue(
                        process.waitFor(10, TimeUnit.SECONDS), "Child did not terminate: " + log);
                for (var child : descendants) {
                    child.onExit().get(10, TimeUnit.SECONDS);
                    assertFalse(child.isAlive(), "Descendant did not terminate");
                }
            }
        }
        assertTrue(exited, "Child startup timed out: " + log);
        assertEquals(
                phase.equals("restart-seed") ? 23 : 0,
                process.exitValue(),
                "Child startup failed: " + log);
        Path result = root.resolve("result.json");
        assertTrue(Files.isRegularFile(result), "Missing probe result");
        Files.copy(
                result,
                target.resolve("result.json"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        var report = new ObjectMapper().readTree(result.toFile());
        assertEquals(variant, report.path("variant").asText());
        assertTrue(report.path("ready_event").asBoolean());
        assertEquals(!phase.equals("restart-seed"), report.path("context_closed").asBoolean());
        assertEquals(phase, report.path("phase").asText());
        assertEquals(
                phase.equals("restart-recover") || phase.equals("restart-repeat"),
                report.path("setup_initialized").asBoolean());
        return report;
    }

    private void cleanup(Path root) throws Exception {
        Files.walkFileTree(
                root,
                new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                            throws IOException {
                        Files.delete(file);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path directory, IOException error)
                            throws IOException {
                        if (error != null) throw error;
                        Files.delete(directory);
                        return FileVisitResult.CONTINUE;
                    }
                });
        assertFalse(Files.exists(root), "Successful sandbox must be removed");
    }
}
