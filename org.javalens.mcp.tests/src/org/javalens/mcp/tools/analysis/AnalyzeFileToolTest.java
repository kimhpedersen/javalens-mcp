package org.javalens.mcp.tools.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.AnalyzeFileTool;
import org.javalens.mcp.tools.GetDocumentSymbolsTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AnalyzeFileToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();
    private JdtServiceImpl service;
    private AnalyzeFileTool tool;
    private EnvelopeHarness envelope;
    private ObjectMapper objectMapper;
    private String calculatorPath;
    private String userServicePath;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProject("simple-maven");
        tool = new AnalyzeFileTool(() -> service);
        envelope = new EnvelopeHarness(service);
        objectMapper = new ObjectMapper();
        calculatorPath = helper.getFixturePath("simple-maven")
            .resolve("src/main/java/com/example/Calculator.java").toString();
        userServicePath = helper.getFixturePath("simple-maven")
            .resolve("src/main/java/com/example/service/UserService.java").toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @Test @DisplayName("Calculator.java: exact file info, single type, clean diagnostics")
    void analyzesFileComprehensively() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);

        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);

        // File info — exact
        @SuppressWarnings("unchecked")
        Map<String, Object> file = (Map<String, Object>) data.get("file");
        assertTrue(((String) file.get("path")).endsWith("Calculator.java"),
            "file.path must end with Calculator.java; got: " + file);
        assertEquals("com.example", file.get("package"));
        assertEquals(49, ((Number) file.get("lineCount")).intValue());

        // Types — exactly one top-level class
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> types = (List<Map<String, Object>>) data.get("types");
        assertEquals(1, ((Number) data.get("typeCount")).intValue());
        assertEquals(1, types.size());
        assertEquals("Calculator", types.get(0).get("name"));
        assertEquals("class", types.get(0).get("kind"));

        // Calculator.java is clean — no errors, no warnings.
        @SuppressWarnings("unchecked")
        Map<String, Object> diagnostics = (Map<String, Object>) data.get("diagnostics");
        assertEquals(0, ((Number) diagnostics.get("errorCount")).intValue());
        assertEquals(0, ((Number) diagnostics.get("warningCount")).intValue());
        assertEquals(false, diagnostics.get("hasProblems"));
    }

    @Test @DisplayName("controls optional output")
    void controlsOptionalOutput() {
        // Include members
        ObjectNode withMembers = objectMapper.createObjectNode();
        withMembers.put("filePath", calculatorPath);
        withMembers.put("includeMembers", true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> types = (List<Map<String, Object>>) getData(tool.execute(withMembers)).get("types");
        assertNotNull(types.get(0).get("methods"));

        // Exclude diagnostics
        ObjectNode noDiag = objectMapper.createObjectNode();
        noDiag.put("filePath", calculatorPath);
        noDiag.put("includeDiagnostics", false);
        assertNull(getData(tool.execute(noDiag)).get("diagnostics"));
    }

    @Test @DisplayName("missing filePath -> exact INVALID_PARAMETER")
    void requiresFilePath() {
        ToolResponse r = tool.execute(objectMapper.createObjectNode());
        assertFalse(r.isSuccess());
        assertEquals(org.javalens.mcp.models.ErrorInfo.INVALID_PARAMETER, r.getError().getCode());
        assertEquals("Invalid parameter 'filePath': Required parameter missing", r.getError().getMessage());
    }

    @Test @DisplayName("non-existent file -> FILE_NOT_FOUND; empty filePath -> INVALID_PARAMETER")
    void handlesInvalidInputs() {
        ObjectNode badPath = objectMapper.createObjectNode();
        badPath.put("filePath", "/nonexistent/File.java");
        ToolResponse badResp = tool.execute(badPath);
        assertFalse(badResp.isSuccess());
        assertEquals(org.javalens.mcp.models.ErrorInfo.FILE_NOT_FOUND, badResp.getError().getCode());
        assertEquals("File not found: /nonexistent/File.java", badResp.getError().getMessage());

        ObjectNode emptyPath = objectMapper.createObjectNode();
        emptyPath.put("filePath", "");
        ToolResponse emptyResp = tool.execute(emptyPath);
        assertFalse(emptyResp.isSuccess());
        assertEquals(org.javalens.mcp.models.ErrorInfo.INVALID_PARAMETER, emptyResp.getError().getCode());
        assertEquals("Invalid parameter 'filePath': Required parameter missing", emptyResp.getError().getMessage());
    }

    // ========== Behavior-matrix coverage ==========

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> typesOf(ToolResponse r) {
        return (List<Map<String, Object>>) getData(r).get("types");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> importsOf(ToolResponse r) {
        return (List<Map<String, Object>>) getData(r).get("imports");
    }

    @Test
    @DisplayName("File block carries path, package, lineCount")
    void fileBlock_shape() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        @SuppressWarnings("unchecked")
        Map<String, Object> file = (Map<String, Object>) getData(r).get("file");
        for (String key : List.of("path", "package", "lineCount")) {
            assertNotNull(file.get(key), key + " missing on file block: " + file);
        }
    }

    @Test
    @DisplayName("UserService imports: exact list of 3 (Calculator, ArrayList, List), none static/onDemand/module")
    void imports_exactList() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", userServicePath);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        List<Map<String, Object>> imports = importsOf(r);
        assertEquals(3, ((Number) data.get("importCount")).intValue());
        assertEquals(3, imports.size());
        // cu.getImports() preserves source order.
        assertEquals(List.of("com.example.Calculator", "java.util.ArrayList", "java.util.List"),
            imports.stream().map(i -> (String) i.get("name")).toList());
        for (Map<String, Object> imp : imports) {
            assertEquals(false, imp.get("static"), "no static imports; got: " + imp);
            assertEquals(false, imp.get("onDemand"), "no on-demand imports; got: " + imp);
            assertEquals(false, imp.get("module"), "no module imports; got: " + imp);
        }
    }

    @Test
    @DisplayName("Types: each entry has name, qualifiedName, kind, modifiers, line")
    void types_shape() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        for (Map<String, Object> t : typesOf(r)) {
            for (String key : List.of("name", "qualifiedName", "kind", "modifiers", "line")) {
                assertNotNull(t.get(key), key + " missing on type: " + t);
            }
        }
    }

    @Test
    @DisplayName("typeCount equals types.size()")
    void typeCount_equalsListSize() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        int count = ((Number) data.get("typeCount")).intValue();
        assertEquals(count, typesOf(r).size());
    }

    @Test
    @DisplayName("Calculator qualifiedName is com.example.Calculator")
    void calculator_qualifiedName() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        assertEquals("com.example.Calculator", typesOf(r).get(0).get("qualifiedName"));
    }

    @Test
    @DisplayName("includeMembers=true populates methods/fields lists on type entries")
    void includeMembers_populatesLists() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("includeMembers", true);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> type = typesOf(r).get(0);
        assertNotNull(type.get("methods"), "methods list missing when includeMembers=true");
        assertNotNull(type.get("fields"), "fields list missing when includeMembers=true");
    }

    // ========== T-2 cross-tool consistency ==========

    @Test
    @DisplayName("Calculator.java: analyze_file top-level type count agrees with get_document_symbols (cross-tool consistency)")
    @SuppressWarnings("unchecked")
    void calculator_topLevelTypeCountAgreesWithDocumentSymbols() throws Exception {
        // Both tools enumerate top-level types via cu.getTypes() on the same compilation
        // unit. If their lists differ in size, one tool dropped (or duplicated) types.
        GetDocumentSymbolsTool detail = new GetDocumentSymbolsTool(() -> service);

        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);

        List<Map<String, Object>> aggregateTypes = typesOf(tool.execute(args));
        List<Map<String, Object>> detailSymbols =
            (List<Map<String, Object>>) getData(detail.execute(args)).get("symbols");

        assertEquals(aggregateTypes.size(), detailSymbols.size(),
            "analyze_file.types.size() must equal get_document_symbols.symbols.size() (both enumerate "
                + "top-level types in the file); aggregate=" + aggregateTypes.size()
                + " detail=" + detailSymbols.size());
    }

    @Test
    @DisplayName("Default-package file: file.package reports '(default package)' literal")
    @SuppressWarnings("unchecked")
    void defaultPackageFile_reportsParenthesizedLabel() throws Exception {
        // Source line 104 has the branch for cu.getPackageDeclarations().length == 0:
        // emits the literal string "(default package)". Use the default-package fixture
        // (NoPackage.java with no package statement) to exercise that branch.
        org.javalens.core.JdtServiceImpl defaultPkgService =
            helper.loadProject("default-package");
        AnalyzeFileTool defaultPkgTool = new AnalyzeFileTool(() -> defaultPkgService);
        String noPackagePath = helper.getFixturePath("default-package")
            .resolve("src/main/java/NoPackage.java").toString();

        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", noPackagePath);
        ToolResponse r = defaultPkgTool.execute(args);
        assertTrue(r.isSuccess(),
            "Default-package file must analyze without error; got: " +
                (r.getError() != null ? r.getError().getMessage() : "n/a"));
        Map<String, Object> file = (Map<String, Object>) getData(r).get("file");
        assertEquals("(default package)", file.get("package"),
            "Default-package file must report file.package='(default package)'; got: " + file);
    }

    @Test
    @DisplayName("typeCount counts TOP-LEVEL types only; nestedTypeCount on each type reports nested members")
    @SuppressWarnings("unchecked")
    void typeCount_isTopLevelOnly_nestedTypesCountedSeparately() {
        // TypeKindsFixture.java has one top-level public class (TypeKindsFixture)
        // with five nested types: Color, GenericContainer, Inner, DefaultMethodHolder,
        // BoundedBox. The tool's contract returns top-level types only at the top
        // level; nested types are surfaced via the per-type nestedTypeCount field.
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", helper.getFixturePath("simple-maven")
            .resolve("src/main/java/com/example/TypeKindsFixture.java").toString());

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertEquals(1, ((Number) data.get("typeCount")).intValue(),
            "TypeKindsFixture.java declares exactly one top-level type; got: " + data.get("typeCount"));

        List<Map<String, Object>> types = (List<Map<String, Object>>) data.get("types");
        Map<String, Object> top = types.get(0);
        assertEquals("TypeKindsFixture", top.get("name"));
        int nestedCount = ((Number) top.get("nestedTypeCount")).intValue();
        assertEquals(5, nestedCount,
            "TypeKindsFixture has 5 nested types (Color, GenericContainer, Inner, " +
                "DefaultMethodHolder, BoundedBox); got: " + nestedCount);
    }

    @Test
    @DisplayName("File with multiple top-level types (NamingViolationFixtures.java): typeCount=2")
    @SuppressWarnings("unchecked")
    void multipleTopLevelTypes_typeCountIs2() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", helper.getFixturePath("simple-maven")
            .resolve("src/main/java/com/example/NamingViolationFixtures.java").toString());

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertEquals(2, ((Number) data.get("typeCount")).intValue(),
            "NamingViolationFixtures.java declares two top-level types "
                + "(@interface bad_annotation and record bad_record); got: " + data.get("typeCount"));
        List<Map<String, Object>> types = (List<Map<String, Object>>) data.get("types");
        java.util.Set<String> kinds = types.stream()
            .map(t -> (String) t.get("kind"))
            .collect(java.util.stream.Collectors.toSet());
        assertEquals(java.util.Set.of("annotation", "record"), kinds,
            "Both kinds must be classified correctly; got: " + types);
    }

    @Test
    @DisplayName("File containing a sealed interface (Vehicle.java): kind=interface, modifiers include sealed")
    @SuppressWarnings("unchecked")
    void sealedInterface_modifiersIncludeSealed() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", helper.getFixturePath("simple-maven")
            .resolve("src/main/java/com/example/Vehicle.java").toString());

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        List<Map<String, Object>> types = (List<Map<String, Object>>) getData(r).get("types");
        Map<String, Object> vehicle = types.stream()
            .filter(t -> "Vehicle".equals(t.get("name")))
            .findFirst().orElseThrow();
        assertEquals("interface", vehicle.get("kind"));
        // `public sealed interface` — source-declared modifiers only (no implicit abstract).
        assertEquals(List.of("public", "sealed"), vehicle.get("modifiers"));
    }

    // ========== MCP envelope seam (exact authored values through processMessage) ==========

    @Test
    @DisplayName("Through the real MCP envelope: NamingViolationFixtures has 2 top-level types (annotation + record)")
    void envelope_namingViolationFixtures_typeCountTwo() {
        String path = helper.getFixturePath("simple-maven")
            .resolve("src/main/java/com/example/NamingViolationFixtures.java").toString();
        ObjectNode args = envelope.args();
        args.put("filePath", path);
        JsonNode payload = envelope.assertEnvelopeFidelity("analyze_file", args);

        assertTrue(payload.get("success").asBoolean(),
            () -> "analyze_file failed through the envelope: " + payload);
        JsonNode data = payload.get("data");
        assertEquals(2, data.get("typeCount").asInt(),
            "two top-level types must survive the envelope");
        java.util.Set<String> kinds = new java.util.TreeSet<>();
        for (JsonNode t : data.get("types")) kinds.add(t.get("kind").asText());
        assertEquals(java.util.Set.of("annotation", "record"), kinds,
            "the exact top-level kind set must survive the envelope; got: " + kinds);
    }
}
