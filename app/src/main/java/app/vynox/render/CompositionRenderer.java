package app.vynox.render;

import android.graphics.*;
import android.media.MediaMetadataRetriever;
import app.vynox.format.VnxArchive;
import app.vynox.model.Project;
import java.io.*;
import java.util.*;

/** Deterministic frame compositor. No screen capture, networking or UI dependency. */
public final class CompositionRenderer implements AutoCloseable {

  private final File root;
  private final Map<String, MediaMetadataRetriever> videos = new HashMap<>();
  private final LinkedHashMap<String, Bitmap> images = new LinkedHashMap<>();

  public CompositionRenderer(File root) {
    this.root = root;
  }

  public Bitmap frame(Project p, long time, int width, int height)
    throws Exception {
    Bitmap result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(result);
    canvas.drawColor(p.background);
    canvas.scale((float) width / p.width, (float) height / p.height);
    try {
      for (Project.Layer l : p.layers)
        if (
          l.active(time) &&
          l.type != Project.Type.AUDIO &&
          l.type != Project.Type.NULL
        ) {
          long t = time - l.startUs;
          int w = bounded(l.at("width", t)),
            h = bounded(l.at("height", t));
          Bitmap layer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
          try {
            Canvas c = new Canvas(layer);
            Paint paint = new Paint(3);
            paint.setColor(l.color);
            switch (l.type) {
              case VIDEO:
              case IMAGE:
                drawMedia(c, paint, p, l, time, w, h);
                break;
              case TEXT:
                drawText(c, paint, l, t, w, h);
                break;
              case SHAPE:
                drawShape(c, paint, l, t, w, h);
                break;
              default:
                break;
            }
            Effects.apply(layer, l, t);
            if (l.mask != null) mask(layer, l.mask, t);
            canvas.save();
            float alpha = applyParents(canvas, p, l, time, new HashSet<>());
            transform(canvas, l, t);
            Paint composite = new Paint(3);
            composite.setAlpha(
              (int) (255 * Math.max(0, Math.min(1, l.at("opacity", t) * alpha)))
            );
            composite.setBlendMode(blend(l.blend));
            canvas.drawBitmap(layer, -w / 2f, -h / 2f, composite);
            canvas.restore();
          } finally {
            layer.recycle();
          }
        }
      return result;
    } catch (Exception e) {
      result.recycle();
      throw e;
    }
  }

  private int bounded(double n) {
    return (int) Math.max(1, Math.min(4096, n));
  }

  private float applyParents(
    Canvas c,
    Project p,
    Project.Layer l,
    long time,
    Set<String> seen
  ) {
    if (l.parentId.isEmpty()) return 1;
    Project.Layer parent = p.layer(l.parentId);
    if (parent == null || !seen.add(parent.id)) return 1;
    float a = applyParents(c, p, parent, time, seen);
    transform(c, parent, time - parent.startUs);
    return parent.active(time)
      ? a * (float) parent.at("opacity", time - parent.startUs)
      : 0;
  }

  private void transform(Canvas c, Project.Layer l, long t) {
    c.translate((float) l.at("x", t), (float) l.at("y", t));
    c.rotate((float) l.at("rotation", t));
    c.scale((float) l.at("scaleX", t), (float) l.at("scaleY", t));
    c.translate(-((float) l.at("anchorX", t)), -((float) l.at("anchorY", t)));
  }

  private BlendMode blend(Project.Blend b) {
    switch (b) {
      case MULTIPLY:
        return BlendMode.MULTIPLY;
      case SCREEN:
        return BlendMode.SCREEN;
      case OVERLAY:
        return BlendMode.OVERLAY;
      case ADD:
        return BlendMode.PLUS;
      case LIGHTEN:
        return BlendMode.LIGHTEN;
      case DARKEN:
        return BlendMode.DARKEN;
      default:
        return BlendMode.SRC_OVER;
    }
  }

