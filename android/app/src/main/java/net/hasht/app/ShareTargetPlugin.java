package net.hasht.app;

import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;
import android.webkit.WebResourceResponse;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The system share sheet's "Hasht" target (ACTION_SEND / SEND_MULTIPLE).
 *
 * Shared streams are copied into the cache as soon as the intent arrives: the
 * read grant belongs to the sending app's intent and can't be relied on once
 * the page gets around to it. The page then fetches each file from its own
 * origin under {@link #PATH_PREFIX} and a random token, which
 * HashtWebViewClient answers from this registry, so a video never goes
 * through the bridge as base64. Only shares the page took are served, each
 * until the page releases it by token.
 *
 * The share goes to whichever instance is active; with none yet, it waits in
 * memory for the one the user picks.
 */
@CapacitorPlugin(name = "ShareTarget")
public class ShareTargetPlugin extends Plugin {

    static final String PATH_PREFIX = "/_hasht_share_/";
    private static final String CACHE_DIR = "shared";
    private static final int MAX_FILES = 10;
    // Instances set their own upload limit and the page reports it; this only
    // stops one share from filling the device.
    private static final long MAX_FILE_BYTES = 1L << 30;

    private static final ExecutorService copier = Executors.newSingleThreadExecutor();
    private static final SecureRandom random = new SecureRandom();

    private static final Object lock = new Object();
    private static Future<Share> pending;
    private static final Map<String, Share> served = new HashMap<>();

    private static final class SharedFile {
        final String name;
        final String type;
        final File file;

        SharedFile(String name, String type, File file) {
            this.name = name;
            this.type = type;
            this.file = file;
        }
    }

    private static final class Share {
        final String token;
        final File dir;
        final String text;
        final List<SharedFile> files;
        final int skipped;

        Share(String token, File dir, String text, List<SharedFile> files, int skipped) {
            this.token = token;
            this.dir = dir;
            this.text = text;
            this.files = files;
            this.skipped = skipped;
        }
    }

    /** Cold start. Not on a restore: the share was taken before the process went. */
    static void prepareLaunch(Context context, Intent intent, boolean restoring) {
        if (restoring) {
            consume(intent);
            return;
        }
        // Left over from a share that was never sent before the process died.
        synchronized (lock) {
            if (pending == null && served.isEmpty()) deleteTree(new File(context.getCacheDir(), CACHE_DIR));
        }
        capture(context, intent);
    }

    @Override
    protected void handleOnNewIntent(Intent intent) {
        super.handleOnNewIntent(intent);
        if (capture(getContext(), intent)) notifyListeners("shareReceived", new JSObject());
    }

    /** The share the user sent here and hasn't taken yet; null if none. */
    @PluginMethod
    public void take(PluginCall call) {
        Future<Share> future;
        synchronized (lock) {
            future = pending;
            pending = null;
        }
        if (future == null) {
            call.resolve(new JSObject().put("share", null));
            return;
        }
        copier.execute(() -> {
            Share share;
            try {
                share = future.get();
            } catch (Exception e) {
                call.reject("Couldn't read the shared content", "READ_FAILED", e);
                return;
            }
            synchronized (lock) {
                served.put(share.token, share);
            }
            call.resolve(new JSObject().put("share", toJs(share)));
        });
    }

    /** The page has this share's files; drop its copies and no other share's. */
    @PluginMethod
    public void release(PluginCall call) {
        String token = call.getString("token");
        Share share;
        synchronized (lock) {
            share = token == null ? null : served.remove(token);
        }
        if (share != null) deleteTree(share.dir);
        call.resolve();
    }

    /** For HashtWebViewClient: a taken share's file, or null for anything else. */
    static WebResourceResponse serve(Uri url) {
        List<String> segments = url.getPathSegments();
        if (segments.size() != 3) return null;
        SharedFile file;
        synchronized (lock) {
            Share share = served.get(segments.get(1));
            if (share == null) return notFound();
            int index;
            try {
                index = Integer.parseInt(segments.get(2));
            } catch (NumberFormatException e) {
                return notFound();
            }
            if (index < 0 || index >= share.files.size()) return notFound();
            file = share.files.get(index);
        }
        try {
            Map<String, String> headers = new HashMap<>();
            headers.put("Cache-Control", "no-store");
            headers.put("Content-Length", Long.toString(file.file.length()));
            return new WebResourceResponse(file.type, null, 200, "OK", headers, new FileInputStream(file.file));
        } catch (IOException e) {
            return notFound();
        }
    }

    private static WebResourceResponse notFound() {
        return new WebResourceResponse("text/plain", null, 404, "Not Found", new HashMap<>(), null);
    }

    /** Starts copying a share intent's content; false if it isn't one. */
    private static boolean capture(Context context, Intent intent) {
        if (intent == null) return false;
        String action = intent.getAction();
        if (!Intent.ACTION_SEND.equals(action) && !Intent.ACTION_SEND_MULTIPLE.equals(action)) return false;
        boolean fromHistory = (intent.getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0;
        String text = sharedText(intent);
        List<Uri> uris = sharedUris(intent);
        String type = intent.getType();
        consume(intent);
        // Reopened from recents, the launching intent comes back: already sent.
        if (fromHistory || (text == null && uris.isEmpty())) return false;

        Context app = context.getApplicationContext();
        Future<Share> future = copier.submit(() -> copy(app, text, uris, type));
        Future<Share> replaced;
        synchronized (lock) {
            replaced = pending;
            pending = future;
        }
        // Queued behind its own copy, so this never blocks.
        if (replaced != null) copier.execute(() -> {
            try {
                deleteTree(replaced.get().dir);
            } catch (Exception ignored) {
            }
        });
        return true;
    }

    /** recreate() reuses the intent: it must not share again. */
    private static void consume(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_SEND.equals(action) && !Intent.ACTION_SEND_MULTIPLE.equals(action)) return;
        intent.setAction(Intent.ACTION_MAIN);
        intent.removeExtra(Intent.EXTRA_TEXT);
        intent.removeExtra(Intent.EXTRA_SUBJECT);
        intent.removeExtra(Intent.EXTRA_STREAM);
        intent.setClipData(null);
    }

    private static String sharedText(Intent intent) {
        CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
        if (text != null && text.toString().trim().length() > 0) return text.toString();
        // Some apps send only a title.
        String subject = intent.getStringExtra(Intent.EXTRA_SUBJECT);
        return subject != null && !subject.trim().isEmpty() ? subject : null;
    }

    @SuppressWarnings("deprecation")
    private static List<Uri> sharedUris(Intent intent) {
        Set<Uri> uris = new LinkedHashSet<>();
        if (Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
            ArrayList<Uri> list = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null) uris.addAll(list);
        } else {
            Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (uri != null) uris.add(uri);
        }
        // Where the read grant actually rides on current Android.
        ClipData clip = intent.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri uri = clip.getItemAt(i).getUri();
                if (uri != null) uris.add(uri);
            }
        }
        uris.remove(null);
        return new ArrayList<>(uris);
    }

    private static Share copy(Context context, String text, List<Uri> uris, String intentType) {
        String token = newToken();
        File dir = new File(new File(context.getCacheDir(), CACHE_DIR), token);
        List<SharedFile> files = new ArrayList<>();
        int skipped = 0;
        ContentResolver resolver = context.getContentResolver();
        for (Uri uri : uris) {
            if (files.size() >= MAX_FILES || !readable(context, uri)) {
                skipped++;
                continue;
            }
            SharedFile file = copyOne(resolver, uri, intentType, dir, files.size());
            if (file == null) skipped++;
            else files.add(file);
        }
        return new Share(token, dir, text, files, skipped);
    }

    /**
     * Any app can name any URI here. A file:// path or our own provider would
     * hand the sender's choice of this app's private files to the server.
     */
    private static boolean readable(Context context, Uri uri) {
        if (!ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) return false;
        String authority = uri.getAuthority();
        if (authority == null) return false;
        ProviderInfo provider = context.getPackageManager().resolveContentProvider(authority, 0);
        return provider == null || !context.getPackageName().equals(provider.packageName);
    }

    private static SharedFile copyOne(ContentResolver resolver, Uri uri, String intentType, File dir, int index) {
        String name = null;
        long size = -1;
        try (Cursor cursor = resolver.query(uri, new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE},
                 null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameCol = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int sizeCol = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (nameCol >= 0) name = cursor.getString(nameCol);
                if (sizeCol >= 0 && !cursor.isNull(sizeCol)) size = cursor.getLong(sizeCol);
            }
        } catch (RuntimeException e) {
            // Metadata is optional; the stream is what matters.
        }
        if (size > MAX_FILE_BYTES) return null;

        String type = resolver.getType(uri);
        if (type == null && intentType != null && !intentType.contains("*")) type = intentType;
        if (type == null) type = "application/octet-stream";
        name = displayName(name, type, index);

        if (!dir.isDirectory() && !dir.mkdirs()) return null;
        File out = new File(dir, Integer.toString(index));
        try (InputStream in = resolver.openInputStream(uri); OutputStream os = new FileOutputStream(out)) {
            if (in == null) return null;
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > MAX_FILE_BYTES) throw new IOException("too large");
                os.write(buffer, 0, read);
            }
        } catch (IOException | RuntimeException e) {
            out.delete();
            return null;
        }
        return new SharedFile(name, type, out);
    }

    private static String displayName(String name, String type, int index) {
        if (name != null) {
            name = name.replaceAll("[/\\\\\\p{Cntrl}]", "_").trim();
            if (!name.isEmpty()) return name;
        }
        String ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(type);
        return "shared-" + (index + 1) + (ext != null ? "." + ext : "");
    }

    private static JSObject toJs(Share share) {
        JSArray files = new JSArray();
        for (int i = 0; i < share.files.size(); i++) {
            SharedFile file = share.files.get(i);
            JSObject js = new JSObject();
            js.put("name", file.name);
            js.put("type", file.type);
            js.put("size", file.file.length());
            js.put("url", PATH_PREFIX + share.token + "/" + i);
            files.put(js);
        }
        JSObject js = new JSObject();
        js.put("token", share.token);
        js.put("text", share.text);
        js.put("files", files);
        js.put("skipped", share.skipped);
        return js;
    }

    private static String newToken() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        StringBuilder hex = new StringBuilder();
        for (byte b : bytes) hex.append(String.format("%02x", b));
        return hex.toString();
    }

    private static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        file.delete();
    }
}
