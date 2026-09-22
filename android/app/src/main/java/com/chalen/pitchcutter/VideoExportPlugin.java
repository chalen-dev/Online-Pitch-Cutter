package com.chalen.pitchcutter;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Base64;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// Exports the trimmed clip + pitch-shifted audio into a real MP4, entirely
// through Android's own MediaExtractor/MediaCodec/MediaMuxer — no WebView
// <video> element, no canvas, no Surface anywhere in this class. That's what
// lets it keep running with the screen fully off, the same way
// AudioExtractorPlugin does for import.
//
// The big simplification that makes this tractable: this app's export never
// touches video pixels (see exportVideo/exportOneJobFallback in index.html —
// frames are drawn to canvas completely unmodified). So instead of a full
// decode+re-encode transcode of the video, this does a "stream copy": the
// compressed video packets are read straight out of the source file and
// written straight into the new container, untouched. Only the audio track
// is actually re-encoded, from the PCM the JS pitch-shifter already produced.
//
// The one real trade-off from stream-copying: compressed video can only be
// cut starting at a "sync sample" (keyframe), typically every 1-2s. So the
// requested trim start gets snapped backward to the nearest one — see
// prepareExport's actualStartSec. To keep audio and video in sync despite
// that, the caller (index.html) is expected to render its pitch-shifted audio
// starting from actualStartSec too, not the originally requested trim start.
// The trim end has no such restriction — the copy loop just stops once it
// passes it.
//
// Both the source video and the rendered PCM audio reach this plugin through
// beginUpload()/appendChunk() rather than one big base64 argument — passing
// a whole file as a single PluginCall argument means Capacitor serializes
// the entire thing into one JSON string on the Java heap. A few minutes of
// raw 16-bit PCM easily reaches tens of MB, and doing that in one shot was
// observed to throw a real OutOfMemoryError and crash the app outright
// (confirmed on-device: a 2:43 export's ~76MB base64 audio payload took the
// whole process down). Chunked upload keeps every single bridge call's
// payload down to a few MB regardless of clip length.
@CapacitorPlugin(name = "VideoExport")
public class VideoExportPlugin extends Plugin {

  private static class UploadSession {
    File file;
    OutputStream out;
  }

  // One entry per prepareExport() call still awaiting its matching
  // finishExport() — Capacitor keeps a plugin instance alive for the whole
  // bridge session, so an in-memory map is enough (nothing here needs to
  // survive a process death, same assumption AudioExtractorPlugin makes).
  private static class ExportSession {
    File videoFile;
    int videoTrackIndex;
    long actualStartUs;
  }

  private final Map<String, UploadSession> uploads = new ConcurrentHashMap<>();
  private final Map<String, ExportSession> sessions = new ConcurrentHashMap<>();

  @PluginMethod
  public void beginUpload(PluginCall call) {
    try {
      File f = File.createTempFile("videoexport_upload_", ".bin", getContext().getCacheDir());
      UploadSession session = new UploadSession();
      session.file = f;
      session.out = new BufferedOutputStream(new FileOutputStream(f));

      String uploadId = UUID.randomUUID().toString();
      uploads.put(uploadId, session);

      JSObject ret = new JSObject();
      ret.put("uploadId", uploadId);
      call.resolve(ret);
    } catch (IOException e) {
      call.reject("Could not start upload: " + e.getMessage(), e);
    }
  }

  @PluginMethod
  public void appendChunk(PluginCall call) {
    String uploadId = call.getString("uploadId");
    String chunkBase64 = call.getString("chunkBase64");
    if (uploadId == null || chunkBase64 == null) {
      call.reject("uploadId and chunkBase64 are required");
      return;
    }
    UploadSession session = uploads.get(uploadId);
    if (session == null) {
      call.reject("Unknown uploadId (already finished, or beginUpload was never called)");
      return;
    }
    try {
      session.out.write(Base64.decode(chunkBase64, Base64.DEFAULT));
      call.resolve();
    } catch (IOException e) {
      call.reject("Could not write chunk: " + e.getMessage(), e);
    }
  }

