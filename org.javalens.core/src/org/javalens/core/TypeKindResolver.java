package org.javalens.core;

import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;

/**
 * Returns the lowercase kind label for an {@link IType}: one of
 * {@code class}, {@code interface}, {@code enum}, {@code record}, {@code annotation}.
 *
 * <p>Lowercase matches Java source keywords and the {@code @interface} declaration
 * form. Callers should never branch on capitalization.
 *
 * <p>Ordering matters: a Java {@code @interface} reports both {@code isAnnotation()=true}
 * and {@code isInterface()=true} via JDT. {@code isAnnotation()} must be checked first
 * so annotations don't classify as interfaces.
 */
public final class TypeKindResolver {

    private TypeKindResolver() {}

    public static String kindOf(IType type) {
        try {
            if (type.isAnnotation()) return "annotation";
            if (type.isInterface()) return "interface";
            if (type.isEnum()) return "enum";
            if (type.isRecord()) return "record";
            return "class";
        } catch (JavaModelException e) {
            return "class";
        }
    }
}
