package com.garganttua.dao.mongodb;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.UUID;

import org.bson.BsonBinarySubType;
import org.bson.Document;
import org.bson.types.Binary;
import org.bson.types.Decimal128;

import com.garganttua.api.commons.ApiException;

/**
 * Adapts one scalar decoded from BSON to the Java type a DTO field declares. MongoDB's Document codec
 * is lossy across the JVM type system: an enum comes back as a String, every {@code java.time} value
 * as a {@link Date}, a {@code char} as a String, a {@code BigDecimal} as a {@link Decimal128}, a
 * 32-bit field as an Integer, a byte array as a {@link Binary}. Without this step those values could
 * be written but never read back.
 *
 * <p>
 * Split out of {@link MongoDocumentReader} (which keeps the structural walk: documents, lists, maps)
 * so each class stays small; the conversions are pure functions of (value, declared type). Anything
 * not recognised is returned untouched, and {@code field.set} then accepts or rejects it.
 * </p>
 *
 * <p>
 * <b>PMD note:</b> {@code java.util.Date} is how the driver decodes a BSON datetime, hence the
 * {@code ReplaceJavaUtilDate} suppression.
 * </p>
 */
@SuppressWarnings({ "PMD.ReplaceJavaUtilDate" })
final class MongoValueCoercer {

	private MongoValueCoercer() {
	}

	/**
	 * {@code value} as an instance of {@code target} when a lossless conversion exists.
	 *
	 * @param value  the decoded BSON value
	 * @param target the declared type, or null when unknown (the value is then only unwrapped)
	 * @return the adapted value
	 * @throws ApiException when a stored number does not fit the declared integral type
	 */
	@SuppressWarnings({ "unchecked", "rawtypes" })
	static Object coerce(Object value, Class<?> target) throws ApiException {
		if (IKeyBsonBridge.isKeyDocument(value)) {
			return IKeyBsonBridge.fromDocument((Document) value);
		}
		Object unwrapped = value instanceof Decimal128 d ? fromDecimal128(d) : value;
		if (unwrapped == null || target == null || target.isInstance(unwrapped)) {
			return unwrapped;
		}
		if (unwrapped instanceof Binary binary) {
			return fromBinary(binary, target);
		}
		if (target.isEnum() && unwrapped instanceof String name) {
			return Enum.valueOf((Class<? extends Enum>) target, name);
		}
		if (unwrapped instanceof Date date) {
			return fromDate(date, target);
		}
		if (unwrapped instanceof Number number) {
			return fromNumber(number, target);
		}
		if (unwrapped instanceof String text) {
			return fromString(text, target);
		}
		return unwrapped;
	}

	/** A finite Decimal128 as the BigDecimal it was written from; NaN / infinities stay as they are. */
	private static Object fromDecimal128(Decimal128 decimal) {
		if (decimal.isNaN() || decimal.isInfinite()) {
			return decimal;
		}
		try {
			return decimal.bigDecimalValue();
		} catch (ArithmeticException negativeZero) {
			// BigDecimal has no -0: the value it stands for is zero.
			return BigDecimal.ZERO;
		}
	}

	/** BSON binary → {@code byte[]}, or a {@link UUID} written by a client that stored UUIDs as binary. */
	private static Object fromBinary(Binary binary, Class<?> target) {
		if (target == byte[].class) {
			return binary.getData();
		}
		if (target == UUID.class && binary.getData().length == 16) {
			return uuidOf(binary);
		}
		return binary;
	}

	/** Subtype 4 is the standard (big-endian) layout; subtype 3 is the legacy Java driver's, each half reversed. */
	private static Object uuidOf(Binary binary) {
		byte[] bytes = binary.getData().clone();
		if (binary.getType() == BsonBinarySubType.UUID_LEGACY.getValue()) {
			reverse(bytes, 0);
			reverse(bytes, 8);
		} else if (binary.getType() != BsonBinarySubType.UUID_STANDARD.getValue()) {
			return binary;
		}
		ByteBuffer buffer = ByteBuffer.wrap(bytes);
		return new UUID(buffer.getLong(), buffer.getLong());
	}

