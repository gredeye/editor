package app.vynox.ui;

import android.content.Context;
import android.graphics.*;
import android.view.*;
import app.vynox.engine.*;
import app.vynox.model.Project;

public final class TimelineView extends View {

  private final EditorState state;
  private final Paint paint = new Paint(3);
  private float pixelsPerSecond = 80,
    scrollX,
    scrollY,
    downX,
    downY;
  private final float density;
  private long originalStart;
  private String dragId = "";
  private boolean moved;
  public Runnable onSeek = () -> {},
    onSelect = () -> {};
  public boolean snapping = true;

  public TimelineView(Context c, EditorState s) {
    super(c);
    state = s;
    density = getResources().getDisplayMetrics().density;
    pixelsPerSecond *= density;
    setContentDescription(
      "Multi-layer timeline. Scrub ruler. Drag clip to move. Drag left track labels to scroll vertically."
    );
  }

  public void zoom(boolean in) {
    pixelsPerSecond = Math.max(
      8 * density,
      Math.min(800 * density, pixelsPerSecond * (in ? 1.4f : 1 / 1.4f))
    );
    invalidate();
  }

  private float label() {
    return 112 * density;
  }

  private float row() {
    return 48 * density;
  }

  private float ruler() {
    return 32 * density;
  }

  private float x(long time) {
    return label() + (time / 1_000_000f) * pixelsPerSecond - scrollX;
  }

  private long time(float x) {
    return Timeline.quantize(
      (long) (((x - label() + scrollX) * 1_000_000) / pixelsPerSecond),
      state.project.fps
    );
  }

  @Override
  protected void onDraw(Canvas c) {
    c.drawColor(0xff151820);
    paint.setTextSize(11 * density);
    long step =
      pixelsPerSecond > 150 * density
        ? 500000
        : pixelsPerSecond < 30 * density
          ? 5000000
          : 1000000;
    c.save();
    c.clipRect(label(), 0, getWidth(), getHeight());
    for (
      long t = Math.max(0, (time(label()) / step) * step);
      t <= state.project.durationUs && x(t) < getWidth();
      t += step
    ) {
      paint.setColor(0xff303642);
      c.drawLine(x(t), ruler(), x(t), getHeight(), paint);
      paint.setColor(0xff9da6b6);
      c.drawText(
        String.format(java.util.Locale.US, "%.1fs", t / 1e6),
        x(t) + 4,
        20 * density,
        paint
      );
    }
    for (int i = 0; i < state.project.layers.size(); i++) {
      Project.Layer l = state.project.layers.get(
        state.project.layers.size() - 1 - i
      );
      float y = ruler() + i * row() - scrollY;
      if (y + row() < ruler() || y > getHeight()) continue;
      paint.setColor(
        l.id.equals(state.selectedId)
          ? 0xffb3f56a
          : l.type == Project.Type.AUDIO
            ? 0xffbba4ef
            : 0xff619ca6
      );
      paint.setAlpha(l.visible ? 255 : 70);
      c.drawRoundRect(
        x(l.startUs),
        y + 5 * density,
        x(l.endUs),
        y + row() - 5 * density,
        6 * density,
        6 * density,
        paint
      );
      paint.setAlpha(255);
      paint.setColor(0xff101217);
      c.drawText(
        l.name,
        x(l.startUs) + 8 * density,
        y + row() / 2 + 4 * density,
        paint
      );
      paint.setColor(0xfff5f8ff);
      for (app.vynox.model.Animation.Property p : l.props.values())
        for (long k : p.keys.keySet())
          if (k >= 0 && k < l.endUs - l.startUs) c.drawCircle(
            x(l.startUs + k),
            y + row() - 10 * density,
            3 * density,
            paint
          );
    }
    paint.setColor(0xffffffff);
    paint.setStrokeWidth(2 * density);
    c.drawLine(x(state.playheadUs), 0, x(state.playheadUs), getHeight(), paint);
    c.restore();
    paint.setColor(0xff1b1f28);
    c.drawRect(0, 0, label(), getHeight(), paint);
    paint.setColor(0xffa5aebe);
    c.drawText("LAYERS", 12 * density, 20 * density, paint);
    for (int i = 0; i < state.project.layers.size(); i++) {
      Project.Layer l = state.project.layers.get(
        state.project.layers.size() - 1 - i
      );
      float y = ruler() + i * row() - scrollY;
      if (y < ruler() || y > getHeight()) continue;
      paint.setColor(l.id.equals(state.selectedId) ? 0xffb3f56a : 0xffc2c9d4);
      String name = (l.locked ? "▣ " : "") + (l.visible ? "" : "○ ") + l.name;
      c.drawText(
        name.substring(0, Math.min(14, name.length())),
        10 * density,
        y + 27 * density,
        paint
      );
    }
  }

  @Override
  public boolean onTouchEvent(MotionEvent e) {
    float ex = e.getX(),
      ey = e.getY();
    switch (e.getActionMasked()) {
      case MotionEvent.ACTION_DOWN:
        downX = ex;
        downY = ey;
        moved = false;
        dragId = "";
        if (ey < ruler()) {
          state.seek(time(ex));
          onSeek.run();
        } else {
          int index = (int) ((ey - ruler() + scrollY) / row());
          if (index >= 0 && index < state.project.layers.size()) {
            Project.Layer l = state.project.layers.get(
              state.project.layers.size() - 1 - index
            );
            state.selectedId = l.id;
            onSelect.run();
            if (
              ex >= x(l.startUs) &&
              ex <= x(l.endUs) &&
              ex > label() &&
              !l.locked
            ) {
              dragId = l.id;
              originalStart = l.startUs;
            }
          }
        }
        invalidate();
        return true;
      case MotionEvent.ACTION_MOVE:
        moved |= Math.abs(ex - downX) + Math.abs(ey - downY) > 8 * density;
        if (downY < ruler()) {
          state.seek(time(ex));
          onSeek.run();
        } else if (downX < label()) {
          scrollY = Math.max(
            0,
            Math.min(
              Math.max(
                0,
                state.project.layers.size() * row() - getHeight() + ruler()
              ),
              scrollY + downY - ey
            )
          );
          downY = ey;
        } else if (dragId.isEmpty()) {
          scrollX = Math.max(
            0,
            Math.min(
              (state.project.durationUs / 1e6f) * pixelsPerSecond,
              scrollX + downX - ex
            )
          );
          downX = ex;
        }
        invalidate();
        return true;
      case MotionEvent.ACTION_UP:
        if (!dragId.isEmpty() && moved) {
          long start = Timeline.quantize(
            originalStart +
              (long) (((ex - downX) * 1_000_000) / pixelsPerSecond),
            state.project.fps
          );
          if (snapping) start = Timeline.snap(
            state.project,
            start,
            (long) ((8 * density * 1_000_000) / pixelsPerSecond),
            dragId
          );
          long target = start;
          String id = dragId;
          state.edit(p -> Timeline.move(p, p.layer(id), target));
        } else if (ex > label() && !moved) {
          state.seek(time(ex));
          onSeek.run();
        }
        performClick();
        invalidate();
        return true;
      default:
        return true;
    }
  }

  @Override
  public boolean performClick() {
    super.performClick();
    return true;
  }
}
