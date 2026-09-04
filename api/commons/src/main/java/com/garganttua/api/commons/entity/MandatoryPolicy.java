package com.garganttua.api.commons.entity;

/**
 * What a {@code mandatory} field is required to carry.
 *
 * <p>
 * The distinction exists because {@code mandatory} used to mean exactly one thing — "not
 * {@code null}" — while its name promises another, and the gap did not show: the constraint refused
 * the most obvious creation, which gave every confidence in the cases where it let a value through.
 * A screen guard written {@code if (!x)} refuses the empty string; a consumer replacing those
 * guards with {@code mandatory} therefore <em>weakened</em> them, and nothing said so.
 * </p>
 *
 * <p>
 * The default stays {@link #anyValue}, so no existing declaration changes meaning.
 * </p>
 */
public enum MandatoryPolicy {

    /**
     * The field must merely be set: {@code null} is refused, an empty or blank string is accepted.
     * The historical behaviour of {@code mandatory}, and the default.
     */
    anyValue,

    /**
     * The field must carry an actual value: {@code null}, the empty string and a string of
     * whitespace are all refused.
     *
     * <p>
     * At CREATION the rule applies to the field itself. At UPDATE it applies only to a value the
     * client actually <strong>sent</strong> — because since {@code PATCH} became the update verb the
     * body is partial, and replaying the creation rule would refuse nearly every request a screen
     * emits. The three cases a partial body can present:
     * </p>
     *
     * <table border="1">
     *   <caption>Update semantics</caption>
     *   <tr><th>What the body carries</th><th>Decision</th></tr>
     *   <tr><td>the field is absent</td><td>accepted — "we are not touching it"</td></tr>
     *   <tr><td>the field is {@code null}</td><td>accepted — this is already the {@code ignoreNull} semantics</td></tr>
     *   <tr><td>the field is {@code ""} or {@code "   "}</td><td><strong>refused</strong> — this is an explicit erasure</td></tr>
     * </table>
     */
    nonBlank
}
