package com.chalen.pitchcutter;

import android.content.Intent;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

// JS-facing switch for ImportExportService. start()/stop() are called around
// the video import and export passes (see public/index.html) so the
// foreground-service window is as short-lived as possible. start() also
// doubles as an in-place progress update — call it again with the same label
// and a new "progress" value to update the running notification. "kind" is
// "import" or "export" (defaults to "import") — the two run independent
// notifications/progress bars since one can still be going while the other
// starts (e.g. a queued import finishing while a clip exports).
@CapacitorPlugin(name = "BackgroundGuard")
public class BackgroundGuardPlugin extends Plugin {

  @PluginMethod
  public void start(PluginCall call) {
    String kind = call.getString("kind", "import");
    if ("export".equals(kind)) MainActivity.exportGuardActive = true; else MainActivity.importGuardActive = true;

    Intent intent = new Intent(getContext(), ImportExportService.class);
    intent.putExtra("kind", kind);
    intent.putExtra("label", call.getString("label", "Processing your file…"));
    if (call.hasOption("progress")) {
      intent.putExtra("progress", call.getInt("progress", -1));
    }
    getContext().startForegroundService(intent);
    call.resolve();
  }

  @PluginMethod
  public void stop(PluginCall call) {
    String kind = call.getString("kind", "import");
    if ("export".equals(kind)) MainActivity.exportGuardActive = false; else MainActivity.importGuardActive = false;

    // Routed through the service itself (not Context.stopService) so it can
    // tear down just this kind's notification and keep running if the other
    // kind is still active, only fully stopping once both are done.
    Intent intent = new Intent(getContext(), ImportExportService.class);
    intent.putExtra("kind", kind);
    intent.putExtra("stop", true);
    getContext().startForegroundService(intent);
    call.resolve();
  }
}
