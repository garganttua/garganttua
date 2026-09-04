package com.garganttua.api.commons.definition;

import java.lang.annotation.Annotation;
import java.util.List;

import org.javatuples.Pair;

import com.garganttua.api.commons.entity.EntityUpdateRule;
import com.garganttua.api.commons.entity.IUuidGenerator;
import com.garganttua.api.commons.entity.annotations.UnicityScope;
import com.garganttua.core.reflection.IClass;
import com.garganttua.api.commons.entity.MandatoryPolicy;
import com.garganttua.core.reflection.ObjectAddress;

public interface IEntityDefinition<E> {

    IClass<E> entityClass();

    ObjectAddress id();

    ObjectAddress uuid();

    /** When true, the framework (re)generates the uuid at creation even if the client supplied one. */
    boolean overwriteUuid();

    /** Custom uuid generator for this domain, or null to use the framework default (time-ordered UUID v7). */
    IUuidGenerator uuidGenerator();

    ObjectAddress tenantId();

    /**
     * The mandatory fields, each paired with what it is required to carry.
     *
     * <p>
     * <b>Changed in 3.0.0-ALPHA17</b> — this returned a bare {@code List<ObjectAddress>} while
     * {@code mandatory} could only mean "not null". Now that a field can declare
     * {@link MandatoryPolicy#nonBlank}, the policy has to travel with the address, or the check
     * downstream would have to guess it.
     * </p>
     */
    List<Pair<ObjectAddress, MandatoryPolicy>> mandatories();

    List<Pair<ObjectAddress, UnicityScope>> unicities();

    /**
     * CREATE-time field whitelist: each pair binds a field a caller may valorize at creation to the
     * authority it requires (null/empty = no authority). When EMPTY, creation is unrestricted (the
     * client body is kept as-is). When non-empty, only these fields are kept — every other
     * client-supplied field is stripped. Declared via {@code entity().create(field[, authority])}.
     */
    default List<Pair<ObjectAddress, String>> creates() {
        return List.of();
    }

    /**
     * UPDATE-time field whitelist: each rule binds a field a caller may valorize at update to the
     * authority it requires and to its null-handling policy. When EMPTY, no field is updatable by a
     * client. Declared via {@code entity().update(field[, authority][, ignoreNull])} or
     * {@link com.garganttua.api.commons.entity.annotations.AuthorizeUpdate}.
     */
    List<EntityUpdateRule> updates();

    List<Pair<ObjectAddress, IClass<? extends Annotation>>> annotatedFields();

    List<Pair<ObjectAddress, IClass<? extends Annotation>>> annotatedMethods();

}
