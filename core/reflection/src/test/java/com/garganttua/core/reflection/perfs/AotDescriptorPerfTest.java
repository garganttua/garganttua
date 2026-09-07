package com.garganttua.core.reflection.perfs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;
import java.util.function.IntConsumer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import com.garganttua.core.aot.reflection.AOTReflectionProvider;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.annotations.Reflected;

/**
 * What a MISSING AOT descriptor costs, on the AOT provider.
 *
 * <p>
 * "Pure AOT" names a provider, not a guarantee. {@code AOTReflectionProvider} answers from its
 * registry when the type has a generated descriptor, and otherwise synthesises a
 * <em>type-identity</em> one — name, modifiers, superclass, interfaces, and <strong>no members</strong>.
 * Asking such a descriptor for a field or a method sends {@code AOTLiveClassFallback} to
 * {@code Class.forName} and the live JVM reflection, which is exactly what a consumer running pure
 * AOT does not expect to be paying for.
 * </p>
 *
 * <p>
 * A full descriptor exists only for a type carrying {@link Reflected} <em>and</em> compiled with the
 * AOT annotation processor — and which of its members it carries depends on the flags. A consumer's
 * entity without those flags resolves through the live fallback on every member, on the JVM and,
 * with its members in {@code reflect-config}, in a native image too.
 * </p>
 *
 * <pre>{@code
 * mvn -o test -pl :garganttua-reflection -Dtest='*PerfTest' -Dgarganttua.perf=true
 * }</pre>
 */
@EnabledIfSystemProperty(named = "garganttua.perf", matches = "true",
        disabledReason = "performance measurement — opt in with -Dgarganttua.perf=true")
@DisplayName("AOT descriptor: full vs shallow")
class AotDescriptorPerfTest {

    private static final int WARMUP = Integer.getInteger("garganttua.perf.warmup", 2_000);
    private static final int RUNS = Integer.getInteger("garganttua.perf.runs", 20_000);

    private static final AOTReflectionProvider PROVIDER = new AOTReflectionProvider();

    /** Carries a FULL descriptor: annotated, and compiled through the AOT annotation processor. */
    @Reflected(allDeclaredFields = true, queryAllPublicMethods = true)
    public static class Described {
        private String id;
        private String uuid;
        private String tenantId;
        private String name;
        private String email;
        private Boolean enabled;

        public String getId() { return id; }
        public String getUuid() { return uuid; }
        public String getTenantId() { return tenantId; }
        public String getName() { return name; }
        public String getEmail() { return email; }
        public Boolean getEnabled() { return enabled; }
    }

    /** The same shape, NOT annotated: the provider can only synthesise a type-identity descriptor. */
    public static class Undescribed {
        private String id;
        private String uuid;
        private String tenantId;
        private String name;
        private String email;
        private Boolean enabled;

        public String getId() { return id; }
        public String getUuid() { return uuid; }
        public String getTenantId() { return tenantId; }
        public String getName() { return name; }
        public String getEmail() { return email; }
        public Boolean getEnabled() { return enabled; }
    }

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Proves the fixture is what the rest of this class claims it is. Without this, a benchmark
     * comparing "full" against "shallow" could quietly be comparing two identical things.
     */
    @Test
    @DisplayName("the fixture really is one full descriptor and one shallow")
    void fixtureIsWhatItClaims() {
        // NOT the registry: AOTReflectionProvider.getClass() REGISTERS the type-identity descriptor
        // it synthesises, so after one lookup both types are present and presence proves nothing.
        // What actually differs is the build artefact — a generated AOTClass_* companion exists for
        // the annotated type and for no other.
        assertTrue(generatedDescriptorExists("Described"),
                "@Reflected + the AOT processor must have generated AOTClass_*_Described — without "
                        + "it this whole comparison is meaningless");
        assertFalse(generatedDescriptorExists("Undescribed"),
                "no descriptor may be generated for Undescribed: it is the shallow case");

        IClass<Described> described = PROVIDER.getClass(Described.class);
        assertEquals(6, described.getDeclaredFields().length);
    }

    /** Whether the annotation processor emitted a companion descriptor for a fixture type. */
    private static boolean generatedDescriptorExists(String simpleName) {
        try {
            Class.forName(AotDescriptorPerfTest.class.getPackageName()
                    + ".AOTClass_AotDescriptorPerfTest_" + simpleName);
            return true;
        } catch (ClassNotFoundException absent) {
            return false;
        }
    }

