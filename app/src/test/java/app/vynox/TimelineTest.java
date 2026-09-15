package app.vynox;

import static org.junit.Assert.*;

import app.vynox.engine.*;
import app.vynox.model.*;
import org.junit.Test;

public class TimelineTest {

  private Project project() {
    Project p = new Project();
    p.layers.add(new Project.Layer());
    return p;
  }

  @Test
  public void splitPreservesBezierAndSource() {
    Project p = project();
    Project.Layer l = p.layers.get(0);
    l.sourceInUs = 1000;
    l.props.get("x").put(0, 0, new Animation.Curve(.42, 0, .58, 1));
    l.props.get("x").put(10000000, 100, Animation.Curve.linear());
    double before = l.at("x", 7500000);
    Project.Layer r = Timeline.split(p, l, 5000000);
    assertEquals(5001000, r.sourceInUs);
    assertEquals(5000000, l.endUs);
    assertEquals(before, r.at("x", 2500000), 1e-9);
    assertEquals(2, p.layers.size());
    assertNotEquals(l.id, r.id);
  }

  @Test
  public void trimRebasesEffectsAndMasks() {
    Project p = project();
    Project.Layer l = p.layers.get(0);
    l.effects.add(new Project.Effect("Opacity", 1));
    l.mask = new Project.Mask();
    l.mask.props.get("x").put(0, 0, Animation.Curve.linear());
    l.mask.props.get("x").put(10000000, 100, Animation.Curve.linear());
    Timeline.trim(p, l, 2000000, 8000000);
    assertEquals(2000000, l.sourceInUs);
    assertEquals(40, l.mask.at("x", 2000000), 1e-8);
  }

  @Test
  public void moveClamps() {
    Project p = project();
    Project.Layer l = p.layers.get(0);
    l.endUs = 2000000;
    Timeline.move(p, l, -500);
    assertEquals(0, l.startUs);
    Timeline.move(p, l, 9999999);
    assertEquals(8000000, l.startUs);
    assertEquals(p.durationUs, l.endUs);
  }

  @Test
  public void frameArithmeticDoesNotAccumulateDrift() {
    assertEquals(1000000000, Timeline.frameTime(30000, 30));
    assertEquals(33333, Timeline.quantize(33000, 30));
  }

  @Test
  public void snapping() {
    Project p = project();
    Project.Layer l = p.layers.get(0);
    l.endUs = 2000000;
    assertEquals(2000000, Timeline.snap(p, 2010000, 20000, "other"));
    assertEquals(2010000, Timeline.snap(p, 2010000, 20000, l.id));
  }

  @Test(expected = IllegalStateException.class)
  public void lockedCannotSplit() {
    Project p = project();
    p.layers.get(0).locked = true;
    Timeline.split(p, p.layers.get(0), 5000000);
  }

  @Test(expected = IllegalArgumentException.class)
  public void cannotSplitAtEnd() {
    Project p = project();
    Timeline.split(p, p.layers.get(0), p.durationUs);
  }

  @Test
  public void reorderChangesCompositionOrder() {
    Project p = project();
    Project.Layer a = p.layers.get(0),
      b = new Project.Layer();
    p.layers.add(b);
    Timeline.reorder(p, a, 1);
    assertSame(a, p.layers.get(1));
  }

  @Test
  public void undoRedoTransactions() {
    EditorState s = new EditorState(project());
    s.edit(p -> p.layers.get(0).text = "hello");
    s.undo();
    assertNotEquals("hello", s.project.layers.get(0).text);
    s.redo();
    assertEquals("hello", s.project.layers.get(0).text);
  }

  @Test
  public void failedEditIsAtomic() {
    EditorState s = new EditorState(project());
    try {
      s.edit(p -> {
        p.name = "corrupt";
        throw new IllegalArgumentException();
      });
      fail();
    } catch (IllegalArgumentException expected) {}
    assertEquals("Untitled", s.project.name);
  }

  @Test
  public void newEditClearsRedo() {
    EditorState s = new EditorState(project());
    s.edit(p -> p.name = "A");
    s.undo();
    s.edit(p -> p.name = "B");
    s.redo();
    assertEquals("B", s.project.name);
  }
}