  private void drawMedia(
    Canvas c,
    Paint paint,
    Project p,
    Project.Layer l,
    long time,
    int w,
    int h
  ) throws Exception {
    Project.Asset a = p.assets.get(l.assetId);
    if (a == null) throw new IOException("Missing asset for " + l.name);
    File f = VnxArchive.resolve(root, a.file);
    if (!f.isFile()) throw new IOException("Relink missing media: " + a.name);
    Bitmap b;
    boolean recycle = false;
    if (l.type == Project.Type.IMAGE) {
      b = images.get(a.file);
      if (b == null) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        o.inSampleSize = 1;
        while (
            o.outWidth / o.inSampleSize > 4096 ||
            o.outHeight / o.inSampleSize > 4096
          )
          o.inSampleSize *= 2;
        o.inJustDecodeBounds = false;
        b = BitmapFactory.decodeFile(f.getPath(), o);
        if (b == null) throw new IOException("Cannot decode " + a.name);
        images.put(a.file, b);
        if (images.size() > 4) {
          String key = images.keySet().iterator().next();
          images.remove(key).recycle();
        }
      }
    } else {
      MediaMetadataRetriever r = videos.get(a.file);
      if (r == null) {
        r = new MediaMetadataRetriever();
        r.setDataSource(f.getPath());
        videos.put(a.file, r);
      }
      long source = l.sourceInUs + time - l.startUs;
      b = r.getScaledFrameAtTime(
        source,
        MediaMetadataRetriever.OPTION_CLOSEST,
        w,
        h
      );
      recycle = true;
    }
    if (b == null) throw new IOException("No video frame: " + a.name);
    c.drawBitmap(b, null, new Rect(0, 0, w, h), paint);
    if (recycle) b.recycle();
  }

  private void drawText(
    Canvas c,
    Paint p,
    Project.Layer l,
    long t,
    int w,
    int h
  ) {
    p.setTypeface(Typeface.create(l.font, l.fontStyle));
    p.setTextSize((float) Math.max(1, l.at("fontSize", t)));
    p.setTextAlign(
      "LEFT".equals(l.align)
        ? Paint.Align.LEFT
        : "RIGHT".equals(l.align)
          ? Paint.Align.RIGHT
          : Paint.Align.CENTER
    );
    String[] lines = l.text.split("\n", -1);
    float line = p.getTextSize() * (float) l.at("lineSpacing", t),
      y =
        h / 2f -
        ((lines.length - 1) * line) / 2 -
        (p.ascent() + p.descent()) / 2;
    float spacing = (float) l.at("letterSpacing", t) * p.getTextSize();
    for (String s : lines) {
      float x =
        p.getTextAlign() == Paint.Align.LEFT
          ? 0
          : p.getTextAlign() == Paint.Align.RIGHT
            ? w
            : w / 2f;
      if (spacing == 0) c.drawText(s, x, y, p);
      else {
        float len =
          p.measureText(s) +
          Math.max(0, s.codePointCount(0, s.length()) - 1) * spacing;
        Paint.Align align = p.getTextAlign();
        if (align == Paint.Align.CENTER) x -= len / 2;
        else if (align == Paint.Align.RIGHT) x -= len;
        p.setTextAlign(Paint.Align.LEFT);
        for (int i = 0; i < s.length(); ) {
          int cp = s.codePointAt(i);
          String ch = new String(Character.toChars(cp));
          c.drawText(ch, x, y, p);
          x += p.measureText(ch) + spacing;
          i += Character.charCount(cp);
        }
        p.setTextAlign(align);
      }
      y += line;
    }
  }

  private void drawShape(
    Canvas c,
    Paint p,
    Project.Layer l,
    long t,
    int w,
    int h
  ) {
    Path path = new Path();
    float sw = (float) Math.max(0, l.at("strokeWidth", t)),
      in = sw / 2;
    RectF rect = new RectF(in, in, w - in, h - in);
    switch (l.shape) {
      case "ELLIPSE":
        path.addOval(rect, Path.Direction.CW);
        break;
      case "ROUNDED":
        float r = (float) l.at("radius", t);
        path.addRoundRect(rect, r, r, Path.Direction.CW);
        break;
      case "LINE":
        path.moveTo(0, h / 2f);
        path.lineTo(w, h / 2f);
        break;
      case "POLYGON":
        for (int i = 0; i < l.sides; i++) {
          double a = -Math.PI / 2 + (i * 2 * Math.PI) / l.sides;
          float x = w / 2f + (w / 2f - in) * (float) Math.cos(a),
            y = h / 2f + (h / 2f - in) * (float) Math.sin(a);
          if (i == 0) path.moveTo(x, y);
          else path.lineTo(x, y);
        }
        path.close();
        break;
      default:
        path.addRect(rect, Path.Direction.CW);
    }
    if (!"LINE".equals(l.shape)) {
      p.setStyle(Paint.Style.FILL);
      c.drawPath(path, p);
    }
    if (sw > 0 || "LINE".equals(l.shape)) {
      p.setStyle(Paint.Style.STROKE);
      p.setStrokeWidth(Math.max(1, sw));
      p.setColor(l.strokeColor);
      c.drawPath(path, p);
    }
  }

  private void mask(Bitmap b, Project.Mask m, long t) {
    int w = b.getWidth(),
      h = b.getHeight();
    Bitmap alpha = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
    Canvas c = new Canvas(alpha);
    Paint p = new Paint(3);
    float opacity = (float) Math.max(0, Math.min(1, m.at("opacity", t)));
    if (m.invert) c.drawColor(Color.argb((int) (opacity * 255), 255, 255, 255));
    p.setColor(Color.WHITE);
    p.setAlpha((int) (255 * opacity));
    if (m.invert) p.setBlendMode(BlendMode.CLEAR);
    float x = w / 2f + (float) m.at("x", t),
      y = h / 2f + (float) m.at("y", t),
      mw = (float) m.at("width", t),
      mh = (float) m.at("height", t);
    RectF r = new RectF(x - mw / 2, y - mh / 2, x + mw / 2, y + mh / 2);
    if ("ELLIPSE".equals(m.kind)) c.drawOval(r, p);
    else c.drawRect(r, p);
    Effects.blur(alpha, (int) Math.max(0, Math.min(64, m.at("feather", t))));
    p = new Paint(3);
    p.setBlendMode(BlendMode.DST_IN);
    new Canvas(b).drawBitmap(alpha, 0, 0, p);
    alpha.recycle();
  }

  @Override
  public void close() {
    for (MediaMetadataRetriever r : videos.values())
      try {
        r.release();
      } catch (Exception ignored) {}
    videos.clear();
    for (Bitmap b : images.values()) b.recycle();
    images.clear();
  }
}
