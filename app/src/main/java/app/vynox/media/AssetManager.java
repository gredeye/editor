package app.vynox.media;

import android.content.*;
import android.database.Cursor;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.OpenableColumns;
import app.vynox.format.VnxArchive;
import app.vynox.model.Project;
import java.io.*;
import java.util.*;

public final class AssetManager {

  public static Project.Asset importLocal(
    Context context,
    Uri uri,
    File root,
    String preserveId
  ) throws Exception {
    Project.Asset a = new Project.Asset();
    if (preserveId != null) a.id = preserveId;
    a.mime = context.getContentResolver().getType(uri);
    if (a.mime == null) {
      String extension = android.webkit.MimeTypeMap.getFileExtensionFromUrl(
        uri.toString()
      );
      a.mime =
        android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(
          extension.toLowerCase(java.util.Locale.ROOT)
        );
    }
    if (
      a.mime == null ||
      !(
        a.mime.startsWith("image/") ||
        a.mime.startsWith("video/") ||
        a.mime.startsWith("audio/")
      )
    ) throw new IOException("Choose a supported image, video or audio file");
    if ("content".equals(uri.getScheme())) try (
      Cursor c = context
        .getContentResolver()
        .query(
          uri,
          new String[] { OpenableColumns.DISPLAY_NAME },
          null,
          null,
          null
        )
    ) {
      if (c != null && c.moveToFirst()) a.name = c.getString(0);
    }
    if (a.name.isEmpty()) a.name =
      uri.getLastPathSegment() == null ? "Media" : uri.getLastPathSegment();
    // Immutable imports: relink never overwrites bytes referenced by undo snapshots.
    a.file = "media/" + UUID.randomUUID() + ".asset";
    File file = VnxArchive.resolve(root, a.file);
    file.getParentFile().mkdirs();
    try (
      InputStream in = context.getContentResolver().openInputStream(uri);
      OutputStream out = new FileOutputStream(file)
    ) {
      if (in == null) throw new IOException("Cannot read media");
      byte[] b = new byte[65536];
      int n;
      long size = 0;
      while ((n = in.read(b)) != -1) {
        size += n;
        if (size > 2L * 1024 * 1024 * 1024) throw new IOException(
          "Media exceeds 2 GB limit"
        );
        out.write(b, 0, n);
      }
    } catch (Exception e) {
      file.delete();
      throw e;
    }
    if (a.mime.startsWith("image/")) {
      BitmapFactory.Options o = new BitmapFactory.Options();
      o.inJustDecodeBounds = true;
      BitmapFactory.decodeFile(file.getPath(), o);
      if (o.outWidth <= 0) {
        file.delete();
        throw new IOException("Unsupported image");
      }
      a.width = o.outWidth;
      a.height = o.outHeight;
    } else {
      try (MediaMetadataRetriever r = new MediaMetadataRetriever()) {
        r.setDataSource(file.getPath());
        String d = r.extractMetadata(
          MediaMetadataRetriever.METADATA_KEY_DURATION
        );
        a.durationUs = d == null ? 0 : Long.parseLong(d) * 1000;
        String w = r.extractMetadata(
            MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
          ),
          h = r.extractMetadata(
            MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
          );
        a.width = w == null ? 0 : Integer.parseInt(w);
        a.height = h == null ? 0 : Integer.parseInt(h);
        String rotation = r.extractMetadata(
          MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION
        );
        if ("90".equals(rotation) || "270".equals(rotation)) {
          int swap = a.width;
          a.width = a.height;
          a.height = swap;
        }
      } catch (Exception e) {
        file.delete();
        throw new IOException("Unsupported media", e);
      }
    }
    return a;
  }

  public static List<Project.Asset> missing(Project p, File root) {
    List<Project.Asset> out = new ArrayList<>();
    for (Project.Asset a : p.assets.values()) {
      try {
        if (
          a.file.isEmpty() || !VnxArchive.resolve(root, a.file).isFile()
        ) out.add(a);
      } catch (IOException e) {
        out.add(a);
      }
    }
    return out;
  }

  private AssetManager() {}
}
