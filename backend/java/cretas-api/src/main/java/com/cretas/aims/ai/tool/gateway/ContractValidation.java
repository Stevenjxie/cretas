package com.cretas.aims.ai.tool.gateway;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

final class ContractValidation {

    private ContractValidation() {
    }

    static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }

    static <T> T requireNonNull(T value, String fieldName) {
        return Objects.requireNonNull(value, fieldName + " must not be null");
    }

    static Set<String> immutableNonBlankSet(Set<String> values, String fieldName) {
        requireNonNull(values, fieldName);
        for (String value : values) {
            requireNonBlank(value, fieldName + " entry");
        }
        return Set.copyOf(values);
    }

    static <T> Set<T> immutableNonNullSet(Set<T> values, String fieldName) {
        requireNonNull(values, fieldName);
        for (T value : values) {
            requireNonNull(value, fieldName + " entry");
        }
        return Set.copyOf(values);
    }

    static Optional<String> optionalNonBlank(Optional<String> value, String fieldName) {
        requireNonNull(value, fieldName);
        return value.map(candidate -> requireNonBlank(candidate, fieldName));
    }

    static <T> Optional<T> immutableOptional(Optional<T> value, String fieldName) {
        requireNonNull(value, fieldName);
        return value.map(candidate -> requireNonNull(candidate, fieldName + " value"));
    }
}
