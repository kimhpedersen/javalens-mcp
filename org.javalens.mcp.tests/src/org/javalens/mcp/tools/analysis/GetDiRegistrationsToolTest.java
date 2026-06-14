package org.javalens.mcp.tools.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.GetDiRegistrationsTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GetDiRegistrationsToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private GetDiRegistrationsTool tool;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new GetDiRegistrationsTool(() -> service);
        objectMapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    // ========== Non-Spring Project Tests ==========

    @Test
    @DisplayName("should return empty results for non-Spring project")
    void returnsEmptyForNonSpringProject() {
        ObjectNode args = objectMapper.createObjectNode();

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);

        // Non-Spring project should have empty categories but not error
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) data.get("summary");
        assertNotNull(summary, "Should include summary");
        assertEquals(0, summary.get("components"));
        assertEquals(0, summary.get("configurations"));
        assertEquals(0, summary.get("beans"));
        assertEquals(0, summary.get("injectionPoints"));
    }

    // ========== Structure Tests ==========

    @Test
    @DisplayName("should return all expected categories in output")
    void returnsAllCategories() {
        ObjectNode args = objectMapper.createObjectNode();

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) data.get("summary");
        assertNotNull(summary, "summary block missing");
        assertFalse(summary.isEmpty(), "summary non-empty; got: " + data);
        @SuppressWarnings("unchecked")
        List<?> components = (List<?>) data.get("components");
        @SuppressWarnings("unchecked")
        List<?> configurations = (List<?>) data.get("configurations");
        @SuppressWarnings("unchecked")
        List<?> beans = (List<?>) data.get("beans");
        @SuppressWarnings("unchecked")
        List<?> injectionPoints = (List<?>) data.get("injectionPoints");
        assertNotNull(components, "components list missing");
        assertNotNull(configurations, "configurations list missing");
        assertNotNull(beans, "beans list missing");
        assertNotNull(injectionPoints, "injectionPoints list missing");
    }

    @Test
    @DisplayName("should respect maxResults parameter")
    void respectsMaxResults() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("maxResults", 1);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        // Should not throw and should return valid structure
        assertNotNull(getData(response).get("summary"));
    }

    // ========== Behavior-matrix coverage ==========

    @Test
    @DisplayName("summary keys are exactly {components, configurations, beans, injectionPoints}")
    void summary_hasExactKeys() {
        ToolResponse r = tool.execute(objectMapper.createObjectNode());
        assertTrue(r.isSuccess());
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) getData(r).get("summary");
        assertEquals(
            java.util.Set.of("components", "configurations", "beans", "injectionPoints"),
            summary.keySet(),
            "summary keys must be exactly the four documented categories; got: " + summary.keySet());
    }

    @Test
    @DisplayName("Top-level data has summary + four category lists; lists are all empty for non-Spring project")
    void categories_emptyListsForNonSpring() {
        ToolResponse r = tool.execute(objectMapper.createObjectNode());
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        for (String key : List.of("components", "configurations", "beans", "injectionPoints")) {
            @SuppressWarnings("unchecked")
            List<?> list = (List<?>) data.get(key);
            assertNotNull(list, key + " missing on data");
            assertTrue(list.isEmpty(),
                key + " must be empty for non-Spring project; got: " + list);
        }
    }

    @Test
    @DisplayName("Each summary count equals the corresponding list's size")
    void summaryCounts_matchListSizes() {
        ToolResponse r = tool.execute(objectMapper.createObjectNode());
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        @SuppressWarnings("unchecked")
        Map<String, Number> summary = (Map<String, Number>) data.get("summary");
        for (String key : List.of("components", "configurations", "beans", "injectionPoints")) {
            @SuppressWarnings("unchecked")
            List<?> list = (List<?>) data.get(key);
            assertEquals(summary.get(key).intValue(), list.size(),
                "summary." + key + " must equal " + key + ".size(); got summary="
                    + summary.get(key) + " listSize=" + list.size());
        }
    }

    // ========== Spring-fixture detection (exact inventory derived from framework-maven source) ==========

    /**
     * framework-maven ground truth (hand-counted from com.fw source):
     *  - components (@Component/@Service/@RestController): GreetingService(@Service),
     *    OrderController(@RestController), StatusController(@RestController),
     *    WiredConsumer(@Component) = 4
     *  - configurations (@Configuration): AppConfig = 1
     *  - beans (@Bean): AppConfig.makeThing = 1
     *  - injectionPoints (@Autowired): WiredConsumer = 1
     */
    @Test
    @DisplayName("framework-maven: exact DI inventory — 4 components, 1 configuration, 1 bean, 1 injection point")
    @SuppressWarnings("unchecked")
    void frameworkMaven_exactRegistrationInventory() throws Exception {
        JdtServiceImpl svc = helper.loadProject("framework-maven");
        GetDiRegistrationsTool fwTool = new GetDiRegistrationsTool(() -> svc);

        ToolResponse r = fwTool.execute(objectMapper.createObjectNode());
        assertTrue(r.isSuccess(), () -> "expected success; got: " + r.getError());
        Map<String, Object> data = getData(r);

        Map<String, Object> summary = (Map<String, Object>) data.get("summary");
        assertEquals(4, summary.get("components"), "4 stereotype-annotated components; got: " + summary);
        assertEquals(1, summary.get("configurations"), "AppConfig is the only @Configuration; got: " + summary);
        assertEquals(1, summary.get("beans"), "one @Bean method; got: " + summary);
        assertEquals(1, summary.get("injectionPoints"), "one @Autowired site; got: " + summary);

        List<Map<String, Object>> components = (List<Map<String, Object>>) data.get("components");
        Map<String, Long> byAnnotation = components.stream().collect(
            java.util.stream.Collectors.groupingBy(m -> (String) m.get("annotation"),
                java.util.stream.Collectors.counting()));
        assertEquals(Map.of("@Service", 1L, "@RestController", 2L, "@Component", 1L), byAnnotation,
            "component annotation multiset must match the fixture; got: " + byAnnotation);
        java.util.Set<String> componentFiles = components.stream()
            .map(m -> ((String) m.get("filePath")).replace('\\', '/'))
            .map(p -> p.substring(p.lastIndexOf('/') + 1))
            .collect(java.util.stream.Collectors.toSet());
        assertEquals(java.util.Set.of("GreetingService.java", "OrderController.java",
            "StatusController.java", "WiredConsumer.java"), componentFiles,
            "the four component declarations must come from exactly these files; got: " + componentFiles);
    }

    // ========== MCP envelope seam (real registerTools() wiring through processMessage) ==========

    @Test
    @DisplayName("Through the real registerTools() wiring: the framework-maven DI inventory (4/1/1/1) survives the envelope")
    void envelope_frameworkMaven_exactInventory() throws Exception {
        JdtServiceImpl svc = helper.loadProject("framework-maven");
        EnvelopeHarness fwEnvelope = new EnvelopeHarness(svc);
        JsonNode payload = fwEnvelope.assertEnvelopeFidelity("get_di_registrations", fwEnvelope.args());

        assertTrue(payload.get("success").asBoolean(),
            () -> "get_di_registrations failed through the envelope: " + payload);
        JsonNode summary = payload.get("data").get("summary");
        assertEquals(4, summary.get("components").asInt(), "4 components through the envelope");
        assertEquals(1, summary.get("configurations").asInt(), "1 configuration through the envelope");
        assertEquals(1, summary.get("beans").asInt(), "1 bean through the envelope");
        assertEquals(1, summary.get("injectionPoints").asInt(), "1 injection point through the envelope");
        java.util.Map<String, Integer> byAnnotation = new java.util.HashMap<>();
        for (JsonNode c : payload.get("data").get("components")) {
            byAnnotation.merge(c.get("annotation").asText(), 1, Integer::sum);
        }
        assertEquals(java.util.Map.of("@Service", 1, "@RestController", 2, "@Component", 1), byAnnotation,
            "the component annotation multiset must survive the real-wiring envelope; got: " + byAnnotation);
    }
}
