package app.vynox.ui;

import android.app.*;
import android.graphics.Color;
import android.widget.*;
import app.vynox.engine.*;
import app.vynox.model.*;
import app.vynox.render.Effects;
import java.util.*;
import java.util.function.*;

/** Inspector operates only through undoable editor transactions. */
final class Inspector {

  private final Activity activity;
  private final EditorState state;
  private Animation.Key clipboard;

  Inspector(Activity a, EditorState s) {
    activity = a;
    state = s;
  }

  private Project.Layer selected() {
    Project.Layer l = state.selected();
    if (l == null) throw new IllegalStateException("Select a layer");
    if (l.locked) throw new IllegalStateException("Unlock the layer first");
    return l;
  }

  private void edit(Consumer<Project.Layer> edit) {
    String id = selected().id;
    state.edit(p -> edit.accept(p.layer(id)));
  }

  private void choose(String title, String[] choices, IntConsumer action) {
    new AlertDialog.Builder(activity)
      .setTitle(title)
      .setItems(choices, (d, n) -> {
        try {
          action.accept(n);
        } catch (Exception e) {
          Toast.makeText(activity, e.getMessage(), Toast.LENGTH_LONG).show();
        }
      })
      .show();
  }

  void properties() {
    Project.Layer l = selected();
    Forms f = new Forms(activity);
    f.add("Name", l.name, false);
    long t = state.playheadUs - l.startUs;
    l.props.forEach((n, p) -> f.add(n, p.at(t), true));
    f.show("Layer properties · canvas units", () -> {
      Map<String, Double> values = new HashMap<>();
      l.props.forEach((n, p) -> values.put(n, f.number(n)));
      edit(a -> {
        a.name = f.text("Name");
        values.forEach((n, v) -> {
          Animation.Property p = a.props.get(n);
          if (Double.compare(v, p.at(t)) != 0) {
            if (p.keys.isEmpty()) p.value = v;
            else p.put(Math.max(0, t), v, Animation.Curve.linear());
          }
        });
      });
    });
  }

  void text() {
    Project.Layer l = selected();
    if (l.type != Project.Type.TEXT) throw new IllegalStateException(
      "Select a text layer"
    );
    Forms f = new Forms(activity);
    f.add("Text", l.text, false)
      .add("Font (sans-serif, serif, monospace)", l.font, false)
      .add(
        "Style (0 normal, 1 bold, 2 italic, 3 bold italic)",
        l.fontStyle,
        true
      )
      .add("Alignment (LEFT, CENTER, RIGHT)", l.align, false)
      .add("Color (#AARRGGBB)", String.format("#%08X", l.color), false);
    f.show("Typography", () -> {
      int color = Color.parseColor(f.text("Color (#AARRGGBB)")),
        style = f.integer("Style (0 normal, 1 bold, 2 italic, 3 bold italic)");
      String align = f.text("Alignment (LEFT, CENTER, RIGHT)");
      if (
        !Arrays.asList("LEFT", "CENTER", "RIGHT").contains(align) ||
        style < 0 ||
        style > 3
      ) throw new IllegalArgumentException("Invalid alignment or style");
      edit(a -> {
        a.text = f.text("Text");
        a.font = f.text("Font (sans-serif, serif, monospace)");
        a.fontStyle = style;
        a.align = align;
        a.color = color;
      });
    });
  }

  void shape() {
    Project.Layer l = selected();
    if (l.type != Project.Type.SHAPE) throw new IllegalStateException(
      "Select a shape layer"
    );
    choose(
      "Shape",
      new String[] { "RECTANGLE", "ROUNDED", "ELLIPSE", "LINE", "POLYGON" },
      n -> {
        String[] kinds = {
          "RECTANGLE",
          "ROUNDED",
          "ELLIPSE",
          "LINE",
          "POLYGON",
        };
        edit(a -> a.shape = kinds[n]);
        Forms f = new Forms(activity);
        f.add("Fill (#AARRGGBB)", String.format("#%08X", l.color), false)
          .add(
            "Stroke (#AARRGGBB)",
            String.format("#%08X", l.strokeColor),
            false
          )
          .add("Polygon sides", l.sides, true);
        f.show("Shape style · size/stroke in Properties", () -> {
          int fill = Color.parseColor(f.text("Fill (#AARRGGBB)")),
            stroke = Color.parseColor(f.text("Stroke (#AARRGGBB)")),
            sides = f.integer("Polygon sides");
          if (sides < 3 || sides > 64) throw new IllegalArgumentException(
            "Use 3–64 sides"
          );
          edit(a -> {
            a.color = fill;
            a.strokeColor = stroke;
            a.sides = sides;
          });
        });
      }
    );
  }