  @PluginMethod
  public void prepareExport(final PluginCall call) {
    final String uploadId = call.getString("uploadId");
    final Double trimStart = call.getDouble("trimStart");
    if (uploadId == null || trimStart == null) {
      call.reject("uploadId and trimStart are required");
      return;
    }
    final UploadSession upload = uploads.remove(uploadId);
    if (upload == null) {
      call.reject("Unknown uploadId (already finished, or beginUpload was never called)");
      return;
    }

    new Thread(new Runnable() {
      @Override
      public void run() {
        MediaExtractor extractor = null;
        try {
          try { upload.out.close(); } catch (IOException ignored) {}
          File videoFile = upload.file;
          cleanupOldExports(getContext(), videoFile);

          extractor = new MediaExtractor();
          extractor.setDataSource(videoFile.getAbsolutePath());

          int videoTrackIndex = -1;
          int trackCount = extractor.getTrackCount();
          for (int i = 0; i < trackCount; i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("video/")) {
              videoTrackIndex = i;
              break;
            }
          }
          if (videoTrackIndex == -1) {
            call.reject("No video track found in this file");
            videoFile.delete();
            return;
          }
          extractor.selectTrack(videoTrackIndex);

          long trimStartUs = Math.round(trimStart * 1_000_000.0);
          // SEEK_TO_PREVIOUS_SYNC: the only direction that can't lose footage —
          // landing on the sync sample *after* the requested point would cut
          // off content the user asked to keep.
          extractor.seekTo(trimStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
          long actualStartUs = extractor.getSampleTime();
          if (actualStartUs < 0) actualStartUs = 0; // defensive: seek failed to land anywhere usable

          ExportSession session = new ExportSession();
          session.videoFile = videoFile;
          session.videoTrackIndex = videoTrackIndex;
          session.actualStartUs = actualStartUs;

          String exportId = UUID.randomUUID().toString();
          sessions.put(exportId, session);

          JSObject ret = new JSObject();
          ret.put("exportId", exportId);
          ret.put("actualStartSec", actualStartUs / 1_000_000.0);
          call.resolve(ret);
        } catch (Exception e) {
          call.reject("Failed to prepare export: " + e.getMessage(), e);
        } finally {
          if (extractor != null) {
            try { extractor.release(); } catch (Exception ignored) {}
          }
        }
      }
    }).start();
  }

