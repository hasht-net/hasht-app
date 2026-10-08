package net.hasht.app;

import android.Manifest;
import android.content.pm.PackageManager;

import androidx.core.content.ContextCompat;

import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * Lets the frontend tell the shell a call is live, so OngoingCallService can
 * keep the mic open while the app is backgrounded. The frontend owns the call;
 * this only mirrors its lifetime.
 */
@CapacitorPlugin(name = "CallSession")
public class CallSessionPlugin extends Plugin {

    @PluginMethod
    public void start(PluginCall call) {
        // Android 14 refuses a microphone foreground service without the
        // runtime grant, which the WebView requests when the call opens the mic.
        if (ContextCompat.checkSelfPermission(getContext(), Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            call.reject("Microphone permission not granted", "NO_MIC_PERMISSION");
            return;
        }
        try {
            ContextCompat.startForegroundService(
                getContext(), OngoingCallService.intent(getContext(), call.getString("title")));
            call.resolve();
        } catch (RuntimeException e) {
            // Starting from the background is disallowed on 12+; the page
            // should only call this while the user is looking at it.
            call.reject(e.getMessage(), "START_FAILED", e);
        }
    }

    @PluginMethod
    public void stop(PluginCall call) {
        stopService();
        call.resolve();
    }

    /** The WebView is going away — switching servers, or the activity dying — and the call with it. */
    @Override
    protected void handleOnDestroy() {
        stopService();
    }

    private void stopService() {
        getContext().stopService(OngoingCallService.intent(getContext(), null));
    }
}
