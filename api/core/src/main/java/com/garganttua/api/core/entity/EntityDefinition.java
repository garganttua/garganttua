package com.garganttua.api.core.entity;

import com.garganttua.api.core.SuppressFBWarnings;

import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Map;

import org.javatuples.Pair;

import com.garganttua.api.commons.definition.IEntityDefinition;
import com.garganttua.api.commons.entity.EntityUpdateRule;
import com.garganttua.api.commons.entity.IUuidGenerator;
import com.garganttua.api.commons.entity.annotations.UnicityScope;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.ObjectAddress;
import com.garganttua.core.reflection.binders.IMethodBinder;

@SuppressFBWarnings(value = {"EI_EXPOSE_REP"}, justification = "Immutable-by-contract value/definition carrier; collections & arrays carried by reference as a snapshot (framework-internal, built once).")
public record EntityDefinition<E>(
    IClass<E> entityClass,
    ObjectAddress id,
    ObjectAddress uuid,
    ObjectAddress tenantId,
    List<ObjectAddress> mandatories,
    List<Pair<ObjectAddress, UnicityScope>> unicities,
    List<Pair<ObjectAddress, String>> creates,
    List<EntityUpdateRule> updates,
    List<Pair<ObjectAddress, IClass<? extends Annotation>>> annotatedFields,
    List<Pair<ObjectAddress, IClass<? extends Annotation>>> annotatedMethods,
    /*
     * Entity-bound lifecycle hooks: the declared method NAME paired with the binder built for it.
     * The name is what invokes the hook (an ObjectAddress resolved against each entity); the binder
     * is what validated, at build time, that the method exists on the entity with the expected
     * signature. The name is carried explicitly because a binder has none to give back:
     * IExecutableBinder.getExecutableReference() is a human-readable, ANSI-COLORED rendering meant
     * for logs and error messages, and an ObjectAddress built from it resolves to nothing.
     */
    List<Pair<String, IMethodBinder<Void>>> afterGetMethodBuilders,
    List<Pair<String, IMethodBinder<Void>>>  beforeCreateMethodBuilders,
    List<Pair<String, IMethodBinder<Void>>>  afterCreateMethodBuilders,
    List<Pair<String, IMethodBinder<Void>>>  beforeUpdateMethodBuilders,
    List<Pair<String, IMethodBinder<Void>>>  afterUpdateMethodBuilders,
    List<Pair<String, IMethodBinder<Void>>>  beforeDeleteMethodBuilders,
    List<Pair<String, IMethodBinder<Void>>>  afterDeleteMethodBuilders,
    boolean overwriteUuid,
    IUuidGenerator uuidGenerator,
    /**
     * Free lifecycle-hook binders keyed by hook name ("beforeCreate" / "afterGet" / …). Unlike the
     * {@code *MethodBuilders} above (instance methods invoked ON the entity via invokeDeep), these are
     * fully-wired binders bound to an EXACT method (possibly on an external class, static or instance),
     * fed the current entity + injected framework context, and EXECUTED. Declared via the
     * {@code entity().beforeCreate(IMethod)} overloads.
     */
    Map<String, List<IMethodBinder<?>>> freeHookBinders) implements IEntityDefinition<E> {

}
