package net.hasht.app;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.function.BooleanSupplier;

/** File actions never pass cookies or account credentials to an object store. */
final class AttachmentFiles {
    static URL url(String raw, boolean secure) throws Exception {
        if (raw == null || raw.length() > 16384) throw new IOException("Invalid attachment URL.");
        URI uri = new URI(raw);
        String scheme = uri.getScheme();
        if (!("https".equalsIgnoreCase(scheme) || (!secure && "http".equalsIgnoreCase(scheme)))
            || uri.getHost() == null || uri.getRawUserInfo() != null)
            throw new IOException("Attachments require a valid HTTP(S) URL; HTTPS cannot redirect to HTTP.");
        return uri.toURL();
    }

    static String filename(String raw) {
        String name = raw == null ? "attachment" : raw.replaceAll("[\\\\/\\p{Cntrl}]", "_").trim();
        if (name.isEmpty() || name.equals(".") || name.equals("..")) name = "attachment";
        return name.length() > 180 ? name.substring(0, 180) : name;
    }

    static void download(String raw, OutputStream output) throws Exception {
        download(raw, output, () -> true);
    }

    static void download(String raw, OutputStream output, BooleanSupplier currentAccount) throws Exception {
        URL current = url(raw, false);
        boolean secure = "https".equalsIgnoreCase(current.getProtocol());
        for (int redirects = 0; redirects <= 10; redirects++) {
            requireCurrentAccount(currentAccount);
            HttpURLConnection connection = (HttpURLConnection) current.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(60000);
            try {
                int status = connection.getResponseCode();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException("The attachment redirect has no destination.");
                    current = url(current.toURI().resolve(location).toString(), secure);
                    secure |= "https".equalsIgnoreCase(current.getProtocol());
                    continue;
                }
                if (status != 200) throw new IOException("The attachment server returned HTTP " + status + ".");
                try (InputStream input = connection.getInputStream()) {
                    byte[] buffer = new byte[64 * 1024];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        requireCurrentAccount(currentAccount);
                        output.write(buffer, 0, count);
                    }
                    requireCurrentAccount(currentAccount);
                }
                return;
            } finally { connection.disconnect(); }
        }
        throw new IOException("Too many attachment redirects.");
    }
    private static void requireCurrentAccount(BooleanSupplier currentAccount) throws IOException {
        if (Thread.currentThread().isInterrupted() || !currentAccount.getAsBoolean())
            throw new IOException("The active account changed. Save the attachment again.");
    }
}
