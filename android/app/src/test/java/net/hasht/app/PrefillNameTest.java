package net.hasht.app;

import static org.junit.Assert.*;
import org.junit.Test;

public class PrefillNameTest {
    @Test public void keepsTrimmedNames() {
        assertEquals("ralph", NativeAuthPlugin.prefillName("  ralph "));
        assertEquals("Ralph L", NativeAuthPlugin.prefillName("Ralph L"));
    }

    @Test public void dropsEmptyLongAndControlCharacters() {
        assertNull(NativeAuthPlugin.prefillName(null));
        assertNull(NativeAuthPlugin.prefillName("   "));
        assertNull(NativeAuthPlugin.prefillName("x".repeat(256)));
        assertNull(NativeAuthPlugin.prefillName("a\u0007b"));
        assertNull(NativeAuthPlugin.prefillName("a\nb"));
    }
}
