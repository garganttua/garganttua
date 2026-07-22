package com.garganttua.core.script.context;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Optional;

import com.garganttua.core.script.ScriptException;

/**
 * Resolves the source text and the registered name of an includable {@code .gs} script, from the
 * filesystem or from the classpath (a {@code classpath:} prefixed path).
 *
 * <p>Extracted so the {@code include()} script function and the build-time warm-up
 * ({@code IScriptingEnvironment.warmUpInclude}) resolve scripts through exactly the same rules —
 * a warm-up that resolved differently would silently miss the cache it is meant to fill.
 *
 * <p>Lookups return {@link Optional#empty()} rather than throwing when the file or resource does
 * not exist, so each caller can phrase its own "not found" error (or, for the warm-up, degrade to
 * a warning and let the runtime {@code include()} fail as before).
 *
 * @since 3.0.0-ALPHA11
 */
public final class ScriptSourceResolver {

    /** Prefix marking a path as a classpath resource rather than a filesystem path. */
    public static final String CLASSPATH_PREFIX = "classpath:";

    private static final String SCRIPT_SUFFIX = "\\.gs$";

    private ScriptSourceResolver() {
    }

    /** {@return whether {@code path} designates a classpath resource} */
    public static boolean isClasspath(String path) {
        return path != null && path.startsWith(CLASSPATH_PREFIX);
    }

    /** {@return {@code path} with the {@code classpath:} prefix removed when present} */
    public static String stripClasspathPrefix(String path) {
        return isClasspath(path) ? path.substring(CLASSPATH_PREFIX.length()) : path;
    }

    /**
     * {@return the name an included script is registered under} — the last path segment without
     * its {@code .gs} suffix, e.g. {@code classpath:scripts/business/READ_ONE.gs} → {@code READ_ONE}.
     */
    public static String scriptName(String path) {
        String resolved = stripClasspathPrefix(path);
        String name = resolved.contains("/")
                ? resolved.substring(resolved.lastIndexOf('/') + 1)
                : resolved;
        return name.replaceFirst(SCRIPT_SUFFIX, "");
    }

    /**
     * Reads a script file.
     *
     * @param file the script file
     * @return its source text
     * @throws ScriptException if the file cannot be read
     */
    public static String readFile(File file) throws ScriptException {
        try {
            return Files.readString(file.toPath());
        } catch (IOException e) {
            throw new ScriptException("Failed to read script file: " + file, e);
        }
    }

    /**
     * Reads a classpath resource, trying the thread-context class loader first and falling back to
     * this class's own loader.
     *
     * @param resource the resource path, without the {@code classpath:} prefix
     * @return its source text, or empty when no such resource exists
     * @throws ScriptException if the resource exists but cannot be read
     */
    // Deliberate fallback to this class's loader after the context loader misses
    @SuppressWarnings("PMD.UseProperClassLoader")
    public static Optional<String> readClasspathResource(String resource) throws ScriptException {
        InputStream fromContext = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource);
        final InputStream is = fromContext != null
                ? fromContext
                : ScriptSourceResolver.class.getClassLoader().getResourceAsStream(resource);
        if (is == null) {
            return Optional.empty();
        }
        try (is) {
            return Optional.of(new String(is.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new ScriptException("Failed to read classpath script: " + resource, e);
        }
    }

    /**
     * Reads an includable script from wherever {@code path} points — classpath or filesystem.
     *
     * @param path the {@code include()} path, with or without the {@code classpath:} prefix
     * @return its source text, or empty when the resource or file does not exist
     * @throws ScriptException if it exists but cannot be read
     */
    public static Optional<String> readSource(String path) throws ScriptException {
        if (isClasspath(path)) {
            return readClasspathResource(stripClasspathPrefix(path));
        }
        File file = new File(path);
        return file.exists() ? Optional.of(readFile(file)) : Optional.empty();
    }
}
