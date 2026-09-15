package app.vynox.model;

import java.util.*;

/** Times are integer microseconds, values are in composition coordinates. */
public final class Animation {

  public static final class Curve {

    public double x1, y1, x2, y2;

    public Curve(double x1, double y1, double x2, double y2) {
      this.x1 = Math.max(0, Math.min(1, x1));
      this.y1 = y1;
      this.x2 = Math.max(0, Math.min(1, x2));
      this.y2 = y2;
    }

    public static Curve linear() {
      return new Curve(0, 0, 1, 1);
    }

    private double cubic(double t, double a, double b) {
      double s = 1 - t;
      return 3 * s * s * t * a + 3 * s * t * t * b + t * t * t;
    }

    public double map(double x) {
      if (x <= 0) return 0;
      if (x >= 1) return 1;
      double lo = 0,
        hi = 1;
      for (int i = 0; i < 40; i++) {
        double t = (lo + hi) / 2;
        if (cubic(t, x1, x2) < x) lo = t;
        else hi = t;
      }
      return cubic((lo + hi) / 2, y1, y2);
    }

    public Curve copy() {
      return new Curve(x1, y1, x2, y2);
    }
  }

  public static final class Key {

    public long timeUs;
    public double value;
    public Curve curve;

    public Key(long timeUs, double value, Curve curve) {
      this.timeUs = timeUs;
      this.value = value;
      this.curve = curve;
    }

    public Key copy() {
      return new Key(timeUs, value, curve.copy());
    }
  }

  public static final class Property {

    public double value;
    public final TreeMap<Long, Key> keys = new TreeMap<>();

    public Property(double value) {
      this.value = value;
    }

    public double at(long time) {
      if (keys.isEmpty()) return value;
      Map.Entry<Long, Key> a = keys.floorEntry(time),
        b = keys.ceilingEntry(time);
      if (a == null) return b.getValue().value;
      if (b == null) return a.getValue().value;
      if (a.getKey().equals(b.getKey())) return a.getValue().value;
      double t = (double) (time - a.getKey()) / (b.getKey() - a.getKey());
      return (
        a.getValue().value +
        (b.getValue().value - a.getValue().value) * a.getValue().curve.map(t)
      );
    }

    public void put(long time, double v, Curve curve) {
      if (time < 0 || !Double.isFinite(v)) throw new IllegalArgumentException(
        "Invalid keyframe"
      );
      keys.put(time, new Key(time, v, curve.copy()));
    }

    public void move(long from, long to) {
      if (
        to < 0 || (from != to && keys.containsKey(to))
      ) throw new IllegalArgumentException(
        "Keyframe position occupied or negative"
      );
      Key k = keys.remove(from);
      if (k != null) {
        k.timeUs = to;
        keys.put(to, k);
      }
    }

    public Property copy() {
      Property p = new Property(value);
      keys.forEach((t, k) -> p.keys.put(t, k.copy()));
      return p;
    }
  }

  private Animation() {}
}