  void blend() {
    Project.Blend[] modes = Project.Blend.values();
    choose(
      "Blend mode",
      Arrays.stream(modes)
        .map(Enum::name)
        .toArray(String[]::new),
      n -> edit(l -> l.blend = modes[n])
    );
  }

  void timing() {
    Project.Layer l = selected();
    Forms f = new Forms(activity);
    f.add("Start seconds", l.startUs / 1e6, true)
      .add("End seconds", l.endUs / 1e6, true)
      .add("Source in seconds", l.sourceInUs / 1e6, true);
    f.show("Timing · move / duration / source", () -> {
      long start = (long) (f.number("Start seconds") * 1e6),
        end = (long) (f.number("End seconds") * 1e6),
        source = (long) (f.number("Source in seconds") * 1e6);
      if (
        start < 0 ||
        end <= start ||
        end > state.project.durationUs ||
        source < 0
      ) throw new IllegalArgumentException("Invalid range");
      Project.Asset asset = state.project.assets.get(l.assetId);
      if (
        asset != null &&
        asset.durationUs > 0 &&
        source + end - start > asset.durationUs
      ) throw new IllegalArgumentException("Clip exceeds source duration");
      edit(a -> {
        a.startUs = start;
        a.endUs = end;
        a.sourceInUs = source;
      });
    });
  }

  void trim() {
    Project.Layer l = selected();
    Forms f = new Forms(activity);
    f.add("Trim start seconds", l.startUs / 1e6, true).add(
      "Trim end seconds",
      l.endUs / 1e6,
      true
    );
    f.show("Trim · preserves animation and source", () -> {
      long start = (long) (f.number("Trim start seconds") * 1e6),
        end = (long) (f.number("Trim end seconds") * 1e6);
      state.edit(p -> Timeline.trim(p, p.layer(l.id), start, end));
    });
  }

  void effects() {
    Project.Layer l = selected();
    List<String> labels = new ArrayList<>();
    labels.add("+ Add effect");
    for (Project.Effect e : l.effects)
      labels.add(e.kind + (e.enabled ? "" : " · disabled"));
    choose("Effects", labels.toArray(new String[0]), n -> {
      if (n == 0) {
        String[] names = Effects.names();
        choose("Add effect", names, i ->
          edit(a ->
            a.effects.add(
              new Project.Effect(
                names[i],
                Arrays.asList("Contrast", "Saturation", "Opacity").contains(
                  names[i]
                )
                  ? 1
                  : 0
              )
            )
          )
        );
      } else {
        int index = n - 1;
        choose(
          l.effects.get(index).kind,
          new String[] {
            "Amount",
            "Animate amount",
            "Enable / disable",
            "Remove",
          },
          action -> {
            if (action == 0) {
              Forms f = new Forms(activity);
              f.add(
                "Amount",
                l.effects
                  .get(index)
                  .params.get("amount")
                  .at(state.playheadUs - l.startUs),
                true
              );
              f.show("Amount · blur pixels; hue degrees; others scalar", () -> {
                double v = f.number("Amount");
                edit(a -> {
                  Animation.Property p = a.effects
                    .get(index)
                    .params.get("amount");
                  if (p.keys.isEmpty()) p.value = v;
                  else p.put(
                    Math.max(0, state.playheadUs - a.startUs),
                    v,
                    Animation.Curve.linear()
                  );
                });
              });
            } else if (action == 1) keyframes(
              a -> a.effects.get(index).params.get("amount"),
              "Effect amount"
            );
            else if (action == 2) edit(
              a -> a.effects.get(index).enabled = !a.effects.get(index).enabled
            );
            else edit(a -> a.effects.remove(index));
          }
        );
      }
    });
  }

  void mask() {
    Project.Layer l = selected();
    choose(
      "Mask",
      new String[] {
        "Rectangle",
        "Ellipse",
        "Edit properties",
        "Animate property",
        "Invert",
        "Remove",
      },
      n -> {
        if (n < 2) edit(a -> {
          if (a.mask == null) a.mask = new Project.Mask();
          a.mask.kind = n == 0 ? "RECTANGLE" : "ELLIPSE";
        });
        else if (n == 5) edit(a -> a.mask = null);
        else {
          if (l.mask == null) throw new IllegalStateException(
            "Add a mask first"
          );
          if (n == 4) edit(a -> a.mask.invert = !a.mask.invert);
          else if (n == 3) {
            String[] names = l.mask.props.keySet().toArray(new String[0]);
            choose("Mask animation", names, i ->
              keyframes(a -> a.mask.props.get(names[i]), "Mask " + names[i])
            );
          } else {
            Forms f = new Forms(activity);
            l.mask.props.forEach((name, p) ->
              f.add(name, p.at(state.playheadUs - l.startUs), true)
            );
            f.show("Mask · local pixels / opacity 0–1", () -> {
              Map<String, Double> values = new HashMap<>();
              l.mask.props.forEach((name, p) ->
                values.put(name, f.number(name))
              );
              edit(a ->
                values.forEach((name, v) -> {
                  Animation.Property p = a.mask.props.get(name);
                  if (p.keys.isEmpty()) p.value = v;
                  else p.put(
                    Math.max(0, state.playheadUs - a.startUs),
                    v,
                    Animation.Curve.linear()
                  );
                })
              );
            });
          }
        }
      }
    );
  }

