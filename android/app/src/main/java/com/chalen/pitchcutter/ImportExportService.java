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
  private static final int NOTIFICATION_ID = 1001;

  @Override
  public void onCreate() {
    super.onCreate();
    createNotificationChannel();
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    String label = intent != null ? intent.getStringExtra("label") : null;
    if (label == null) label = "Processing your file…";
    // -1 = indeterminate/no bar (e.g. before the first progress callback fires).
    int progress = intent != null ? intent.getIntExtra("progress", -1) : -1;

    NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
      .setContentTitle("Offline Pitch Cutter")
      .setContentText(label)
      .setSmallIcon(R.mipmap.ic_launcher)
      .setOngoing(true)
      .setPriority(NotificationCompat.PRIORITY_LOW);

    if (progress >= 0) {
      builder.setProgress(100, Math.min(progress, 100), false);
    }
    Notification notification = builder.build();

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
    } else {
      startForeground(NOTIFICATION_ID, notification);
    }
    return START_NOT_STICKY;
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