	private static void reverse(byte[] bytes, int from) {
		for (int i = 0; i < 4; i++) {
			byte swap = bytes[from + i];
			bytes[from + i] = bytes[from + 7 - i];
			bytes[from + 7 - i] = swap;
		}
	}

	/** A BSON datetime → the declared {@code java.time} type, at UTC (how the driver's JSR-310 codecs wrote it). */
	static Object fromDate(Date date, Class<?> target) {
		Instant instant = date.toInstant();
		if (target == Instant.class) {
			return instant;
		}
		if (target == LocalDateTime.class) {
			return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
		}
		if (target == LocalDate.class) {
			return LocalDate.ofInstant(instant, ZoneOffset.UTC);
		}
		if (target == LocalTime.class) {
			return LocalTime.ofInstant(instant, ZoneOffset.UTC);
		}
		if (target == ZonedDateTime.class) {
			return instant.atZone(ZoneOffset.UTC);
		}
		if (target == OffsetDateTime.class) {
			return instant.atOffset(ZoneOffset.UTC);
		}
		return date;
	}

	/** Widens/narrows a stored number to the declared numeric type (primitives included). */
	static Object fromNumber(Number number, Class<?> target) throws ApiException {
		if (target == BigDecimal.class) {
			return number instanceof Double || number instanceof Float
					? BigDecimal.valueOf(number.doubleValue())
					: new BigDecimal(number.toString());
		}
		if (target == BigInteger.class) {
			return bigInteger(number);
		}
		return fromNumberToPrimitive(number, target);
	}

	private static Object fromNumberToPrimitive(Number number, Class<?> target) {
		if (target == Long.class || target == long.class) {
			return number.longValue();
		}
		if (target == Integer.class || target == int.class) {
			return number.intValue();
		}
		if (target == Double.class || target == double.class) {
			return number.doubleValue();
		}
		if (target == Float.class || target == float.class) {
			return number.floatValue();
		}
		if (target == Short.class || target == short.class) {
			return number.shortValue();
		}
		if (target == Byte.class || target == byte.class) {
			return number.byteValue();
		}
		return number;
	}

	private static BigInteger bigInteger(Number number) throws ApiException {
		try {
			return number instanceof BigDecimal decimal
					? decimal.toBigIntegerExact()
					: new BigDecimal(number.toString()).toBigIntegerExact();
		} catch (ArithmeticException | NumberFormatException e) {
			throw new ApiException("Stored number " + number + " is not an integer and cannot be read as a BigInteger", e);
		}
	}

	/** Parses a stored string into the declared scalar type: chars, UUIDs, map keys, configs stored as text. */
	static Object fromString(String text, Class<?> target) {
		if ((target == Character.class || target == char.class) && text.length() == 1) {
			return text.charAt(0);
		}
		if (target == UUID.class) {
			return UUID.fromString(text);
		}
		if (target == BigDecimal.class) {
			return new BigDecimal(text);
		}
		if (target == BigInteger.class) {
			return new BigInteger(text);
		}
		return fromStringToPrimitive(text, target);
	}

	private static Object fromStringToPrimitive(String text, Class<?> target) {
		if (target == Integer.class || target == int.class) {
			return Integer.valueOf(text);
		}
		if (target == Long.class || target == long.class) {
			return Long.valueOf(text);
		}
		if (target == Double.class || target == double.class) {
			return Double.valueOf(text);
		}
		if (target == Float.class || target == float.class) {
			return Float.valueOf(text);
		}
		if (target == Short.class || target == short.class) {
			return Short.valueOf(text);
		}
		if (target == Byte.class || target == byte.class) {
			return Byte.valueOf(text);
		}
		if (target == Boolean.class || target == boolean.class) {
			return Boolean.valueOf(text);
		}
		return text;
	}
}
