package net.hasht.app;

import static org.junit.Assert.*;

import android.media.AudioDeviceInfo;
import org.junit.Test;

public class CallAudioRouteTest {
    @Test public void headsetsOutrankTheSpeakerInTheWebViewsOrder() {
        int wired = CallAudioRoute.rank(AudioDeviceInfo.TYPE_WIRED_HEADPHONES);
        int usb = CallAudioRoute.rank(AudioDeviceInfo.TYPE_USB_HEADSET);
        int bluetooth = CallAudioRoute.rank(AudioDeviceInfo.TYPE_BLUETOOTH_SCO);
        int speaker = CallAudioRoute.rank(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER);
        int earpiece = CallAudioRoute.rank(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE);
        assertTrue(wired > usb);
        assertTrue(usb > bluetooth);
        assertTrue(bluetooth > speaker);
        assertTrue(speaker > earpiece);
    }

    @Test public void everyBluetoothKindCountsAsAHeadset() {
        for (int type : new int[] {
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_HEARING_AID,
        }) {
            assertEquals(CallAudioRoute.BLUETOOTH, CallAudioRoute.rank(type));
        }
    }
}
