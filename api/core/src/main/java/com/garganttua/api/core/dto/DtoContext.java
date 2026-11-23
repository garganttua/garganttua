package com.garganttua.api.core.dto;

import java.util.Objects;
import java.util.Optional;

import com.garganttua.api.core.dto.DtoDefinition;
import com.garganttua.api.commons.context.IDtoContext;
import com.garganttua.api.commons.dao.IDao;
import com.garganttua.api.commons.ApiException;
import java.util.List;

import com.garganttua.api.core.mapper.DefaultMapper;
import com.garganttua.core.reflection.IObjectQuery;
import com.garganttua.core.reflection.IReflection;
import com.garganttua.core.reflection.ObjectAddress;
import com.garganttua.core.reflection.query.ObjectQueryFactory;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.IField;
import com.garganttua.core.supply.ISupplier;
import com.garganttua.core.supply.dsl.ISupplierBuilder;

public class DtoContext<D> implements IDtoContext<D> {

    private DtoDefinition<D> dtoDefinition;
    private ISupplierBuilder<? extends IDao, ISupplier<? extends IDao>> dao;

    @Override
    public DtoDefinition<D> getDtoDefinition() { return this.dtoDefinition; }

    public DtoContext(DtoDefinition<D> dtoDefinition, ISupplierBuilder<? extends IDao, ISupplier<? extends IDao>> dao) {
        this.dtoDefinition = Objects.requireNonNull(dtoDefinition, "Dto definition cannot be null");
        this.dao = Objects.requireNonNull(dao, "Dao supplier cannot be null");
    }

    @Override
    public IDao getDao() throws ApiException {
        return this.dao.build().supply().get();
    }

    private static final IReflection REFLECTION = DefaultMapper.reflection();

    // AvoidAccessibilityAlteration: the by-name lookup opens the field the same way; this does it once.
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
    private Optional<IField> uuidField() {
        Optional<IField> known = this.resolvedUuidField;
        if (known != null) {
            return known;
        }
        Optional<IField> found = Optional.empty();
        ObjectAddress address = dtoDefinition.uuid();
        if (address != null && address.length() == 1) {
            for (IClass<?> c = dtoDefinition.dtoClass(); c != null && found.isEmpty(); c = c.getSuperclass()) {
                found = c.findDeclaredField(address.getElement(0));
            }
            found.ifPresent(f -> f.setAccessible(true));
        }
        this.resolvedUuidField = found;
        return found;
    }

    /**
     * The uuid field of the DTO class, resolved once — empty when the uuid address is not a single field of it. A
     * list read asks every row for its uuid; going through the reflection facade by name each time resolved the
     * class, the address and an accessor per row.
     */
    private volatile Optional<IField> resolvedUuidField;

    @Override
    public String getUuid(Object object) throws ApiException {
        try {
            ObjectAddress uuidAddress = dtoDefinition.uuid();
            Optional<IField> direct = uuidField();
            // Only on an instance of the DTO class itself: a subclass could declare its own field of that name, which
            // the by-name lookup below finds first.
            Object value = direct.isPresent() && object != null && dtoDefinition.dtoClass().represents(object.getClass())
                    ? direct.get().get(object)
                    : REFLECTION.getFieldValue(object, uuidAddress.toString());
            return value != null ? value.toString() : null;
        } catch (Exception e) {
            throw new ApiException("Failed to get uuid from DTO", e);
        }
    }
}
