package net.hasht.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;

/**
 * Keeps the process allowed to record while a call is up and the app is in
 * the background. The call's media lives in the WebView; without a
 * microphone-typed foreground service Android silences the mic shortly after
 * the activity leaves the screen, and the other side hears nothing.
 *
 * Holds no audio itself — its only job is the foreground state and the
 * ongoing notification that makes the recording visible to the user.
 */
public class OngoingCallService extends Service {

    static final String EXTRA_TITLE = "title";

    private static final String CHANNEL_ID = "ongoing_call";
    private static final int NOTIFICATION_ID = 1;

    static Intent intent(Context context, String title) {
        return new Intent(context, OngoingCallService.class).putExtra(EXTRA_TITLE, title);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String title = intent != null ? intent.getStringExtra(EXTRA_TITLE) : null;
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
            ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            : 0;
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(title), type);
        // A call can't be resumed by a restarted service with no WebView behind it.
        return START_NOT_STICKY;
    }

    /** Swiping the app away ends the WebView, and with it the call. */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        stopSelf();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification notification(String title) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            && manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.call_channel_name),
                NotificationManager.IMPORTANCE_LOW));
        }

        Intent open = new Intent(this, MainActivity.class)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER);
        PendingIntent tap = PendingIntent.getActivity(
            this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_call)
            .setContentTitle(title != null && !title.isEmpty()
                ? title
                : getString(R.string.call_notification_title))
            .setContentText(getString(R.string.call_notification_text))
            .setContentIntent(tap)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build();
    }
}
