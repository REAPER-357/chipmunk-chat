package __PKG__;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;

/**
 * Foreground service that keeps the app process (and its WebView connection to contacts)
 * alive while Chipmunk Chat is in the background. It only holds a notification and,
 * optionally, a partial wake lock. All chat logic stays in the web code.
 */
public class ChipmunkService extends Service {
    static final String CH_SERVICE = "chipmunk_service";
    static final String CH_MSG = "chipmunk_messages";
    static final String CH_CALL = "chipmunk_calls";
    static final int NOTIF_ID = 1001;
    static final String EXTRA_CID = "ck_cid";
    static final String EXTRA_KIND = "ck_kind";

    // Foreground service type bits, written as literals so the code compiles on any compileSdk.
    private static final int TYPE_CAMERA = 0x40;
    private static final int TYPE_MICROPHONE = 0x80;
    private static final int TYPE_SPECIAL_USE = 0x40000000;

    private PowerManager.WakeLock wakeLock;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            // Process was restarted by the system: the in-memory vault is gone, so do not pretend to listen.
            stopSelf();
            return START_NOT_STICKY;
        }
        ensureChannels(this);

        String title = intent.getStringExtra("title");
        if (title == null) title = "Chipmunk Chat";
        String text = intent.getStringExtra("text");
        if (text == null) text = "Listening for messages and calls";
        boolean call = intent.getBooleanExtra("call", false);
        boolean video = intent.getBooleanExtra("video", false);
        boolean wake = intent.getBooleanExtra("wakeLock", true);

        Notification n = new NotificationCompat.Builder(this, CH_SERVICE)
                .setSmallIcon(statusIcon(this))
                .setContentTitle(call ? "Call in progress" : title)
                .setContentText(call ? "Chipmunk Chat is using the microphone" + (video ? " and camera" : "") : text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setContentIntent(openApp(this, null, "open", 1))
                .build();

        try {
            if (Build.VERSION.SDK_INT >= 29) {
                int type = 0;
                if (Build.VERSION.SDK_INT >= 34) type |= TYPE_SPECIAL_USE;
                if (call && Build.VERSION.SDK_INT >= 30) {
                    type |= TYPE_MICROPHONE;
                    if (video) type |= TYPE_CAMERA;
                }
                startForeground(NOTIF_ID, n, type);
            } else {
                startForeground(NOTIF_ID, n);
            }
        } catch (Exception e) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (wake) acquireWake(); else releaseWake();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        releaseWake();
        super.onDestroy();
    }

    private void acquireWake() {
        try {
            if (wakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "chipmunk:background");
                wakeLock.setReferenceCounted(false);
            }
            if (!wakeLock.isHeld()) wakeLock.acquire();
        } catch (Exception ignored) {
        }
    }

    private void releaseWake() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Exception ignored) {
        }
    }

    // ---------- helpers shared with the plugin ----------

    static int statusIcon(Context c) {
        int id = c.getResources().getIdentifier("ic_stat_chipmunk", "drawable", c.getPackageName());
        return id != 0 ? id : android.R.drawable.ic_dialog_info;
    }

    static PendingIntent openApp(Context c, String cid, String kind, int requestCode) {
        Intent i = c.getPackageManager().getLaunchIntentForPackage(c.getPackageName());
        if (i == null) i = new Intent();
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (cid != null) i.putExtra(EXTRA_CID, cid);
        i.putExtra(EXTRA_KIND, kind);
        int fl = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) fl |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getActivity(c, requestCode, i, fl);
    }

    static void ensureChannels(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        NotificationChannel service = new NotificationChannel(CH_SERVICE, "Background connection", NotificationManager.IMPORTANCE_LOW);
        service.setDescription("Keeps Chipmunk Chat connected so messages and calls can arrive");
        service.setShowBadge(false);

        NotificationChannel msg = new NotificationChannel(CH_MSG, "Messages", NotificationManager.IMPORTANCE_HIGH);
        msg.setDescription("New messages and missed calls");
        msg.enableVibration(true);

        NotificationChannel call = new NotificationChannel(CH_CALL, "Incoming calls", NotificationManager.IMPORTANCE_HIGH);
        call.setDescription("Rings when a contact calls you");
        Uri ring = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
        call.setSound(ring, new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIC)
                .build());
        call.enableVibration(true);
        call.setVibrationPattern(new long[]{0, 600, 400, 600, 400});
        call.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);

        nm.createNotificationChannel(service);
        nm.createNotificationChannel(msg);
        nm.createNotificationChannel(call);
    }
}
