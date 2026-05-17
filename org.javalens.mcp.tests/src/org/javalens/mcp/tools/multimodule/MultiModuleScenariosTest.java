package org.javalens.mcp.tools.multimodule;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.AnalyzeChangeImpactTool;
import org.javalens.mcp.tools.FindCircularDependenciesTool;
import org.javalens.mcp.tools.FindImplementationsTool;
import org.javalens.mcp.tools.GetDependencyGraphTool;
import org.javalens.mcp.tools.RenameSymbolTool;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cross-module behavior through the MCP tool boundary for tools that aggregate
 * across reactor modules. Pairs with {@code CrossModuleNavigationToolTest} (which
 * covers find_references). T-4 in the 1.4.0 plan: the single-module test bias
 * (97.7% of tool tests use simple-maven) was missing per-tool variants that
 * exercise multi-module-aware behavior.
 *
 * <p>One {@code @BeforeAll} bootstraps the reactor (copies the fixture, runs
 * {@code mvn install} to seed the local repo with sibling artifacts so
 * {@code dependency:build-classpath} resolves) and loads a {@code JdtServiceImpl}
 * shared across all tests in this class. Tests are read-only relative to that
 * state.
 *
 * <p>Each test exercises one tool against the {@code multi-module-maven} fixture:
 * web → impl → api with {@code Greeter} (interface in :api), {@code GreeterImpl}
 * (in :impl), {@code GreeterController} (in :web).
 */
class MultiModuleScenariosTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private Path projectRoot;
    private ObjectMapper objectMapper;

    @BeforeEach
    void bootstrap() throws Exception {
        String mvn = resolveMavenBinary();
        if (Boolean.parseBoolean(System.getenv("JAVALENS_TESTS_REQUIRE_TOOLS"))) {
            assertNotNull(mvn,
                "JAVALENS_TESTS_REQUIRE_TOOLS=true requires Maven for multi-module tests");
        } else {
            Assumptions.assumeTrue(mvn != null,
                "Maven binary unavailable; multi-module navigation needs the reactor loaded");
        }

        // Point ProjectImporter at the same Maven binary the test bootstraps with.
        System.setProperty("javalens.maven.binary", mvn);

        projectRoot = helper.copyFixture("multi-module-maven");
        runMaven(mvn, projectRoot, "install", "-DskipTests", "-B", "-fae", "-q");

        service = new JdtServiceImpl();
        service.loadProject(projectRoot);
        objectMapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @Test
    @DisplayName("find_implementations on Greeter (in :api) finds GreeterImpl (in :impl) across module boundary")
    @SuppressWarnings("unchecked")
    void findImplementations_crossesModuleBoundary() {
        FindImplementationsTool tool = new FindImplementationsTool(() -> service);

        // Position on "Greeter" type name in interface declaration:
        // line 2 (0-based) of "public interface Greeter {", column 17 where 'G' begins.
        Path greeter = projectRoot.resolve("api/src/main/java/com/example/api/Greeter.java");
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", greeter.toString());
        args.put("line", 2);
        args.put("column", 17);
        args.put("maxResults", 50);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(),
            "find_implementations must succeed; got: "
                + (r.getError() != null ? r.getError().getCode() : "n/a"));
        Map<String, Object> data = getData(r);
        List<Map<String, Object>> implementations = (List<Map<String, Object>>) data.get("implementations");
        assertNotNull(implementations, "implementations list must be present; got: " + data);

        boolean foundGreeterImpl = implementations.stream().anyMatch(impl -> {
            Object qn = impl.get("qualifiedName");
            return qn != null && qn.toString().equals("com.example.impl.GreeterImpl");
        });
        assertTrue(foundGreeterImpl,
            "GreeterImpl in :impl must be returned as an implementation of Greeter declared in :api. "
                + "Got: " + implementations);
    }

    @Test
    @DisplayName("rename_symbol on Greeter.sayHello propagates to GreeterImpl (in :impl)")
    @SuppressWarnings("unchecked")
    void renameSymbol_crossesModuleBoundary() {
        RenameSymbolTool tool = new RenameSymbolTool(() -> service);

        // Greeter.sayHello declared at line 3 (0-based), method name starts at column 11
        // ("    String sayHello(...)" — 4 spaces + "String " = 11 chars).
        Path greeter = projectRoot.resolve("api/src/main/java/com/example/api/Greeter.java");
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", greeter.toString());
        args.put("line", 3);
        args.put("column", 11);
        args.put("newName", "greet");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(),
            "rename_symbol must succeed; got: "
                + (r.getError() != null ? r.getError().getCode() : "n/a"));
        Map<String, Object> data = getData(r);

        // The tool should report edits in both :api (declaration) and :impl
        // (the @Override method must be renamed for the contract to hold).
        // Edits/changes might be under various keys depending on the tool's output shape;
        // accept "changes" or "edits" or a stringified content that mentions both files.
        String repr = data.toString();
        assertTrue(repr.contains("Greeter.java"),
            "rename must touch Greeter.java (the declaration); got: " + repr);
        assertTrue(repr.contains("GreeterImpl.java"),
            "rename must propagate to GreeterImpl.java (the implementor); got: " + repr);
    }

    @Test
    @DisplayName("analyze_change_impact on Greeter.sayHello reports cross-module affected files")
    @SuppressWarnings("unchecked")
    void analyzeChangeImpact_crossesModuleBoundary() {
        AnalyzeChangeImpactTool tool = new AnalyzeChangeImpactTool(() -> service);

        Path greeter = projectRoot.resolve("api/src/main/java/com/example/api/Greeter.java");
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", greeter.toString());
        args.put("line", 3);
        args.put("column", 11);
        args.put("depth", 2);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(),
            "analyze_change_impact must succeed; got: "
                + (r.getError() != null ? r.getError().getCode() : "n/a"));
        Map<String, Object> data = getData(r);

        List<Map<String, Object>> affectedFiles = (List<Map<String, Object>>) data.get("affectedFiles");
        assertNotNull(affectedFiles, "affectedFiles list must be present; got: " + data);

        // analyze_change_impact tracks CALL sites, not OVERRIDE sites. GreeterImpl
        // implements (overrides) sayHello but doesn't call it, so it legitimately won't
        // appear here. GreeterController (in :web) calls Greeter.sayHello via the
        // injected interface — that's the cross-module hit this tool should surface.
        boolean spansForeignModule = affectedFiles.stream().anyMatch(af -> {
            Object fp = af.get("filePath");
            if (fp == null) return false;
            String p = fp.toString().replace('\\', '/');
            // Anything outside the :api module is a cross-module affected file.
            return !p.contains("/api/src/");
        });
        assertTrue(spansForeignModule,
            "analyze_change_impact must report at least one cross-module affected file "
                + "(GreeterController in :web calls Greeter.sayHello). Got: " + affectedFiles);
    }

    @Test
    @DisplayName("get_dependency_graph for GreeterImpl includes the api.Greeter type from a sibling module")
    @SuppressWarnings("unchecked")
    void getDependencyGraph_crossesModuleBoundary() {
        GetDependencyGraphTool tool = new GetDependencyGraphTool(() -> service);

        ObjectNode args = objectMapper.createObjectNode();
        args.put("scope", "type");
        args.put("name", "com.example.impl.GreeterImpl");
        args.put("depth", 2);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(),
            "get_dependency_graph must succeed; got: "
                + (r.getError() != null ? r.getError().getCode() : "n/a"));
        Map<String, Object> data = getData(r);

        // The output shape is rich; assert the api.Greeter appears anywhere in the graph
        // representation. If it doesn't, the dependency-graph builder missed the cross-
        // module edge from impl to api.
        String repr = data.toString();
        assertTrue(repr.contains("com.example.api.Greeter"),
            "Dependency graph for GreeterImpl must include com.example.api.Greeter as a "
                + "cross-module dependency; got: " + repr);
    }

    @Test
    @DisplayName("find_circular_dependencies reports no cycles for the clean multi-module fixture")
    @SuppressWarnings("unchecked")
    void findCircularDependencies_cleanReactor_isEmpty() {
        FindCircularDependenciesTool tool = new FindCircularDependenciesTool(() -> service);

        ObjectNode args = objectMapper.createObjectNode();
        args.put("packageFilter", "com.example");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(),
            "find_circular_dependencies must succeed; got: "
                + (r.getError() != null ? r.getError().getCode() : "n/a"));
        Map<String, Object> data = getData(r);

        // Reactor is web -> impl -> api with no back-edges; cycle list must be empty.
        // Output key may be "cycles" or "circularDependencies" depending on the tool.
        Object cycles = data.get("cycles");
        if (cycles == null) cycles = data.get("circularDependencies");
        assertNotNull(cycles, "cycles list must be present under 'cycles' or 'circularDependencies'; got: " + data);
        if (cycles instanceof List<?> list) {
            assertEquals(0, list.size(),
                "Clean multi-module fixture has no cycles (web -> impl -> api, no back-edges); "
                    + "got: " + list);
        } else {
            assertFalse(cycles.toString().contains("->"),
                "Expected empty cycles or non-list shape with no '->' arrows; got: " + cycles);
        }
    }

    // ========== Maven bootstrap helpers (mirror CrossModuleNavigationToolTest) ==========

    private static void runMaven(String mvn, Path projectRoot, String... goals)
            throws IOException, InterruptedException {
        java.util.List<String> command = new java.util.ArrayList<>();
        command.add(mvn);
        for (String g : goals) command.add(g);
        Process p = new ProcessBuilder(command)
            .directory(projectRoot.toFile())
            .redirectErrorStream(true)
            .start();
        StringBuilder captured = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (captured.length() < 8192) captured.append(line).append('\n');
            }
        }
        if (!p.waitFor(5, TimeUnit.MINUTES)) {
            p.destroyForcibly();
            throw new RuntimeException("mvn " + String.join(" ", goals) + " timed out\n" + captured);
        }
        if (p.exitValue() != 0) {
            throw new RuntimeException("mvn " + String.join(" ", goals)
                + " failed with exit code " + p.exitValue() + "\n" + captured);
        }
    }

    private static String resolveMavenBinary() {
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        String name = isWindows ? "mvn.cmd" : "mvn";
        try {
            Process p = new ProcessBuilder(name, "-v").redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                while (r.readLine() != null) { /* drain */ }
            }
            p.waitFor();
            if (p.exitValue() == 0) return name;
        } catch (IOException | InterruptedException ignored) {
            if (Thread.interrupted()) Thread.currentThread().interrupt();
        }
        Path wrapperDists = Path.of(System.getProperty("user.home"), ".m2", "wrapper", "dists");
        if (!Files.isDirectory(wrapperDists)) return null;
        try (Stream<Path> distros = Files.list(wrapperDists)) {
            for (Path distro : distros.toList()) {
                try (Stream<Path> hashes = Files.list(distro)) {
                    for (Path h : hashes.toList()) {
                        Path bin = h.resolve("bin").resolve(name);
                        if (Files.isRegularFile(bin)) return bin.toString();
                    }
                }
            }
        } catch (IOException ignored) {}
        return null;
    }
}
