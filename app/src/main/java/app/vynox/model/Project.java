package app.vynox.model;

import static app.vynox.model.Animation.*;

import java.util.*;

public final class Project {

  public static final int SCHEMA = 1;
  public String id = UUID.randomUUID().toString(),
    name = "Untitled",
    appVersion = "0.1.0";
  public int width = 1280,
    height = 720,
    fps = 30,
    background = 0xff101217;
  public long durationUs = 10_000_000;
  public final Map<String, String> metadata = new LinkedHashMap<>();
  public final Map<String, Asset> assets = new LinkedHashMap<>();
  /** Back to front compositing order. */
  public final List<Layer> layers = new ArrayList<>();

  public enum Type {
    VIDEO,
    IMAGE,
    TEXT,
    SHAPE,
    AUDIO,
    NULL,
  }

  public enum Blend {
    NORMAL,
    MULTIPLY,
    SCREEN,
    OVERLAY,
    ADD,
    LIGHTEN,
    DARKEN,
  }

  public static final class Asset {

    public String id = UUID.randomUUID().toString(),
      name = "",
      mime = "",
      file = "";
    public long durationUs;
    public int width, height;

    public Asset copy() {
      Asset a = new Asset();
      a.id = id;
      a.name = name;
      a.mime = mime;
      a.file = file;
      a.durationUs = durationUs;
      a.width = width;
      a.height = height;
      return a;
    }
  }

  public static final class Effect {

    public String kind;
    public boolean enabled = true;
    public final Map<String, Property> params = new LinkedHashMap<>();

    public Effect(String kind, double amount) {
      this.kind = kind;
      params.put("amount", new Property(amount));
    }

    public Effect copy() {
      Effect e = new Effect(kind, 0);
      e.enabled = enabled;
      params.forEach((n, p) -> e.params.put(n, p.copy()));
      return e;
    }
  }

  public static final class Mask {

    public String kind = "RECTANGLE";
    public boolean invert;
    public final Map<String, Property> props = new LinkedHashMap<>();

    public Mask() {
      props.put("x", new Property(0));
      props.put("y", new Property(0));
      props.put("width", new Property(300));
      props.put("height", new Property(300));
      props.put("feather", new Property(0));
      props.put("opacity", new Property(1));
    }

    public double at(String name, long t) {
      return props.get(name).at(t);
    }

    public Mask copy() {
      Mask m = new Mask();
      m.kind = kind;
      m.invert = invert;
      props.forEach((n, p) -> m.props.put(n, p.copy()));
      return m;
    }
  }

  public static final class Layer {

    public String id = UUID.randomUUID().toString(),
      name = "Layer",
      assetId = "",
      parentId = "";
    public Type type = Type.SHAPE;
    public Blend blend = Blend.NORMAL;
    public boolean visible = true,
      locked = false,
      muted = false;
    public long startUs = 0,
      endUs = 10_000_000,
      sourceInUs = 0;
    public String text = "Your story starts here",
      font = "sans-serif",
      align = "CENTER",
      shape = "RECTANGLE";
    public int color = 0xffb3f56a,
      strokeColor = 0xffffffff,
      fontStyle = 0,
      sides = 6;
    public final Map<String, Property> props = new LinkedHashMap<>();
    public final List<Effect> effects = new ArrayList<>();
    public Mask mask;

    public Layer() {
      String[] names = {
        "x",
        "y",
        "scaleX",
        "scaleY",
        "rotation",
        "anchorX",
        "anchorY",
        "opacity",
        "width",
        "height",
        "fontSize",
        "letterSpacing",
        "lineSpacing",
        "strokeWidth",
        "radius",
        "volume",
      };
      double[] vals = {
        640,
        360,
        1,
        1,
        0,
        0,
        0,
        1,
        400,
        240,
        64,
        0,
        1.2,
        0,
        32,
        1,
      };
      for (int i = 0; i < names.length; i++) props.put(
        names[i],
        new Property(vals[i])
      );
    }

    public double at(String n, long localUs) {
      Property p = props.get(n);
      return p == null ? 0 : p.at(localUs);
    }

    public void set(String n, double v) {
      props.computeIfAbsent(n, k -> new Property(v)).value = v;
    }

    public boolean active(long t) {
      return visible && t >= startUs && t < endUs;
    }

    public Layer copy() {
      Layer l = new Layer();
      l.id = id;
      l.name = name;
      l.assetId = assetId;
      l.parentId = parentId;
      l.type = type;
      l.blend = blend;
      l.visible = visible;
      l.locked = locked;
      l.muted = muted;
      l.startUs = startUs;
      l.endUs = endUs;
      l.sourceInUs = sourceInUs;
      l.text = text;
      l.font = font;
      l.align = align;
      l.shape = shape;
      l.color = color;
      l.strokeColor = strokeColor;
      l.fontStyle = fontStyle;
      l.sides = sides;
      props.forEach((n, p) -> l.props.put(n, p.copy()));
      effects.forEach(e -> l.effects.add(e.copy()));
      l.mask = mask == null ? null : mask.copy();
      return l;
    }
  }

  public Layer layer(String id) {
    for (Layer l : layers) if (l.id.equals(id)) return l;
    return null;
  }

  public Project copy() {
    Project p = new Project();
    p.id = id;
    p.name = name;
    p.appVersion = appVersion;
    p.width = width;
    p.height = height;
    p.fps = fps;
    p.background = background;
    p.durationUs = durationUs;
    p.metadata.putAll(metadata);
    assets.forEach((k, a) -> p.assets.put(k, a.copy()));
    layers.forEach(l -> p.layers.add(l.copy()));
    return p;
  }
}
