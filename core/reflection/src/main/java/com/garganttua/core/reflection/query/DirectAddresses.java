package com.garganttua.core.reflection.query;

import java.util.ArrayList;
import java.util.List;

import com.garganttua.core.observability.Logger;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.IField;
import com.garganttua.core.reflection.IMethod;
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

    // ForLoopCanBeForeach: a counted loop adds one identical address per matching overload; a foreach
    // variable would be an unused dead store (SpotBugs DLS), so the index form is the correct shape.
    @SuppressWarnings("PMD.ForLoopCanBeForeach")
    static List<ObjectAddress> resolve(IClass<?> objectClass, String elementName, ObjectAddress baseAddress)
            throws ReflectionException {
        List<ObjectAddress> result = new ArrayList<>();
        IField field = null;
        try {
            field = objectClass.getDeclaredField(elementName);
        } catch (NoSuchFieldException | SecurityException ignored) {
        }

        List<IMethod> methods = MemberLookup.getMethods(objectClass, elementName);
        if (!methods.isEmpty()) {
            log.debug("Found {} method(s) named '{}' in {}", methods.size(), elementName, objectClass.getName());
            String methodAddress = baseAddress == null ? elementName : baseAddress + "." + elementName;
            for (int i = 0; i < methods.size(); i++) {
                result.add(new ObjectAddress(methodAddress, true));
            }
        }

        if (field != null) {
            log.debug("Found field '{}' in {}", elementName, objectClass.getName());
            result.add(new ObjectAddress(baseAddress == null ? elementName : baseAddress + "." + elementName, true));
        }
        return result;
    }
}
