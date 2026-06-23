package com.garganttua.api.core.dto;

import com.garganttua.api.core.SuppressFBWarnings;

import java.util.List;

import com.garganttua.api.commons.definition.DtoComposition;
import com.garganttua.api.commons.definition.IDtoDefinition;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.ObjectAddress;

@SuppressFBWarnings(value = {"EI_EXPOSE_REP"}, justification = "Immutable-by-contract value/definition carrier; collections & arrays carried by reference as a snapshot (framework-internal, built once).")
public record DtoDefinition<D>(IClass<D> dtoClass, ObjectAddress uuid, ObjectAddress id, ObjectAddress tenantId,
		List<DtoComposition> compositions) implements IDtoDefinition<D> {

}
