package net.hasht.app;

/** Notifications without server metadata cannot participate in read reconciliation. */
final class LegacyNotificationMetadata {
    private LegacyNotificationMetadata() {}

    static boolean shouldRetire(String scope, boolean hasCreated, boolean hasTag) {
        return scope != null && !scope.isEmpty() && (!hasCreated || !hasTag);
    }
}
