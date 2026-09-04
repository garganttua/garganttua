package com.garganttua.core.reflection.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.garganttua.core.reflection.IMethod;
import com.garganttua.core.reflection.ObjectAddress;
import com.garganttua.core.reflection.ReflectionException;
import com.garganttua.core.reflection.runtime.RuntimeClass;
import com.garganttua.core.reflection.runtime.RuntimeReflectionProvider;

/**
 * Guards the memoisation of element resolution: the answers are now computed once per
 * {@code (class, name)} and shared, so what is cached must be the part that genuinely does not
 * vary, and what varies must still be rebuilt on every call.
 */
@DisplayName("Element-resolution memoisation")
class ResolutionMemoBehaviourTest {

    private static final RuntimeReflectionProvider PROVIDER = new RuntimeReflectionProvider();

    public static class Base {
        protected String baseField;

        public String shared() { return "base"; }
    }

    public static class Sample extends Base {
        private String name;
        private String both;

        public String getName() { return name; }
        public String both() { return both; }
        public String overload(int a) { return "i"; }
        public String overload(String a) { return "s"; }
    }

    private static ObjectQuery<Sample> query() throws ReflectionException {
        return new ObjectQuery<>(RuntimeClass.of(Sample.class), PROVIDER);
    }

    @Nested
    @DisplayName("what is cached is the shape, not the address")
    class ShapeNotAddress {

        /**
         * The regression this guards: caching the resolved ADDRESSES would key on the class and
         * name only, and hand a nested scan the address computed for an unrelated base.
         */
        @Test
        @DisplayName("the same element under different base addresses yields different addresses")
        void baseAddressIsNeverCached() throws ReflectionException {
            List<ObjectAddress> root = DirectAddresses.resolve(RuntimeClass.of(Sample.class), "name", null);
            List<ObjectAddress> nested = DirectAddresses.resolve(RuntimeClass.of(Sample.class), "name",
                    new ObjectAddress("owner.details", true));
            List<ObjectAddress> other = DirectAddresses.resolve(RuntimeClass.of(Sample.class), "name",
                    new ObjectAddress("somethingElse", true));

            assertEquals(List.of("name"), texts(root));
            assertEquals(List.of("owner.details.name"), texts(nested));
            assertEquals(List.of("somethingElse.name"), texts(other));
        }

        @Test
        @DisplayName("resolving twice under the same base gives equal, independently-built lists")
        void repeatedResolutionIsStable() throws ReflectionException {
            List<ObjectAddress> first = DirectAddresses.resolve(RuntimeClass.of(Sample.class), "overload", null);
            List<ObjectAddress> second = DirectAddresses.resolve(RuntimeClass.of(Sample.class), "overload", null);

            assertEquals(texts(first), texts(second));
            assertEquals(2, first.size(), "both overloads are reported, as before the memo");
        }

        @Test
        @DisplayName("an element that is BOTH a field and a method still reports both")
        void fieldAndMethodBothReported() throws ReflectionException {
            List<ObjectAddress> addresses = DirectAddresses.resolve(RuntimeClass.of(Sample.class), "both", null);

            assertEquals(2, addresses.size(), "one for the method, one for the field");
        }

        @Test
        @DisplayName("an unknown element resolves to nothing")
        void unknownElementIsEmpty() throws ReflectionException {
            assertTrue(DirectAddresses.resolve(RuntimeClass.of(Sample.class), "nope", null).isEmpty());
        }

        private List<String> texts(List<ObjectAddress> addresses) {
            List<String> out = new ArrayList<>();
            for (ObjectAddress a : addresses) {
                out.add(a.toString());
            }
            return out;
        }
    }

    @Nested
    @DisplayName("memoised lookups keep their contract")
    class LookupContract {

        @Test
        @DisplayName("the same lookup answers the same instance on the second call")
        void secondCallIsServedFromTheMemo() {
            IMethod first = MemberLookup.getMethod(RuntimeClass.of(Sample.class), "getName");
            IMethod second = MemberLookup.getMethod(RuntimeClass.of(Sample.class), "getName");

            assertNotNull(first);
            assertSame(first, second);
        }

        @Test
        @DisplayName("a negative answer is memoised too, and stays negative")
        void negativeAnswersAreCached() {
            assertNull(MemberLookup.getMethod(RuntimeClass.of(Sample.class), "absent"));
            assertNull(MemberLookup.getMethod(RuntimeClass.of(Sample.class), "absent"));
            assertNull(MemberLookup.getField(RuntimeClass.of(Sample.class), "absent"));
            assertNull(MemberLookup.getField(RuntimeClass.of(Sample.class), "absent"));
        }

        @Test
        @DisplayName("inherited members still resolve through the hierarchy")
        void inheritedMembersResolve() {
            assertNotNull(MemberLookup.getField(RuntimeClass.of(Sample.class), "baseField"));
            assertNotNull(MemberLookup.getMethod(RuntimeClass.of(Sample.class), "shared"));
            assertEquals(1, MemberLookup.getMethods(RuntimeClass.of(Sample.class), "shared").size(),
                    "the override supersedes the base declaration — one signature, not two");
        }

        @Test
        @DisplayName("the shared overload list cannot be modified by a caller")
        void returnedListIsImmutable() {
            List<IMethod> methods = MemberLookup.getMethods(RuntimeClass.of(Sample.class), "overload");

            assertEquals(2, methods.size());
            assertThrows(UnsupportedOperationException.class, methods::clear,
                    "the list is shared between every caller — it must not be mutable");
        }
    }

    @Test
    @DisplayName("concurrent first-time resolution of the same element agrees")
    void concurrentResolutionAgrees() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<String>> tasks = new ArrayList<>();
            for (int i = 0; i < 64; i++) {
                tasks.add(() -> query().addresses("getName").toString());
            }
            List<Future<String>> results = pool.invokeAll(tasks);
            String expected = results.get(0).get();
            for (Future<String> result : results) {
                assertEquals(expected, result.get());
            }
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        }
    }
}
