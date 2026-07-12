package com.cretas.aims.integration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DisposablePostgresTargetGuardTest {

    @Test
    void acceptsOnlyExplicitLocalhostTargetsWithDisposableDatabasePrefix() {
        assertDoesNotThrow(() -> DisposablePostgresTargetGuard.requireSafeUrl(
                "jdbc:postgresql://127.0.0.1:55432/cretas_workflow_verify_20260711"));
        assertDoesNotThrow(() -> DisposablePostgresTargetGuard.requireSafeUrl(
                "jdbc:postgresql://localhost/cretas_workflow_verify_local"));
    }

    @Test
    void rejectsRemoteHostsAndNonPostgresSchemes() {
        assertRejected("jdbc:postgresql://db.example.com:5432/cretas_workflow_verify_remote");
        assertRejected("jdbc:postgresql://10.0.0.8:5432/cretas_workflow_verify_remote");
        assertRejected("jdbc:postgresql://[::1]:5432/cretas_workflow_verify_ipv6");
        assertRejected("jdbc:mysql://127.0.0.1:3306/cretas_workflow_verify_wrong_driver");
    }

    @Test
    void rejectsDatabaseNamesOutsideDisposablePrefix() {
        assertRejected("jdbc:postgresql://127.0.0.1:5432/postgres");
        assertRejected("jdbc:postgresql://localhost/cretas_prod_db");
        assertRejected("jdbc:postgresql://localhost/cretas_workflow_verify_");
    }

    @Test
    void rejectsPathQueryFragmentAndAuthorityBypasses() {
        assertRejected("jdbc:postgresql://localhost/cretas_workflow_verify_safe/../cretas_prod_db");
        assertRejected("jdbc:postgresql://localhost/cretas_workflow_verify_safe/extra");
        assertRejected("jdbc:postgresql://localhost/cretas_workflow_verify_safe%2Fextra");
        assertRejected("jdbc:postgresql://localhost/postgres?databaseName=cretas_workflow_verify_safe");
        assertRejected("jdbc:postgresql://localhost/cretas_workflow_verify_safe?options=-csearch_path%3Dpublic");
        assertRejected("jdbc:postgresql://localhost/cretas_workflow_verify_safe#ignored");
        assertRejected("jdbc:postgresql://localhost@db.example.com/cretas_workflow_verify_safe");
    }

    @Test
    void rejectsMissingMalformedOrWhitespacePaddedUrls() {
        assertRejected(null);
        assertRejected("");
        assertRejected(" jdbc:postgresql://localhost/cretas_workflow_verify_safe");
        assertRejected("jdbc:postgresql://localhost/cretas_workflow_verify_safe ");
        assertRejected("jdbc:postgresql://localhost");
    }

    private void assertRejected(String url) {
        assertThrows(IllegalArgumentException.class,
                () -> DisposablePostgresTargetGuard.requireSafeUrl(url));
    }
}
