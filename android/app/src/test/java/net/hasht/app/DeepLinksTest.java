package net.hasht.app;

import static org.junit.Assert.*;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class DeepLinksTest {
    private static final String HOSTED = "https://chat.hasht.net";
    private static final String MESSAGE = "/channels/0b6f3c1e-8a2d-4c7e-9f10-2a3b4c5d6e7f/9f102a3b-4c5d-6e7f-0b6f-3c1e8a2d4c7e";

    private static DeepLinks.Entry entry(String id, String url) {
        return new DeepLinks.Entry(id, url);
    }

    @Test public void httpsLinksKeepAllowlistedPathsOnly() {
        assertEquals(HOSTED + "/invite/abc123", DeepLinks.target(HOSTED + "/invite/abc123").url);
        assertEquals(HOSTED + MESSAGE, DeepLinks.target(HOSTED + MESSAGE + "?x=1#y").url);
        assertEquals(HOSTED, DeepLinks.target(HOSTED + "/invite/abc123").origin);
        for (String path : new String[] {"/", "/settings", "/invite/a-b", "/invite/abc/extra", "/channels/abc", "/?native_auth=x"}) {
            assertEquals(path, HOSTED + "/", DeepLinks.target(HOSTED + path).url);
        }
    }

    @Test public void queryAndFragmentNeverSurvive() {
        assertEquals(HOSTED + "/invite/abc", DeepLinks.target(HOSTED + "/invite/abc?native_auth=1&token=2").url);
    }

    @Test public void hashtOpenCarriesAnEncodedHttpsUrl() {
        DeepLinks.Target target = DeepLinks.target("hasht://open?url=https%3A%2F%2Fchat.example.com%3A8443%2Finvite%2FABC");
        assertEquals("https://chat.example.com:8443", target.origin);
        assertEquals("https://chat.example.com:8443/invite/ABC", target.url);
    }

    @Test public void rejectsNonHttpsAndOddLinks() {
        String[] bad = {
            "http://chat.hasht.net/invite/abc",
            "javascript:alert(1)",
            "hasht://open?url=http%3A%2F%2Fchat.hasht.net%2Finvite%2Fabc",
            "hasht://open?url=javascript%3Aalert(1)",
            "hasht://open",
            "hasht://auth?request_id=1&code=2",
            "hasht://signin?server=https%3A%2F%2Fchat.hasht.net",
            "https://user:pass@chat.hasht.net/invite/abc",
            "https:///invite/abc",
            "not a url",
            "",
        };
        for (String raw : bad) assertNull(raw, DeepLinks.target(raw));
        assertNull(DeepLinks.target(null));
    }

    @Test public void originsCompareCaseInsensitivelyWithPorts() {
        assertEquals("https://chat.hasht.net", DeepLinks.target("HTTPS://Chat.Hasht.NET/invite/abc").origin);
    }

    @Test public void noEntryAddsHostedSilentlyButConfirmsOthers() {
        List<DeepLinks.Entry> none = Collections.emptyList();
        assertEquals(DeepLinks.Decision.Kind.ADD_HOSTED, DeepLinks.decide(DeepLinks.target(HOSTED + "/invite/a"), none, null).kind);
        assertEquals(DeepLinks.Decision.Kind.CONFIRM_ADD,
            DeepLinks.decide(DeepLinks.target("https://chat.example.com/invite/a"), none, null).kind);
        // A self-hosted entry doesn't count for a hosted link.
        List<DeepLinks.Entry> selfHosted = Collections.singletonList(entry("s", "https://chat.example.com"));
        assertEquals(DeepLinks.Decision.Kind.ADD_HOSTED, DeepLinks.decide(DeepLinks.target(HOSTED + "/invite/a"), selfHosted, "s").kind);
    }

    @Test public void oneMatchingEntryOpensThere() {
        List<DeepLinks.Entry> entries = Arrays.asList(entry("s", "https://chat.example.com"), entry("h", "https://chat.hasht.net/"));
        DeepLinks.Decision decision = DeepLinks.decide(DeepLinks.target(HOSTED + "/invite/a"), entries, null);
        assertEquals(DeepLinks.Decision.Kind.OPEN, decision.kind);
        assertEquals("h", decision.id);
    }

    @Test public void severalMatchesUseTheDefaultOrAsk() {
        List<DeepLinks.Entry> entries = Arrays.asList(
            entry("a", HOSTED), entry("s", "https://chat.example.com"), entry("b", HOSTED + "/"));
        DeepLinks.Target target = DeepLinks.target(HOSTED + "/invite/a");

        DeepLinks.Decision ask = DeepLinks.decide(target, entries, null);
        assertEquals(DeepLinks.Decision.Kind.CHOOSE, ask.kind);
        assertEquals(Arrays.asList("a", "b"), ask.ids);

        DeepLinks.Decision withDefault = DeepLinks.decide(target, entries, "b");
        assertEquals(DeepLinks.Decision.Kind.OPEN, withDefault.kind);
        assertEquals("b", withDefault.id);

        // A default on another origin doesn't settle it.
        assertEquals(DeepLinks.Decision.Kind.CHOOSE, DeepLinks.decide(target, entries, "s").kind);
    }

    @Test public void routedPathsMatchTheWebviewInterceptRule() {
        assertTrue(DeepLinks.isRoutedPath("/invite/abc"));
        assertTrue(DeepLinks.isRoutedPath(MESSAGE));
        assertFalse(DeepLinks.isRoutedPath("/"));
        assertFalse(DeepLinks.isRoutedPath(null));
    }
}