    @Test
    @DisplayName("looking up one field: registry hit versus live fallback")
    void singleFieldLookup() {
        IClass<Described> full = PROVIDER.getClass(Described.class);
        IClass<Undescribed> shallow = PROVIDER.getClass(Undescribed.class);
        warmRegistry(full, shallow);

        Stats[] both = compare(
                "findDeclaredField — full descriptor", i -> full.findDeclaredField("email"),
                "findDeclaredField — shallow (live fallback)", i -> shallow.findDeclaredField("email"));

        System.out.printf(Locale.ROOT,
                "%nA missing descriptor costs %.2fx on a single field lookup (%.2f -> %.2f us).%n",
                both[1].minUs / Math.max(both[0].minUs, 0.0001d), both[0].minUs, both[1].minUs);
        report("single field lookup", both);
    }

    @Test
    @DisplayName("asking for a field that does NOT exist — what the removed exception cost")
    void absentFieldLookup() {
        IClass<Undescribed> shallow = PROVIDER.getClass(Undescribed.class);
        warmRegistry(shallow, shallow);

        // The same search, twice, differing only in how it reports absence. Before 3.0.0-ALPHA17 the
        // resolution path took the throwing form, and absence is its ORDINARY answer: most elements
        // walked are methods, not fields.
        Stats[] both = compare(
                "findDeclaredField(absent) — returns empty", i -> shallow.findDeclaredField("nope"),
                "getDeclaredField(absent) — throws", i -> {
                    try {
                        shallow.getDeclaredField("nope");
                    } catch (NoSuchFieldException | SecurityException expected) {
                        // the ordinary answer, paid for with a stack trace
                    }
                });

        System.out.printf(Locale.ROOT,
                "%nSaying \"no such field\" by throwing costs %.2fx saying it by returning empty "
                        + "(%.2f -> %.2f us).%nThat multiplier was paid on nearly every element the "
                        + "pipeline resolved.%n",
                both[1].minUs / Math.max(both[0].minUs, 0.0001d), both[0].minUs, both[1].minUs);
        report("absence, thrown versus returned", both);
    }

    @Test
    @DisplayName("listing the members: registry hit versus live fallback")
    void memberListing() {
        IClass<Described> full = PROVIDER.getClass(Described.class);
        IClass<Undescribed> shallow = PROVIDER.getClass(Undescribed.class);
        warmRegistry(full, shallow);

        Stats[] both = compare(
                "getDeclaredMethods — full descriptor", i -> full.getDeclaredMethods(),
                "getDeclaredMethods — shallow (live fallback)", i -> shallow.getDeclaredMethods());
        report("member listing", both);
    }

    // ─────────────────────────────────────────────────────────────────────────

    /** Resolves both descriptors once so the registry synthesis is not part of any measurement. */
    private static void warmRegistry(IClass<?> a, IClass<?> b) {
        a.getDeclaredFields();
        b.getDeclaredFields();
        a.findDeclaredField("id");
        b.findDeclaredField("id");
    }

    /** Duration summary in microseconds. The floor is what two scenarios are compared on. */
    private record Stats(String label, double medianUs, double p95Us, double minUs) {
    }

    /**
     * Interleaved a/b measurement — the two scenarios alternate so that whatever the machine does
     * during the run hits both equally.
     */
    private static Stats[] compare(String labelA, IntConsumer a, String labelB, IntConsumer b) {
        for (int i = 0; i < WARMUP; i++) {
            a.accept(i);
            b.accept(i);
        }
        long[] da = new long[RUNS];
        long[] db = new long[RUNS];
        for (int i = 0; i < RUNS; i++) {
            long t = System.nanoTime();
            a.accept(i);
            da[i] = System.nanoTime() - t;
            t = System.nanoTime();
            b.accept(i);
            db[i] = System.nanoTime() - t;
        }
        return new Stats[] { summarise(labelA, da), summarise(labelB, db) };
    }

    private static Stats summarise(String label, long[] durations) {
        long[] sorted = durations.clone();
        java.util.Arrays.sort(sorted);
        int n = sorted.length;
        return new Stats(label, sorted[n / 2] / 1_000.0d,
                sorted[Math.min(n - 1, (int) (n * 0.95))] / 1_000.0d, sorted[0] / 1_000.0d);
    }

    private static void report(String title, Stats[] stats) {
        System.out.printf(Locale.ROOT, "%n%s%n%-48s %11s %11s %11s%n",
                title, "operation", "median(us)", "p95(us)", "min(us)");
        for (Stats s : stats) {
            System.out.printf(Locale.ROOT, "%-48s %11.3f %11.3f %11.3f%n",
                    s.label(), s.medianUs(), s.p95Us(), s.minUs());
        }
    }
}
