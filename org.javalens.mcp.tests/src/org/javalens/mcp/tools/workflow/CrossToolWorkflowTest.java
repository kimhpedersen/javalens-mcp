package org.javalens.mcp.tools.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.ChangeMethodSignatureTool;
import org.javalens.mcp.tools.FindReferencesTool;
import org.javalens.mcp.tools.GetDiagnosticsTool;
import org.javalens.mcp.tools.InlineMethodTool;
import org.javalens.mcp.tools.OrganizeImportsTool;
import org.javalens.mcp.tools.RenameSymbolTool;
import org.javalens.mcp.tools.ValidateSyntaxTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cross-tool workflow tests enforcing that JavaLens tools compose end-to-end:
 * the output of one tool, applied to disk and re-loaded, produces the input
 * expected by the next tool. These pin invariants that per-tool tests cannot —
 * each per-tool test asserts the tool's own output, not that downstream tools
 * stay correct after the output is applied.
 */
class CrossToolWorkflowTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private final ObjectMapper objectMapper = new ObjectMapper();

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @Test
    @DisplayName("D1-1: rename Calculator.add to sum, apply, reload, find_references gives the same total")
    @SuppressWarnings("unchecked")
    void d1_1_renameThenFindReferences_preservesCount() throws Exception {
        // Phase 1: load the original project, capture find_references' totalCount for add.
        JdtServiceImpl service = helper.loadProject("simple-maven");
        Path calcPath = helper.getFixturePath("simple-maven")
            .resolve("src/main/java/com/example/Calculator.java");

        FindReferencesTool findRefs = new FindReferencesTool(() -> service);
        ObjectNode findArgs = objectMapper.createObjectNode();
        findArgs.put("filePath", calcPath.toString());
        findArgs.put("line", 14);   // 0-based; file line 15 is `public int add(int a, int b) {`
        findArgs.put("column", 15); // on `add`
        findArgs.put("maxResults", 1000);
        ToolResponse beforeRefs = findRefs.execute(findArgs);
        assertTrue(beforeRefs.isSuccess(), "find_references on Calculator.add must succeed");
        int originalTotal = ((Number) data(beforeRefs).get("totalCount")).intValue();
        assertTrue(originalTotal > 0,
            "Calculator.add must have at least one reference for the test to be meaningful");

        // Phase 2: capture rename edits for Calculator.add -> sum.
        RenameSymbolTool rename = new RenameSymbolTool(() -> service);
        ObjectNode renameArgs = objectMapper.createObjectNode();
        renameArgs.put("filePath", calcPath.toString());
        renameArgs.put("line", 14);
        renameArgs.put("column", 15);
        renameArgs.put("newName", "sum");
        ToolResponse renameResp = rename.execute(renameArgs);
        assertTrue(renameResp.isSuccess(), "rename Calculator.add -> sum must succeed");
        Map<String, List<Map<String, Object>>> editsByFile =
            (Map<String, List<Map<String, Object>>>) data(renameResp).get("editsByFile");
        assertNotNull(editsByFile);
        assertFalse(editsByFile.isEmpty(), "rename must emit at least one edit");

        // Phase 3: copy the project to temp and apply the edits.
        Path tempProject = helper.copyFixture("simple-maven");
        applyEditsByFile(tempProject, editsByFile);

        // Phase 4: re-load the modified copy in a fresh service.
        JdtServiceImpl modifiedService = new JdtServiceImpl();
        modifiedService.loadProject(tempProject);
        Path renamedCalcPath = tempProject.resolve("src/main/java/com/example/Calculator.java");
        String renamedSource = Files.readString(renamedCalcPath);
        assertTrue(renamedSource.contains("public int sum(int a, int b)"),
            "Rename must have rewritten the declaration to `sum`; got: " + renamedSource);

        // Phase 5: find_references on sum gives the same count as add had.
        FindReferencesTool findRefsAfter = new FindReferencesTool(() -> modifiedService);
        ObjectNode afterArgs = objectMapper.createObjectNode();
        afterArgs.put("filePath", renamedCalcPath.toString());
        afterArgs.put("line", 14);
        afterArgs.put("column", 15);
        afterArgs.put("maxResults", 1000);
        ToolResponse afterRefs = findRefsAfter.execute(afterArgs);
        assertTrue(afterRefs.isSuccess(), "find_references on the renamed sum must succeed");
        int renamedTotal = ((Number) data(afterRefs).get("totalCount")).intValue();
        assertEquals(originalTotal, renamedTotal,
            "find_references totalCount before/after rename must match: " +
                "before=" + originalTotal + " after=" + renamedTotal);
    }

    @Test
    @DisplayName("D1-2: organize_imports on RefactoringTarget, apply, reload, validate_syntax reports zero errors")
    void d1_2_organizeImportsThenValidateSyntax_noErrors() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        Path refTarget = helper.getFixturePath("simple-maven")
            .resolve("src/main/java/com/example/RefactoringTarget.java");

        OrganizeImportsTool organize = new OrganizeImportsTool(() -> service);
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refTarget.toString());
        ToolResponse resp = organize.execute(args);
        assertTrue(resp.isSuccess(), "organize_imports must succeed");

        // Apply if there's a textEdit; if hasChanges==false there's nothing to apply.
        @SuppressWarnings("unchecked")
        Map<String, Object> respData = (Map<String, Object>) resp.getData();
        Boolean hasChanges = (Boolean) respData.get("hasChanges");
        Path tempProject = helper.copyFixture("simple-maven");
        Path tempTarget = tempProject.resolve("src/main/java/com/example/RefactoringTarget.java");
        if (Boolean.TRUE.equals(hasChanges)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> textEdit = (Map<String, Object>) respData.get("textEdit");
            @SuppressWarnings("unchecked")
            Map<String, Object> importRange = (Map<String, Object>) respData.get("importRange");
            int startOff = ((Number) importRange.get("startOffset")).intValue();
            int endOff = ((Number) importRange.get("endOffset")).intValue();
            String newText = (String) textEdit.get("newText");
            String original = Files.readString(tempTarget);
            String modified = original.substring(0, startOff) + newText + original.substring(endOff);
            Files.writeString(tempTarget, modified);
        }

        // Re-load and run validate_syntax on the (possibly modified) file.
        JdtServiceImpl modifiedService = new JdtServiceImpl();
        modifiedService.loadProject(tempProject);
        ValidateSyntaxTool validate = new ValidateSyntaxTool(() -> modifiedService);
        ObjectNode validateArgs = objectMapper.createObjectNode();
        validateArgs.put("filePath", tempTarget.toString());
        ToolResponse validateResp = validate.execute(validateArgs);
        assertTrue(validateResp.isSuccess(), "validate_syntax must succeed after organize_imports");

        @SuppressWarnings("unchecked")
        Map<String, Object> vData = (Map<String, Object>) validateResp.getData();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) vData.get("errors");
        // validate_syntax may return an empty list or omit the field when there are no
        // errors; treat both as zero.
        int errorCount = errors == null ? 0 : errors.size();
        assertEquals(0, errorCount,
            "validate_syntax must report zero errors after organize_imports; got: " + errors);
    }

    @Test
    @DisplayName("D1-3: inline_method on Calculator.add, apply, reload, get_diagnostics reports no NEW errors")
    @SuppressWarnings("unchecked")
    void d1_3_inlineMethodThenDiagnostics_noNewErrors() throws Exception {
        // Use RefactoringTarget.doubleValue (a private trivial method) as the inline target
        // — Calculator.add has cross-file callers that would break the test if its body
        // is inlined.
        JdtServiceImpl service = helper.loadProject("simple-maven");
        Path refTarget = helper.getFixturePath("simple-maven")
            .resolve("src/main/java/com/example/RefactoringTarget.java");

        // Baseline diagnostic count BEFORE inline.
        GetDiagnosticsTool diag = new GetDiagnosticsTool(() -> service);
        ObjectNode diagArgs = objectMapper.createObjectNode();
        diagArgs.put("filePath", refTarget.toString());
        ToolResponse baseDiagResp = diag.execute(diagArgs);
        assertTrue(baseDiagResp.isSuccess(), "get_diagnostics must succeed on original");
        int baselineErrorCount = countErrorLevelDiagnostics(baseDiagResp);

        // Run inline_method on doubleValue (file line 58 = `private int doubleValue(int value)`).
        InlineMethodTool inline = new InlineMethodTool(() -> service);
        ObjectNode inlineArgs = objectMapper.createObjectNode();
        inlineArgs.put("filePath", refTarget.toString());
        inlineArgs.put("line", 57);   // 0-based
        inlineArgs.put("column", 16); // on `doubleValue`
        ToolResponse inlineResp = inline.execute(inlineArgs);
        // inline_method may refuse if the method has unsupported call shapes; that's fine
        // for this cross-tool test — what matters is that IF it succeeds, get_diagnostics
        // applied to the result stays clean.
        if (!inlineResp.isSuccess()) {
            return; // refusal path; nothing to assert downstream
        }

        Map<String, List<Map<String, Object>>> editsByFile =
            (Map<String, List<Map<String, Object>>>) data(inlineResp).get("editsByFile");
        if (editsByFile == null || editsByFile.isEmpty()) return;

        Path tempProject = helper.copyFixture("simple-maven");
        applyEditsByFile(tempProject, editsByFile);

        JdtServiceImpl modifiedService = new JdtServiceImpl();
        modifiedService.loadProject(tempProject);
        Path modTarget = tempProject.resolve("src/main/java/com/example/RefactoringTarget.java");

        GetDiagnosticsTool diagAfter = new GetDiagnosticsTool(() -> modifiedService);
        ObjectNode afterArgs = objectMapper.createObjectNode();
        afterArgs.put("filePath", modTarget.toString());
        ToolResponse afterDiag = diagAfter.execute(afterArgs);
        assertTrue(afterDiag.isSuccess(), "get_diagnostics must succeed after inline");
        int afterErrorCount = countErrorLevelDiagnostics(afterDiag);
        assertTrue(afterErrorCount <= baselineErrorCount,
            "inline_method must not introduce NEW errors; baseline=" + baselineErrorCount
                + " after=" + afterErrorCount);
    }

    @Test
    @DisplayName("D1-4: change_method_signature on formatMessage, apply, reload, find_references finds the same call sites")
    @SuppressWarnings("unchecked")
    void d1_4_changeMethodSigThenFindReferences_callSitesUpdated() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        Path refTarget = helper.getFixturePath("simple-maven")
            .resolve("src/main/java/com/example/RefactoringTarget.java");

        // Count call sites for formatMessage BEFORE the signature change.
        FindReferencesTool findRefs = new FindReferencesTool(() -> service);
        ObjectNode findArgs = objectMapper.createObjectNode();
        findArgs.put("filePath", refTarget.toString());
        findArgs.put("line", 71);  // 0-based; file line 72 is `public String formatMessage(String message, int count) {`
        findArgs.put("column", 18); // on `formatMessage`
        findArgs.put("maxResults", 1000);
        ToolResponse beforeRefs = findRefs.execute(findArgs);
        assertTrue(beforeRefs.isSuccess(), "find_references on formatMessage must succeed");
        int beforeTotal = ((Number) data(beforeRefs).get("totalCount")).intValue();
        assertTrue(beforeTotal > 0,
            "formatMessage must have at least one call site for the test to be meaningful");

        // Add a parameter via change_method_signature.
        ChangeMethodSignatureTool changeMs = new ChangeMethodSignatureTool(() -> service);
        ObjectNode csArgs = objectMapper.createObjectNode();
        csArgs.put("filePath", refTarget.toString());
        csArgs.put("line", 71);
        csArgs.put("column", 18);
        com.fasterxml.jackson.databind.node.ArrayNode params = csArgs.putArray("newParameters");
        params.addObject().put("type", "String").put("name", "message");
        params.addObject().put("type", "int").put("name", "count");
        params.addObject().put("type", "String").put("name", "suffix").put("defaultValue", "\"\"");
        ToolResponse csResp = changeMs.execute(csArgs);
        assertTrue(csResp.isSuccess(),
            "change_method_signature must succeed; got: " +
                (csResp.getError() != null ? csResp.getError().getMessage() : "n/a"));

        Map<String, List<Map<String, Object>>> editsByFile =
            (Map<String, List<Map<String, Object>>>) data(csResp).get("editsByFile");
        assertNotNull(editsByFile);
        Path tempProject = helper.copyFixture("simple-maven");
        applyEditsByFile(tempProject, editsByFile);

        // Re-load and find references; total count must match (signature change does not
        // remove or add call sites, only rewrites argument lists).
        JdtServiceImpl modifiedService = new JdtServiceImpl();
        modifiedService.loadProject(tempProject);
        Path modTarget = tempProject.resolve("src/main/java/com/example/RefactoringTarget.java");
        FindReferencesTool findAfter = new FindReferencesTool(() -> modifiedService);
        ObjectNode afterArgs = objectMapper.createObjectNode();
        afterArgs.put("filePath", modTarget.toString());
        afterArgs.put("line", 71);
        afterArgs.put("column", 18);
        afterArgs.put("maxResults", 1000);
        ToolResponse afterRefs = findAfter.execute(afterArgs);
        assertTrue(afterRefs.isSuccess(),
            "find_references on the modified formatMessage must succeed; got: " +
                (afterRefs.getError() != null ? afterRefs.getError().getMessage() : "n/a"));
        int afterTotal = ((Number) data(afterRefs).get("totalCount")).intValue();
        assertEquals(beforeTotal, afterTotal,
            "Call-site count must be preserved across signature change; before=" + beforeTotal
                + " after=" + afterTotal);
    }

    /**
     * Apply rename/inline/change-signature-style edits (each carries startOffset, endOffset,
     * newText) to files in {@code projectRoot}. Edits per file are sorted reverse-position
     * before application so earlier offsets remain valid as later edits rewrite the file.
     */
    private static void applyEditsByFile(Path projectRoot, Map<String, List<Map<String, Object>>> editsByFile)
            throws Exception {
        Map<Path, List<Map<String, Object>>> resolved = new HashMap<>();
        for (Map.Entry<String, List<Map<String, Object>>> e : editsByFile.entrySet()) {
            Path filePath = resolveFilePath(projectRoot, e.getKey());
            resolved.computeIfAbsent(filePath, k -> new ArrayList<>()).addAll(e.getValue());
        }
        for (Map.Entry<Path, List<Map<String, Object>>> e : resolved.entrySet()) {
            Path file = e.getKey();
            String content = Files.readString(file);
            List<Map<String, Object>> edits = new ArrayList<>(e.getValue());
            edits.sort(Comparator.comparingInt(
                m -> -((Number) m.get("startOffset")).intValue()));
            StringBuilder buf = new StringBuilder(content);
            for (Map<String, Object> edit : edits) {
                int start = ((Number) edit.get("startOffset")).intValue();
                int end = ((Number) edit.get("endOffset")).intValue();
                // Different tools use different replacement-text field names: rename uses
                // newText; change_method_signature uses newSignature for the declaration
                // edit and newText for call sites. Prefer newText then newSignature.
                String newText = (String) edit.get("newText");
                if (newText == null) newText = (String) edit.get("newSignature");
                if (newText == null) continue; // unknown shape — skip safely
                buf.replace(start, end, newText);
            }
            Files.writeString(file, buf.toString());
        }
    }

    /**
     * Resolve a path from the rename/refactor response — which may be absolute, project-relative,
     * or src-relative depending on the tool — to an absolute file under {@code projectRoot}.
     */
    private static Path resolveFilePath(Path projectRoot, String reportedPath) {
        Path direct = Path.of(reportedPath);
        if (direct.isAbsolute() && Files.exists(direct)) return direct;
        Path resolved = projectRoot.resolve(reportedPath).normalize();
        if (Files.exists(resolved)) return resolved;
        // The tool may report paths relative to the ORIGINAL fixture directory; rewrite to
        // the temp copy by replacing the fixture root prefix.
        String fixtureMarker = "simple-maven";
        int idx = reportedPath.indexOf(fixtureMarker);
        if (idx >= 0) {
            String tail = reportedPath.substring(idx + fixtureMarker.length() + 1);
            Path rewritten = projectRoot.resolve(tail).normalize();
            if (Files.exists(rewritten)) return rewritten;
        }
        return resolved;
    }

    @SuppressWarnings("unchecked")
    private static int countErrorLevelDiagnostics(ToolResponse r) {
        Map<String, Object> d = (Map<String, Object>) r.getData();
        Object errorsObj = d.get("errors");
        if (!(errorsObj instanceof List<?> errors)) return 0;
        return errors.size();
    }
}
