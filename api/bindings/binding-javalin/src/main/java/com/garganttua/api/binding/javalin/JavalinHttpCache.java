package com.garganttua.api.binding.javalin;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Optional;

import com.garganttua.api.commons.context.HttpCachePolicy;
import com.garganttua.api.commons.context.IDomain;
import com.garganttua.core.observability.Logger;

import io.javalin.http.Context;

/**
 * The HTTP cache of a domain that declares one ({@code domain.httpCache(...)}): on a successful GET, an
 * {@code ETag} derived from the body, a {@code 304 Not Modified} without body when the client's
 * {@code If-None-Match} names it, and the declared {@code Cache-Control} — {@code private} whenever the request
 * carried credentials. Nothing is stored here: the client, a proxy or a CDN keeps the response.
 */
final class JavalinHttpCache {

    private static final Logger log = Logger.getLogger(JavalinHttpCache.class);
    private static final int OK = 200;
    private static final int NOT_MODIFIED = 304;
    private static final int ETAG_LENGTH = 22;

    private JavalinHttpCache() {
    }

    /** Applies the domain's HTTP cache to the response just written, when it declares one and the read succeeded. */
    static void apply(Context ctx, IDomain<?> domain) {
        Optional<HttpCachePolicy> policy = domain.httpCache();
        if (policy.isEmpty() || !"GET".equals(ctx.method().name()) || ctx.status().getCode() != OK) {
            return;
        }
        Optional<byte[]> written = body(ctx);
        if (written.isEmpty()) {
            return;
        }
        byte[] body = written.get();
        String etag = etag(body);
        ctx.header("ETag", etag);
        ctx.header("Cache-Control", policy.get().cacheControl(ctx.header("Authorization") != null));
        ctx.header("Vary", "Accept, Authorization");
        if (matches(ctx.header("If-None-Match"), etag)) {
            ctx.status(NOT_MODIFIED);
            ctx.result(new byte[0]);
        } else {
            ctx.result(body);
        }
    }

    /** {@return the body written so far; empty when there is none or it cannot be read back} */
    private static Optional<byte[]> body(Context ctx) {
        InputStream in = ctx.resultInputStream();
        if (in == null) {
            return Optional.empty();
        }
        try (in) {
            return Optional.of(in.readAllBytes());
        } catch (IOException e) {
            log.warn("HTTP cache: the response body could not be read back, sent without validator ({})",
                    e.getMessage());
            return Optional.empty();
        }
    }

    /** {@return a strong validator of {@code body}: the start of its SHA-256, quoted} */
    static String etag(byte[] body) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(body);
            return '"' + Base64.getUrlEncoder().withoutPadding().encodeToString(digest).substring(0, ETAG_LENGTH) + '"';
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every JVM", e);
        }
    }

    /** Whether {@code ifNoneMatch} — {@code *}, or validators separated by commas, weak or not — names {@code etag}. */
    static boolean matches(String ifNoneMatch, String etag) {
        if (ifNoneMatch == null || ifNoneMatch.isBlank()) {
            return false;
        }
        for (String candidate : ifNoneMatch.split(",")) {
            String tag = candidate.strip();
            if ("*".equals(tag) || etag.equals(tag.startsWith("W/") ? tag.substring(2) : tag)) {
                return true;
            }
        }
        return false;
    }
}
