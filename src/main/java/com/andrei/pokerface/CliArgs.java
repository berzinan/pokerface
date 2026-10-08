package com.andrei.pokerface;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Minimal --flag value parser shared by the Benchmark and Play entry points.
 *
 * Exists because both mains want the same thing -- typed lookups with
 * defaults, and a loud failure on a misspelled flag. Writing that twice is how
 * the six near-identical Run* classes this project used to have got started.
 *
 * Unknown flags are rejected rather than ignored. A silently-dropped
 * "--hand 500" instead of "--hands 500" would run the default hand count and
 * report a perfectly plausible wrong answer.
 */
final class CliArgs {

    private final Map<String, String> values = new HashMap<>();
    private final Set<String> present = new HashSet<>();

    private CliArgs() {}

    /**
     * @param args      raw argv
     * @param valueKeys flags that take a value, e.g. "--hands"
     * @param boolKeys  flags that stand alone, e.g. "--help"
     * @throws IllegalArgumentException on an unknown flag, a missing value, or
     *                                  a bare argument with no flag
     */
    static CliArgs parse(String[] args, Set<String> valueKeys, Set<String> boolKeys) {
        CliArgs parsed = new CliArgs();
        for (int i = 0; i < args.length; i++) {
            String flag = args[i];
            if (boolKeys.contains(flag)) {
                parsed.present.add(flag);
                continue;
            }
            if (!valueKeys.contains(flag)) {
                throw new IllegalArgumentException("unknown option " + flag);
            }
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException(flag + " requires a value");
            }
            parsed.values.put(flag, args[++i]);
            parsed.present.add(flag);
        }
        return parsed;
    }

    boolean has(String flag) {
        return present.contains(flag);
    }

    String str(String key, String fallback) {
        return values.getOrDefault(key, fallback);
    }

    /** @throws IllegalArgumentException if present but not a positive integer */
    int positiveInt(String key, int fallback) {
        int parsed = integer(key, fallback);
        if (parsed <= 0) {
            throw new IllegalArgumentException(key + " must be positive (got " + parsed + ")");
        }
        return parsed;
    }

    int integer(String key, int fallback) {
        String raw = values.get(key);
        if (raw == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " expects an integer (got '" + raw + "')");
        }
    }

    long longValue(String key, long fallback) {
        String raw = values.get(key);
        if (raw == null) {
            return fallback;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " expects an integer (got '" + raw + "')");
        }
    }
}