package net.hasht.app;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicInteger;

public class AttachmentFilesTest {
    @Test public void validatesUrlsAndSafeNames() throws Exception {
        assertEquals("https", AttachmentFiles.url("https://cdn.example/file?sig=x", true).getProtocol());
        assertEquals("http", AttachmentFiles.url("http://chat.local/file", false).getProtocol());
        for (String raw : new String[] {"file:///private/file", "https://user:pass@example.com/a", "http://example.com/a", "javascript:alert(1)", "https:///missing-host"}) {
            assertThrows(Exception.class, () -> AttachmentFiles.url(raw, true));
        }
        assertEquals(".._.._notes.txt", AttachmentFiles.filename("../../notes.txt"));
        assertEquals("attachment", AttachmentFiles.filename(".."));
        assertEquals("a_b.txt", AttachmentFiles.filename("a\nb.txt"));
    }

    @Test public void streamsLocalAndRedirectedFilesAndRejectsBadResponses() throws Exception {
        byte[] body = new byte[2 * 1024 * 1024 + 7];
        for (int i = 0; i < body.length; i++) body[i] = (byte)(i % 251);
        Fixture cdn = new Fixture(body, 0);
        Fixture origin = new Fixture(body, cdn.port());
        try {
            String base = "http://127.0.0.1:" + origin.port();
            for (String path : new String[] {"/file", "/redirect"}) {
                ByteArrayOutputStream saved = new ByteArrayOutputStream();
                AttachmentFiles.download(base + path, saved);
                assertArrayEquals(body, saved.toByteArray());
            }
            for (String path : new String[] {"/bad", "/unsafe", "/loop"})
                assertThrows(Exception.class, () -> AttachmentFiles.download(base + path, new ByteArrayOutputStream()));
            AtomicInteger checks = new AtomicInteger();
            assertThrows(Exception.class, () -> AttachmentFiles.download(base + "/file", new ByteArrayOutputStream(), () -> checks.incrementAndGet() < 3));
        } finally { origin.close(); cdn.close(); }
    }
    private static final class Fixture implements AutoCloseable {
        private final ServerSocket server;
        Fixture(byte[] body, int redirectPort) throws Exception {
            server = new ServerSocket();
            server.bind(new InetSocketAddress("127.0.0.1", 0));
            Thread thread = new Thread(() -> {
                while (!server.isClosed()) {
                    try (Socket socket = server.accept()) {
                        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                        String path = reader.readLine().split(" ")[1];
                        String header;
                        while ((header = reader.readLine()) != null && !header.isEmpty()) { }
                        String status = "200 OK", extra = "", length = String.valueOf(body.length);
                        if (path.equals("/redirect")) { status = "302 Found"; extra = "Location: http://127.0.0.1:" + redirectPort + "/file\r\n"; length = "0"; }
                        if (path.equals("/loop")) { status = "302 Found"; extra = "Location: /loop\r\n"; length = "0"; }
                        if (path.equals("/unsafe")) { status = "302 Found"; extra = "Location: file:///etc/passwd\r\n"; length = "0"; }
                        if (path.equals("/bad")) { status = "403 Forbidden"; length = "0"; }
                        socket.getOutputStream().write(("HTTP/1.1 " + status + "\r\nConnection: close\r\n" + extra + "Content-Length: " + length + "\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                        if (status.equals("200 OK")) socket.getOutputStream().write(body);
                    } catch (Exception ignored) { }
                }
            }, "attachment-test-http");
            thread.setDaemon(true);
            thread.start();
        }
        int port() { return server.getLocalPort(); }
        @Override public void close() throws Exception { server.close(); }
    }
}
