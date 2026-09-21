package com.garganttua.dao.mongodb;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.UUID;

import org.bson.types.Decimal128;

import com.garganttua.api.commons.ApiException;

/**
 * Turns the Java values the default BSON codecs cannot store faithfully into the BSON form the
 * MongoDB DAO persists — shared by the write side ({@link MongoDocumentWriter}) and by the filter
 * side ({@link MongoFilterConverter}), so a value is compared in exactly the shape it was stored in.
 *
 * <p>
 * Why each conversion exists:
 * </p>
 * <ul>
 * <li>{@link BigDecimal} / {@link BigInteger} become a {@link Decimal128}. The default registry has
 * no {@code BigInteger} codec at all (a save used to fail), and a {@code BigDecimal} the driver cannot
 * represent exactly used to escape as a bare {@code NumberFormatException}. Decimal128 holds
 * {@value #MAX_DIGITS} significant digits: beyond, the value is refused with an {@link ApiException}
 * naming the field — the PostgreSQL DAO enforces the same limit, so both stores accept exactly the
 * same numbers and give them back unchanged (scale included).</li>
 * <li>{@link UUID} becomes its canonical string: the driver refuses to encode a UUID unless the client
 * was configured with a {@code uuidRepresentation}, so the DAO's behaviour would otherwise depend on
 * how the application built its {@code MongoClient}.</li>
 * </ul>
 */
final class MongoBsonValues {

	/** Significant digits a BSON Decimal128 holds (IEEE 754-2008 decimal128). */
	static final int MAX_DIGITS = 34;

	private MongoBsonValues() {
	}

	/**
	 * The BSON form of {@code value}: a {@link Decimal128} for a big number, a string for a UUID, the
	 * value itself otherwise.
	 *
	 * @param value the Java value (may be null)
	 * @param field the field it belongs to, for the error message
	 * @return the value to hand to the driver
	 * @throws ApiException when a big number exceeds what Decimal128 can hold
	 */
	static Object toBson(Object value, String field) throws ApiException {
		if (value instanceof BigDecimal decimal) {
			return decimal128(decimal, field);
		}
		if (value instanceof BigInteger integer) {
			return decimal128(new BigDecimal(integer), field);
		}
		if (value instanceof UUID uuid) {
			return uuid.toString();
		}
		return value;
	}

	private static Decimal128 decimal128(BigDecimal decimal, String field) throws ApiException {
		if (decimal.precision() > MAX_DIGITS) {
			throw new ApiException("Field '" + field + "' holds a number of " + decimal.precision()
					+ " significant digits: MongoDB's Decimal128 keeps at most " + MAX_DIGITS
					+ " significant digits, so it cannot be stored without losing precision");
		}
		try {
			return new Decimal128(decimal);
		} catch (NumberFormatException e) {
			throw new ApiException("Field '" + field + "' holds the number " + decimal
					+ ", whose exponent is beyond what MongoDB's Decimal128 (" + MAX_DIGITS
					+ " significant digits) can represent", e);
		}
	}
}
