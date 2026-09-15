package app.vynox.media;

import android.media.*;
import app.vynox.model.Project;
import java.io.File;

/** Audio hardware is the playback clock; seeks restart at the requested sample. */
public final class PreviewPlayer implements AutoCloseable {

  private volatile boolean cancelled, ready;
  private volatile AudioTrack track;
  private volatile long startUs;
  private Thread thread;

  public interface Listener {
    void ready();
    void failed(String message);
    void completed();
  }

  public void play(
    Project p,
    File root,
    File cache,
    long time,
    Listener listener
  ) {
    startUs = time;
    cancelled = false;
    thread = new Thread(() -> {
      AudioTrack local = null;
      try (AudioMixer mixer = new AudioMixer(p, root, cache, () -> cancelled)) {
        if (cancelled) return;
        int size = Math.max(
          16384,
          AudioTrack.getMinBufferSize(
            AudioMixer.RATE,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
          )
        );
        local = new AudioTrack.Builder()
          .setAudioAttributes(
            new AudioAttributes.Builder()
              .setUsage(AudioAttributes.USAGE_MEDIA)
              .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
              .build()
          )
          .setAudioFormat(
            new AudioFormat.Builder()
              .setSampleRate(AudioMixer.RATE)
              .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
              .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
              .build()
          )
          .setBufferSizeInBytes(size)
          .setTransferMode(AudioTrack.MODE_STREAM)
          .build();
        track = local;
        long frame = (time * AudioMixer.RATE) / 1_000_000,
          total = (p.durationUs * AudioMixer.RATE) / 1_000_000;
        local.play();
        ready = true;
        listener.ready();
        while (!cancelled && frame < total) {
          int count = (int) Math.min(1024, total - frame);
          short[] pcm = mixer.mix(frame, count);
          int offset = 0;
          while (!cancelled && offset < pcm.length) {
            int n = local.write(
              pcm,
              offset,
              pcm.length - offset,
              AudioTrack.WRITE_BLOCKING
            );
            if (n < 0) throw new IllegalStateException(
              "Audio output error " + n
            );
            offset += n;
          }
          frame += count;
        }
        while (!cancelled && positionUs() < p.durationUs - 1000)
          Thread.sleep(10);
        if (!cancelled) listener.completed();
      } catch (Exception e) {
        if (!cancelled) listener.failed(e.getMessage());
      } finally {
        ready = false;
        track = null;
        if (local != null) {
          try {
            local.stop();
          } catch (Exception ignored) {}
          local.release();
        }
      }
    }, "vynox-audio");
    thread.start();
  }

  public long positionUs() {
    AudioTrack a = track;
    try {
      return (
        startUs +
        (a == null
          ? 0
          : ((a.getPlaybackHeadPosition() & 0xffffffffL) * 1_000_000L) /
            AudioMixer.RATE)
      );
    } catch (IllegalStateException e) {
      return startUs;
    }
  }

  public boolean ready() {
    return ready;
  }

  @Override
  public void close() {
    cancelled = true;
    ready = false;
    AudioTrack a = track;
    if (a != null) try {
      a.pause();
      a.flush();
    } catch (IllegalStateException ignored) {}
  }
}
