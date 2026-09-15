package app.vynox;

import static org.junit.Assert.*;

import app.vynox.model.Animation.*;
import org.junit.Test;

public class AnimationTest {

  @Test
  public void constant() {
    assertEquals(7, new Property(7).at(500), 0);
  }

  @Test
  public void interpolateAndClamp() {
    Property p = new Property(2);
    p.put(100, 0, Curve.linear());
    p.put(1100, 100, Curve.linear());
    assertEquals(0, p.at(0), 0);
    assertEquals(50, p.at(600), 1e-8);
    assertEquals(100, p.at(2000), 0);
  }

  @Test
  public void easeInChangesValues() {
    Curve c = new Curve(.42, 0, 1, 1);
    assertTrue(c.map(.5) < .5);
    assertEquals(0, c.map(0), 0);
    assertEquals(1, c.map(1), 0);
  }

  @Test
  public void easeOutChangesValues() {
    assertTrue(new Curve(0, 0, .58, 1).map(.5) > .5);
  }

  @Test
  public void easeInOutIsSymmetric() {
    Curve c = new Curve(.42, 0, .58, 1);
    assertEquals(.5, c.map(.5), 1e-9);
    assertEquals(1 - c.map(.25), c.map(.75), 1e-9);
  }

  @Test
  public void customAllowsOvershoot() {
    assertTrue(new Curve(.2, 2, .8, 2).map(.5) > 1);
  }

  @Test
  public void copyDoesNotAlias() {
    Property a = new Property(1);
    a.put(0, 2, Curve.linear());
    Property b = a.copy();
    b.keys.get(0L).curve.y1 = 2;
    b.keys.get(0L).value = 9;
    assertEquals(2, a.at(0), 0);
    assertEquals(0, a.keys.get(0L).curve.y1, 0);
  }

  @Test
  public void moveDeleteAndReplace() {
    Property p = new Property(1);
    p.put(0, 2, Curve.linear());
    p.move(0, 100);
    assertFalse(p.keys.containsKey(0L));
    assertEquals(100, p.keys.get(100L).timeUs);
    p.put(100, 5, Curve.linear());
    assertEquals(1, p.keys.size());
    p.keys.remove(100L);
    assertEquals(1, p.at(100), 0);
  }

  @Test(expected = IllegalArgumentException.class)
  public void rejectKeyCollision() {
    Property p = new Property(0);
    p.put(0, 1, Curve.linear());
    p.put(1, 2, Curve.linear());
    p.move(0, 1);
  }

  @Test(expected = IllegalArgumentException.class)
  public void rejectNan() {
    new Property(0).put(0, Double.NaN, Curve.linear());
  }
}
