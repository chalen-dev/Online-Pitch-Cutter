package com.chalen.pitchcutter;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import androidx.core.app.NotificationCompat;

// Elevates this process to foreground priority for the duration of a video
// import/export pass. Both of those replay the source video in real time to
// pull PCM samples out of it (decodeAudioData can't touch a muxed container),
// so without this, Android suspends the WebView's JS as soon as the app is
// backgrounded mid-pass and the capture silently stalls or gets killed.
public class ImportExportService extends Service {
  private static final String CHANNEL_ID = "pitch_cutter_processing";
  private static final int NOTIFICATION_ID_IMPORT = 1001;
  private static final int NOTIFICATION_ID_EXPORT = 1002;

  // Import and export can be in flight at once (e.g. the next file is still
  // importing while the current clip is exporting), so each gets its own
  // notification/progress bar instead of sharing one that flips between
  // whichever operation called start() most recently.
  private boolean importActive = false;
  private boolean exportActive = false;
  private String importLabel = "Processing your file…";
  private int importProgress = -1;
  private String exportLabel = "Exporting video…";
  private int exportProgress = -1;

  @Override
  public void onCreate() {
    super.onCreate();
    createNotificationChannel();
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    boolean isExport = intent != null && "export".equals(intent.getStringExtra("kind"));
    boolean stop = intent != null && intent.getBooleanExtra("stop", false);

    if (isExport) {
      exportActive = !stop;
      if (!stop) {
        String label = intent.getStringExtra("label");
        if (label != null) exportLabel = label;
        exportProgress = intent.getIntExtra("progress", -1);
      }
    } else {
      importActive = !stop;
      if (!stop) {
        String label = intent.getStringExtra("label");
        if (label != null) importLabel = label;
        importProgress = intent.getIntExtra("progress", -1);
      }
    }

    NotificationManager manager = getSystemService(NotificationManager.class);
    if (manager != null) {
      if (importActive) manager.notify(NOTIFICATION_ID_IMPORT, buildNotification(importLabel, importProgress));
      else manager.cancel(NOTIFICATION_ID_IMPORT);
      if (exportActive) manager.notify(NOTIFICATION_ID_EXPORT, buildNotification(exportLabel, exportProgress));
      else manager.cancel(NOTIFICATION_ID_EXPORT);
    }

    if (!importActive && !exportActive) {
      stopForeground(true);
      stopSelf();
      return START_NOT_STICKY;
    }

    // The id passed to startForeground must stay backed by a currently-posted
    // notification for as long as the service claims foreground status —
    // recomputed every call so stopping one operation while the other keeps
    // running doesn't leave the service pointing at a cancelled notification.
    int primaryId = importActive ? NOTIFICATION_ID_IMPORT : NOTIFICATION_ID_EXPORT;
    Notification primary = importActive
      ? buildNotification(importLabel, importProgress)
      : buildNotification(exportLabel, exportProgress);

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      startForeground(primaryId, primary, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
    } else {
      startForeground(primaryId, primary);
    }
    return START_NOT_STICKY;
  }

  private Notification buildNotification(String label, int progress) {
    NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
      .setContentTitle("Offline Pitch Cutter")
      .setContentText(label)
      .setSmallIcon(R.mipmap.ic_launcher)
      .setOngoing(true)
      .setPriority(NotificationCompat.PRIORITY_LOW);
    // -1 = indeterminate/no bar (e.g. before the first progress callback fires).
    if (progress >= 0) {
      builder.setProgress(100, Math.min(progress, 100), false);
    }
    return builder.build();
  }

  private void createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      NotificationChannel channel = new NotificationChannel(
        CHANNEL_ID, "Import/Export Processing", NotificationManager.IMPORTANCE_LOW);
      channel.setDescription("Keeps video import/export running while the app is in the background.");
      NotificationManager manager = getSystemService(NotificationManager.class);
      if (manager != null) manager.createNotificationChannel(channel);
    }
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }
}
