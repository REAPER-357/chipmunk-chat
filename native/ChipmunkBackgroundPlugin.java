package __PKG__;

import android.app.Activity;
import android.app.Notification;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.MediaStore;
import android.provider.Settings;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebView;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Bridge between the Chipmunk Chat web code and Android:
 * foreground service, notifications (messages and ringing calls), battery settings,
 * lock-screen display for calls, saving backups, and keeping the WebView running in the background.
 */
@CapacitorPlugin(
        name = "ChipmunkBackground",
        permissions = {
                @Permission(alias = "notifications", strings = {"android.permission.POST_NOTIFICATIONS"})
        }
)
public class ChipmunkBackgroundPlugin extends Plugin {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean serviceRunning = false;
    private boolean inBackground = false;
    private String svcTitle = "Chipmunk Chat";
    private String svcText = "Listening for messages and calls";
    private boolean svcWake = true;
    private boolean inCall = false;
    private boolean inVideo = false;

    /** Chromium throttles a WebView whose window is hidden. Re-announcing visibility keeps timers and sockets running. */
    private final Runnable keepAlive = new Runnable() {
        @Override
        public void run() {
            if (!inBackground || !serviceRunning) return;
            try {
                WebView wv = getBridge().getWebView();
                wv.resumeTimers();
                wv.dispatchWindowVisibilityChanged(View.VISIBLE);
            } catch (Exception ignored) {
            }
            handler.postDelayed(this, 15000);
        }
    };

    @Override
    public void load() {
        Activity a = getActivity();
        if (a != null) handleIntent(a.getIntent());
    }

    // ---------- lifecycle ----------

    @Override
    protected void handleOnPause() {
        super.handleOnPause();
        inBackground = true;
        JSObject s = new JSObject();
        s.put("active", false);
        notifyListeners("state", s);
        if (serviceRunning) {
            handler.removeCallbacks(keepAlive);
            handler.postDelayed(keepAlive, 1000);
        }
    }

    @Override
    protected void handleOnResume() {
        super.handleOnResume();
        inBackground = false;
        handler.removeCallbacks(keepAlive);
        JSObject s = new JSObject();
        s.put("active", true);
        notifyListeners("state", s);
    }

    @Override
    protected void handleOnDestroy() {
        handler.removeCallbacks(keepAlive);
        super.handleOnDestroy();
    }

    @Override
    protected void handleOnNewIntent(Intent intent) {
        super.handleOnNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        String kind = intent.getStringExtra(ChipmunkService.EXTRA_KIND);
        if (kind == null) return;
        JSObject d = new JSObject();
        d.put("kind", kind);
        String cid = intent.getStringExtra(ChipmunkService.EXTRA_CID);
        if (cid != null) d.put("cid", cid);
        intent.removeExtra(ChipmunkService.EXTRA_KIND);
        intent.removeExtra(ChipmunkService.EXTRA_CID);
        notifyListeners("tap", d, true);
    }

    // ---------- permissions ----------

    @PluginMethod
    public void requestNotificationPermission(PluginCall call) {
        if (Build.VERSION.SDK_INT < 33 || getPermissionState("notifications") == PermissionState.GRANTED) {
            JSObject r = new JSObject();
            r.put("granted", true);
            call.resolve(r);
            return;
        }
        requestPermissionForAlias("notifications", call, "notificationsResult");
    }

    @PermissionCallback
    private void notificationsResult(PluginCall call) {
        JSObject r = new JSObject();
        r.put("granted", getPermissionState("notifications") == PermissionState.GRANTED);
        call.resolve(r);
    }

    // ---------- foreground service ----------

    private void startFg() throws Exception {
        Intent i = new Intent(getContext(), ChipmunkService.class);
        i.putExtra("title", svcTitle);
        i.putExtra("text", svcText);
        i.putExtra("wakeLock", svcWake);
        i.putExtra("call", inCall);
        i.putExtra("video", inVideo);
        ContextCompat.startForegroundService(getContext(), i);
    }

    @PluginMethod
    public void start(PluginCall call) {
        svcTitle = call.getString("title", "Chipmunk Chat");
        svcText = call.getString("text", "Listening for messages and calls");
        Boolean w = call.getBoolean("wakeLock", true);
        svcWake = w == null || w;
        try {
            startFg();
            serviceRunning = true;
            call.resolve();
        } catch (Exception e) {
            serviceRunning = false;
            call.reject("Could not start background service: " + e.getMessage());
        }
    }

    @PluginMethod
    public void stop(PluginCall call) {
        serviceRunning = false;
        inCall = false;
        inVideo = false;
        handler.removeCallbacks(keepAlive);
        try {
            getContext().stopService(new Intent(getContext(), ChipmunkService.class));
        } catch (Exception ignored) {
        }
        call.resolve();
    }

    /** Tells the service a call is active so Android keeps the microphone and camera available in the background. */
    @PluginMethod
    public void callMode(PluginCall call) {
        Boolean on = call.getBoolean("on", false);
        Boolean video = call.getBoolean("video", false);
        inCall = on != null && on;
        inVideo = inCall && video != null && video;
        if (serviceRunning) {
            try {
                startFg();
            } catch (Exception ignored) {
            }
        }
        call.resolve();
    }

