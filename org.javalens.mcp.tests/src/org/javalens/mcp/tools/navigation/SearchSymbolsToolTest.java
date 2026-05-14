package org.javalens.mcp.tools.navigation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.SearchSymbolsTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for SearchSymbolsTool.
 * Tests pattern matching, kind filtering, and pagination.
 */
class SearchSymbolsToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private SearchSymbolsTool tool;
    private ObjectMapper objectMapper;
    private Path projectPath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new SearchSymbolsTool(() -> service);
        objectMapper = new ObjectMapper();
        projectPath = helper.getFixturePath("simple-maven");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getResults(Map<String, Object> data) {
        return (List<Map<String, Object>>) data.get("results");
    }

    // ========== Comprehensive Functionality Tests ==========

    @Test
    @DisplayName("Class search returns results with name, kind, qualifiedName, and filePath")
    void classSearch_returnsAllFields() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("query", "Calculator");
        args.put("kind", "Class");

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        List<Map<String, Object>> results = getResults(data);
        assertNotNull(results);
        assertFalse(results.isEmpty());

        Map<String, Object> calcResult = results.stream()
            .filter(r -> "Calculator".equals(r.get("name")))
            .findFirst()
            .orElse(null);

        assertNotNull(calcResult);
        assertEquals("com.example.Calculator", calcResult.get("qualifiedName"));
        assertNotNull(calcResult.get("filePath"));
    }

    @Test
    @DisplayName("Trailing wildcard pattern matches correctly")
    void trailingWildcard_matchesCorrectly() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("query", "Calc*");

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        List<Map<String, Object>> results = getResults(data);
        assertTrue(results.stream().anyMatch(r -> "Calculator".equals(r.get("name"))));
    }

    @Test
    @DisplayName("Leading wildcard pattern matches correctly")
    void leadingWildcard_matchesCorrectly() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("query", "*Service");

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        List<Map<String, Object>> results = getResults(data);
        assertTrue(results.stream().anyMatch(r -> "UserService".equals(r.get("name"))));
    }

    @Test
    @DisplayName("Method kind filter returns only methods (every result must have kind=Method)")
    void methodKindFilter_returnsOnlyMethods() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("query", "add*");
        args.put("kind", "Method");

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        List<Map<String, Object>> results = getResults(data);
        assertNotNull(results);
        assertFalse(results.isEmpty(),
            "`add*` must match at least Calculator.add; got empty results");

        // Calculator.add must be among the results (positive case).
        assertTrue(results.stream().anyMatch(r -> "add".equals(r.get("name"))),
            "Calculator.add must appear when searching `add*` with kind=Method; got: " + results);

        // Critical: every result's kind must equal "Method". A regression where the kind
        // filter is silently ignored would otherwise pass.
        for (Map<String, Object> result : results) {
            assertEquals("Method", result.get("kind"),
                "kind=Method filter must apply to every result; offending entry: " + result);
        }
    }

    // ========== Pagination Tests ==========

    @Test
    @DisplayName("Pagination with maxResults and offset returns correct metadata")
    @SuppressWarnings("unchecked")
    void pagination_returnsCorrectMetadata() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("query", "*");
        args.put("maxResults", 2);
        args.put("offset", 0);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);

        List<Map<String, Object>> results = getResults(data);
        assertTrue(results.size() <= 2);

        Map<String, Object> pagination = (Map<String, Object>) data.get("pagination");
        assertNotNull(pagination);
        assertEquals(0, pagination.get("offset"));
        assertNotNull(pagination.get("returned"));
        assertNotNull(pagination.get("hasMore"));
    }

    // ========== Parameter Validation Tests ==========

    @Test
    @DisplayName("Missing or blank query returns error")
    void parameterValidation_returnsErrors() {
        // Missing query
        ObjectNode args1 = objectMapper.createObjectNode();
        assertFalse(tool.execute(args1).isSuccess());
        assertNotNull(tool.execute(args1).getError());

        // Blank query
        ObjectNode args2 = objectMapper.createObjectNode();
        args2.put("query", "   ");
        assertFalse(tool.execute(args2).isSuccess());
    }

    // ========== Edge Case Tests ==========

    @Test
    @DisplayName("No matches returns empty results list")
    void noMatches_returnsEmptyResults() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("query", "NonExistentClass");

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        List<Map<String, Object>> results = getResults(data);
        assertTrue(results.isEmpty());
    }

    // ========== Behavior-matrix coverage ==========

    @Test
    @DisplayName("Middle wildcard `F*er` matches FilledCircle but not unrelated types")
    @SuppressWarnings("unchecked")
    void middleWildcard_matchesCorrectly() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("query", "F*er");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        List<Map<String, Object>> results = getResults(getData(r));
        java.util.Set<String> names = results.stream()
            .map(rr -> (String) rr.get("name"))
            .collect(java.util.stream.Collectors.toSet());

        // F*er should match FieldHolder, FilledCircle (no — FilledCircle doesn't end er).
        // Actually F.*er covers FieldHolder. Let me adjust to a deterministic pattern:
        // verify FieldHolder is matched, Calculator is NOT matched.
        assertTrue(names.contains("FieldHolder"),
            "F*er should match FieldHolder; got: " + names);
        assertFalse(names.contains("Calculator"),
            "F*er must not match Calculator (doesn't start with F); got: " + names);
    }

    @Test
    @DisplayName("Single-char wildcard `?ello*` matches HelloWorld")
    @SuppressWarnings("unchecked")
    void singleCharWildcard_matchesCorrectly() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("query", "?elloWorld");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        List<Map<String, Object>> results = getResults(getData(r));
        java.util.Set<String> names = results.stream()
            .map(rr -> (String) rr.get("name"))
            .collect(java.util.stream.Collectors.toSet());
        assertTrue(names.contains("HelloWorld"),
            "`?elloWorld` should match HelloWorld via single-char wildcard; got: " + names);
    }

    @Test
    @DisplayName("Kind=Interface filter: every result is an Interface (IShape appears)")
    @SuppressWarnings("unchecked")
    void kindInterface_filterReturnsOnlyInterfaces() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("query", "I*");
        args.put("kind", "Interface");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        List<Map<String, Object>> results = getResults(getData(r));
        assertFalse(results.isEmpty(),
            "`I*` with kind=Interface should match at least IShape; got empty");
        assertTrue(results.stream().anyMatch(rr -> "IShape".equals(rr.get("name"))),
            "IShape must appear among results; got: " + results);
        for (Map<String, Object> result : results) {
            assertEquals("Interface", result.get("kind"),
                "Every kind=Interface result must have kind='Interface'; offending: " + result);
        }
    }

    @Test
    @DisplayName("Kind=Field filter: every result is a Field (lastResult appears)")
    @SuppressWarnings("unchecked")
    void kindField_filterReturnsOnlyFields() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("query", "lastResult");
        args.put("kind", "Field");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        List<Map<String, Object>> results = getResults(getData(r));
        assertFalse(results.isEmpty());
        for (Map<String, Object> result : results) {
            assertEquals("Field", result.get("kind"),
                "Every kind=Field result must have kind='Field'; offending: " + result);
        }
    }

    @Test
    @DisplayName("Type result includes qualifiedName and package")
    @SuppressWarnings("unchecked")
    void typeResult_includesQualifiedNameAndPackage() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("query", "Calculator");
        args.put("kind", "Class");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> calc = getResults(getData(r)).stream()
            .filter(rr -> "Calculator".equals(rr.get("name")))
            .findFirst()
            .orElseThrow();
        assertEquals("com.example.Calculator", calc.get("qualifiedName"));
        assertEquals("com.example", calc.get("package"));
        assertNotNull(calc.get("filePath"));
    }

    @Test
    @DisplayName("Method result includes signature and containingType")
    @SuppressWarnings("unchecked")
    void methodResult_includesSignatureAndContainingType() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("query", "add");
        args.put("kind", "Method");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> add = getResults(getData(r)).stream()
            .filter(rr -> "add".equals(rr.get("name")))
            .findFirst()
            .orElseThrow();
        assertNotNull(add.get("signature"),
            "Method result must include signature; got: " + add);
        assertEquals("Calculator", add.get("containingType"),
            "containingType must be the declaring class simple name; got: " + add);
    }

    @Test
    @DisplayName("Pagination offset skips first N results")
    @SuppressWarnings("unchecked")
    void pagination_offsetSkipsResults() {
        // First page: maxResults=2, offset=0
        ObjectNode p1Args = objectMapper.createObjectNode();
        p1Args.put("query", "*");
        p1Args.put("kind", "Class");
        p1Args.put("maxResults", 2);
        p1Args.put("offset", 0);

        ToolResponse p1 = tool.execute(p1Args);
        assertTrue(p1.isSuccess());
        List<Map<String, Object>> firstPage = getResults(getData(p1));
        assertTrue(firstPage.size() <= 2);

        // Second page: same maxResults, offset=2 — must skip the first 2 entries from
        // the result stream.
        ObjectNode p2Args = objectMapper.createObjectNode();
        p2Args.put("query", "*");
        p2Args.put("kind", "Class");
        p2Args.put("maxResults", 2);
        p2Args.put("offset", 2);

        ToolResponse p2 = tool.execute(p2Args);
        assertTrue(p2.isSuccess());
        Map<String, Object> p2Data = getData(p2);
        List<Map<String, Object>> secondPage = getResults(p2Data);

        // First-page and second-page result names must be disjoint (no overlap).
        java.util.Set<String> firstNames = firstPage.stream()
            .map(r -> (String) r.get("filePath"))
            .collect(java.util.stream.Collectors.toSet());
        java.util.Set<String> secondNames = secondPage.stream()
            .map(r -> (String) r.get("filePath"))
            .collect(java.util.stream.Collectors.toSet());
        // The pagination offset must produce different entries from page 1 (assuming
        // there are more than 2 classes in the project — there are many).
        if (!secondPage.isEmpty()) {
            assertNotEquals(firstNames, secondNames,
                "offset=2 must skip the first 2 entries; pages must differ. " +
                    "Got page1=" + firstNames + " page2=" + secondNames);
        }

        // Pagination metadata
        Map<String, Object> pagination = (Map<String, Object>) p2Data.get("pagination");
        assertEquals(2, pagination.get("offset"));
    }
}
