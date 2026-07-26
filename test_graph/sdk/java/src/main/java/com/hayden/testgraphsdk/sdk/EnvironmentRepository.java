package com.hayden.testgraphsdk.sdk;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Provider-neutral Git environment repository contract metadata.
 *
 * This class only declares and validates the NodeSpec JSON contract. Git clone,
 * OpenTofu execution, reset, destroy, and env propagation are runtime behavior
 * layered onto this metadata by later SDK tickets.
 */
public final class EnvironmentRepository {
    private static final Set<String> REQUIRED_OUTPUT_KEYS =
            new LinkedHashSet<>(Arrays.asList("EnvironmentId", "KUBECONFIG", "KUBECONTEXT"));
    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]*");
    private static final Pattern ENV_KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern ARCHIVE = Pattern.compile(".*\\.(tar|tar\\.gz|tgz|zip)$");

    private final String source;
    private final String template;
    private final String target;
    private final String backend;
    private final String branch;
    private final Set<String> outputKeys;

    private EnvironmentRepository(
            String source,
            String template,
            String target,
            String backend,
            String branch,
            Set<String> outputKeys
    ) {
        this.source = requireNonBlank(source, "environmentRepository.source");
        this.template = validateTemplate(template);
        this.target = validateName(target, "environmentRepository.target");
        this.backend = validateName(backend, "environmentRepository.backend");
        this.branch = requireNonBlank(branch, "environmentRepository.branch");
        this.outputKeys = validateOutputKeys(outputKeys);
        if (ARCHIVE.matcher(source.toLowerCase()).matches()) {
            throw new IllegalArgumentException("environmentRepository.source must be an ordinary Git URL/path, not an archive or tarball");
        }
    }

    public static EnvironmentRepository of(String source, String template) {
        return new EnvironmentRepository(source, template, "local-preview", "local", "feature", REQUIRED_OUTPUT_KEYS);
    }

    public static Builder builder(String source, String template) {
        return new Builder(source, template);
    }

    Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", source);
        out.put("template", template);
        out.put("target", target);
        out.put("backend", backend);
        out.put("branch", branch);
        out.put("outputKeys", outputKeys);
        return out;
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static String validateTemplate(String value) {
        String template = requireNonBlank(value, "environmentRepository.template");
        String[] segments = template.split("/");
        for (String segment : segments) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("environmentRepository.template must be a relative repository path without '.', '..', or empty segments");
            }
        }
        if (template.startsWith("/") || template.contains("\\")) {
            throw new IllegalArgumentException("environmentRepository.template must use a relative '/'-separated repository path");
        }
        return template;
    }

    private static String validateName(String value, String field) {
        String name = requireNonBlank(value, field);
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(field + " must use lowercase words and '-' separators");
        }
        return name;
    }

    private static Set<String> validateOutputKeys(Set<String> keys) {
        Set<String> values = new LinkedHashSet<>(keys == null || keys.isEmpty() ? REQUIRED_OUTPUT_KEYS : keys);
        if (!values.containsAll(REQUIRED_OUTPUT_KEYS)) {
            throw new IllegalArgumentException("environmentRepository.outputKeys must include " + REQUIRED_OUTPUT_KEYS);
        }
        for (String key : values) {
            if (key == null || !ENV_KEY.matcher(key).matches()) {
                throw new IllegalArgumentException("environmentRepository.outputKeys contains invalid key '" + key + "'");
            }
        }
        return values;
    }

    public static final class Builder {
        private final String source;
        private final String template;
        private String target = "local-preview";
        private String backend = "local";
        private String branch = "feature";
        private Set<String> outputKeys = REQUIRED_OUTPUT_KEYS;

        private Builder(String source, String template) {
            this.source = source;
            this.template = template;
        }

        public Builder target(String value) { this.target = value; return this; }
        public Builder backend(String value) { this.backend = value; return this; }
        public Builder branch(String value) { this.branch = value; return this; }
        public Builder outputKeys(String... keys) {
            this.outputKeys = new LinkedHashSet<>(Arrays.asList(keys));
            return this;
        }

        public EnvironmentRepository build() {
            return new EnvironmentRepository(source, template, target, backend, branch, outputKeys);
        }
    }
}
