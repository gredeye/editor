package app.vynox.media;

import android.media.*;
import app.vynox.format.VnxArchive;
import app.vynox.model.Project;
import java.io.*;
import java.nio.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Disk-backed PCM decoding and sample-accurate stereo mixing at 48 kHz. */
public final class AudioMixer implements AutoCloseable {

  public static final int RATE = 48000;

  private static final class Source {

    File file;
    RandomAccessFile pcm;
    int rate, channels;
    long frames;
    byte[] cache = new byte[65536];
    long cacheStart = -1;
    int cacheSize;

    float sample(long frame, int channel) throws IOException {
      if (frame < 0 || frame >= frames) return 0;
      long offset = (frame * channels + Math.min(channel, channels - 1)) * 2;
      if (
        cacheStart < 0 ||
        offset < cacheStart ||
        offset + 2 > cacheStart + cacheSize
      ) {
        cacheStart = offset;
        pcm.seek(offset);
        cacheSize = pcm.read(cache);
      }
      int i = (int) (offset - cacheStart);
      return (short) ((cache[i] & 255) | (cache[i + 1] << 8)) / 32768f;
    }
  }

  private final Project project;
  private final Map<String, Source> sources = new HashMap<>();
  private final File cache;

  public AudioMixer(
    Project project,
    File root,
    File cache,
    BooleanSupplier cancel
  ) throws Exception {
    this.project = project;
    this.cache = cache;
    cache.mkdirs();
    try {
      for (Project.Layer l : project.layers)
        if (
          (l.type == Project.Type.AUDIO || l.type == Project.Type.VIDEO) &&
          !sources.containsKey(l.assetId)
        ) {
          Project.Asset a = project.assets.get(l.assetId);
          if (a == null) throw new IOException("Missing audio asset");
          Source s = decode(VnxArchive.resolve(root, a.file), cancel);
          sources.put(l.assetId, s);
        }
    } catch (Exception e) {
      close();
      throw e;
    }
  }

  public boolean hasAudio() {
    for (Source s : sources.values()) if (s != null) return true;
    return false;
  }

  private Source decode(File input, BooleanSupplier cancel) throws Exception {
    MediaExtractor extractor = new MediaExtractor();
    MediaCodec codec = null;
    File raw = File.createTempFile("pcm-", ".raw", cache);
    boolean ok = false;
    try {
      extractor.setDataSource(input.getPath());
      int track = -1;
      for (int i = 0; i < extractor.getTrackCount(); i++) if (
        extractor
          .getTrackFormat(i)
          .getString(MediaFormat.KEY_MIME)
          .startsWith("audio/")
      ) {
        track = i;
        break;
      }
      if (track < 0) return null;
      extractor.selectTrack(track);
      MediaFormat format = extractor.getTrackFormat(track);
      format.setInteger(
        MediaFormat.KEY_PCM_ENCODING,
        AudioFormat.ENCODING_PCM_16BIT
      );
      codec = MediaCodec.createDecoderByType(
        format.getString(MediaFormat.KEY_MIME)
      );
      codec.configure(format, null, null, 0);
      codec.start();
      Source source = new Source();
      source.file = raw;
      source.rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
      source.channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
      boolean inputDone = false,
        outputDone = false;
      MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
      long bytes = 0;
      try (FileOutputStream out = new FileOutputStream(raw)) {
        while (!outputDone) {
          if (cancel.getAsBoolean()) throw new InterruptedIOException(
            "Cancelled"
          );
          if (!inputDone) {
            int n = codec.dequeueInputBuffer(10000);
            if (n >= 0) {
              ByteBuffer b = codec.getInputBuffer(n);
              int size = extractor.readSampleData(b, 0);
              if (size < 0) {
                codec.queueInputBuffer(
                  n,
                  0,
                  0,
                  0,
                  MediaCodec.BUFFER_FLAG_END_OF_STREAM
                );
                inputDone = true;
              } else {
                codec.queueInputBuffer(
                  n,
                  0,
                  size,
                  Math.max(0, extractor.getSampleTime()),
                  0
                );
                extractor.advance();
              }
            }
          }
          int n = codec.dequeueOutputBuffer(info, 10000);
          if (n == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            MediaFormat f = codec.getOutputFormat();
            source.rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            source.channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            if (
              f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
              f.getInteger(MediaFormat.KEY_PCM_ENCODING) !=
                AudioFormat.ENCODING_PCM_16BIT
            ) throw new IOException("Decoder does not support 16-bit PCM");
          } else if (n >= 0) {
            ByteBuffer b = codec.getOutputBuffer(n);
            b.position(info.offset);
            b.limit(info.offset + info.size);
            byte[] data = new byte[info.size];
            b.get(data);
            out.write(data);
            bytes += data.length;
            if (bytes > 2L * 1024 * 1024 * 1024) throw new IOException(
              "Decoded audio exceeds 2 GB"
            );
            outputDone =
              (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
            codec.releaseOutputBuffer(n, false);
          }
        }
      }
      source.frames = bytes / (source.channels * 2L);
      source.pcm = new RandomAccessFile(raw, "r");
      ok = true;
      return source;
    } finally {
      extractor.release();
      if (codec != null) {
        try {
          codec.stop();
        } catch (Exception ignored) {}
        codec.release();
      }
      if (!ok) raw.delete();
    }
  }

  public short[] mix(long firstFrame, int count) throws IOException {
    float[] mix = new float[count * 2];
    for (Project.Layer l : project.layers) {
      if (
        l.type != Project.Type.AUDIO && l.type != Project.Type.VIDEO
      ) continue;
      Source s = sources.get(l.assetId);
      if (s == null || l.muted || !l.visible) continue;
      for (int i = 0; i < count; i++) {
        long time = ((firstFrame + i) * 1_000_000L) / RATE;
        if (time < l.startUs || time >= l.endUs) continue;
        double pos = ((l.sourceInUs + time - l.startUs) * s.rate) / 1_000_000.0;
        long frame = (long) pos;
        float frac = (float) (pos - frame),
          volume = (float) Math.max(
            0,
            Math.min(4, l.at("volume", time - l.startUs))
          );
        for (int ch = 0; ch < 2; ch++) {
          float a = s.sample(frame, ch),
            b = s.sample(frame + 1, ch);
          mix[i * 2 + ch] += (a + (b - a) * frac) * volume;
        }
      }
    }
    short[] out = new short[mix.length];
    for (int i = 0; i < out.length; i++) out[i] = (short) (Math.max(
      -1,
      Math.min(1, mix[i])
    ) * 32767);
    return out;
  }

  @Override
  public void close() {
    for (Source s : sources.values())
      if (s != null) {
        try {
          s.pcm.close();
        } catch (IOException ignored) {}
        s.file.delete();
      }
    sources.clear();
  }
}
