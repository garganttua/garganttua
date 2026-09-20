package fixtures.expressions;

import com.garganttua.core.expression.annotations.Expression;

/**
 * Deliberately malformed (and well-formed) {@code @Expression} declarations, used to pin the
 * one-name-one-method rule.
 *
 * <p>
 * They live OUTSIDE {@code com.garganttua} on purpose: package auto-detection scans that prefix, so
 * a broken fixture there would be registered by every other test in the module and fail them all —
 * which is exactly what happened the first time these were written.
 * </p>
 */
public final class AmbiguousFixtures {

    private AmbiguousFixtures() {
        // Fixture holder
    }

    /** The shape that shipped broken: same name, same arity, differing only in a parameter type. */
    public static class Ambiguous {

        @Expression(name = "collide", description = "one")
        public static Object collide(String first, Object second) {
            return first;
        }

        @Expression(name = "collide", description = "the other")
        public static Object collide(Object first, Object second) {
            return first;
        }
    }

    /** Same name, DIFFERENT arity — unambiguous, and a legitimate way to offer an optional argument. */
    public static class DifferentArities {

        @Expression(name = "fine", description = "one argument")
        public static Object fine(Object only) {
            return only;
        }

        @Expression(name = "fine", description = "two arguments")
        public static Object fine(Object first, Object second) {
            return first;
        }
    }

    /**
     * Same name, same arity, UNRELATED parameter types — the shape {@code Beans.bean} actually has.
     * Resolution tells these apart, so refusing them would condemn a function that works.
     */
    public static class SameArityUnrelatedTypes {

        @Expression(name = "pick", description = "by number")
        public static Object pick(Integer index, String label) {
            return label;
        }

        @Expression(name = "pick", description = "by reference")
        public static Object pick(java.util.Optional<String> reference, Boolean flag) {
            return reference;
        }
    }

    /** Distinct names on same-arity methods — the ordinary case. */
    public static class DistinctNames {

        @Expression(name = "left", description = "left")
        public static Object left(Object only) {
            return only;
        }

        @Expression(name = "right", description = "right")
        public static Object right(Object only) {
            return only;
        }
    }
}
