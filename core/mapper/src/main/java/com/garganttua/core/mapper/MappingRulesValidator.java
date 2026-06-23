package com.garganttua.core.mapper;

import java.util.List;

import com.garganttua.core.observability.Logger;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.IField;
import com.garganttua.core.reflection.IMethod;
import com.garganttua.core.reflection.IObjectQuery;
import com.garganttua.core.reflection.IReflection;
import com.garganttua.core.reflection.ReflectionException;

/**
 * Validates resolved {@link MappingRule}s against a source/destination type pair:
 * every rule's source and destination fields must exist, and any declared
 * converter method must have a compatible single-parameter signature.
 *
 * <p>Extracted from {@code MappingRules} to keep that type focused on rule
 * parsing, executor selection and convention generation.</p>
 */
final class MappingRulesValidator {

    private static final Logger log = Logger.getLogger(MappingRulesValidator.class);

    private static final int CONVERTER_METHOD_PARAM_COUNT = 1;

    private final IReflection reflection;

    MappingRulesValidator(IReflection reflection) {
        this.reflection = reflection;
    }

    /**
     * Validates that each rule's source/destination fields exist and that any
     * declared converter method has a compatible one-parameter signature.
     *
     * @param sourceClass the class whose fields the rules are resolved against
     * @param rules the rules to validate
     * @throws MapperException if a field or method is missing or has an incompatible signature
     */
    void validate(IClass<?> sourceClass, List<MappingRule> rules) throws MapperException {
        log.debug("Validating {} rules for {}", rules.size(), sourceClass.getSimpleName());
        try {
            IObjectQuery<?> sourceQuery = this.reflection.query(sourceClass);
            for (MappingRule rule : rules) {
                this.validate(sourceQuery, rule);
            }
        } catch (ReflectionException e) {
            throw new MapperException(e);
        }
    }

    private void validate(IObjectQuery<?> sourceQuery, MappingRule rule)
            throws MapperException {
        try {
            IObjectQuery<?> destQuery = this.reflection.query(rule.destinationClass());

            List<Object> sourceField_ = sourceQuery.find(rule.sourceFieldAddress());
            List<Object> destField_ = destQuery.find(rule.destinationFieldAddress());

            IField sourceField = (IField) sourceField_.get(sourceField_.size() - 1);
            IField destField = (IField) destField_.get(destField_.size() - 1);

            if (rule.fromSourceMethodAddress() != null) {
                List<Object> fromMethod_ = destQuery.find(rule.fromSourceMethodAddress());
                IMethod fromMethod = (IMethod) fromMethod_.get(fromMethod_.size() - 1);
                validateMethod(rule, sourceField, destField, fromMethod);
            }
            if (rule.toSourceMethodAddress() != null) {
                List<Object> toMethod_ = destQuery.find(rule.toSourceMethodAddress());
                IMethod toMethod = (IMethod) toMethod_.get(toMethod_.size() - 1);
                validateMethod(rule, destField, sourceField, toMethod);
            }
        } catch (ReflectionException e) {
            throw new MapperException(e);
        }
    }

    private static void validateMethod(MappingRule rule, IField sourceField, IField destField, IMethod method)
            throws MapperException {
        if (method.getParameterTypes().length != CONVERTER_METHOD_PARAM_COUNT) {
            throw new MapperException("Invalid method " + method.getName() + " of class "
                    + rule.destinationClass().getSimpleName() + " : must have exactly one parameter");
        }

        IClass<?> paramType = method.getParameterTypes()[0];
        IClass<?> returnType = method.getReturnType();

        if (!paramType.getType().equals(sourceField.getType().getType())) {
            throw new MapperException(
                    "Invalid method " + method.getName() + " of class " + rule.destinationClass().getSimpleName()
                            + " : parameter must be of type " + sourceField.getType().getType());
        }

        if (!returnType.getType().equals(destField.getType().getType())) {
            throw new MapperException("Invalid method " + method.getName() + " of class "
                    + rule.destinationClass().getSimpleName() + " : return type must be " + destField.getType().getType());
        }
    }
}
