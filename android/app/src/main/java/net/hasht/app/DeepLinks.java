package net.hasht.app;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Which saved server a link opens in. Pure (java.net.URI, no Android types) so
 * the rules are unit-testable; ServerConfigPlugin does the I/O and dialogs.
 * The Swift twin is DeepLinks.swift; keep the two in step.
 *
 * Every link is untrusted: it only ever becomes an https URL on an allowlisted
 * path, never anything the page would run.
 */
final class DeepLinks {
    /** The hosted instance: a link to it may add it without asking. */
    static final String HOSTED_ORIGIN = "https://chat.hasht.net";

    private static final Pattern INVITE = Pattern.compile("^/invite/[A-Za-z0-9]{1,32}/?$");
    private static final Pattern SERVER = Pattern.compile("^/servers/[0-9a-fA-F-]{1,64}$");
    private static final Pattern MESSAGE = Pattern.compile("^/channels/[0-9a-fA-F-]{1,64}/[0-9a-fA-F-]{1,64}$");

    private DeepLinks() {}

    /** Where a link points: an origin, and the full URL to load there. */
    static final class Target {
        final String origin;
        final String url;

        Target(String origin, String url) {
            this.origin = origin;
            this.url = url;
        }
    }

    /**
     * An https link (App Link, banner) or hasht://open?url=…, reduced to its
     * origin plus an allowlisted path. Any other path opens the instance root.
     * Null for anything else.
     */
    static Target target(String raw) {
        URI link = parse(raw);
        if (link == null) return null;
        if ("hasht".equalsIgnoreCase(link.getScheme())) {
            if (!"open".equalsIgnoreCase(link.getHost())) return null;
            link = parse(queryParam(link.getRawQuery(), "url"));
            if (link == null) return null;
        }
        if (!"https".equalsIgnoreCase(link.getScheme()) || link.getHost() == null || link.getHost().isEmpty()) {
            return null;
        }
        if (link.getRawUserInfo() != null) return null;
        String origin = originKey(link);
        String path = link.getRawPath() == null ? "" : link.getRawPath();
        boolean routed = INVITE.matcher(path).matches() || SERVER.matcher(path).matches() || MESSAGE.matcher(path).matches();
        return new Target(origin, origin + (routed ? path : "/"));
    }

    /** Whether a link tapped inside the webview should go through the router. */
    static boolean isRoutedPath(String path) {
        return path != null && (INVITE.matcher(path).matches() || SERVER.matcher(path).matches() || MESSAGE.matcher(path).matches());
    }

    static String continuationPath(String path) {
        if ("/".equals(path)) return path;
        if (!isRoutedPath(path)) return "/";
        return path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }

    static final class Entry {
        final String id;
        final String url;

        Entry(String id, String url) {
            this.id = id;
            this.url = url;
        }
    }

    static final class Decision {
        enum Kind { ADD_HOSTED, CONFIRM_ADD, OPEN, CHOOSE }

        final Kind kind;
        /** OPEN: the entry. */
        final String id;
        /** CHOOSE: the matching entries, in list order. */
        final List<String> ids;

        private Decision(Kind kind, String id, List<String> ids) {
            this.kind = kind;
            this.id = id;
            this.ids = ids;
        }
    }

    /**
     * Only entries on the link's own origin count: no entry adds one (silently
     * for the hosted instance, after a confirm for any other), one entry opens
     * there, several use the default if it's among them and otherwise ask.
     */
    static Decision decide(Target target, List<Entry> entries, String defaultId) {
        List<String> matches = new ArrayList<>();
        for (Entry entry : entries) {
            URI url = parse(entry.url);
            if (url != null && target.origin.equals(originKey(url))) matches.add(entry.id);
        }
        if (matches.isEmpty()) {
            Decision.Kind kind = HOSTED_ORIGIN.equals(target.origin) ? Decision.Kind.ADD_HOSTED : Decision.Kind.CONFIRM_ADD;
            return new Decision(kind, null, Collections.emptyList());
        }
        if (matches.size() == 1) return new Decision(Decision.Kind.OPEN, matches.get(0), matches);
        if (defaultId != null && matches.contains(defaultId)) return new Decision(Decision.Kind.OPEN, defaultId, matches);
        return new Decision(Decision.Kind.CHOOSE, null, matches);
    }

    /** Same shape as ServerConfigPlugin.originKey, for URIs. */
    static String originKey(URI url) {
        String port = url.getPort() == -1 ? "" : ":" + url.getPort();
        return (url.getScheme() + "://" + url.getHost() + port).toLowerCase(Locale.ROOT);
    }

    private static URI parse(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        try {
            return new URI(raw);
        } catch (Exception e) {
            return null;
        }
    }

    private static String queryParam(String rawQuery, String name) {
        if (rawQuery == null) return null;
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            if (!key.equals(name) || eq < 0) continue;
            try {
                return java.net.URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }
}
