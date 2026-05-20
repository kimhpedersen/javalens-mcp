package org.javalens.mcp.tools.quickfix;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.SuggestImportsTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for SuggestImportsTool.
 * Tests import suggestion and relevance ranking.
 */
class SuggestImportsToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private SuggestImportsTool tool;
    private ObjectMapper objectMapper;
    private Path projectPath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new SuggestImportsTool(() -> service);
        objectMapper = new ObjectMapper();
        projectPath = helper.getFixturePath("simple-maven");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getCandidates(Map<String, Object> data) {
        return (List<Map<String, Object>>) data.get("candidates");
    }

    @Test
    @DisplayName("finds JDK types with complete candidate structure and relevance ranking")
    void findsJdkTypesWithCompleteStructure() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "List");

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);

        // Verify summary fields
        assertEquals("List", data.get("typeName"));
        int totalCandidates = ((Number) data.get("totalCandidates")).intValue();
        assertTrue(totalCandidates > 0, "List has candidate imports; got: " + data);

        // Verify candidates structure
        List<Map<String, Object>> candidates = getCandidates(data);
        assertEquals(totalCandidates, candidates.size(),
            "totalCandidates must equal candidates list size; got: " + data);

        // Verify java.util.List is found
        assertTrue(candidates.stream()
            .anyMatch(c -> "java.util.List".equals(c.get("fullyQualifiedName"))));

        // Verify first candidate has all required fields with valid values
        Map<String, Object> first = candidates.get(0);
        String fqn = (String) first.get("fullyQualifiedName");
        assertNotNull(fqn, "fullyQualifiedName missing");
        assertTrue(fqn.contains("."), "FQN must include package; got: " + first);
        String pkg = (String) first.get("packageName");
        assertNotNull(pkg, "packageName missing");
        assertTrue(fqn.startsWith(pkg + "."),
            "FQN must start with packageName; got: " + first);
        assertTrue(((Number) first.get("relevance")).intValue() >= 0,
            "relevance >= 0; got: " + first);
        assertTrue(first.get("isInterface") instanceof Boolean,
            "isInterface must be Boolean; got: " + first);
        assertTrue(first.get("isClass") instanceof Boolean,
            "isClass must be Boolean; got: " + first);
        assertTrue(first.get("isEnum") instanceof Boolean,
            "isEnum must be Boolean; got: " + first);

        // Verify fixId format
        String fixId = (String) first.get("fixId");
        assertNotNull(fixId);
        assertTrue(fixId.startsWith("add_import:"));

        // Verify relevance ranking: java.util.List should come before java.awt types
        int utilIndex = -1;
        int awtIndex = -1;
        for (int i = 0; i < candidates.size(); i++) {
            String candFqn = (String) candidates.get(i).get("fullyQualifiedName");
            if ("java.util.List".equals(candFqn)) {
                utilIndex = i;
            } else if (candFqn != null && candFqn.startsWith("java.awt.")) {
                awtIndex = i;
            }
        }
        if (utilIndex >= 0 && awtIndex >= 0) {
            assertTrue(utilIndex < awtIndex, "java.util.List should rank higher than java.awt types");
        }
    }

    @Test
    @DisplayName("respects maxResults parameter")
    void respectsMaxResults() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "List");
        args.put("maxResults", 3);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        List<Map<String, Object>> candidates = getCandidates(data);
        assertTrue(candidates.size() <= 3);
    }

    @Test
    @DisplayName("finds project types")
    void findsProjectTypes() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "Calculator");

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        List<Map<String, Object>> candidates = getCandidates(data);
        assertTrue(candidates.stream()
            .anyMatch(c -> "com.example.Calculator".equals(c.get("fullyQualifiedName"))));
    }

    @Test
    @DisplayName("requires typeName parameter")
    void requiresTypeName() {
        ObjectNode args = objectMapper.createObjectNode();

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
        assertNotNull(response.getError());
    }

    @Test
    @DisplayName("rejects blank typeName")
    void rejectsBlankTypeName() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "   ");

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
        assertNotNull(response.getError());
    }

    @Test
    @DisplayName("returns empty list for unknown type")
    void returnsEmptyForUnknownType() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "NonExistentType12345");

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        List<Map<String, Object>> candidates = getCandidates(data);
        assertEquals(0, candidates.size());
    }

    // ========== Behavior-matrix coverage ==========

    @Test
    @DisplayName("Candidates sorted by relevance descending")
    void candidates_sortedByRelevanceDescending() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "List");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        List<Map<String, Object>> cands = getCandidates(getData(r));
        for (int i = 1; i < cands.size(); i++) {
            int prev = ((Number) cands.get(i - 1).get("relevance")).intValue();
            int curr = ((Number) cands.get(i).get("relevance")).intValue();
            assertTrue(prev >= curr,
                "Candidates must be sorted by relevance descending; got prev=" + prev + " curr=" + curr);
        }
    }

    @Test
    @DisplayName("totalCandidates == candidates.size()")
    void totalCandidates_equalsListSize() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "List");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        int total = ((Number) data.get("totalCandidates")).intValue();
        assertEquals(total, getCandidates(data).size());
    }

    @Test
    @DisplayName("java.util.List relevance > java.awt.List relevance")
    void relevance_ranking_javaUtilOverJavaAwt() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "List");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        List<Map<String, Object>> cands = getCandidates(getData(r));
        int utilRel = -1, awtRel = -1;
        for (Map<String, Object> c : cands) {
            String fqn = (String) c.get("fullyQualifiedName");
            int rel = ((Number) c.get("relevance")).intValue();
            if ("java.util.List".equals(fqn)) utilRel = rel;
            else if ("java.awt.List".equals(fqn)) awtRel = rel;
        }
        if (utilRel >= 0 && awtRel >= 0) {
            assertTrue(utilRel > awtRel,
                "java.util.List must rank higher than java.awt.List; got util=" + utilRel + " awt=" + awtRel);
        }
    }

    @Test
    @DisplayName("Project-local Calculator has fixId=add_import:com.example.Calculator")
    void projectType_fixIdShape() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "Calculator");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> calc = getCandidates(getData(r)).stream()
            .filter(c -> "com.example.Calculator".equals(c.get("fullyQualifiedName")))
            .findFirst().orElseThrow();
        assertEquals("add_import:com.example.Calculator", calc.get("fixId"));
        assertEquals("com.example", calc.get("packageName"));
        assertEquals("Calculator", calc.get("simpleName"));
    }

    @Test
    @DisplayName("isInterface/isClass/isEnum flags are correct for known types")
    void kindFlags_correct() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "List");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> jUtilList = getCandidates(getData(r)).stream()
            .filter(c -> "java.util.List".equals(c.get("fullyQualifiedName")))
            .findFirst().orElseThrow();
        assertEquals(Boolean.TRUE, jUtilList.get("isInterface"),
            "java.util.List is an interface");
        assertEquals(Boolean.FALSE, jUtilList.get("isClass"));
        assertEquals(Boolean.FALSE, jUtilList.get("isEnum"));
    }

    @Test
    @DisplayName("Negative maxResults returns INVALID_PARAMETER naming maxResults")
    void negativeMaxResults_returnsInvalidParameter() {
        // Source: `if (maxResults < 0) return invalidParameter("maxResults", ...)`.
        // Pin the strict-rejection branch.
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "List");
        args.put("maxResults", -1);

        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess());
        assertEquals(org.javalens.mcp.models.ErrorInfo.INVALID_PARAMETER,
            r.getError().getCode());
        assertTrue(r.getError().getMessage().contains("maxResults"),
            "Error must name maxResults; got: " + r.getError().getMessage());
    }

    @Test
    @DisplayName("Internal packages (sun.*, com.sun.*, *.internal.*, *.impl.*) are filtered from candidates")
    void internalPackages_filteredFromResults() {
        // Source skip-list in acceptTypeNameMatch:
        //   if (packageName.contains(".internal.") || .contains(".impl.") ||
        //       packageName.startsWith("sun.") || packageName.startsWith("com.sun.")) return;
        // Search a common JDK name; assert NO candidate's packageName is in the skip-list.
        // Without this guard the LLM consumer would see implementation-detail types in the
        // import suggestion list — a real correctness problem.
        ObjectNode args = objectMapper.createObjectNode();
        args.put("typeName", "List");
        args.put("maxResults", 100);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        for (Map<String, Object> c : getCandidates(getData(r))) {
            String pkg = (String) c.get("packageName");
            assertNotNull(pkg, "packageName missing on candidate: " + c);
            assertFalse(pkg.startsWith("sun."),
                "sun.* packages must be filtered; got: " + c);
            assertFalse(pkg.startsWith("com.sun."),
                "com.sun.* packages must be filtered; got: " + c);
            assertFalse(pkg.contains(".internal."),
                "*.internal.* packages must be filtered; got: " + c);
            assertFalse(pkg.contains(".impl."),
                "*.impl.* packages must be filtered; got: " + c);
        }
    }
}
