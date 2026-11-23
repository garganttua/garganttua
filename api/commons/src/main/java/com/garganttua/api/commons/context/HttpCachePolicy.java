package com.garganttua.api.commons.context;

import java.time.Duration;
import java.util.Objects;

/**
 * How the HTTP transport lets clients, proxies and CDNs keep a domain's GET responses: an {@code ETag} on each, a
 * {@code 304 Not Modified} when the client's copy is current, and a {@code Cache-Control} directive. Nothing is
 * stored on the server, and nothing changes for a domain that does not declare one.
 *
 * <p>
 * <b>A response to an authenticated request is never {@code public}.</b> It is computed for its caller (its tenant,
 * its rights): a shared cache must not hand it to another. {@code public} applies to anonymous requests only, and
 * an authenticated one is always answered {@code private}.
 * </p>
 *
 * @param shared               whether an anonymous response may be kept by shared caches ({@code public})
 * @param maxAge               how long a copy is fresh; {@link Duration#ZERO} with {@code revalidate}
 * @param staleWhileRevalidate how long a stale copy may still be served while it is revalidated
 * @param revalidate           whether every use of a copy must be revalidated ({@code no-cache}: the {@code ETag}
 *                             alone saves the body, never the round trip)
 */
public record HttpCachePolicy(boolean shared, Duration maxAge, Duration staleWhileRevalidate, boolean revalidate) {

    public HttpCachePolicy {
        Objects.requireNonNull(maxAge, "Max age cannot be null");
        Objects.requireNonNull(staleWhileRevalidate, "Stale-while-revalidate cannot be null");
        if (maxAge.isNegative() || staleWhileRevalidate.isNegative()) {
            throw new IllegalArgumentException("HTTP cache durations are not negative");
        }
    }

    /** {@return responses kept by browsers, proxies and CDNs for {@code maxAge} — anonymous ones only} */
    public static HttpCachePolicy publicFor(Duration maxAge) {
        return new HttpCachePolicy(true, maxAge, Duration.ZERO, false);
    }

    /** {@return responses kept by the client only, for {@code maxAge}} */
    public static HttpCachePolicy privateFor(Duration maxAge) {
        return new HttpCachePolicy(false, maxAge, Duration.ZERO, false);
    }

    /** {@return responses revalidated at each use: a {@code 304} saves the body when nothing changed} */
    public static HttpCachePolicy revalidated() {
        return new HttpCachePolicy(false, Duration.ZERO, Duration.ZERO, true);
    }

    /** {@return this policy, serving a stale copy for {@code window} while it is revalidated} */
    public HttpCachePolicy staleWhileRevalidate(Duration window) {
        return new HttpCachePolicy(this.shared, this.maxAge, window, this.revalidate);
    }

    /**
     * The {@code Cache-Control} value for one response.
     *
     * @param authenticated whether the request carried credentials — then the response is {@code private}
     * @return the directive
     */
    public String cacheControl(boolean authenticated) {
        String visibility = this.shared && !authenticated ? "public" : "private";
        if (this.revalidate) {
            return visibility + ", no-cache";
        }
        StringBuilder directive = new StringBuilder(visibility).append(", max-age=").append(this.maxAge.toSeconds());
        if (!this.staleWhileRevalidate.isZero()) {
            directive.append(", stale-while-revalidate=").append(this.staleWhileRevalidate.toSeconds());
        }
        return directive.toString();
    }
}
