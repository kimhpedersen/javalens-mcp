package org.javalens.core;

import org.eclipse.jdt.core.Flags;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModifierFormatterTest {

    @Test
    @DisplayName("zero flags returns an empty list")
    void zeroFlags_emptyList() {
        assertTrue(ModifierFormatter.format(0).isEmpty());
    }

    @Test
    @DisplayName("public static final emits in declaration order")
    void publicStaticFinal_inOrder() {
        int flags = Flags.AccPublic | Flags.AccStatic | Flags.AccFinal;
        assertEquals(List.of("public", "static", "final"), ModifierFormatter.format(flags));
    }

    @Test
    @DisplayName("private synchronized native default surfaces method-only modifiers")
    void methodOnlyFlags() {
        int flags = Flags.AccPrivate | Flags.AccSynchronized | Flags.AccNative | Flags.AccDefaultMethod;
        assertEquals(
            List.of("private", "synchronized", "native", "default"),
            ModifierFormatter.format(flags));
    }

    @Test
    @DisplayName("transient volatile surfaces field-only modifiers")
    void fieldOnlyFlags() {
        int flags = Flags.AccTransient | Flags.AccVolatile;
        assertEquals(List.of("transient", "volatile"), ModifierFormatter.format(flags));
    }

    @Test
    @DisplayName("protected abstract surfaces type/method modifiers in correct order")
    void protectedAbstract_inOrder() {
        int flags = Flags.AccProtected | Flags.AccAbstract;
        assertEquals(List.of("protected", "abstract"), ModifierFormatter.format(flags));
    }

    @Test
    @DisplayName("strictfp emitted in its documented position (after default, before transient)")
    void strictfp_inOrder() {
        // The only one of the 12 modifiers not covered by the existing tests. Source
        // orders: ... default, strictfp, transient, volatile. Combine strictfp with
        // surrounding-position modifiers to pin the ordering.
        int flags = Flags.AccPublic | Flags.AccStrictfp | Flags.AccFinal;
        assertEquals(List.of("public", "final", "strictfp"), ModifierFormatter.format(flags));
    }

    @Test
    @DisplayName("all 12 modifiers together emit in documented declaration order")
    void allTwelveModifiers_inDocumentedOrder() {
        // Combining every modifier the source recognizes catches any ordering regression
        // in a single assertion. JDT's Flags constants are independent bits, so the OR
        // is well-defined even though no real Java element would carry all 12.
        int flags = Flags.AccPublic | Flags.AccProtected | Flags.AccPrivate
            | Flags.AccStatic | Flags.AccFinal | Flags.AccAbstract
            | Flags.AccSynchronized | Flags.AccNative | Flags.AccDefaultMethod
            | Flags.AccStrictfp | Flags.AccTransient | Flags.AccVolatile;
        assertEquals(
            List.of("public", "protected", "private", "static", "final", "abstract",
                "synchronized", "native", "default", "strictfp", "transient", "volatile"),
            ModifierFormatter.format(flags));
    }

    @Test
    @DisplayName("Each of the 12 modifiers emits exactly itself when set alone")
    void eachModifier_emittedInIsolation() {
        // Every flag in isolation — catches a regression where a flag check
        // accidentally matches an unrelated bit (e.g. a copy-paste swapping
        // Flags.isFinal for Flags.isStatic).
        record Row(int flag, String name) {}
        Row[] rows = {
            new Row(Flags.AccPublic, "public"),
            new Row(Flags.AccProtected, "protected"),
            new Row(Flags.AccPrivate, "private"),
            new Row(Flags.AccStatic, "static"),
            new Row(Flags.AccFinal, "final"),
            new Row(Flags.AccAbstract, "abstract"),
            new Row(Flags.AccSynchronized, "synchronized"),
            new Row(Flags.AccNative, "native"),
            new Row(Flags.AccDefaultMethod, "default"),
            new Row(Flags.AccStrictfp, "strictfp"),
            new Row(Flags.AccTransient, "transient"),
            new Row(Flags.AccVolatile, "volatile")
        };
        for (Row row : rows) {
            assertEquals(List.of(row.name()), ModifierFormatter.format(row.flag()),
                "flag " + row.name() + " in isolation must produce exactly [" + row.name() + "]");
        }
    }

    @Test
    @DisplayName("Truly unmapped bits are silently ignored (AccSynthetic has no isXxx check)")
    void unmappedBits_ignored() {
        // AccSynthetic (0x1000) is unique among JVM access bits: it has no
        // counterpart in ModifierFormatter (no `Flags.isSynthetic` call). Passing
        // it alone must produce an empty list, not a stray entry.
        //
        // Note: AccBridge/AccSuper would NOT be safe to use here because the JVM
        // bit-packs them with AccVolatile/AccSynchronized respectively — JDT's
        // Flags.isVolatile() and Flags.isSynchronized() see the bit regardless of
        // which element type it came from. The formatter inherits that ambiguity
        // (it's resolved by the caller, who knows the element kind). Pin AccSynthetic
        // because it's the one truly-unmapped bit available for this contract test.
        assertEquals(List.of(), ModifierFormatter.format(Flags.AccSynthetic),
            "AccSynthetic must produce []; got: " + ModifierFormatter.format(Flags.AccSynthetic));
    }

    @Test
    @DisplayName("AccBridge surfaces as 'volatile' because the JVM packs both into bit 0x40 (documented limitation)")
    void bridgeAndVolatile_shareBit() {
        // This pins the documented limitation: ModifierFormatter cannot distinguish
        // a method's BRIDGE flag from a field's VOLATILE flag because they share
        // the underlying access-flags bit. The caller (the tool that built the
        // flag value via IMember.getFlags) is responsible for context — fields
        // pass field-element flags, methods pass method-element flags, and the
        // formatter does not need to disambiguate. A regression that changed bit
        // assignments in a future JVM revision would surface here.
        assertEquals(Flags.AccVolatile, Flags.AccBridge,
            "Document JVM reality: AccVolatile and AccBridge are the same bit");
        assertEquals(Flags.AccSynchronized, Flags.AccSuper,
            "Document JVM reality: AccSynchronized and AccSuper are the same bit");
        // Consequence: feeding AccBridge to the formatter yields ["volatile"], not [].
        assertEquals(List.of("volatile"), ModifierFormatter.format(Flags.AccBridge),
            "AccBridge feeds as 'volatile' due to bit-sharing; documented limitation");
    }

    @Test
    @DisplayName("Consecutive calls do not share state (each returns its own list)")
    void consecutiveCalls_independent() {
        // format() must allocate a fresh ArrayList per call. If it ever switched to a
        // shared/cached collection the second call would carry leftovers. Pin the
        // independence.
        List<String> first = ModifierFormatter.format(Flags.AccPublic);
        List<String> second = ModifierFormatter.format(Flags.AccPrivate);
        assertEquals(List.of("public"), first);
        assertEquals(List.of("private"), second);
        assertTrue(first != second, "Each call must return a distinct list instance");
    }
}
