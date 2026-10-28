package net.hasht.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class PushImageTest {
    @Test public void imageIsOnlyFetchedFromTheSendingInstance() {
        assertEquals("https://a.test/api/attachments/1?exp=2&sig=3",
            PushNotifications.imageUrl("https://a.test", "/api/attachments/1?exp=2&sig=3"));
        assertNull(PushNotifications.imageUrl("https://a.test", ""));
        assertNull(PushNotifications.imageUrl("https://a.test", "https://evil.test/x.png"));
        assertNull(PushNotifications.imageUrl("https://a.test", "//evil.test/x.png"));
    }
}
