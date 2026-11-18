package sternhalma.app;

/**
 * One engine the server offers.
 *
 * @param id       {@code browser}, {@code best} or {@code net}; the page prefixes it with {@code server:}
 * @param labelKey i18n key of the UI text
 * @param backend  {@code jvm}, {@code jvm-vector} or {@code tornadovm}: what computes the evaluation
 */
public record ServerEngine(String id, String labelKey, String backend) {
}