    // ---------- notifications ----------

    @PluginMethod
    public void notify(PluginCall call) {
        Context c = getContext();
        Integer idObj = call.getInt("id", 2000);
        int id = idObj == null ? 2000 : idObj;
        String kind = call.getString("kind", "msg");
        String title = call.getString("title", "Chipmunk Chat");
        String text = call.getString("text", "");
        String cid = call.getString("cid");
        boolean isCall = "call".equals(kind);

        ChipmunkService.ensureChannels(c);
        android.app.PendingIntent pi = ChipmunkService.openApp(c, cid, isCall ? "call" : "msg", id);

        NotificationCompat.Builder b = new NotificationCompat.Builder(c, isCall ? ChipmunkService.CH_CALL : ChipmunkService.CH_MSG)
                .setSmallIcon(ChipmunkService.statusIcon(c))
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setWhen(System.currentTimeMillis());

        if (isCall) {
            b.setCategory(NotificationCompat.CATEGORY_CALL)
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .setFullScreenIntent(pi, true)
                    .setOngoing(true)
                    .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
                    .setVibrate(new long[]{0, 600, 400, 600, 400});
        } else {
            Notification pub = new NotificationCompat.Builder(c, ChipmunkService.CH_MSG)
                    .setSmallIcon(ChipmunkService.statusIcon(c))
                    .setContentTitle("Chipmunk Chat")
                    .setContentText("New message")
                    .build();
            b.setCategory(NotificationCompat.CATEGORY_MESSAGE)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setDefaults(NotificationCompat.DEFAULT_ALL)
                    .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                    .setPublicVersion(pub);
        }

        Notification n = b.build();
        if (isCall) n.flags |= Notification.FLAG_INSISTENT;

        JSObject r = new JSObject();
        try {
            NotificationManagerCompat nm = NotificationManagerCompat.from(c);
            if (nm.areNotificationsEnabled()) {
                nm.notify(id, n);
                r.put("shown", true);
            } else {
                r.put("shown", false);
            }
        } catch (SecurityException e) {
            r.put("shown", false);
        }
        call.resolve(r);
    }

    @PluginMethod
    public void cancel(PluginCall call) {
        Integer id = call.getInt("id");
        if (id != null) {
            try {
                NotificationManagerCompat.from(getContext()).cancel(id);
            } catch (Exception ignored) {
            }
        }
        call.resolve();
    }

    // ---------- lock screen / window ----------

    /** Lets the app appear over the lock screen and wake the screen while a call is ringing. */
    @PluginMethod
    public void overLock(PluginCall call) {
        Boolean onObj = call.getBoolean("on", false);
        final boolean on = onObj != null && onObj;
        final Activity a = getActivity();
        if (a != null) {
            a.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (Build.VERSION.SDK_INT >= 27) {
                        a.setShowWhenLocked(on);
                        a.setTurnScreenOn(on);
                    } else {
                        int f = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON;
                        if (on) a.getWindow().addFlags(f); else a.getWindow().clearFlags(f);
                    }
                }
            });
        }
        call.resolve();
    }

    // ---------- battery ----------

    @PluginMethod
    public void batteryStatus(PluginCall call) {
        boolean unrestricted = true;
        if (Build.VERSION.SDK_INT >= 23) {
            PowerManager pm = (PowerManager) getContext().getSystemService(Context.POWER_SERVICE);
            unrestricted = pm != null && pm.isIgnoringBatteryOptimizations(getContext().getPackageName());
        }
        JSObject r = new JSObject();
        r.put("unrestricted", unrestricted);
        call.resolve(r);
    }

    @PluginMethod
    public void requestUnrestricted(PluginCall call) {
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getContext().getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
        } catch (Exception e) {
            try {
                Intent i = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(i);
            } catch (Exception ignored) {
            }
        }
        call.resolve();
    }

    // ---------- misc ----------

    @PluginMethod
    public void minimize(PluginCall call) {
        Activity a = getActivity();
        if (a != null) a.moveTaskToBack(true);
        call.resolve();
    }

    /** Saves a text file (the encrypted backup) to the phone's Downloads folder. */
    @PluginMethod
    public void saveFile(PluginCall call) {
        String name = call.getString("filename", "chipmunk-backup.json");
        String data = call.getString("data", "");
        if (Build.VERSION.SDK_INT < 29) {
            call.reject("Saving needs Android 10 or newer");
            return;
        }
        try {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Downloads.DISPLAY_NAME, name);
            v.put(MediaStore.Downloads.MIME_TYPE, "application/json");
            v.put(MediaStore.Downloads.RELATIVE_PATH, "Download");
            Uri uri = getContext().getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) {
                call.reject("Could not create the file");
                return;
            }
            OutputStream os = getContext().getContentResolver().openOutputStream(uri);
            if (os == null) {
                call.reject("Could not open the file");
                return;
            }
            try {
                os.write(data.getBytes(StandardCharsets.UTF_8));
            } finally {
                os.close();
            }
            JSObject r = new JSObject();
            r.put("saved", "Downloads/" + name);
            call.resolve(r);
        } catch (Exception e) {
            call.reject("Could not save: " + e.getMessage());
        }
    }
}