  void animation() {
    String[] names = selected()
      .props.keySet()
      .toArray(new String[0]);
    choose("Animate property", names, n ->
      keyframes(a -> a.props.get(names[n]), names[n])
    );
  }

  private void keyframes(
    Function<Project.Layer, Animation.Property> property,
    String name
  ) {
    Project.Layer l = selected();
    Animation.Property p = property.apply(l);
    List<Long> times = new ArrayList<>(p.keys.keySet());
    List<String> labels = new ArrayList<>();
    labels.add("+ Keyframe at playhead");
    labels.add("Paste keyframe at playhead");
    for (long t : times)
      labels.add(
        String.format(Locale.US, "%.3f s → %.3f", t / 1e6, p.keys.get(t).value)
      );
    choose(name + " · layer-local time", labels.toArray(new String[0]), n -> {
      long local = Math.max(0, state.playheadUs - l.startUs);
      if (n == 0) {
        Forms f = new Forms(activity);
        f.add("Value", p.at(local), true);
        f.show("Add / replace keyframe", () -> {
          double value = f.number("Value");
          edit(a ->
            property.apply(a).put(local, value, Animation.Curve.linear())
          );
        });
      } else if (n == 1) {
        if (clipboard == null) throw new IllegalStateException(
          "Copy a keyframe first"
        );
        edit(a ->
          property.apply(a).put(local, clipboard.value, clipboard.curve)
        );
      } else {
        long t = times.get(n - 2);
        choose(
          "Keyframe",
          new String[] { "Value / time", "Easing / graph", "Copy", "Delete" },
          action -> {
            if (action == 0) {
              Forms f = new Forms(activity);
              f.add("Time seconds", t / 1e6, true).add(
                "Value",
                p.keys.get(t).value,
                true
              );
              f.show("Move keyframe", () -> {
                long target = (long) (f.number("Time seconds") * 1e6);
                double v = f.number("Value");
                edit(a -> {
                  Animation.Property prop = property.apply(a);
                  prop.move(t, target);
                  prop.keys.get(target).value = v;
                });
              });
            } else if (action == 1) easing(property, t);
            else if (action == 2) clipboard = p.keys.get(t).copy();
            else edit(a -> property.apply(a).keys.remove(t));
          }
        );
      }
    });
  }

  private void easing(
    Function<Project.Layer, Animation.Property> property,
    long t
  ) {
    choose(
      "Outgoing interpolation",
      new String[] {
        "Linear",
        "Ease in",
        "Ease out",
        "Ease in/out",
        "Custom Bezier graph",
      },
      n -> {
        Animation.Curve[] curves = {
          Animation.Curve.linear(),
          new Animation.Curve(.42, 0, 1, 1),
          new Animation.Curve(0, 0, .58, 1),
          new Animation.Curve(.42, 0, .58, 1),
        };
        if (n < 4) edit(a -> property.apply(a).keys.get(t).curve = curves[n]);
        else {
          CurveView graph = new CurveView(
            activity,
            property.apply(selected()).keys.get(t).curve
          );
          new AlertDialog.Builder(activity)
            .setTitle("Bezier · time → progress")
            .setView(graph)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Apply", (d, i) ->
              edit(
                a -> property.apply(a).keys.get(t).curve = graph.curve.copy()
              )
            )
            .show();
        }
      }
    );
  }

  void parent() {
    Project.Layer l = selected();
    List<Project.Layer> parents = new ArrayList<>();
    List<String> names = new ArrayList<>();
    names.add("No parent");
    for (Project.Layer a : state.project.layers)
      if (a.type == Project.Type.NULL && !a.id.equals(l.id)) {
        parents.add(a);
        names.add(a.name);
      }
    choose("Transform parent", names.toArray(new String[0]), n -> {
      String id = n == 0 ? "" : parents.get(n - 1).id;
      Set<String> seen = new HashSet<>();
      String at = id;
      while (!at.isEmpty()) {
        if (
          at.equals(l.id) || !seen.add(at)
        ) throw new IllegalArgumentException("Parent cycle");
        at = state.project.layer(at).parentId;
      }
      edit(a -> a.parentId = id);
    });
  }
}
