package com.garganttua.core.reflection.dsl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.garganttua.core.dsl.DslException;
import com.garganttua.core.reflection.IReflection;
import com.garganttua.core.reflection.ObjectAddress;
import com.garganttua.core.reflection.ReflectionException;
import com.garganttua.core.reflection.runtime.RuntimeReflectionProvider;

/**
 * Fields read and written by name or address are resolved once per type and kept: what is kept must be exactly what
 * a fresh resolution gives, for every type, every thread, and every failure.
 */
@DisplayName("Field access by name — resolved once per type")
class FieldAccessCacheTest {

    private IReflection reflection;

    public static class Person {
        private String name;
        private Address address = new Address();
    }

    public static class Address {
        private String city;
    }

    /** Same field name, another type, another declared type. */
    public static class Counter {
        private int name;
    }

    public static class Employee extends Person {
        private String badge;
    }

    @BeforeEach
    void setUp() throws DslException {
        this.reflection = ReflectionBuilder.builder().withProvider(new RuntimeReflectionProvider()).build();
    }

    @Test
    @DisplayName("two types with a field of the same name each get their own: values and types do not cross")
    void sameNameOnTwoTypes() throws ReflectionException {
        Person person = new Person();
        Counter counter = new Counter();
        for (int i = 0; i < 3; i++) {
            this.reflection.setFieldValue(person, "name", "Ada " + i);
            this.reflection.setFieldValue(counter, "name", i);
            assertEquals("Ada " + i, this.reflection.getFieldValue(person, "name"));
            assertEquals(i, this.reflection.getFieldValue(counter, "name"));
        }
    }

    @Test
    @DisplayName("a subclass resolves its own and its inherited fields")
    void inheritedFields() throws ReflectionException {
        Employee employee = new Employee();
        this.reflection.setFieldValue(employee, "name", "Grace");
        this.reflection.setFieldValue(employee, "badge", "B-1");
        assertEquals("Grace", this.reflection.getFieldValue(employee, "name"));
        assertEquals("B-1", this.reflection.getFieldValue(employee, "badge"));
        assertNull(this.reflection.getFieldValue(new Person(), "name"), "the parent type keeps its own resolution");
    }

    @Test
    @DisplayName("a missing field fails at every call, with the same exception — a failure is never kept")
    void missingFieldFailsEveryTime() {
        Person person = new Person();
        ReflectionException first = assertThrows(ReflectionException.class,
                () -> this.reflection.getFieldValue(person, "missing"));
        ReflectionException second = assertThrows(ReflectionException.class,
                () -> this.reflection.getFieldValue(person, "missing"));
        assertEquals(first.getMessage(), second.getMessage());
        assertThrows(ReflectionException.class, () -> this.reflection.setFieldValue(person, "missing", "x"));
    }

    @Test
    @DisplayName("a nested address reads and writes through the kept resolution")
    void nestedAddress() throws ReflectionException {
        Person person = new Person();
        ObjectAddress city = new ObjectAddress("address.city", true);
        this.reflection.setFieldValue(person, city, "Lyon");
        assertEquals("Lyon", this.reflection.getFieldValue(person, city));
        assertEquals("Lyon", person.address.city);
        assertEquals("Lyon", this.reflection.getFieldValue(person, new ObjectAddress("address.city", true)),
                "an equal address finds the same resolution");
    }

    @Test
    @DisplayName("eight threads reading and writing the same fields of their own objects see only their own values")
    void concurrentAccess() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                int thread = t;
                results.add(pool.submit(() -> {
                    Person mine = new Person();
                    for (int i = 0; i < 2_000; i++) {
                        String value = thread + ":" + i;
                        this.reflection.setFieldValue(mine, "name", value);
                        if (!value.equals(this.reflection.getFieldValue(mine, "name"))) {
                            return false;
                        }
                    }
                    return true;
                }));
            }
            for (Future<Boolean> result : results) {
                assertEquals(Boolean.TRUE, result.get());
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