  @PluginMethod
  public void finishExport(final PluginCall call) {
    final String exportId = call.getString("exportId");
    final String audioUploadId = call.getString("audioUploadId");
    final Integer sampleRate = call.getInt("sampleRate");
    final Integer channelCount = call.getInt("channelCount");
    final Double trimEnd = call.getDouble("trimEnd");
    if (exportId == null || audioUploadId == null || sampleRate == null || channelCount == null || trimEnd == null) {
      call.reject("exportId, audioUploadId, sampleRate, channelCount and trimEnd are required");
      return;
    }
    final ExportSession session = sessions.remove(exportId);
    final UploadSession audioUpload = uploads.remove(audioUploadId);
    if (session == null) {
      call.reject("Unknown exportId (already finished, or prepareExport was never called)");
      return;
    }
    if (audioUpload == null) {
      call.reject("Unknown audioUploadId (already finished, or beginUpload was never called)");
      return;
    }

    new Thread(new Runnable() {
      @Override
      public void run() {
        MediaExtractor extractor = null;
        MediaMuxer muxer = null;
        MediaCodec audioEncoder = null;
        File outputFile = null;
        try {
          try { audioUpload.out.close(); } catch (IOException ignored) {}
          Context ctx = getContext();

          // ---- Phase 1: encode the pitch-shifted PCM to AAC, fully in memory.
          // Buffering the (small — this is compressed AAC, not raw PCM) encoded
          // audio up front, rather than interleaving it with the video copy
          // below, avoids having to juggle two producers writing into one
          // MediaMuxer, which can only start() once every track's format is known.
          audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
          MediaFormat audioInFormat = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount);
          audioInFormat.setInteger(MediaFormat.KEY_BIT_RATE, 192_000);
          audioInFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
          audioEncoder.configure(audioInFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
          audioEncoder.start();

          List<byte[]> encodedChunks = new ArrayList<>();
          List<MediaCodec.BufferInfo> encodedInfos = new ArrayList<>();
          MediaFormat audioOutFormat = encodeAudio(
            audioEncoder, audioUpload.file, sampleRate, channelCount, encodedChunks, encodedInfos);

          // ---- Phase 2: open the source video again fresh (prepareExport's
          // extractor was already released) and set up the muxer now that
          // both track formats are known.
          extractor = new MediaExtractor();
          extractor.setDataSource(session.videoFile.getAbsolutePath());
          extractor.selectTrack(session.videoTrackIndex);
          MediaFormat videoFormat = extractor.getTrackFormat(session.videoTrackIndex);

          outputFile = File.createTempFile("videoexport_out_", ".mp4", ctx.getCacheDir());
          muxer = new MediaMuxer(outputFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
          int muxVideoTrack = muxer.addTrack(videoFormat);
          int muxAudioTrack = muxer.addTrack(audioOutFormat);
          muxer.start();

          for (int i = 0; i < encodedChunks.size(); i++) {
            muxer.writeSampleData(muxAudioTrack, ByteBuffer.wrap(encodedChunks.get(i)), encodedInfos.get(i));
          }

          // ---- Phase 3: stream-copy the video packets from actualStartUs to
          // trimEnd, re-basing timestamps so the output starts at t=0.
          extractor.seekTo(session.actualStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
          long trimEndUs = Math.round(trimEnd * 1_000_000.0);
          long totalUs = Math.max(1, trimEndUs - session.actualStartUs);
          ByteBuffer packetBuf = ByteBuffer.allocate(2 * 1024 * 1024);
          MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
          long lastProgressEmitMs = 0;

          while (true) {
            long sampleTimeUs = extractor.getSampleTime();
            if (sampleTimeUs < 0 || sampleTimeUs > trimEndUs) break; // EOS or past the requested end

            packetBuf.clear();
            int size = extractor.readSampleData(packetBuf, 0);
            if (size < 0) break;

            info.offset = 0;
            info.size = size;
            info.presentationTimeUs = Math.max(0, sampleTimeUs - session.actualStartUs);
            info.flags = (extractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
              ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
            muxer.writeSampleData(muxVideoTrack, packetBuf, info);

            long nowMs = System.currentTimeMillis();
            if (nowMs - lastProgressEmitMs > 150) {
              lastProgressEmitMs = nowMs;
              double progress = Math.min(1.0, info.presentationTimeUs / (double) totalUs);
              JSObject data = new JSObject();
              data.put("exportId", exportId);
              data.put("progress", progress);
              notifyListeners("exportProgress", data);
            }

            if (!extractor.advance()) break;
          }

          muxer.stop();

          JSObject ret = new JSObject();
          ret.put("path", outputFile.getAbsolutePath());
          call.resolve(ret);
        } catch (Exception e) {
          call.reject("Failed to finish export: " + e.getMessage(), e);
        } finally {
          if (muxer != null) {
            try { muxer.release(); } catch (Exception ignored) {}
          }
          if (audioEncoder != null) {
            try { audioEncoder.stop(); } catch (Exception ignored) {}
            try { audioEncoder.release(); } catch (Exception ignored) {}
          }
          if (extractor != null) {
            try { extractor.release(); } catch (Exception ignored) {}
          }
          // The source video temp file and the uploaded raw-PCM temp file are
          // only needed for this one export pass — outputFile is deliberately
          // left behind; JS still has to read it (via Capacitor.convertFileSrc)
          // after this call resolves, and the next prepareExport()'s
          // cleanupOldExports() sweeps it away later.
          if (session.videoFile != null) session.videoFile.delete();
          if (audioUpload.file != null) audioUpload.file.delete();
        }
      }
    }).start();
  }

  // Feeds every PCM sample into the encoder and drains its output, blocking
  // until the encoder itself signals end-of-stream. Returns the encoder's
  // real output MediaFormat (only available once encoding has actually
  // started — encoders don't know their own container-level format, like
  // AAC's ADTS-adjacent csd-0, until they've processed at least one frame).
  // Reads the raw PCM from disk in bounded-size chunks rather than loading it
  // into one big byte[] — same reasoning as the chunked upload above.
  private MediaFormat encodeAudio(
      MediaCodec encoder, File pcmFile, int sampleRate, int channelCount,
      List<byte[]> outChunks, List<MediaCodec.BufferInfo> outInfos) throws IOException {
    int bytesPerFrame = channelCount * 2; // 16-bit PCM
    boolean sawInputEOS = false;
    boolean sawOutputEOS = false;
    MediaFormat outFormat = null;
    MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

    long totalFramesFed = 0;
    byte[] readBuf = new byte[64 * 1024];
    int readBufLen = 0;
    int readBufPos = 0;

    try (FileInputStream pcmIn = new FileInputStream(pcmFile)) {
      while (!sawOutputEOS) {
        if (!sawInputEOS) {
          int inIndex = encoder.dequeueInputBuffer(10_000);
          if (inIndex >= 0) {
            ByteBuffer inBuf = encoder.getInputBuffer(inIndex);
            int capacity = inBuf.capacity() - (inBuf.capacity() % bytesPerFrame);
            int written = 0;
            while (written < capacity) {
              if (readBufPos >= readBufLen) {
                readBufLen = pcmIn.read(readBuf);
                readBufPos = 0;
                if (readBufLen <= 0) break; // end of file
              }
              int toCopy = Math.min(capacity - written, readBufLen - readBufPos);
              inBuf.put(readBuf, readBufPos, toCopy);
              readBufPos += toCopy;
              written += toCopy;
            }

            if (written == 0) {
              encoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
              sawInputEOS = true;
            } else {
              long ptsUs = totalFramesFed * 1_000_000L / sampleRate;
              encoder.queueInputBuffer(inIndex, 0, written, ptsUs, 0);
              totalFramesFed += written / bytesPerFrame;
            }
          }
        }

        int outIndex = encoder.dequeueOutputBuffer(info, 10_000);
        if (outIndex >= 0) {
          if (info.size > 0) {
            ByteBuffer outBuf = encoder.getOutputBuffer(outIndex);
            byte[] chunk = new byte[info.size];
            outBuf.get(chunk);
            MediaCodec.BufferInfo copy = new MediaCodec.BufferInfo();
            copy.set(0, chunk.length, info.presentationTimeUs, info.flags);
            outChunks.add(chunk);
            outInfos.add(copy);
          }
          encoder.releaseOutputBuffer(outIndex, false);
          if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
            sawOutputEOS = true;
          }
        } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
          outFormat = encoder.getOutputFormat();
        }
      }
    }
    return outFormat;
  }

  // Mirrors AudioExtractorPlugin.cleanupOldExtracts — this plugin's leftover
  // temp files (uploads, source copies, and finished MP4s from a previous run
  // that JS already finished reading) are only ever cleared out lazily, on
  // the next export's prepare call. keep is the just-closed upload file that
  // is about to become this session's videoFile, so it must survive the sweep.
  private void cleanupOldExports(Context ctx, File keep) {
    File cacheDir = ctx.getCacheDir();
    File[] files = cacheDir.listFiles();
    if (files == null) return;
    for (File f : files) {
      if (f.equals(keep)) continue;
      String name = f.getName();
      if (name.startsWith("videoexport_src_") || name.startsWith("videoexport_out_") || name.startsWith("videoexport_upload_")) {
        f.delete();
      }
    }
  }
}
