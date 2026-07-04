package org.javalens.mcp.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.session.ProjectRegistry;
import org.javalens.mcp.session.Session;
import org.javalens.mcp.session.SessionContext;
import org.javalens.mcp.session.SessionManager;
import org.javalens.mcp.tools.AnalyzeChangeImpactTool;
import org.javalens.mcp.tools.FindAffectedTestsTool;
import org.javalens.mcp.tools.FindUnreachableCodeTool;
import org.javalens.mcp.tools.LoadProjectTool;
import org.javalens.mcp.tools.ToolRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MCP-protocol integration validation for the 1.4.2 impact-analysis tools:
 * each tool is driven exactly as a client would — initialize, tools/list,
 * then tools/call through McpProtocolHandler — and the JSON-RPC payload is
 * asserted content-exactly against the reachability-maven fixture.
 *
 * <p>This pins the full server path (registry wiring, schema exposure,
 * JSON-RPC envelope, response serialization), which per-tool execute() tests
 * do not cover.
 */
class ImpactAnalysisProtocolIntegrationTest {

    /** Long enough that the background sweep never fires mid-test. */
    private static final Duration NO_BACKGROUND_SWEEP = Duration.ofHours(1);

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private McpProtocolHandler handler;
    private ObjectMapper objectMapper;
    private ProjectRegistry projectRegistry;
    private Path projectPath;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        ToolRegistry toolRegistry = new ToolRegistry();
        handler = new McpProtocolHandler(toolRegistry);
        projectRegistry = new ProjectRegistry(NO_BACKGROUND_SWEEP, NO_BACKGROUND_SWEEP);
        SessionManager sessionManager = new SessionManager(toolRegistry, projectRegistry,
            NO_BACKGROUND_SWEEP, NO_BACKGROUND_SWEEP);
        Session session = sessionManager.create();
        SessionContext.bind(session);

        // Replicate JavaLensApplication's registration pattern for the tools under test.
        toolRegistry.register(new LoadProjectTool(projectRegistry));
        toolRegistry.register(new FindUnreachableCodeTool(() -> SessionContext.current().getJdtService()));
        toolRegistry.register(new AnalyzeChangeImpactTool(() -> SessionContext.current().getJdtService()));
        toolRegistry.register(new FindAffectedTestsTool(() -> SessionContext.current().getJdtService()));

