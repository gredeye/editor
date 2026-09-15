package app.vynox.render;

import android.graphics.*;
import android.media.*;
import app.vynox.engine.Timeline;
import app.vynox.media.AudioMixer;
import app.vynox.model.Project;
import java.io.*;
import java.nio.*;
import java.util.*;
import java.util.function.*;

/** Encodes actual composition frames using platform AVC and mixes audio to AAC. */
public final class VideoExporter {

  public static final class Options {

    public int width = 1280,
      height = 720,
      fps = 30,
      bitrate = 8_000_000;
  }

  public static void export(
    Project p,
    File root,
    File output,
    File scratch,
    Options o,
    BooleanSupplier cancelled,
    IntConsumer progress
  ) throws Exception {
    if (
      o.width % 2 != 0 ||
      o.height % 2 != 0 ||
      o.width < 16 ||
      o.height < 16 ||
      o.width > 3840 ||
      o.height > 3840 ||
      o.fps < 1 ||
      o.fps > 60
    ) throw new IllegalArgumentException(
      "Export requires even dimensions and 1–60 fps"
    );
    if (
      !app.vynox.media.AssetManager.missing(p, root).isEmpty()
    ) throw new IOException("Relink missing assets before export");
    scratch.mkdirs();
    File video = File.createTempFile("video-", ".mp4", scratch),
      audio = File.createTempFile("audio-", ".m4a", scratch);
    boolean success = false;
    try (
      AudioMixer mixer = new AudioMixer(p, root, scratch, cancelled);
      CompositionRenderer renderer = new CompositionRenderer(root)
    ) {
      progress.accept(5);
      encodeVideo(p, renderer, video, o, cancelled, v ->
        progress.accept(5 + (v * 75) / 100)
      );
      boolean sound = mixer.hasAudio();
      if (sound) encodeAudio(p, mixer, audio, cancelled, v ->
        progress.accept(80 + (v * 15) / 100)
      );
      remux(video, sound ? audio : null, output, cancelled);
      success = true;
      progress.accept(100);
    } finally {
      video.delete();
      audio.delete();
      if (!success) output.delete();
    }
  }

  private static final class Encoder implements AutoCloseable {

    final MediaCodec codec;
    final MediaMuxer muxer;
    int track = -1;
    boolean started, eos;

    Encoder(MediaFormat format, File output) throws IOException {
      codec = MediaCodec.createEncoderByType(
        format.getString(MediaFormat.KEY_MIME)
      );
      MediaMuxer m = null;
      try {
        m = new MediaMuxer(
          output.getPath(),
          MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
        );
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        codec.start();
      } catch (Exception e) {
        codec.release();
        if (m != null) m.release();
        throw e;
      }
      muxer = m;
    }

    void drain() throws IOException {
      MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
      while (true) {
        int index = codec.dequeueOutputBuffer(info, 10000);
        if (index == MediaCodec.INFO_TRY_AGAIN_LATER) return;
        if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
          if (started) throw new IOException("Encoder format changed twice");
          track = muxer.addTrack(codec.getOutputFormat());
          muxer.start();
          started = true;
        } else if (index >= 0) {
          ByteBuffer b = codec.getOutputBuffer(index);
          if (
            (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
          ) info.size = 0;
          if (info.size > 0) {
            if (!started) throw new IOException("Encoder missing format");
            b.position(info.offset);
            b.limit(info.offset + info.size);
            muxer.writeSampleData(track, b, info);
          }
          eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
          codec.releaseOutputBuffer(index, false);
          if (eos) return;
        }
      }
    }

    void finish(BooleanSupplier cancelled) throws Exception {
      long deadline = System.nanoTime() + 30_000_000_000L;
      while (!eos) {
        check(cancelled);
        drain();
        if (System.nanoTime() > deadline) throw new IOException(
          "Encoder drain timeout"
        );
      }
    }

