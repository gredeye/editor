package app.vynox.engine;

import app.vynox.model.*;
import java.util.*;

public final class Timeline {

  private static void editable(Project.Layer l) {
    if (l.locked) throw new IllegalStateException("Layer is locked");
  }

  public static long frameTime(long frame, int fps) {
    return (frame * 1_000_000L) / fps;
  }

  public static long quantize(long t, int fps) {
    return frameTime(Math.round((t * fps) / 1_000_000.0), fps);
  }

  public static long snap(Project p, long time, long tolerance, String except) {
    long best = time,
      d = Math.abs(time);
    if (d <= tolerance) best = 0;
    else d = Long.MAX_VALUE;
    for (Project.Layer l : p.layers)
      if (!l.id.equals(except)) for (long t : new long[] { l.startUs, l.endUs })
        if (Math.abs(t - time) < d && Math.abs(t - time) <= tolerance) {
          best = t;
          d = Math.abs(t - time);
        }
    return best;
  }

  public static void move(Project p, Project.Layer l, long start) {
    editable(l);
    long duration = l.endUs - l.startUs;
    start = Math.max(0, Math.min(p.durationUs - duration, start));
    l.startUs = start;
    l.endUs = start + duration;
  }

  /** Trim preserves source time and animation phase by rebasing keyframes. */
  public static void trim(Project p, Project.Layer l, long start, long end) {
    editable(l);
    if (
      start < l.startUs || end > l.endUs || start >= end || end > p.durationUs
    ) throw new IllegalArgumentException("Trim must stay within clip bounds");
    long delta = start - l.startUs;
    shiftAnimation(l, delta);
    l.sourceInUs += delta;
    l.startUs = start;
    l.endUs = end;
  }

  private static void shift(Animation.Property p, long delta) {
    // Keep negative keys: they preserve Bezier segments exactly across a split/trim.
    TreeMap<Long, Animation.Key> shifted = new TreeMap<>();
    p.keys.forEach((t, k) -> {
      Animation.Key n = k.copy();
      n.timeUs = t - delta;
      shifted.put(n.timeUs, n);
    });
    p.keys.clear();
    p.keys.putAll(shifted);
  }

  private static void shiftAnimation(Project.Layer l, long delta) {
    l.props.values().forEach(p -> shift(p, delta));
    l.effects.forEach(e -> e.params.values().forEach(p -> shift(p, delta)));
    if (l.mask != null) l.mask.props.values().forEach(p -> shift(p, delta));
  }

  public static Project.Layer split(Project p, Project.Layer l, long time) {
    editable(l);
    if (
      time <= l.startUs || time >= l.endUs
    ) throw new IllegalArgumentException("Place playhead inside the clip");
    Project.Layer r = l.copy();
    r.id = UUID.randomUUID().toString();
    r.name = l.name + " · split";
    long delta = time - l.startUs;
    r.startUs = time;
    r.sourceInUs += delta;
    shiftAnimation(r, delta);
    l.endUs = time;
    p.layers.add(p.layers.indexOf(l) + 1, r);
    return r;
  }

  public static void reorder(Project p, Project.Layer l, int index) {
    editable(l);
    p.layers.remove(l);
    p.layers.add(Math.max(0, Math.min(index, p.layers.size())), l);
  }

  private Timeline() {}
}