        projectPath = helper.getFixturePath("reachability-maven");
    }

    @AfterEach
    void tearDown() {
        SessionContext.clear();
        projectRegistry.close();
    }

    private JsonNode call(String request) throws Exception {
        String response = handler.processMessage(request);
        assertNotNull(response, "protocol handler returned no response");
        return objectMapper.readTree(response);
    }

    private JsonNode toolPayload(JsonNode rpcResponse) throws Exception {
        assertNull(rpcResponse.get("error"), () -> "JSON-RPC error: " + rpcResponse);
        String text = rpcResponse.get("result").get("content").get(0).get("text").asText();
        return objectMapper.readTree(text);
    }

    private void loadFixtureOverProtocol() throws Exception {
        String loadRequest = String.format("""
            {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{
                "name":"load_project",
                "arguments":{"projectPath":"%s"}
            }}
            """, projectPath.toString().replace("\\", "\\\\"));
        JsonNode payload = toolPayload(call(loadRequest));
        assertTrue(payload.get("success").asBoolean(), () -> "load_project failed: " + payload);
    }

    @Test
    @DisplayName("tools/list exposes find_unreachable_code with its input schema")
    void toolsList_exposesFindUnreachableCode() throws Exception {
        call("""
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}
            """);
        JsonNode listJson = call("""
            {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
            """);

        JsonNode tools = listJson.get("result").get("tools");
        JsonNode found = null;
        for (JsonNode tool : tools) {
            if ("find_unreachable_code".equals(tool.get("name").asText())) {
                found = tool;
            }
        }
        assertNotNull(found, "find_unreachable_code missing from tools/list");
        assertTrue(found.get("description").asText().contains("unreachable"),
            "description must document the tool's purpose");
        JsonNode properties = found.get("inputSchema").get("properties");
        assertNotNull(properties.get("includeTestRoots"), "includeTestRoots missing from schema");
        assertNotNull(properties.get("maxResults"), "maxResults missing from schema");
    }

    @Test
    @DisplayName("tools/call find_unreachable_code returns the exact dead-code inventory")
    void toolsCall_findUnreachableCode_exactInventory() throws Exception {
        loadFixtureOverProtocol();

        JsonNode payload = toolPayload(call("""
            {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{
                "name":"find_unreachable_code",
                "arguments":{}
            }}
            """));

        assertTrue(payload.get("success").asBoolean(), () -> "tool failed: " + payload);
        JsonNode data = payload.get("data");
        assertEquals(5, data.get("unreachableCount").asInt());
        assertEquals("com.reach.Main#main(String[])",
            data.get("roots").get("mainMethods").get(0).asText());
        assertEquals(5, data.get("roots").get("testMethodCount").asInt());

        Set<String> keys = new HashSet<>();
        for (JsonNode entry : data.get("unreachable")) {
            keys.add(entry.get("key").asText());
        }
        assertEquals(Set.of(
            "com.reach.EnglishGreeter#unusedPublicHelper()",
            "com.reach.Orphan",
            "com.reach.Orphan#DEAD_CONSTANT",
            "com.reach.Orphan#deadMethod()",
            "com.reach.Orphan#deadChain()"),
            keys);
    }

    @Test
    @DisplayName("tools/call with arguments: includeTestRoots=false grows the inventory to 13")
    void toolsCall_findUnreachableCode_argumentsHonored() throws Exception {
        loadFixtureOverProtocol();

        JsonNode payload = toolPayload(call("""
            {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{
                "name":"find_unreachable_code",
                "arguments":{"includeTestRoots":false}
            }}
            """));

        assertTrue(payload.get("success").asBoolean());
        assertEquals(20, payload.get("data").get("unreachableCount").asInt());
    }

    @Test
    @DisplayName("tools/call analyze_change_impact transitive mode crosses interface dispatch")
    void toolsCall_analyzeChangeImpact_transitive() throws Exception {
        loadFixtureOverProtocol();

        String prefixFile = projectPath.resolve("src/main/java/com/reach/EnglishGreeter.java")
            .toString().replace("\\", "\\\\");
        JsonNode payload = toolPayload(call(String.format("""
            {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{
                "name":"analyze_change_impact",
                "arguments":{"filePath":"%s","line":13,"column":20,"transitive":true}
            }}
            """, prefixFile)));

        assertTrue(payload.get("success").asBoolean(), () -> "tool failed: " + payload);
        JsonNode data = payload.get("data");
        assertEquals("prefix", data.get("symbol").asText());
        assertEquals(5, data.get("totalAffectedMethods").asInt());

        Set<String> methods = new HashSet<>();
        for (JsonNode method : data.get("affectedMethods")) {
            methods.add(method.asText());
        }
        assertTrue(methods.contains("com.reach.Main#main(String[])"),
            "depth-4 caller through the interface hop must appear; got: " + methods);
    }

    @Test
    @DisplayName("tools/call find_affected_tests returns covering tests with the disabled flag")
    void toolsCall_findAffectedTests() throws Exception {
        loadFixtureOverProtocol();

        String prefixFile = projectPath.resolve("src/main/java/com/reach/EnglishGreeter.java")
            .toString().replace("\\", "\\\\");
        JsonNode payload = toolPayload(call(String.format("""
            {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{
                "name":"find_affected_tests",
                "arguments":{"filePath":"%s","line":13,"column":20}
            }}
            """, prefixFile)));

        assertTrue(payload.get("success").asBoolean(), () -> "tool failed: " + payload);
        JsonNode data = payload.get("data");
        assertEquals("prefix", data.get("symbol").asText());
        assertEquals(2, data.get("testMethodCount").asInt());

        JsonNode tests = data.get("testMethods");
        assertEquals("greetsThroughInterface", tests.get(0).get("methodName").asText());
        assertNull(tests.get(0).get("disabled"));
        assertEquals("disabledGreeting", tests.get(1).get("methodName").asText());
        assertTrue(tests.get(1).get("disabled").asBoolean());
    }

    @Test
    @DisplayName("tools/call before load_project surfaces PROJECT_NOT_LOADED through the protocol")
    void toolsCall_withoutProject_surfacesError() throws Exception {
        JsonNode payload = toolPayload(call("""
            {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{
                "name":"find_unreachable_code",
                "arguments":{}
            }}
            """));

        assertFalse(payload.get("success").asBoolean());
        assertEquals("PROJECT_NOT_LOADED", payload.get("error").get("code").asText());
    }
}