    @Override
    public void close() {
      try {
        codec.stop();
      } finally {
        codec.release();
        try {
          if (started) muxer.stop();
        } finally {
          muxer.release();
        }
      }
    }
  }

  private static void encodeVideo(
    Project p,
    CompositionRenderer renderer,
    File file,
    Options o,
    BooleanSupplier cancel,
    IntConsumer progress
  ) throws Exception {
    MediaFormat format = MediaFormat.createVideoFormat(
      MediaFormat.MIMETYPE_VIDEO_AVC,
      o.width,
      o.height
    );
    format.setInteger(
      MediaFormat.KEY_COLOR_FORMAT,
      MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
    );
    format.setInteger(MediaFormat.KEY_BIT_RATE, o.bitrate);
    format.setInteger(MediaFormat.KEY_FRAME_RATE, o.fps);
    format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
    long frames = (p.durationUs * o.fps + 999999) / 1_000_000;
    try (Encoder e = new Encoder(format, file)) {
      long frame = 0;
      long last = System.nanoTime();
      while (frame <= frames) {
        check(cancel);
        e.drain();
        int index = e.codec.dequeueInputBuffer(10000);
        if (index < 0) {
          if (System.nanoTime() - last > 30_000_000_000L) throw new IOException(
            "Video encoder stalled"
          );
          continue;
        }
        last = System.nanoTime();
        long time = Timeline.frameTime(frame, o.fps);
        if (frame == frames) e.codec.queueInputBuffer(
          index,
          0,
          0,
          time,
          MediaCodec.BUFFER_FLAG_END_OF_STREAM
        );
        else {
          Bitmap bitmap = renderer.frame(p, time, o.width, o.height);
          try {
            Image image = e.codec.getInputImage(index);
            if (image == null) throw new IOException(
              "Device encoder does not support flexible YUV input"
            );
            try {
              toYuv(bitmap, image);
            } finally {
              image.close();
            }
            e.codec.queueInputBuffer(
              index,
              0,
              (o.width * o.height * 3) / 2,
              time,
              0
            );
          } finally {
            bitmap.recycle();
          }
          progress.accept((int) ((frame * 100) / frames));
        }
        frame++;
      }
      e.finish(cancel);
    }
  }

  private static int clamp(int v) {
    return Math.max(0, Math.min(255, v));
  }

