package net.hasht.app;

import androidx.annotation.NonNull;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

/**
 * Receives the relay's FCM data messages, including while the app is killed.
 * Every message is data-only ciphertext (`enc`); nothing is shown until it
 * decrypts here, so Google and the relay never see the content.
 */
public class HashtMessagingService extends FirebaseMessagingService {
    @Override
    public void onNewToken(@NonNull String token) {
        PushStore.setToken(this, token);
        PushRelay.refreshToken(this, token);
    }

    @Override
    public void onDeletedMessages() {
        PushNotifications.overflow(this);
    }

    @Override
    public void onMessageReceived(@NonNull RemoteMessage remote) {
        String enc = remote.getData().get("enc");
        if (enc == null) return;
        PushNotifications.Message message = PushNotifications.open(this, enc);
        if (message == null) return;
        PushNotifications.receive(this, message);
    }
}
