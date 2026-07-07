package org.javalens.mcp.tools.project;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.fixtures.TestRegistryBuilder;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.Tool;
import org.javalens.mcp.tools.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the load_project -> health_check state seam against the REAL
 * {@code JavaLensApplication.registerTools()} wiring. The earlier protocol
 * tests used replica registrations (a test-local callback that only stores
 * the service), so they structurally could not catch the production wiring
 * bug where loading a project via the tool left health_check reporting "not
 * loaded" (the loading state was flipped only by the JAVA_PROJECT_PATH
 * auto-load path). This test exercises the actual registered tools.
 */
class LoadProjectHealthCheckIntegrationTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private ToolRegistry registry;
    private ObjectMapper objectMapper;
    private Path projectPath;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper();
        projectPath = helper.getFixturePath("simple-maven");

        // Build the app and run its REAL registerTools() so the load_project
        // tool carries its production callback (the place the bug lived).
        // No service yet — these tests call load_project themselves and assert
        // it flips the bound session's attached project.
        registry = TestRegistryBuilder.buildRegistry(null);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) {
        return (Map<String, Object>) r.getData();
    }

    private ToolResponse call(String toolName, ObjectNode args) {
        Tool tool = registry.getTool(toolName).orElseThrow();
        return tool.execute(args);
    }

    @Test
    @DisplayName("health_check reports not-loaded before any load")
    void healthCheck_beforeLoad_notLoaded() {
        ToolResponse health = call("health_check", objectMapper.createObjectNode());
        assertTrue(health.isSuccess());
        @SuppressWarnings("unchecked")
        Map<String, Object> project = (Map<String, Object>) getData(health).get("project");
        assertEquals(false, project.get("loaded"));
    }

    @Test
    @DisplayName("health_check reports LOADED after a successful load_project tool call")
    void healthCheck_afterToolLoad_loaded() {
        ObjectNode loadArgs = objectMapper.createObjectNode();
        loadArgs.put("projectPath", projectPath.toString());
        ToolResponse load = call("load_project", loadArgs);
        assertTrue(load.isSuccess(), () -> "load_project failed: " + load.getError());

        ToolResponse health = call("health_check", objectMapper.createObjectNode());
        assertTrue(health.isSuccess());
        Map<String, Object> data = getData(health);
        @SuppressWarnings("unchecked")
        Map<String, Object> project = (Map<String, Object>) data.get("project");

        assertEquals(true, project.get("loaded"),
            "loading a project via the tool must flip health_check to loaded (issue #30 thread)");
        assertEquals("Ready", data.get("status"));
        assertEquals("loaded", project.get("status"));
        assertEquals("strict", data.get("diskSync"), "the live service's disk-sync mode is reported");
    }

    // ========== MCP envelope seam: load_project -> health_check through processMessage ==========

    @Test
    @DisplayName("Through the real MCP envelope (processMessage): load_project flips health_check to loaded/Ready/strict")
    void envelope_loadThenHealthCheck_reportsLoaded() throws Exception {
        // The integration tests above drive the real registerTools() wiring but call tools
        // via execute(). This adds the missing JSON-RPC serialization layer: the same
        // load_project -> health_check transition driven through McpProtocolHandler.processMessage.
        JdtServiceImpl loaded = helper.loadProject("simple-maven");
        EnvelopeHarness envelope = new EnvelopeHarness(loaded);

        // Drive load_project through the wire so its production callback flips the load state.
        ObjectNode loadArgs = envelope.args();
        loadArgs.put("projectPath", projectPath.toString());
        JsonNode loadPayload = envelope.assertEnvelopeFidelity("load_project", loadArgs);
        assertTrue(loadPayload.get("success").asBoolean(),
            () -> "load_project failed through the envelope: " + loadPayload);

        JsonNode healthPayload = envelope.assertEnvelopeFidelity("health_check", envelope.args());
        assertTrue(healthPayload.get("success").asBoolean(),
            () -> "health_check failed through the envelope: " + healthPayload);
        JsonNode project = healthPayload.get("data").get("project");
        assertTrue(project.get("loaded").asBoolean(),
            "health_check must report loaded after a load_project tools/call through processMessage");
        assertEquals("Ready", healthPayload.get("data").get("status").asText());
        assertEquals("loaded", project.get("status").asText());
        assertEquals("strict", healthPayload.get("data").get("diskSync").asText(),
            "the live service's disk-sync mode must survive the JSON-RPC envelope");
    }
}
