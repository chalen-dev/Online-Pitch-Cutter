package com.chalen.pitchcutter;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.util.Base64;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

// Decodes a video's audio track directly via Android's own MediaExtractor +
// MediaCodec, configured with no output Surface at all. Unlike the WebView
// <video> element approach (captureVideoAudioTrack in public/index.html),
// this isn't tied to real-time playback or to anything being visible on
// screen — it decodes as fast as the hardware allows, headless, the same
// class of operation as a background download. Hands back a plain WAV file
// path, which JS decodes with the browser's own decodeAudioData exactly like
// a regular audio import.
@CapacitorPlugin(name = "AudioExtractor")
public class AudioExtractorPlugin extends Plugin {

  @PluginMethod
  public void extractAudio(final PluginCall call) {
    final String base64Data = call.getString("base64Data");
    if (base64Data == null) {
      call.reject("base64Data is required");
      return;
    }

    // The decode loop below blocks; never run it on the thread Capacitor
    // dispatches plugin calls on.
    new Thread(new Runnable() {
      @Override
      public void run() {
        File tempInput = null;
        File outputWav = null;
        MediaExtractor extractor = null;
        MediaCodec codec = null;
        RandomAccessFile raf = null;
        try {
          Context ctx = getContext();
          cleanupOldExtracts(ctx);

          byte[] videoBytes = Base64.decode(base64Data, Base64.DEFAULT);
          tempInput = File.createTempFile("import_", ".tmp", ctx.getCacheDir());
          try (FileOutputStream fos = new FileOutputStream(tempInput)) {
            fos.write(videoBytes);
          }
          videoBytes = null; // eligible for GC before decode — these can be sizeable

          extractor = new MediaExtractor();
          extractor.setDataSource(tempInput.getAbsolutePath());

          int audioTrackIndex = -1;
          MediaFormat audioFormat = null;
          int trackCount = extractor.getTrackCount();
          for (int i = 0; i < trackCount; i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) {
              audioTrackIndex = i;
              audioFormat = format;
              break;
            }
          }
          if (audioTrackIndex == -1) {
            call.reject("No audio track found in this file");
            return;
          }
          extractor.selectTrack(audioTrackIndex);

          String mime = audioFormat.getString(MediaFormat.KEY_MIME);
          codec = MediaCodec.createDecoderByType(mime);
          codec.configure(audioFormat, null, null, 0);
          codec.start();

          int sampleRate = audioFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)
            ? audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
          int channelCount = audioFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
            ? audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;

          outputWav = File.createTempFile("extracted_", ".wav", ctx.getCacheDir());
          raf = new RandomAccessFile(outputWav, "rw");
          raf.write(new byte[44]); // placeholder header, patched once the real size is known

          MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
          boolean sawInputEOS = false;
          boolean sawOutputEOS = false;
          long dataBytesWritten = 0;

          while (!sawOutputEOS) {
            if (!sawInputEOS) {
              int inIndex = codec.dequeueInputBuffer(10000);
              if (inIndex >= 0) {
                ByteBuffer inBuf = codec.getInputBuffer(inIndex);
                int sampleSize = inBuf != null ? extractor.readSampleData(inBuf, 0) : -1;
                if (sampleSize < 0) {
                  codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                  sawInputEOS = true;
                } else {
                  long pts = extractor.getSampleTime();
                  codec.queueInputBuffer(inIndex, 0, sampleSize, pts, 0);
                  extractor.advance();
                }
              }
            }

            int outIndex = codec.dequeueOutputBuffer(info, 10000);
            if (outIndex >= 0) {
              if (info.size > 0) {
                ByteBuffer outBuf = codec.getOutputBuffer(outIndex);
                if (outBuf != null) {
                  byte[] chunk = new byte[info.size];
                  outBuf.get(chunk);
                  raf.write(chunk);
                  dataBytesWritten += chunk.length;
                }
              }
              codec.releaseOutputBuffer(outIndex, false);
              if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                sawOutputEOS = true;
              }
            } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
              MediaFormat newFormat = codec.getOutputFormat();
              if (newFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                sampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
              }
              if (newFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                channelCount = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
              }
            }
          }

          patchWavHeader(raf, sampleRate, channelCount, dataBytesWritten);

          JSObject ret = new JSObject();
          ret.put("path", outputWav.getAbsolutePath());
          call.resolve(ret);
        } catch (Exception e) {
          call.reject("Failed to decode audio: " + e.getMessage(), e);
        } finally {
          if (raf != null) {
            try { raf.close(); } catch (Exception ignored) {}
          }
          if (codec != null) {
            try { codec.stop(); } catch (Exception ignored) {}
            try { codec.release(); } catch (Exception ignored) {}
          }
          if (extractor != null) {
            try { extractor.release(); } catch (Exception ignored) {}
          }
          if (tempInput != null) tempInput.delete();
        }
      }
    }).start();
  }

  // Extracted WAVs are handed to JS by path and read via Capacitor's file
  // bridge, so they can't be deleted right after resolve() — clear out
  // whatever a previous call left behind instead of ever deleting the
  // current call's own output.
  private void cleanupOldExtracts(Context ctx) {
    File cacheDir = ctx.getCacheDir();
    File[] files = cacheDir.listFiles();
    if (files == null) return;
    for (File f : files) {
      if (f.getName().startsWith("extracted_") && f.getName().endsWith(".wav")) {
        f.delete();
      }
    }
  }

  private void patchWavHeader(RandomAccessFile raf, int sampleRate, int channelCount, long dataSize) throws IOException {
    int bitsPerSample = 16;
    int blockAlign = channelCount * bitsPerSample / 8;
    int byteRate = sampleRate * blockAlign;

    ByteBuffer header = ByteBuffer.allocate(44);
    header.order(ByteOrder.LITTLE_ENDIAN);
    header.put("RIFF".getBytes());
    header.putInt((int) (36 + dataSize));
    header.put("WAVE".getBytes());
    header.put("fmt ".getBytes());
    header.putInt(16);
    header.putShort((short) 1); // PCM
    header.putShort((short) channelCount);
    header.putInt(sampleRate);
    header.putInt(byteRate);
    header.putShort((short) blockAlign);
    header.putShort((short) bitsPerSample);
    header.put("data".getBytes());
    header.putInt((int) dataSize);

    raf.seek(0);
    raf.write(header.array());
  }
}
