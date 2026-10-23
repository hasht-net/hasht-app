package net.hasht.app;

import static org.junit.Assert.*;
import org.junit.Test;

public class LegacyNotificationMetadataTest {
    @Test public void retiresOldMessagesAndBlankControlsAcrossAccountFormats() {
        assertTrue(LegacyNotificationMetadata.shouldRetire("https://chat.test", false, false));
        assertTrue(LegacyNotificationMetadata.shouldRetire("account-key", false, false));
        assertTrue(LegacyNotificationMetadata.shouldRetire("account-key", true, false));
        assertTrue(LegacyNotificationMetadata.shouldRetire("account-key", false, true));
    }

    @Test public void preservesCurrentMessagesAndRecoveryNotifications() {
        assertFalse(LegacyNotificationMetadata.shouldRetire("account-key", true, true));
    }

    @Test public void leavesOtherAppNotificationsAlone() {
        assertFalse(LegacyNotificationMetadata.shouldRetire(null, false, false));
        assertFalse(LegacyNotificationMetadata.shouldRetire("", false, false));
    }
}
