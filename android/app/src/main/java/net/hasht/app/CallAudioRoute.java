package net.hasht.app;

import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

/**
 * Moves a live call onto a headset that connects mid-call, and off one that
 * goes away.
 *
 * The WebView picks the communication device once, when the mic opens. It
 * follows Bluetooth only with BLUETOOTH_CONNECT, a runtime prompt this app
 * doesn't ask for, so a call that started on the speaker stayed there after
 * earbuds connected. Device callbacks and communication devices need no
 * permission. The ranking matches the WebView's own default choice, so the
 * two agree whenever the mic restarts.
 */
@RequiresApi(Build.VERSION_CODES.S)
final class CallAudioRoute {

    private final AudioManager audio;
    private final Handler handler = new Handler(Looper.getMainLooper());
    // Only touched on the handler's thread.
    private boolean registered;
    private boolean live;
    private boolean routed;

    private final AudioDeviceCallback callback = new AudioDeviceCallback() {
        @Override
        public void onAudioDevicesAdded(AudioDeviceInfo[] added) {
            if (!live) return;
            int current = rank(audio.getCommunicationDevice());
            for (AudioDeviceInfo device : added) {
                if (rank(device.getType()) > current) {
                    route();
                    return;
                }
            }
        }

        @Override
        public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) {
            if (!live) return;
            for (AudioDeviceInfo device : removed) {
                if (rank(device.getType()) >= BLUETOOTH) {
                    route();
                    return;
                }
            }
        }
    };

    CallAudioRoute(AudioManager audio) {
        this.audio = audio;
    }

    void start() {
        handler.post(() -> {
            if (registered) return;
            registered = true;
            audio.registerAudioDeviceCallback(callback, handler);
            // Queued behind the registration replay, which must not count.
            handler.post(() -> live = registered);
        });
    }

    void stop() {
        handler.post(() -> {
            if (!registered) return;
            registered = false;
            live = false;
            audio.unregisterAudioDeviceCallback(callback);
            if (routed) audio.clearCommunicationDevice();
            routed = false;
        });
    }

    private void route() {
        AudioDeviceInfo best = null;
        for (AudioDeviceInfo device : audio.getAvailableCommunicationDevices()) {
            if (best == null || rank(device.getType()) > rank(best.getType())) best = device;
        }
        AudioDeviceInfo current = audio.getCommunicationDevice();
        if (best == null || (current != null && current.getId() == best.getId())) return;
        routed |= audio.setCommunicationDevice(best);
    }

    private static int rank(@Nullable AudioDeviceInfo device) {
        return device == null ? OTHER : rank(device.getType());
    }

    static final int OTHER = 0;
    static final int SPEAKER = 1;
    static final int BLUETOOTH = 2;
    static final int USB = 3;
    static final int WIRED = 4;

    /** Wired, then USB, then Bluetooth, then the loudspeaker, as the WebView ranks them. */
    static int rank(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
                return WIRED;
            case AudioDeviceInfo.TYPE_USB_HEADSET:
            case AudioDeviceInfo.TYPE_USB_DEVICE:
                return USB;
            case AudioDeviceInfo.TYPE_BLE_HEADSET:
            case AudioDeviceInfo.TYPE_BLE_SPEAKER:
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
            case AudioDeviceInfo.TYPE_HEARING_AID:
                return BLUETOOTH;
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER:
                return SPEAKER;
            default:
                return OTHER;
        }
    }
}
