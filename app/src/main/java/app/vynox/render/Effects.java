package app.vynox.render;

import android.graphics.*;
import app.vynox.model.Project;
import java.util.*;

/** CPU effect registry shared by preview and export. Parameters are keyframe properties. */
public final class Effects {

  public interface Filter {
    void apply(Bitmap bitmap, double amount);
  }

  private static final Map<String, Filter> REGISTRY = new LinkedHashMap<>();

  static {
    REGISTRY.put("Brightness", (b, a) ->
      color(b, new float[] {
        1,
        0,
        0,
        0,
        (float) a * 255,
        0,
        1,
        0,
        0,
        (float) a * 255,
        0,
        0,
        1,
        0,
        (float) a * 255,
        0,
        0,
        0,
        1,
        0,
      })
    );
    REGISTRY.put("Contrast", (b, a) -> {
      float c = (float) a,
        t = 128 * (1 - c);
      color(b, new float[] {
        c,
        0,
        0,
        0,
        t,
        0,
        c,
        0,
        0,
        t,
        0,
        0,
        c,
        0,
        t,
        0,
        0,
        0,
        1,
        0,
      });
    });
    REGISTRY.put("Saturation", (b, a) -> {
      ColorMatrix m = new ColorMatrix();
      m.setSaturation((float) a);
      color(b, m.getArray());
    });
    REGISTRY.put("Exposure", (b, a) -> {
      float c = (float) Math.pow(2, a);
      color(b, new float[] {
        c,
        0,
        0,
        0,
        0,
        0,
        c,
        0,
        0,
        0,
        0,
        0,
        c,
        0,
        0,
        0,
        0,
        0,
        1,
        0,
      });
    });
    REGISTRY.put("Hue", Effects::hue);
    REGISTRY.put("Opacity", (b, a) ->
      color(b, new float[] {
        1,
        0,
        0,
        0,
        0,
        0,
        1,
        0,
        0,
        0,
        0,
        0,
        1,
        0,
        0,
        0,
        0,
        0,
        (float) Math.max(0, Math.min(1, a)),
        0,
      })
    );
    REGISTRY.put("Blur", (b, a) -> blur(b, (int) Math.max(0, Math.min(64, a))));
    REGISTRY.put("Sharpen", Effects::sharpen);
    REGISTRY.put("Glow", (b, a) -> {
      Bitmap glow = b.copy(Bitmap.Config.ARGB_8888, true);
      blur(glow, 12);
      Canvas c = new Canvas(b);
      Paint p = new Paint(3);
      p.setBlendMode(BlendMode.PLUS);
      p.setAlpha((int) (255 * Math.max(0, Math.min(1, a))));
      c.drawBitmap(glow, 0, 0, p);
      glow.recycle();
    });
  }

  public static String[] names() {
    return REGISTRY.keySet().toArray(new String[0]);
  }

  public static void apply(Bitmap b, Project.Layer l, long time) {
    for (Project.Effect e : l.effects)
      if (e.enabled) {
        Filter f = REGISTRY.get(e.kind);
        if (f == null) throw new IllegalArgumentException(
          "Unsupported effect: " + e.kind
        );
        f.apply(b, e.params.get("amount").at(time));
      }
  }

  private static void color(Bitmap b, float[] values) {
    Bitmap copy = b.copy(Bitmap.Config.ARGB_8888, false);
    Paint p = new Paint(3);
    p.setColorFilter(new ColorMatrixColorFilter(values));
    p.setBlendMode(BlendMode.SRC);
    new Canvas(b).drawBitmap(copy, 0, 0, p);
    copy.recycle();
  }

  private static void hue(Bitmap b, double degrees) {
    int w = b.getWidth(),
      h = b.getHeight();
    int[] px = new int[w * h];
    b.getPixels(px, 0, w, 0, 0, w, h);
    float[] hsv = new float[3];
    for (int i = 0; i < px.length; i++) {
      Color.colorToHSV(px[i], hsv);
      hsv[0] = (float) (((hsv[0] + degrees) % 360) + 360) % 360;
      px[i] = Color.HSVToColor(Color.alpha(px[i]), hsv);
    }
    b.setPixels(px, 0, w, 0, 0, w, h);
  }

  static void blur(Bitmap b, int radius) {
    if (radius < 1) return;
    int w = b.getWidth(),
      h = b.getHeight();
    int[] a = new int[w * h],
      tmp = new int[w * h];
    b.getPixels(a, 0, w, 0, 0, w, h);
    // Separable box filter over premultiplied components to avoid transparent-edge halos.
    for (int pass = 0; pass < 2; pass++) {
      int major = pass == 0 ? h : w,
        minor = pass == 0 ? w : h;
      for (int row = 0; row < major; row++) {
        long sa = 0,
          sr = 0,
          sg = 0,
          sb = 0;
        int count = 0;
        for (int col = -radius; col < minor; col++) {
          int add = col + radius;
          if (add < minor) {
            int v = a[pass == 0 ? row * w + add : add * w + row],
              al = Color.alpha(v);
            sa += al;
            sr += Color.red(v) * al;
            sg += Color.green(v) * al;
            sb += Color.blue(v) * al;
            count++;
          }
          int rem = col - radius - 1;
          if (rem >= 0) {
            int v = a[pass == 0 ? row * w + rem : rem * w + row],
              al = Color.alpha(v);
            sa -= al;
            sr -= Color.red(v) * al;
            sg -= Color.green(v) * al;
            sb -= Color.blue(v) * al;
            count--;
          }
          if (col >= 0) tmp[pass == 0 ? row * w + col : col * w + row] =
            Color.argb(
              (int) (sa / count),
              sa == 0 ? 0 : (int) (sr / sa),
              sa == 0 ? 0 : (int) (sg / sa),
              sa == 0 ? 0 : (int) (sb / sa)
            );
        }
      }
      int[] swap = a;
      a = tmp;
      tmp = swap;
    }
    b.setPixels(a, 0, w, 0, 0, w, h);
  }

  private static void sharpen(Bitmap b, double amount) {
    Bitmap soft = b.copy(Bitmap.Config.ARGB_8888, true);
    blur(soft, 2);
    int w = b.getWidth(),
      h = b.getHeight();
    int[] src = new int[w * h],
      low = new int[w * h];
    b.getPixels(src, 0, w, 0, 0, w, h);
    soft.getPixels(low, 0, w, 0, 0, w, h);
    soft.recycle();
    double a = Math.max(0, Math.min(4, amount));
    for (int i = 0; i < src.length; i++) src[i] = Color.argb(
      Color.alpha(src[i]),
      clamp(Color.red(src[i]) + a * (Color.red(src[i]) - Color.red(low[i]))),
      clamp(
        Color.green(src[i]) + a * (Color.green(src[i]) - Color.green(low[i]))
      ),
      clamp(Color.blue(src[i]) + a * (Color.blue(src[i]) - Color.blue(low[i])))
    );
    b.setPixels(src, 0, w, 0, 0, w, h);
  }

  private static int clamp(double v) {
    return (int) Math.max(0, Math.min(255, v));
  }

  private Effects() {}
}
