package app.vynox.render;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.*;
import app.vynox.format.ProjectJson;
import app.vynox.model.Project;
import app.vynox.storage.ProjectStore;
import java.io.*;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ExportService extends Service {

  private final AtomicBoolean cancelled = new AtomicBoolean();
  public static volatile boolean running;
  public static final String PREFS = "export";

  @Override
  public IBinder onBind(Intent i) {
    return null;
  }

  private void status(String text, int progress, boolean active) {
    getSharedPreferences(PREFS, 0)
      .edit()
      .putString("status", text)
      .putInt("progress", progress)
      .putBoolean("active", active)
      .apply();
  }

  private Notification notification(String text, int progress) {
    Intent cancel = new Intent(this, ExportService.class).setAction("cancel");
    PendingIntent action = PendingIntent.getService(
      this,
      1,
      cancel,
      PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
    );
    return new Notification.Builder(this, "exports")
      .setSmallIcon(app.vynox.R.drawable.ic_vynox)
      .setContentTitle("Vynox · rendering")
      .setContentText(text)
      .setProgress(100, progress, progress == 0)
      .setOngoing(true)
      .addAction(
        new Notification.Action.Builder(null, "Cancel", action).build()
      )
      .build();
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    if (intent == null) {
      stopSelf();
      return START_NOT_STICKY;
    }
    if ("cancel".equals(intent.getAction())) {
      cancelled.set(true);
      if (!running) stopSelf();
      return START_NOT_STICKY;
    }
    if (running) return START_NOT_STICKY;
    running = true;
    cancelled.set(false);
    NotificationManager nm = getSystemService(NotificationManager.class);
    nm.createNotificationChannel(
      new NotificationChannel(
        "exports",
        "Video exports",
        NotificationManager.IMPORTANCE_LOW
      )
    );
    startForeground(
      12,
      notification("Preparing local media", 0),
      Build.VERSION.SDK_INT >= 35
        ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        : ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
    );
    status("Preparing local media", 0, true);
    new Thread(() -> {
      File output = null;
      File snapshot = new File(getFilesDir(), "export-snapshot.json");
      Uri uri = Uri.parse(intent.getStringExtra("uri"));
      boolean published = false;
      try {
        Project p = ProjectJson.decode(
          new String(
            Files.readAllBytes(snapshot.toPath()),
            java.nio.charset.StandardCharsets.UTF_8
          )
        );
        VideoExporter.Options o = new VideoExporter.Options();
        o.width = intent.getIntExtra("width", p.width);
        o.height = intent.getIntExtra("height", p.height);
        o.fps = intent.getIntExtra("fps", p.fps);
        o.bitrate = intent.getIntExtra("bitrate", 8000000);
        output = File.createTempFile("vynox-export-", ".mp4", getCacheDir());
        VideoExporter.export(
          p,
          new ProjectStore(this).root(p.id),
          output,
          new File(getCacheDir(), "render"),
          o,
          cancelled::get,
          progress -> {
            status("Rendering MP4", progress, true);
            nm.notify(12, notification("Rendering MP4", progress));
          }
        );
        try (
          InputStream in = new FileInputStream(output);
          OutputStream out = getContentResolver().openOutputStream(uri, "wt")
        ) {
          if (out == null) throw new IOException("Cannot write destination");
          byte[] b = new byte[65536];
          int n;
          while ((n = in.read(b)) != -1) {
            if (cancelled.get()) throw new InterruptedIOException(
              "Export cancelled"
            );
            out.write(b, 0, n);
          }
        }
        published = true;
        status("Export complete · saved to selected location", 100, false);
      } catch (Exception e) {
        status(
          cancelled.get()
            ? "Export cancelled"
            : "Export failed: " + e.getMessage(),
          0,
          false
        );
      } finally {
        if (!published) try {
          android.provider.DocumentsContract.deleteDocument(
            getContentResolver(),
            uri
          );
        } catch (Exception ignored) {}
        if (output != null) output.delete();
        snapshot.delete();
        running = false;
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
      }
    }, "vynox-export").start();
    return START_NOT_STICKY;
  }

  @Override
  public void onTimeout(int startId, int fgsType) {
    cancelled.set(true);
    status("Export stopped: Android background time limit", 0, false);
    stopForeground(STOP_FOREGROUND_REMOVE);
    stopSelf();
  }

  @Override
  public void onDestroy() {
    cancelled.set(true);
    super.onDestroy();
  }
}
