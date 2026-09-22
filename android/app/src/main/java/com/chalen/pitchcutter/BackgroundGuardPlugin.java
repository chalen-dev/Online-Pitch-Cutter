package com.chalen.pitchcutter;

import android.content.Intent;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

// JS-facing switch for ImportExportService. start()/stop() are called around
// the video import and export passes (see public/index.html) so the
// foreground-service window is as short-lived as possible.
@CapacitorPlugin(name = "BackgroundGuard")
public class BackgroundGuardPlugin extends Plugin {

  @PluginMethod
  public void start(PluginCall call) {
    Intent intent = new Intent(getContext(), ImportExportService.class);
    intent.putExtra("label", call.getString("label", "Processing your file…"));
    getContext().startForegroundService(intent);
    call.resolve();
  }

  @PluginMethod
  public void stop(PluginCall call) {
    getContext().stopService(new Intent(getContext(), ImportExportService.class));
    call.resolve();
  }
}
