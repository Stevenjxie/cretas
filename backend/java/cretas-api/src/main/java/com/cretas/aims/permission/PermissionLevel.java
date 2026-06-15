package com.cretas.aims.permission;

import java.util.Locale;

public enum PermissionLevel {

    HIDDEN("-", "hidden", false, false),
    READ("r", "read", true, false),
    WRITE("rw", "write", true, true);

    private final String legacyCode;
    private final String apiCode;
    private final boolean canRead;
    private final boolean canWrite;

    PermissionLevel(String legacyCode, String apiCode, boolean canRead, boolean canWrite) {
        this.legacyCode = legacyCode;
        this.apiCode = apiCode;
        this.canRead = canRead;
        this.canWrite = canWrite;
    }

    public String legacyCode() {
        return legacyCode;
    }

    public String apiCode() {
        return apiCode;
    }

    public boolean canRead() {
        return canRead;
    }

    public boolean canWrite() {
        return canWrite;
    }

    public static PermissionLevel fromAny(String value) {
        if (value == null) {
            return HIDDEN;
        }

        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty() || "null".equals(normalized)) {
            return HIDDEN;
        }

        return switch (normalized) {
            case "-", "hidden", "deny", "none" -> HIDDEN;
            case "r", "read", "readonly", "read_only" -> READ;
            case "rw", "w", "write", "editable", "grant", "read_write", "read-write" -> WRITE;
            default -> throw new IllegalArgumentException("Unknown permission level: " + value);
        };
    }
}
