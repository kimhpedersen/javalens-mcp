package org.javalens.mcp.tools.navigation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.GetHoverInfoTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for GetHoverInfoTool.
 * Tests hover documentation extraction.
 */
class GetHoverInfoToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private GetHoverInfoTool tool;
    private EnvelopeHarness envelope;
    private ObjectMapper objectMapper;
    private Path projectPath;
    private String calculatorPath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new GetHoverInfoTool(() -> service);
        envelope = new EnvelopeHarness(service);
        objectMapper = new ObjectMapper();
        projectPath = helper.getFixturePath("simple-maven");
        calculatorPath = projectPath.resolve("src/main/java/com/example/Calculator.java").toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    // ========== Comprehensive Functionality Tests ==========

    @Test
    @DisplayName("Class hover returns name, kind, and signature with class keyword")
    void classHover_returnsAllFields() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 5);
        args.put("column", 13);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        assertEquals("Calculator", data.get("name"));
        assertEquals("class", data.get("kind"));

        assertEquals("public class Calculator", data.get("signature"),
            "exact class hover signature; got: " + data.get("signature"));
    }

    @Test
    @DisplayName("Method hover returns name, kind, signature, modifiers, declaringType, filePath, and docComment")
    @SuppressWarnings("unchecked")
    void methodHover_returnsAllFields() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 14);
        args.put("column", 15);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        assertEquals("add", data.get("name"));
        assertEquals("method", data.get("kind"));
        assertEquals("com.example.Calculator", data.get("declaringType"));
        String fp = (String) data.get("filePath");
        assertNotNull(fp, "filePath missing");
        assertTrue(fp.endsWith("Calculator.java"),
            "filePath must point to Calculator.java; got: " + fp);

        assertEquals("public int add(int a, int b)", data.get("signature"),
            "exact method hover signature; got: " + data.get("signature"));
        assertEquals(List.of("public"), data.get("modifiers"),
            "Calculator.add is exactly `public`; got: " + data.get("modifiers"));
        // docComment is pinned non-null + content by addHover_docCommentPresent.
    }

    @Test
    @DisplayName("Field hover returns name, kind, and signature with type")
    void fieldHover_returnsAllFields() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 6);
        args.put("column", 16);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        assertEquals("lastResult", data.get("name"));
        assertEquals("field", data.get("kind"));

        assertEquals("private int lastResult", data.get("signature"),
            "exact field hover signature; got: " + data.get("signature"));
    }

    // ========== Semantic-grade tests ==========

    @Test
    @DisplayName("Calculator.add hover: docComment is non-null and mentions 'sum'")
    void addHover_docCommentPresent() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 14);
        args.put("column", 15);

        ToolResponse response = tool.execute(args);
        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        // Calculator.add has Javadoc "@return the sum" — hover must surface it.
        String docComment = (String) data.get("docComment");
        assertNotNull(docComment,
            "Calculator.add has a Javadoc comment; hover docComment must be non-null");
        assertTrue(docComment.contains("sum"),
            "Javadoc text says `@return the sum`; docComment must contain `sum`; got: " + docComment);
    }

    // ========== Parameter Validation Tests ==========

    @Test
    @DisplayName("Missing filePath / negative line / negative column all rejected with INVALID_PARAMETER")
    void parameterValidation_returnsErrors() {
        ObjectNode args1 = objectMapper.createObjectNode();
        args1.put("line", 14);
        args1.put("column", 15);
        ToolResponse r1 = tool.execute(args1);
        assertFalse(r1.isSuccess());
        assertEquals(org.javalens.mcp.models.ErrorInfo.INVALID_PARAMETER, r1.getError().getCode());

        ObjectNode args2 = objectMapper.createObjectNode();
        args2.put("filePath", calculatorPath);
        args2.put("line", -1);
        args2.put("column", 15);
        ToolResponse r2 = tool.execute(args2);
        assertFalse(r2.isSuccess());
        assertEquals(org.javalens.mcp.models.ErrorInfo.INVALID_PARAMETER, r2.getError().getCode());

        ObjectNode args3 = objectMapper.createObjectNode();
        args3.put("filePath", calculatorPath);
        args3.put("line", 14);
        args3.put("column", -1);
        ToolResponse r3 = tool.execute(args3);
        assertFalse(r3.isSuccess());
        assertEquals(org.javalens.mcp.models.ErrorInfo.INVALID_PARAMETER, r3.getError().getCode());
    }

    // ========== Edge Case Tests ==========

    @Test
    @DisplayName("Position on a blank line (no symbol) returns SYMBOL_NOT_FOUND")
    void positionWithNoSymbol_handlesGracefully() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 1);  // blank line between package and Javadoc
        args.put("column", 0);

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
        assertEquals(org.javalens.mcp.models.ErrorInfo.SYMBOL_NOT_FOUND, response.getError().getCode());
    }

    // ========== Behavior-matrix coverage ==========

    private ObjectNode argsAtIdentifier(String filePath, String identifier) throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(filePath));
        int idx = source.indexOf(identifier);
        if (idx < 0) {
            throw new AssertionError("`" + identifier + "` not found in " + filePath);
        }
        int line = (int) source.substring(0, idx).chars().filter(c -> c == '\n').count();
        int lineStart = source.lastIndexOf('\n', idx) + 1;
        int column = idx - lineStart;
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", filePath);
        args.put("line", line);
        args.put("column", column);
        return args;
    }

    @Test
    @DisplayName("Interface hover: signature uses 'interface' keyword")
    void interfaceHover_signatureUsesInterfaceKeyword() throws Exception {
        String iShape = projectPath.resolve("src/main/java/com/example/IShape.java").toString();
        ToolResponse r = tool.execute(argsAtIdentifier(iShape, "IShape"));
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertEquals("IShape", data.get("name"));
        String sig = (String) data.get("signature");
        assertNotNull(sig);
        assertTrue(sig.contains("interface IShape"),
            "Interface signature must contain `interface IShape`; got: " + sig);
        // Must NOT contain "@interface" (would only apply to annotation types).
        assertFalse(sig.contains("@interface"),
            "Non-annotation interface must not use @interface; got: " + sig);
    }

    @Test
    @DisplayName("Annotation hover: signature uses '@interface' keyword")
    void annotationHover_signatureUsesAtInterface() throws Exception {
        String marker = projectPath.resolve("src/main/java/com/example/Marker.java").toString();
        // Find `@interface Marker` declaration — but `Marker` is also in @Target list,
        // so search for the class declaration specifically.
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(marker));
        int idx = source.indexOf("@interface Marker");
        idx = source.indexOf("Marker", idx); // skip past "@interface " to "Marker"
        int line = (int) source.substring(0, idx).chars().filter(c -> c == '\n').count();
        int lineStart = source.lastIndexOf('\n', idx) + 1;
        int column = idx - lineStart;

        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", marker);
        args.put("line", line);
        args.put("column", column);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertEquals("Marker", data.get("name"));
        assertEquals("annotation", data.get("kind"),
            "Marker is @interface — kind must be 'annotation' (delegates to TypeKindResolver)");
        String sig = (String) data.get("signature");
        assertNotNull(sig);
        assertTrue(sig.contains("@interface Marker"),
            "Annotation hover signature must contain `@interface Marker`; got: " + sig);
    }

    @Test
    @DisplayName("Enum hover: signature uses 'enum' keyword")
    void enumHover_signatureUsesEnumKeyword() throws Exception {
        String tkf = projectPath.resolve("src/main/java/com/example/TypeKindsFixture.java").toString();
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(tkf));
        int idx = source.indexOf("enum Color");
        idx = source.indexOf("Color", idx);
        int line = (int) source.substring(0, idx).chars().filter(c -> c == '\n').count();
        int lineStart = source.lastIndexOf('\n', idx) + 1;
        int column = idx - lineStart;
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", tkf);
        args.put("line", line);
        args.put("column", column);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        String sig = (String) getData(r).get("signature");
        assertNotNull(sig);
        assertTrue(sig.contains("enum Color"),
            "Enum hover signature must contain `enum Color`; got: " + sig);
    }

    @Test
    @DisplayName("Record hover: signature uses 'record' keyword")
    void recordHover_signatureUsesRecordKeyword() throws Exception {
        String point = projectPath.resolve("src/main/java/com/example/Point.java").toString();
        ToolResponse r = tool.execute(argsAtIdentifier(point, "Point"));
        assertTrue(r.isSuccess());
        String sig = (String) getData(r).get("signature");
        assertNotNull(sig);
        assertTrue(sig.contains("record Point"),
            "Record hover signature must contain `record Point`; got: " + sig);
    }

    @Test
    @DisplayName("Method hover signature includes throws clause for SearchPatterns.readFile")
    void methodWithThrows_signatureIncludesThrows() throws Exception {
        String searchPath = projectPath.resolve("src/main/java/com/example/SearchPatterns.java").toString();
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(searchPath));
        int idx = source.indexOf("void readFile");
        idx = source.indexOf("readFile", idx);
        int line = (int) source.substring(0, idx).chars().filter(c -> c == '\n').count();
        int lineStart = source.lastIndexOf('\n', idx) + 1;
        int column = idx - lineStart;
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", searchPath);
        args.put("line", line);
        args.put("column", column);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        String sig = (String) getData(r).get("signature");
        assertNotNull(sig);
        assertTrue(sig.contains("throws IOException"),
            "Method declaring throws must surface in signature; got: " + sig);
    }

    @Test
    @DisplayName("Static final field signature includes constant value `= 100`")
    void staticFinalField_signatureIncludesConstant() throws Exception {
        String refTarget = projectPath.resolve("src/main/java/com/example/RefactoringTarget.java").toString();
        ToolResponse r = tool.execute(argsAtIdentifier(refTarget, "MAX_SIZE"));
        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertEquals("MAX_SIZE", data.get("name"));
        String sig = (String) data.get("signature");
        assertNotNull(sig);
        assertTrue(sig.contains("static") && sig.contains("final"),
            "Static-final field signature must contain both modifiers; got: " + sig);
        assertTrue(sig.contains("= 100"),
            "Field with compile-time constant must surface `= 100` in signature; got: " + sig);
    }

    @Test
    @DisplayName("Class with extends: Dog signature contains 'extends Animal'")
    void classWithExtends_signatureContainsExtends() throws Exception {
        String animal = projectPath.resolve("src/main/java/com/example/Animal.java").toString();
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(animal));
        int idx = source.indexOf("class Dog");
        idx = source.indexOf("Dog", idx);
        int line = (int) source.substring(0, idx).chars().filter(c -> c == '\n').count();
        int lineStart = source.lastIndexOf('\n', idx) + 1;
        int column = idx - lineStart;
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", animal);
        args.put("line", line);
        args.put("column", column);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        String sig = (String) getData(r).get("signature");
        assertNotNull(sig);
        assertTrue(sig.contains("extends Animal"),
            "Dog extends Animal must appear in signature; got: " + sig);
    }

    @Test
    @DisplayName("Generic class hover: signature includes the bounded type parameter list")
    void genericClass_signatureIncludesTypeParameters() throws Exception {
        // GenericInterfaceExtractTarget<T extends Number> — hover on the class
        // name must report the class with its bounded type parameter list.
        // Without `<T extends Number>` the signature is incomplete and a tool
        // consuming it would think the class is non-generic.
        String generic = projectPath.resolve("src/main/java/com/example/GenericInterfaceExtractTarget.java").toString();
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(generic));
        int idx = source.indexOf("class GenericInterfaceExtractTarget");
        idx = source.indexOf("GenericInterfaceExtractTarget", idx);
        int line = (int) source.substring(0, idx).chars().filter(c -> c == '\n').count();
        int lineStart = source.lastIndexOf('\n', idx) + 1;
        int column = idx - lineStart;
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", generic);
        args.put("line", line);
        args.put("column", column);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        String sig = (String) getData(r).get("signature");
        assertNotNull(sig);
        assertTrue(sig.contains("<T extends Number>"),
            "Generic class signature must include `<T extends Number>`; got: " + sig);
    }

    @Test
    @DisplayName("Generic method hover: signature includes the method-level type parameter clause")
    void genericMethod_signatureIncludesTypeParameters() throws Exception {
        // identity declared as `public <U extends Comparable<U>> U identity(U input)`.
        // Hover signature must include `<U` so the consumer knows the method
        // declares its own type variable.
        String generic = projectPath.resolve("src/main/java/com/example/GenericInterfaceExtractTarget.java").toString();
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(generic));
        int idx = source.indexOf("identity(");
        int line = (int) source.substring(0, idx).chars().filter(c -> c == '\n').count();
        int lineStart = source.lastIndexOf('\n', idx) + 1;
        int column = idx - lineStart;
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", generic);
        args.put("line", line);
        args.put("column", column);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        String sig = (String) getData(r).get("signature");
        assertNotNull(sig);
        assertTrue(sig.contains("<U"),
            "Generic method signature must include `<U` (method type parameter clause); got: " + sig);
    }

    @Test
    @DisplayName("Class with implements: Rectangle signature contains 'implements IShape'")
    void classWithImplements_signatureContainsImplements() throws Exception {
        String rect = projectPath.resolve("src/main/java/com/example/Rectangle.java").toString();
        ToolResponse r = tool.execute(argsAtIdentifier(rect, "class Rectangle"));
        assertTrue(r.isSuccess());
        // Position is on "class" — we want "Rectangle". Re-target.
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(rect));
        int idx = source.indexOf("class Rectangle");
        idx = source.indexOf("Rectangle", idx);
        int line = (int) source.substring(0, idx).chars().filter(c -> c == '\n').count();
        int lineStart = source.lastIndexOf('\n', idx) + 1;
        int column = idx - lineStart;
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", rect);
        args.put("line", line);
        args.put("column", column);

        r = tool.execute(args);
        assertTrue(r.isSuccess());
        String sig = (String) getData(r).get("signature");
        assertNotNull(sig);
        assertTrue(sig.contains("implements IShape"),
            "Rectangle implements IShape must appear in signature; got: " + sig);
    }

    @Test
    @DisplayName("Static method hover: signature includes 'static' modifier")
    void staticMethod_signatureIncludesStaticModifier() throws Exception {
        String tkf = projectPath.resolve("src/main/java/com/example/TypeKindsFixture.java").toString();
        ToolResponse r = tool.execute(argsAtIdentifier(tkf, "staticHelper"));
        assertTrue(r.isSuccess());
        String sig = (String) getData(r).get("signature");
        assertNotNull(sig);
        assertTrue(sig.contains("static"),
            "Method signature must include `static` modifier; got: " + sig);
    }

    // ========== MCP envelope seam (exact authored values through processMessage) ==========

    @Test
    @DisplayName("Through the real MCP envelope: Calculator.add hover is a method with declaringType and `sum` doc")
    void envelope_addHover_exactFields() {
        ObjectNode args = envelope.args();
        args.put("filePath", calculatorPath);
        args.put("line", 14);
        args.put("column", 15);
        JsonNode payload = envelope.assertEnvelopeFidelity("get_hover_info", args);

        assertTrue(payload.get("success").asBoolean(),
            () -> "get_hover_info failed through the envelope: " + payload);
        JsonNode data = payload.get("data");
        assertEquals("add", data.get("name").asText());
        assertEquals("method", data.get("kind").asText());
        assertEquals("com.example.Calculator", data.get("declaringType").asText());
        assertTrue(data.get("docComment").asText().contains("sum"),
            "the Javadoc `@return the sum` must survive the envelope; got: " + data.get("docComment"));
    }
}
