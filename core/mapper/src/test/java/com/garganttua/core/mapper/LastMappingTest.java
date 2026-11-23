package com.garganttua.core.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.IReflection;
import com.garganttua.core.reflection.dsl.ReflectionBuilder;
import com.garganttua.core.reflection.runtime.RuntimeReflectionProvider;

/**
 * The mapper remembers the pair it mapped last. Whatever the order of the pairs, and whatever is recorded in
 * between, each mapping must use the configuration of its own pair.
 */
@DisplayName("Mapper — the last mapped pair")
class LastMappingTest {

    private static IReflection reflection;

    @BeforeAll
    static void setUp() throws Exception {
        reflection = ReflectionBuilder.builder().withProvider(new RuntimeReflectionProvider()).build();
        IClass.setReflection(reflection);
    }

    @AfterAll
    static void tearDown() {
        IClass.setReflection(null);
    }

    private static GenericEntity entity(int i) {
        GenericEntity entity = new GenericEntity();
        entity.setUuid("uuid-" + i);
        entity.setId("id-" + i);
        return entity;
    }

    @Test
    @DisplayName("alternating two pairs maps each one by its own configuration")
    void alternatingPairs() throws MapperException {
        Mapper mapper = new Mapper(reflection).configure(MapperConfigurationItem.FAIL_ON_ERROR, false);
        for (int i = 0; i < 50; i++) {
            GenericDto dto = mapper.map(entity(i), reflection.getClass(GenericDto.class));
            assertEquals("uuid-" + i, dto.getUuid());
            GenericEntity back = mapper.map(dto, reflection.getClass(GenericEntity.class));
            assertEquals("id-" + i, back.getId());
        }
        assertEquals(2, mapper.mappingConfigurations.size());
    }

    @Test
    @DisplayName("a configuration recorded between two mappings of the same pair is the one the second uses")
    void recordedInBetween() throws MapperException {
        Mapper mapper = new Mapper(reflection).configure(MapperConfigurationItem.FAIL_ON_ERROR, false);
        IClass<GenericEntity> source = reflection.getClass(GenericEntity.class);
        IClass<GenericDto> destination = reflection.getClass(GenericDto.class);
        mapper.map(entity(1), destination);
        MappingConfiguration recorded = mapper.recordMappingConfiguration(source, destination);
        mapper.map(entity(2), destination);
        assertEquals(recorded, mapper.getMappingConfiguration(source, destination));
        assertEquals("uuid-3", mapper.map(entity(3), destination).getUuid());
    }
}
