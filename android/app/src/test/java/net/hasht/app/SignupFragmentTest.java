package net.hasht.app;

import static org.junit.Assert.*;
import org.junit.Test;

public class SignupFragmentTest {
    @Test public void keepsValidDetails() {
        assertEquals("a+b@example.com", NativeAuthPlugin.prefillEmail(" a+b@example.com "));
        assertEquals("1990-05-04", NativeAuthPlugin.prefillBirthday("1990-05-04"));
        assertEquals("2000-02-29", NativeAuthPlugin.prefillBirthday("2000-02-29"));
        assertEquals("month_day", NativeAuthPlugin.prefillVisibility("month_day"));
    }

    @Test public void dropsInvalidDetails() {
        assertNull(NativeAuthPlugin.prefillEmail(null));
        assertNull(NativeAuthPlugin.prefillEmail("no-at"));
        assertNull(NativeAuthPlugin.prefillEmail("a b@example.com"));
        assertNull(NativeAuthPlugin.prefillEmail("a\u0007@x.co"));
        assertNull(NativeAuthPlugin.prefillEmail("x".repeat(250) + "@e.co"));
        assertNull(NativeAuthPlugin.prefillBirthday("1990-02-30"));
        assertNull(NativeAuthPlugin.prefillBirthday("1990-13-01"));
        assertNull(NativeAuthPlugin.prefillBirthday("May 4"));
        assertNull(NativeAuthPlugin.prefillVisibility("everyone"));
    }

    @Test public void encodesLikeUrlSearchParams() {
        assertNull(NativeAuthPlugin.signupFragment(null, null, null));
        assertEquals(
            "email=a%2Bb%40example.com&birthday=1990-05-04&birthday_visibility=full",
            NativeAuthPlugin.signupFragment("a+b@example.com", "1990-05-04", "full"));
    }
}
