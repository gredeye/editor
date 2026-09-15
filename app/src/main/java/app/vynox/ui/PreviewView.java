package app.vynox.ui;

import android.content.Context;
import android.graphics.*;
import android.view.*;
import app.vynox.engine.EditorState;
import app.vynox.model.Project;
import app.vynox.render.CompositionRenderer;
import java.io.File;
import java.util.concurrent.*;

public final class PreviewView extends View implements AutoCloseable {

  private final EditorState state;
  private final CompositionRenderer renderer;
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private Bitmap bitmap;
  private boolean busy, closed, dirty;
  private String error = "";
  private final Paint paint = new Paint(3);
  private final RectF canvasRect = new RectF();
  private float zoom = 1,
    downX,
    downY;
  private double oldX, oldY;
  private String dragId;

  public PreviewView(Context c, EditorState s, File root) {
    super(c);
    state = s;
    renderer = new CompositionRenderer(root);
    setContentDescription(
      "Composition preview. Drag selected layer to reposition."
    );
  }

  public void zoom() {
    zoom = zoom == 1 ? 1.5f : zoom == 1.5f ? 2 : 1;
    invalidate();
  }

  public void requestFrame() {
    if (closed) return;
    if (busy) {
      dirty = true;
      return;
    }
    busy = true;
    Project p = state.project.copy();
    long t = state.playheadUs;
    int w = Math.min(640, p.width),
      h = Math.max(1, (int) (((long) w * p.height) / p.width));
    worker.execute(() -> {
      Bitmap frame = null;
      String failure = "";
      try {
        frame = renderer.frame(p, t, w, h);
      } catch (Exception e) {
        failure = e.getMessage();
      }
      Bitmap result = frame;
      String message = failure;
      post(() -> {
        busy = false;
        if (closed) {
          if (result != null) result.recycle();
          return;
        }
        if (bitmap != null) bitmap.recycle();
        bitmap = result;
        error = message;
        invalidate();
        if (dirty) {
          dirty = false;
          requestFrame();
        }
      });
    });
  }

  @Override
  protected void onDraw(Canvas c) {
    super.onDraw(c);
    c.drawColor(0xff080a0e);
    float scale =
      Math.min(
        (getWidth() - 32f) / state.project.width,
        (getHeight() - 32f) / state.project.height
      ) * zoom;
    float w = state.project.width * scale,
      h = state.project.height * scale;
    canvasRect.set(
      (getWidth() - w) / 2,
      (getHeight() - h) / 2,
      (getWidth() + w) / 2,
      (getHeight() + h) / 2
    );
    paint.setColor(state.project.background);
    c.drawRect(canvasRect, paint);
    if (bitmap != null) c.drawBitmap(bitmap, null, canvasRect, paint);
    if (!error.isEmpty()) {
      paint.setColor(0xffffbb99);
      paint.setTextSize(28);
      c.drawText(
        error.length() > 60 ? error.substring(0, 60) : error,
        16,
        40,
        paint
      );
    }
    Project.Layer l = state.selected();
    if (l != null && l.active(state.playheadUs) && l.parentId.isEmpty()) {
      long t = state.playheadUs - l.startUs;
      c.save();
      c.translate(
        canvasRect.left + (float) l.at("x", t) * scale,
        canvasRect.top + (float) l.at("y", t) * scale
      );
      c.rotate((float) l.at("rotation", t));
      c.scale((float) l.at("scaleX", t), (float) l.at("scaleY", t));
      float lw = (float) l.at("width", t) * scale,
        lh = (float) l.at("height", t) * scale;
      c.translate(
        -((float) l.at("anchorX", t)) * scale,
        -((float) l.at("anchorY", t)) * scale
      );
      paint.setStyle(Paint.Style.STROKE);
      paint.setStrokeWidth(2);
      paint.setColor(0xffb3f56a);
      c.drawRect(-lw / 2, -lh / 2, lw / 2, lh / 2, paint);
      paint.setStyle(Paint.Style.FILL);
      c.drawCircle(lw / 2, lh / 2, 5, paint);
      c.restore();
    }
  }

  @Override
  public boolean onTouchEvent(MotionEvent e) {
    Project.Layer l = state.selected();
    if (l == null || l.locked || !l.parentId.isEmpty()) return false;
    switch (e.getAction()) {
      case MotionEvent.ACTION_DOWN:
        downX = e.getX();
        downY = e.getY();
        dragId = l.id;
        oldX = l.at("x", state.playheadUs - l.startUs);
        oldY = l.at("y", state.playheadUs - l.startUs);
        return true;
      case MotionEvent.ACTION_UP:
        float scale = canvasRect.width() / state.project.width;
        double x = oldX + (e.getX() - downX) / scale,
          y = oldY + (e.getY() - downY) / scale;
        state.edit(p -> {
          Project.Layer a = p.layer(dragId);
          set(a, "x", x);
          set(a, "y", y);
        });
        performClick();
        return true;
      default:
        return true;
    }
  }

  private void set(Project.Layer l, String n, double value) {
    app.vynox.model.Animation.Property p = l.props.get(n);
    if (p.keys.isEmpty()) p.value = value;
    else p.put(
      Math.max(0, state.playheadUs - l.startUs),
      value,
      app.vynox.model.Animation.Curve.linear()
    );
  }

  @Override
  public boolean performClick() {
    super.performClick();
    return true;
  }

  @Override
  public void close() {
    closed = true;
    worker.execute(renderer::close);
    worker.shutdown();
    if (bitmap != null) {
      bitmap.recycle();
      bitmap = null;
    }
  }
}
