package app.vynox.ui;

import android.content.Context;
import android.graphics.*;
import android.view.*;
import app.vynox.model.Animation;

/** The handles edit the outgoing keyframe's actual cubic Bezier timing curve. */
public final class CurveView extends View {

  public Animation.Curve curve;
  private final Paint paint = new Paint(3);
  private int handle;

  public CurveView(Context c, Animation.Curve curve) {
    super(c);
    this.curve = curve.copy();
    setMinimumHeight(500);
    setContentDescription(
      "Bezier easing graph. Drag either control handle, then Apply."
    );
  }

  private float x(double v) {
    return 50 + (getWidth() - 100) * (float) v;
  }

  private float y(double v) {
    return getHeight() - 60 - (getHeight() - 120) * (float) v;
  }

  @Override
  protected void onDraw(Canvas c) {
    c.drawColor(0xff151820);
    paint.setStrokeWidth(1);
    paint.setColor(0xff353c49);
    for (int i = 0; i <= 4; i++) {
      c.drawLine(x(i / 4.0), y(0), x(i / 4.0), y(1), paint);
      c.drawLine(x(0), y(i / 4.0), x(1), y(i / 4.0), paint);
    }
    paint.setColor(0xff909bad);
    c.drawLine(x(0), y(0), x(curve.x1), y(curve.y1), paint);
    c.drawLine(x(1), y(1), x(curve.x2), y(curve.y2), paint);
    Path path = new Path();
    path.moveTo(x(0), y(0));
    path.cubicTo(
      x(curve.x1),
      y(curve.y1),
      x(curve.x2),
      y(curve.y2),
      x(1),
      y(1)
    );
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeWidth(5);
    paint.setColor(0xffb3f56a);
    c.drawPath(path, paint);
    paint.setStyle(Paint.Style.FILL);
    c.drawCircle(x(curve.x1), y(curve.y1), 14, paint);
    paint.setColor(0xffbba4ef);
    c.drawCircle(x(curve.x2), y(curve.y2), 14, paint);
  }

  @Override
  public boolean onTouchEvent(MotionEvent e) {
    if (e.getAction() == MotionEvent.ACTION_DOWN) handle =
      Math.hypot(e.getX() - x(curve.x1), e.getY() - y(curve.y1)) <
      Math.hypot(e.getX() - x(curve.x2), e.getY() - y(curve.y2))
        ? 1
        : 2;
    if (
      e.getAction() == MotionEvent.ACTION_MOVE ||
      e.getAction() == MotionEvent.ACTION_DOWN
    ) {
      double x = Math.max(0, Math.min(1, (e.getX() - 50) / (getWidth() - 100))),
        y = Math.max(
          -1,
          Math.min(2, (getHeight() - 60 - e.getY()) / (getHeight() - 120))
        );
      if (handle == 1) {
        curve.x1 = x;
        curve.y1 = y;
      } else {
        curve.x2 = x;
        curve.y2 = y;
      }
      invalidate();
    }
    if (e.getAction() == MotionEvent.ACTION_UP) performClick();
    return true;
  }

  @Override
  public boolean performClick() {
    super.performClick();
    return true;
  }
}
