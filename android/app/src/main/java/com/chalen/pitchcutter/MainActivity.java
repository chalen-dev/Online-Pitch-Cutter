package com.chalen.pitchcutter;

import android.Manifest;
import android.app.PictureInPictureParams;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Rational;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
  // Set by BackgroundGuardPlugin while a video import/export is running.
  // Tracked separately since one can still be active while the other starts.
  static volatile boolean importGuardActive = false;
  static volatile boolean exportGuardActive = false;

  @Override
  public void onCreate(Bundle savedInstanceState) {
    registerPlugin(BackgroundGuardPlugin.class);
    registerPlugin(FileSaverPlugin.class);
    registerPlugin(AudioExtractorPlugin.class);
    super.onCreate(savedInstanceState);

    // Without this, the foreground service still runs on Android 13+, but its
    // notification stays hidden until the user grants it from system settings.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
      ActivityCompat.requestPermissions(this, new String[] { Manifest.permission.POST_NOTIFICATIONS }, 1001);
    }
  }

  // Fires when the user presses Home or switches to another app/Recents (not
  // on screen lock — Android gives no hook for that case). The foreground
  // service alone keeps the process alive, but the WebView's <video> decode
  // still freezes the instant its window isn't visible on screen. Entering
  // PiP keeps that window visible (small and floating) instead of hidden,
  // which is what actually keeps decode running during an import/export.
  @Override
  protected void onUserLeaveHint() {
    super.onUserLeaveHint();
    if ((importGuardActive || exportGuardActive) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      try {
        enterPictureInPictureMode(new PictureInPictureParams.Builder()
          .setAspectRatio(new Rational(9, 16))
          .build());
      } catch (Exception e) {
        // Best effort — if PiP can't be entered (device/policy restriction),
        // the import/export continues but will still freeze while hidden.
      }
    }
  }
}
