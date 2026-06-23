package com.garganttua.core.mapper.rules;

import java.util.Optional;
import java.util.function.Function;

import com.garganttua.core.reflection.IClass;

/**
 * Provides implicit type conversions for the mapper.
 * Supports String ↔ primitives/wrappers, String ↔ enum, and Optional unwrapping.
 */
public final class ImplicitConversions {

	private static final String STRING = "java.lang.String";
	private static final String OPTIONAL = "java.util.Optional";

	private ImplicitConversions() {
	}

	/**
	 * Finds an implicit conversion function from the source type to the
	 * destination type, if one is supported.
	 *
	 * @param source the source value type
	 * @param dest the destination value type
	 * @return a conversion function (null-safe) wrapped in an {@link Optional}, or
	 *         {@link Optional#empty()} if no implicit conversion applies
	 */
	public static Optional<Function<Object, Object>> findConversion(IClass<?> source, IClass<?> dest) {
		String srcName = source.getName();
		String dstName = dest.getName();

		Optional<Function<Object, Object>> enumConversion = findEnumConversion(srcName, dstName, source, dest);
		if (enumConversion.isPresent()) {
			return enumConversion;
		}

		Optional<Function<Object, Object>> numericConversion = findNumericConversion(srcName, dstName);
		if (numericConversion.isPresent()) {
			return numericConversion;
		}

		return findOptionalConversion(srcName, dstName);
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	private static Optional<Function<Object, Object>> findEnumConversion(String srcName, String dstName,
			IClass<?> source, IClass<?> dest) {
		// String -> enum
		if (STRING.equals(srcName) && dest.isEnum()) {
			return Optional.of(val -> {
				if (val == null) {
					return null;
				}
				return Enum.valueOf((Class<Enum>) dest.getType(), (String) val);
			});
		}
		// enum -> String
		if (source.isEnum() && STRING.equals(dstName)) {
			return Optional.of(val -> val == null ? null : ((Enum<?>) val).name());
		}
		return Optional.empty();
	}

	private static Optional<Function<Object, Object>> findNumericConversion(String srcName, String dstName) {
		// String -> Integer/int
		if (STRING.equals(srcName) && ("java.lang.Integer".equals(dstName) || "int".equals(dstName))) {
			return Optional.of(val -> val == null ? null : Integer.parseInt((String) val));
		}
		// String -> Long/long
		if (STRING.equals(srcName) && ("java.lang.Long".equals(dstName) || "long".equals(dstName))) {
			return Optional.of(val -> val == null ? null : Long.parseLong((String) val));
		}
		// String -> Double/double
		if (STRING.equals(srcName) && ("java.lang.Double".equals(dstName) || "double".equals(dstName))) {
			return Optional.of(val -> val == null ? null : Double.parseDouble((String) val));
		}
		// String -> Float/float
		if (STRING.equals(srcName) && ("java.lang.Float".equals(dstName) || "float".equals(dstName))) {
			return Optional.of(val -> val == null ? null : Float.parseFloat((String) val));
		}
		// String -> Boolean/boolean
		if (STRING.equals(srcName) && ("java.lang.Boolean".equals(dstName) || "boolean".equals(dstName))) {
			return Optional.of(val -> val == null ? null : Boolean.parseBoolean((String) val));
		}
		// primitive/wrapper -> String
		if (STRING.equals(dstName) && isStringifiableNumeric(srcName)) {
			return Optional.of(val -> val == null ? null : val.toString());
		}
		return Optional.empty();
	}

	private static boolean isStringifiableNumeric(String srcName) {
		return "java.lang.Integer".equals(srcName) || "int".equals(srcName)
				|| "java.lang.Long".equals(srcName) || "long".equals(srcName)
				|| "java.lang.Double".equals(srcName) || "double".equals(srcName)
				|| "java.lang.Float".equals(srcName) || "float".equals(srcName)
				|| "java.lang.Boolean".equals(srcName) || "boolean".equals(srcName);
	}

	private static Optional<Function<Object, Object>> findOptionalConversion(String srcName, String dstName) {
		// Optional<T> -> T (unwrap)
		if (OPTIONAL.equals(srcName)) {
			return Optional.of(val -> {
				if (val == null) {
					return null;
				}
				return ((java.util.Optional<?>) val).orElse(null);
			});
		}
		// T -> Optional<T> (wrap)
		if (OPTIONAL.equals(dstName)) {
			return Optional.of(Optional::ofNullable);
		}
		return Optional.empty();
	}
}
