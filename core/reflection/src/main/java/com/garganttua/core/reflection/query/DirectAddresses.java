package com.garganttua.core.reflection.query;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.garganttua.core.observability.Logger;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.ObjectAddress;
import com.garganttua.core.reflection.ReflectionException;

/**
 * Resolves the addresses for an element declared <em>directly</em> on a class
 * (its own fields and methods, ignoring superclasses and nested types). Extracted
 * from {@link ObjectQuery} to keep that class within the size gate; it is pure
 * (no instance state), hence a static helper.
 */
final class DirectAddresses {

    private static final Logger log = Logger.getLogger(DirectAddresses.class);

    private DirectAddresses() {
    }

    /**
     * What {@code elementName} resolves to on a class: how many overloads carry that name, and
     * whether a field does too. This is the only part of {@link #resolve} that depends on the
     * class — the addresses themselves are just that name appended to a base — so it is what gets
     * memoised. The alternative, caching the addresses, would need the base address in the key,
     * and the base address varies with every nested scan.
     *
     * @param methodCount number of declared/inherited overloads named {@code elementName}
     * @param hasField    whether a field of that name is declared directly on the class
     */
    private record Shape(int methodCount, boolean hasField) {
        boolean isEmpty() {
            return methodCount == 0 && !hasField;
        }

        int total() {
            return methodCount + (hasField ? 1 : 0);
        }
    }

    /**
     * Memoised {@code (class, element name) -> Shape}. A class's members do not change once it is
     * loaded, so a resolved shape is valid for the life of the JVM; without this the pipeline
     * re-walks the hierarchy on every request for what the AOT index already settled at compile
     * time. Keys hold the class descriptors strongly, exactly as {@code RuntimeClass}' own
     * instance cache does — the framework does not unload classes.
     */
    private static final ConcurrentMap<ShapeKey, Shape> SHAPES = new ConcurrentHashMap<>();

    private record ShapeKey(IClass<?> owner, String elementName) {
    }

    private static Shape shape(IClass<?> objectClass, String elementName) {
        return SHAPES.computeIfAbsent(new ShapeKey(objectClass, elementName),
                key -> new Shape(MemberLookup.getMethods(key.owner(), key.elementName()).size(),
                        key.owner().findDeclaredField(key.elementName()).isPresent()));
    }

    // ForLoopCanBeForeach: a counted loop adds one identical address per matching overload; a foreach
    // variable would be an unused dead store (SpotBugs DLS), so the index form is the correct shape.
    @SuppressWarnings("PMD.ForLoopCanBeForeach")
    static List<ObjectAddress> resolve(IClass<?> objectClass, String elementName, ObjectAddress baseAddress)
            throws ReflectionException {
        Shape shape = shape(objectClass, elementName);
        if (shape.isEmpty()) {
            return List.of();
        }
        String address = baseAddress == null ? elementName : baseAddress + "." + elementName;
        List<ObjectAddress> result = new ArrayList<>(shape.total());
        if (shape.methodCount() > 0) {
            log.debug("Found {} method(s) named '{}' in {}", shape.methodCount(), elementName, objectClass.getName());
            for (int i = 0; i < shape.methodCount(); i++) {
                result.add(new ObjectAddress(address, true));
            }
        }
        if (shape.hasField()) {
            log.debug("Found field '{}' in {}", elementName, objectClass.getName());
            result.add(new ObjectAddress(address, true));
        }
        return result;
    }
}
