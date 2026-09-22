package com.chalen.pitchcutter;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

// Android's embedded WebView (unlike full Chrome) has no download manager and
// never resolves blob: URLs handed to an <a download> click — that silently
// no-ops instead of saving anything. This plugin is the actual save path for
// the Capacitor build: the finished export blob is base64-encoded in JS and
// handed here to be written into the device's real Downloads folder.
@CapacitorPlugin(name = "FileSaver")
public class FileSaverPlugin extends Plugin {

  @PluginMethod
  public void saveToDownloads(PluginCall call) {
    String filename = call.getString("filename");
    String mimeType = call.getString("mimeType", "application/octet-stream");
    String base64Data = call.getString("base64Data");

    if (filename == null || base64Data == null) {
      call.reject("filename and base64Data are required");
      return;
    }

    byte[] bytes;
    try {
      bytes = Base64.decode(base64Data, Base64.DEFAULT);
    } catch (IllegalArgumentException e) {
      call.reject("Invalid base64 data: " + e.getMessage());
      return;
    }

    Context ctx = getContext();
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        // Scoped-storage-safe: writing a new entry the app itself created into
        // the public Downloads collection needs no storage permission on 29+.
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, filename);
        values.put(MediaStore.Downloads.MIME_TYPE, mimeType);
        values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);

        Uri itemUri = ctx.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (itemUri == null) {
          call.reject("Could not create file entry in MediaStore");
          return;
        }
        try (OutputStream out = ctx.getContentResolver().openOutputStream(itemUri)) {
          if (out == null) {
            call.reject("Could not open output stream");
            return;
          }
          out.write(bytes);
        }
        JSObject ret = new JSObject();
        ret.put("uri", itemUri.toString());
        call.resolve(ret);
      } else {
        // Pre-Android 10 fallback: direct write into the legacy public
        // Downloads directory (needs WRITE_EXTERNAL_STORAGE, declared with
        // maxSdkVersion="28" in the manifest since it's unused/unneeded above).
        File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (!downloadsDir.exists()) downloadsDir.mkdirs();
        File outFile = new File(downloadsDir, filename);
        try (FileOutputStream out = new FileOutputStream(outFile)) {
          out.write(bytes);
        }
        JSObject ret = new JSObject();
        ret.put("uri", Uri.fromFile(outFile).toString());
        call.resolve(ret);
      }
    } catch (Exception e) {
      call.reject("Failed to save file: " + e.getMessage(), e);
    }
  }
}
