package com.garganttua.dao.postgresql;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.garganttua.api.commons.definition.DtoComposition;
import com.garganttua.api.commons.definition.IDomainDefinition;
import com.garganttua.api.commons.definition.IDtoDefinition;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.ObjectAddress;

/** Domain definitions for DAO tests, built the way the MongoDB DAO tests build theirs. */
public final class TestDomains {

    private TestDomains() {
        // Static helpers
    }

    /**
     * A domain definition whose single DTO is {@code dtoClass}.
     *
     * @param dtoClass     the DTO
     * @param compositions {@code @Composed} field name to target domain
     * @return the definition
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public static IDomainDefinition definition(Class<?> dtoClass, Map<String, String> compositions) {
        IDtoDefinition dto = mock(IDtoDefinition.class);
        when(dto.dtoClass()).thenReturn(IClass.getClass(dtoClass));
        when(dto.uuid()).thenReturn(new ObjectAddress("uuid"));
        List<DtoComposition> composed = new ArrayList<>();
        compositions.forEach((field, target) -> composed.add(new DtoComposition(new ObjectAddress(field), target)));
        when(dto.compositions()).thenReturn(composed);
        IDomainDefinition domain = mock(IDomainDefinition.class);
        when(domain.dtoDefinitions()).thenReturn(List.of(dto));
        return domain;
    }
}
