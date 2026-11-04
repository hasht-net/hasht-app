package net.hasht.app;

import android.media.AudioAttributes;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads text aloud with the system TextToSpeech engine. Android's WebView has
 * no speechSynthesis, so the page's "Speak message" and /tts playback come
 * here instead. The page keeps its own queue: each speak() replaces whatever
 * is playing and resolves when it finishes or is stopped.
 */
@CapacitorPlugin(name = "NativeSpeech")
public class NativeSpeechPlugin extends Plugin {

    private TextToSpeech tts;
    /** Null until the engine reports in. */
    private Boolean ready;
    private final List<PluginCall> awaitingEngine = new ArrayList<>();
    private final Map<String, PluginCall> speaking = new ConcurrentHashMap<>();

    @Override
    public void load() {
        tts = new TextToSpeech(getContext(), status -> {
            List<PluginCall> waiting;
            synchronized (this) {
                ready = status == TextToSpeech.SUCCESS;
                waiting = new ArrayList<>(awaitingEngine);
                awaitingEngine.clear();
            }
            for (PluginCall call : waiting) resolveAvailable(call);
        });
        tts.setAudioAttributes(new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build());
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) {}
            @Override public void onDone(String id) { finish(id); }
            @Override public void onError(String id) { finish(id); }
            @Override public void onStop(String id, boolean interrupted) { finish(id); }
        });
    }

    @PluginMethod
    public void available(PluginCall call) {
        synchronized (this) {
            if (ready == null) {
                awaitingEngine.add(call);
                return;
            }
        }
        resolveAvailable(call);
    }

    @PluginMethod
    public void speak(PluginCall call) {
        String text = call.getString("text", "");
        if (!Boolean.TRUE.equals(ready) || text.isEmpty()) {
            call.resolve();
            return;
        }
        Double rate = call.getDouble("rate", 1.0);
        tts.setSpeechRate(rate == null ? 1f : rate.floatValue());
        String id = UUID.randomUUID().toString();
        speaking.put(id, call);
        if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) finish(id);
    }

    @PluginMethod
    public void stop(PluginCall call) {
        if (tts != null) tts.stop();
        finishAll();
        call.resolve();
    }

    @Override
    protected void handleOnDestroy() {
        if (tts != null) tts.shutdown();
        finishAll();
    }

    private void resolveAvailable(PluginCall call) {
        JSObject result = new JSObject();
        result.put("available", Boolean.TRUE.equals(ready));
        call.resolve(result);
    }

    private void finish(String id) {
        PluginCall call = speaking.remove(id);
        if (call != null) call.resolve();
    }

    private void finishAll() {
        for (String id : new ArrayList<>(speaking.keySet())) finish(id);
    }
}
