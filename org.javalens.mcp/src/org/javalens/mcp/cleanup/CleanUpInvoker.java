package org.javalens.mcp.cleanup;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.manipulation.JavaManipulation;
import org.eclipse.jdt.core.refactoring.CompilationUnitChange;
import org.eclipse.jdt.internal.corext.fix.ConvertLoopFixCore;
import org.eclipse.jdt.ui.cleanup.ICleanUpFix;
import org.osgi.service.prefs.Preferences;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Invokes JDT's own clean-up operations (from org.eclipse.jdt.core.manipulation)
 * headlessly and returns the resulting edits as text. JavaLens never writes the
 * file — the caller gets the rewritten source and applies it.
 *
 * <p>The clean-up operations live in the IDE-independent core.manipulation bundle,
 * but they read a handful of preferences that the desktop IDE normally seeds (the
 * import-rewrite order/thresholds). {@link #ensureHeadlessEnvironment()} sets the
 * manipulation preference node and those defaults so the operations run without
 * the Eclipse UI.
 */
public final class CleanUpInvoker {

    /** Clean-up id -> factory producing the whole-file fix for a parsed AST. */
    private static final Map<String, Function<CompilationUnit, ICleanUpFix>> CLEAN_UPS = new LinkedHashMap<>();
    static {
        // Convert index-based and iterator-based for loops to enhanced for loops.
        CLEAN_UPS.put("convert_loops",
            ast -> ConvertLoopFixCore.createCleanUp(ast, true, true, false, false));
    }

    private static volatile boolean environmentReady = false;

    private CleanUpInvoker() {
    }

    /** The clean-up ids this tool can apply. */
    public static Set<String> supportedCleanUps() {
        return CLEAN_UPS.keySet();
    }

    /**
     * Apply a named clean-up to the whole compilation unit.
     *
     * @return the result; {@link CleanUpResult#changed()} is false when the
     *         clean-up found nothing to do (the source is returned unchanged).
     * @throws IllegalArgumentException if the id is unknown
     */
    public static CleanUpResult apply(ICompilationUnit cu, String cleanUpId) throws Exception {
        Function<CompilationUnit, ICleanUpFix> factory = CLEAN_UPS.get(cleanUpId);
        if (factory == null) {
            throw new IllegalArgumentException("Unknown cleanUpId: " + cleanUpId
                + ". Supported: " + supportedCleanUps());
        }

        ensureHeadlessEnvironment();

        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(cu);
        parser.setResolveBindings(true);
        CompilationUnit ast = (CompilationUnit) parser.createAST(null);

        String original = cu.getSource();

        ICleanUpFix fix = factory.apply(ast);
        if (fix == null) {
            return new CleanUpResult(false, original, null);
        }

        // getPreviewContent applies the change's edits to the unit's current
        // content and returns the rewritten source as a String — so we never
        // touch the file or the UI-only jface text document types.
        CompilationUnitChange change = fix.createChange(new NullProgressMonitor());
        String preview = change.getPreviewContent(new NullProgressMonitor());
        boolean changed = !preview.equals(original);
        return new CleanUpResult(changed, changed ? preview : original, changed ? change.getName() : null);
    }

    /**
     * Seed the minimal preference state the clean-up operations need to run
     * outside the Eclipse IDE. Idempotent.
     */
    private static void ensureHeadlessEnvironment() {
        if (environmentReady) {
            return;
        }
        synchronized (CleanUpInvoker.class) {
            if (environmentReady) {
                return;
            }
            if (JavaManipulation.getPreferenceNodeId() == null) {
                JavaManipulation.setPreferenceNodeId(JavaManipulation.ID_PLUGIN);
            }
            // The import rewrite used by several clean-ups reads these; without
            // them it throws on a null import order. These mirror JDT's defaults.
            Preferences node = InstanceScope.INSTANCE.getNode(JavaManipulation.ID_PLUGIN);
            putIfAbsent(node, "org.eclipse.jdt.ui.importorder", "java;javax;jakarta;org;com");
            putIfAbsent(node, "org.eclipse.jdt.ui.ondemandthreshold", "99");
            putIfAbsent(node, "org.eclipse.jdt.ui.staticondemandthreshold", "99");
            environmentReady = true;
        }
    }

    private static void putIfAbsent(Preferences node, String key, String value) {
        if (node.get(key, null) == null) {
            node.put(key, value);
        }
    }

    /** The outcome of a clean-up: whether anything changed, the resulting source, and a label. */
    public record CleanUpResult(boolean changed, String source, String label) {
    }
}
