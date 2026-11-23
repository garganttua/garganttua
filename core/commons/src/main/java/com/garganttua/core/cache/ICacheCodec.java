package com.garganttua.core.cache;

/**
 * Turns a cached value into bytes and back, for an implementation that stores values outside the process (a
 * shared store). Implementations that keep values in memory do not use it.
 *
 * @param <V> the value type
 */
public interface ICacheCodec<V> {

    /** {@return the bytes of {@code value}} */
    byte[] encode(V value);

    /** {@return the value {@code bytes} encode} */
    V decode(byte[] bytes);
}