  static void toYuv(Bitmap bitmap, Image image) {
    int w = bitmap.getWidth(),
      h = bitmap.getHeight();
    int[] pixels = new int[w * h];
    bitmap.getPixels(pixels, 0, w, 0, 0, w, h);
    Image.Plane[] planes = image.getPlanes();
    for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
      int c = pixels[y * w + x],
        r = Color.red(c),
        g = Color.green(c),
        b = Color.blue(c);
      put(
        planes[0],
        x,
        y,
        clamp(((66 * r + 129 * g + 25 * b + 128) >> 8) + 16)
      );
      if ((x & 1) == 0 && (y & 1) == 0) {
        int rr = 0,
          gg = 0,
          bb = 0;
        for (int dy = 0; dy < 2; dy++) for (int dx = 0; dx < 2; dx++) {
          int v = pixels[(y + dy) * w + x + dx];
          rr += Color.red(v);
          gg += Color.green(v);
          bb += Color.blue(v);
        }
        r = rr / 4;
        g = gg / 4;
        b = bb / 4;
        put(
          planes[1],
          x / 2,
          y / 2,
          clamp(((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128)
        );
        put(
          planes[2],
          x / 2,
          y / 2,
          clamp(((112 * r - 94 * g - 18 * b + 128) >> 8) + 128)
        );
      }
    }
  }

  private static void put(Image.Plane p, int x, int y, int value) {
    ByteBuffer b = p.getBuffer();
    b.put(
      b.position() + y * p.getRowStride() + x * p.getPixelStride(),
      (byte) value
    );
  }

  private static void encodeAudio(
    Project p,
    AudioMixer mixer,
    File file,
    BooleanSupplier cancel,
    IntConsumer progress
  ) throws Exception {
    MediaFormat format = MediaFormat.createAudioFormat(
      MediaFormat.MIMETYPE_AUDIO_AAC,
      AudioMixer.RATE,
      2
    );
    format.setInteger(
      MediaFormat.KEY_AAC_PROFILE,
      MediaCodecInfo.CodecProfileLevel.AACObjectLC
    );
    format.setInteger(MediaFormat.KEY_BIT_RATE, 192000);
    format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
    long total = (p.durationUs * AudioMixer.RATE + 999999) / 1_000_000,
      frame = 0,
      last = System.nanoTime();
    try (Encoder e = new Encoder(format, file)) {
      boolean done = false;
      while (!done) {
        check(cancel);
        e.drain();
        int index = e.codec.dequeueInputBuffer(10000);
        if (index < 0) {
          if (System.nanoTime() - last > 30_000_000_000L) throw new IOException(
            "Audio encoder stalled"
          );
          continue;
        }
        last = System.nanoTime();
        ByteBuffer b = e.codec.getInputBuffer(index);
        b.clear();
        b.order(ByteOrder.LITTLE_ENDIAN);
        int count = (int) Math.min(
          Math.min(1024, b.remaining() / 4),
          total - frame
        );
        if (count == 0) {
          e.codec.queueInputBuffer(
            index,
            0,
            0,
            (frame * 1_000_000L) / AudioMixer.RATE,
            MediaCodec.BUFFER_FLAG_END_OF_STREAM
          );
          done = true;
        } else {
          short[] pcm = mixer.mix(frame, count);
          for (short s : pcm) b.putShort(s);
          e.codec.queueInputBuffer(
            index,
            0,
            count * 4,
            (frame * 1_000_000L) / AudioMixer.RATE,
            0
          );
          frame += count;
          progress.accept((int) ((frame * 100) / total));
        }
      }
      e.finish(cancel);
    }
  }

  private static void remux(
    File video,
    File audio,
    File output,
    BooleanSupplier cancel
  ) throws Exception {
    MediaMuxer mux = new MediaMuxer(
      output.getPath(),
      MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
    );
    List<MediaExtractor> extractors = new ArrayList<>();
    List<Integer> tracks = new ArrayList<>();
    boolean started = false;
    try {
      for (File f : audio == null
        ? new File[] { video }
        : new File[] { video, audio }) {
        MediaExtractor x = new MediaExtractor();
        extractors.add(x);
        x.setDataSource(f.getPath());
        x.selectTrack(0);
        tracks.add(mux.addTrack(x.getTrackFormat(0)));
      }
      mux.start();
      started = true;
      ByteBuffer buffer = ByteBuffer.allocateDirect(16 * 1024 * 1024);
      MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
      while (true) {
        check(cancel);
        int best = -1;
        long time = Long.MAX_VALUE;
        for (int i = 0; i < extractors.size(); i++) {
          long t = extractors.get(i).getSampleTime();
          if (t >= 0 && t < time) {
            best = i;
            time = t;
          }
        }
        if (best < 0) break;
        MediaExtractor x = extractors.get(best);
        buffer.clear();
        int n = x.readSampleData(buffer, 0);
        if (n < 0) break;
        if (
          (x.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_ENCRYPTED) != 0
        ) throw new IOException("Encrypted output cannot be muxed");
        int flags =
          (x.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
            ? MediaCodec.BUFFER_FLAG_KEY_FRAME
            : 0;
        info.set(0, n, time, flags);
        mux.writeSampleData(tracks.get(best), buffer, info);
        x.advance();
      }
    } finally {
      for (MediaExtractor x : extractors) x.release();
      try {
        if (started) mux.stop();
      } finally {
        mux.release();
      }
    }
  }

  private static void check(BooleanSupplier c) throws InterruptedIOException {
    if (c.getAsBoolean()) throw new InterruptedIOException("Export cancelled");
  }

  private VideoExporter() {}
}
