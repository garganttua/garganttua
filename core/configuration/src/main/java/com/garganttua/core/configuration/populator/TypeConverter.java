package com.garganttua.core.configuration.populator;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Period;
import java.util.Optional;
import java.util.UUID;

import com.garganttua.core.configuration.ConfigurationException;
import com.garganttua.core.reflection.IClass;

/**
 * Converts configuration string values into target Java types: primitives and wrappers,
 * big numbers, {@code java.time} temporals, IO/net types ({@link Path}, {@link URI},
 * {@link URL}), {@link UUID}, {@link Class}, and enums.
 */
public class TypeConverter {

    /** Number of characters in a {@code char} value. */
    private static final int CHAR_LENGTH = 1;

    /**
     * Converts a string to the requested target type.
     *
     * @param value      the raw string value, may be {@code null}
     * @param targetType the type to convert to
     * @param <T>        the target type
     * @return the converted value, or {@code null} when {@code value} is {@code null}
     * @throws ConfigurationException if the target type is unsupported or the value cannot be parsed
     */
    public <T> T convert(String value, Class<T> targetType) throws ConfigurationException {
        if (value == null) {
            return null;
        }
        try {
            var result = convertScalar(value, targetType);
            if (result != null) {
                return result;
            }
            throw new ConfigurationException("Unsupported type conversion: String -> " + targetType.getName());
        } catch (ConfigurationException e) {
            throw e;
        } catch (Exception e) {
            throw new ConfigurationException("Failed to convert '" + value + "' to " + targetType.getName(), e);
        }
    }

    // single unchecked cast point: helpers return Object boxed to the requested target type
    @SuppressWarnings("unchecked")
    private <T> T convertScalar(String value, Class<T> targetType)
            throws ConfigurationException, ClassNotFoundException, MalformedURLException {
        Object result = convertPrimitive(value, targetType);
        if (result == null) {
            result = convertBigNumber(value, targetType);
        }
        if (result == null) {
            result = convertTemporal(value, targetType);
        }
        if (result == null) {
            result = convertOther(value, targetType);
        }
        return (T) result;
    }

    private Object convertPrimitive(String value, Class<?> targetType) throws ConfigurationException {
        if (targetType == String.class) {
            return value;
        }
        if (targetType == int.class || targetType == Integer.class) {
            return Integer.valueOf(value);
        }
        if (targetType == long.class || targetType == Long.class) {
            return Long.valueOf(value);
        }
        if (targetType == double.class || targetType == Double.class) {
            return Double.valueOf(value);
        }
        if (targetType == float.class || targetType == Float.class) {
            return Float.valueOf(value);
        }
        if (targetType == boolean.class || targetType == Boolean.class) {
            return Boolean.valueOf(value);
        }
        if (targetType == byte.class || targetType == Byte.class) {
            return Byte.valueOf(value);
        }
        if (targetType == short.class || targetType == Short.class) {
            return Short.valueOf(value);
        }
        if (targetType == char.class || targetType == Character.class) {
            if (value.length() != CHAR_LENGTH) {
                throw new ConfigurationException("Cannot convert '" + value + "' to char");
            }
            return Character.valueOf(value.charAt(0));
        }
        return null;
    }

    private Object convertBigNumber(String value, Class<?> targetType) {
        if (targetType == BigDecimal.class) {
            return new BigDecimal(value);
        }
        if (targetType == BigInteger.class) {
            return new BigInteger(value);
        }
        return null;
    }

    private Object convertTemporal(String value, Class<?> targetType) {
        if (targetType == Duration.class) {
            return Duration.parse(value);
        }
        if (targetType == Period.class) {
            return Period.parse(value);
        }
        if (targetType == Instant.class) {
            return Instant.parse(value);
        }
        if (targetType == LocalDate.class) {
            return LocalDate.parse(value);
        }
        if (targetType == LocalTime.class) {
            return LocalTime.parse(value);
        }
        if (targetType == LocalDateTime.class) {
            return LocalDateTime.parse(value);
        }
        return null;
    }

    private Object convertOther(String value, Class<?> targetType)
            throws ClassNotFoundException, MalformedURLException {
        if (targetType == Path.class) {
            return Path.of(value);
        }
        if (targetType == URI.class) {
            return URI.create(value);
        }
        if (targetType == URL.class) {
            return URI.create(value).toURL();
        }
        if (targetType == UUID.class) {
            return UUID.fromString(value);
        }
        if (targetType == Class.class) {
            return IClass.forName(value);
        }
        // IClass — the framework's reflection wrapper (e.g. withBean(IClass<?>) keys)
        if (IClass.class.isAssignableFrom(targetType)) {
            return IClass.forName(value);
        }
        if (targetType.isEnum()) {
            return convertEnum(value, targetType);
        }
        return null;
    }

    private Object convertEnum(String value, Class<?> enumType) {
        return convertEnumTyped(value, enumType.asSubclass(Enum.class));
    }

    private <E extends Enum<E>> E convertEnumTyped(String value, Class<E> enumType) {
        for (E c : enumType.getEnumConstants()) {
            if (c.name().equals(value)) {
                return c; // exact match (handles lower-case enums like BeanStrategy.singleton)
            }
        }
        for (E c : enumType.getEnumConstants()) {
            if (c.name().equalsIgnoreCase(value)) {
                return c; // case-insensitive fallback
            }
        }
        // No match — let Enum.valueOf raise a clear IllegalArgumentException
        return Enum.valueOf(enumType, value);
    }

    /**
     * Returns the boxed wrapper type for a primitive type.
     *
     * @param type the type to inspect
     * @return the corresponding wrapper class, or empty if {@code type} is not a primitive
     */
    public Optional<Class<?>> toPrimitiveWrapper(Class<?> type) {
        if (type == int.class) return Optional.of(Integer.class);
        if (type == long.class) return Optional.of(Long.class);
        if (type == double.class) return Optional.of(Double.class);
        if (type == float.class) return Optional.of(Float.class);
        if (type == boolean.class) return Optional.of(Boolean.class);
        if (type == byte.class) return Optional.of(Byte.class);
        if (type == short.class) return Optional.of(Short.class);
        if (type == char.class) return Optional.of(Character.class);
        return Optional.empty();
    }

    /**
     * Indicates whether {@link #convert(String, Class)} supports the given type.
     *
     * @param type the candidate target type
     * @return {@code true} if the type can be converted from a string
     */
    public boolean isConvertible(Class<?> type) {
        return type == String.class
                || type.isPrimitive()
                || Number.class.isAssignableFrom(type)
                || type == Boolean.class
                || type == Character.class
                || type == Duration.class
                || type == Period.class
                || type == Instant.class
                || type == LocalDate.class
                || type == LocalTime.class
                || type == LocalDateTime.class
                || type == Path.class
                || type == URI.class
                || type == URL.class
                || type == UUID.class
                || type == Class.class
                || type.isEnum();
    }
}
