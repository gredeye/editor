package app.vynox.format;

import app.vynox.model.*;
import java.util.*;
import org.json.*;

/** UI-independent, explicit versioned schema. Unknown property names round-trip. */
public final class ProjectJson {

  private static JSONObject properties(Map<String, Animation.Property> props)
    throws JSONException {
    JSONObject out = new JSONObject();
    for (Map.Entry<String, Animation.Property> e : props.entrySet()) {
      JSONArray keys = new JSONArray();
      for (Animation.Key k : e.getValue().keys.values())
        keys.put(
          new JSONObject()
            .put("timeUs", k.timeUs)
            .put("value", k.value)
            .put(
              "curve",
              new JSONArray(new double[] {
                k.curve.x1,
                k.curve.y1,
                k.curve.x2,
                k.curve.y2,
              })
            )
        );
      out.put(
        e.getKey(),
        new JSONObject().put("value", e.getValue().value).put("keys", keys)
      );
    }
    return out;
  }

  private static void readProperties(
    JSONObject obj,
    Map<String, Animation.Property> dest
  ) throws JSONException {
    Iterator<String> names = obj.keys();
    while (names.hasNext()) {
      String name = names.next();
      JSONObject v = obj.getJSONObject(name);
      Animation.Property p = new Animation.Property(v.getDouble("value"));
      JSONArray keys = v.getJSONArray("keys");
      if (keys.length() > 10000) throw new JSONException("Too many keyframes");
      for (int i = 0; i < keys.length(); i++) {
        JSONObject k = keys.getJSONObject(i);
        JSONArray c = k.getJSONArray("curve");
        long t = k.getLong("timeUs");
        if (
          t < -86_400_000_000L || t > 86_400_000_000L
        ) throw new JSONException("Keyframe time out of range");
        p.keys.put(
          t,
          new Animation.Key(
            t,
            k.getDouble("value"),
            new Animation.Curve(
              c.getDouble(0),
              c.getDouble(1),
              c.getDouble(2),
              c.getDouble(3)
            )
          )
        );
      }
      dest.put(name, p);
    }
  }

  public static String encode(Project p) throws JSONException {
    JSONObject o = new JSONObject()
      .put("format", "Vynox")
      .put("schema", Project.SCHEMA)
      .put("appVersion", p.appVersion)
      .put("id", p.id)
      .put("name", p.name)
      .put("width", p.width)
      .put("height", p.height)
      .put("fps", p.fps)
      .put("durationUs", p.durationUs)
      .put("background", p.background)
      .put("metadata", new JSONObject(p.metadata));
    JSONArray assets = new JSONArray(),
      layers = new JSONArray();
    for (Project.Asset a : p.assets.values())
      assets.put(
        new JSONObject()
          .put("id", a.id)
          .put("name", a.name)
          .put("mime", a.mime)
          .put("file", a.file)
          .put("durationUs", a.durationUs)
          .put("width", a.width)
          .put("height", a.height)
      );
    for (Project.Layer l : p.layers) {
      JSONObject j = new JSONObject()
        .put("id", l.id)
        .put("name", l.name)
        .put("type", l.type.name())
        .put("assetId", l.assetId)
        .put("parentId", l.parentId)
        .put("blend", l.blend.name())
        .put("visible", l.visible)
        .put("locked", l.locked)
        .put("muted", l.muted)
        .put("startUs", l.startUs)
        .put("endUs", l.endUs)
        .put("sourceInUs", l.sourceInUs)
        .put("text", l.text)
        .put("font", l.font)
        .put("align", l.align)
        .put("shape", l.shape)
        .put("color", l.color)
        .put("strokeColor", l.strokeColor)
        .put("fontStyle", l.fontStyle)
        .put("sides", l.sides)
        .put("properties", properties(l.props));
      JSONArray effects = new JSONArray();
      for (Project.Effect e : l.effects)
        effects.put(
          new JSONObject()
            .put("kind", e.kind)
            .put("enabled", e.enabled)
            .put("parameters", properties(e.params))
        );
      j.put("effects", effects);
      if (l.mask != null) j.put(
        "mask",
        new JSONObject()
          .put("kind", l.mask.kind)
          .put("invert", l.mask.invert)
          .put("properties", properties(l.mask.props))
      );
      layers.put(j);
    }
    return o.put("assets", assets).put("layers", layers).toString(2);
  }

  public static Project decode(String text) throws JSONException {
    JSONObject o = new JSONObject(text);
    if (
      !"Vynox".equals(o.getString("format")) ||
      o.getInt("schema") != Project.SCHEMA
    ) throw new JSONException("Unsupported VNX version");
    Project p = new Project();
    p.id = o.getString("id");
    p.name = o.getString("name");
    p.appVersion = o.getString("appVersion");
    p.width = o.getInt("width");
    p.height = o.getInt("height");
    p.fps = o.getInt("fps");
    p.durationUs = o.getLong("durationUs");
    p.background = o.getInt("background");
    if (
      p.width < 16 ||
      p.height < 16 ||
      p.width > 3840 ||
      p.height > 3840 ||
      p.fps < 1 ||
      p.fps > 60 ||
      p.durationUs < 1 ||
      p.durationUs > 3_600_000_000L
    ) throw new JSONException("Unsupported canvas, frame rate or duration");
    JSONObject metadata = o.optJSONObject("metadata");
    if (metadata != null) {
      Iterator<String> it = metadata.keys();
      while (it.hasNext()) {
        String k = it.next();
        p.metadata.put(k, metadata.getString(k));
      }
    }
    JSONArray assets = o.getJSONArray("assets"),
      layers = o.getJSONArray("layers");
    if (assets.length() > 500 || layers.length() > 200) throw new JSONException(
      "Project exceeds layer/asset limits"
    );
    for (int i = 0; i < assets.length(); i++) {
      JSONObject j = assets.getJSONObject(i);
      Project.Asset a = new Project.Asset();
      a.id = j.getString("id");
      a.name = j.getString("name");
      a.mime = j.getString("mime");
      a.file = j.getString("file");
      a.durationUs = j.getLong("durationUs");
      a.width = j.getInt("width");
      a.height = j.getInt("height");
      if (
        !a.file.isEmpty() && !a.file.matches("media/[A-Za-z0-9._-]+")
      ) throw new JSONException("Unsafe asset path");
      if (p.assets.put(a.id, a) != null) throw new JSONException(
        "Duplicate asset ID"
      );
    }
    Set<String> ids = new HashSet<>();
    for (int i = 0; i < layers.length(); i++) {
      JSONObject j = layers.getJSONObject(i);
      Project.Layer l = new Project.Layer();
      l.id = j.getString("id");
      if (!ids.add(l.id)) throw new JSONException("Duplicate layer ID");
      l.name = j.getString("name");
      try {
        l.type = Project.Type.valueOf(j.getString("type"));
        l.blend = Project.Blend.valueOf(j.getString("blend"));
      } catch (IllegalArgumentException e) {
        throw new JSONException("Unsupported layer or blend");
      }
      l.assetId = j.getString("assetId");
      if (
        (l.type == Project.Type.VIDEO ||
          l.type == Project.Type.AUDIO ||
          l.type == Project.Type.IMAGE) &&
        !p.assets.containsKey(l.assetId)
      ) throw new JSONException("Layer references an undefined asset");
      l.parentId = j.optString("parentId", "");
      l.visible = j.getBoolean("visible");
      l.locked = j.getBoolean("locked");
      l.muted = j.getBoolean("muted");
      l.startUs = j.getLong("startUs");
      l.endUs = j.getLong("endUs");
      l.sourceInUs = j.getLong("sourceInUs");
      if (
        l.startUs < 0 ||
        l.endUs <= l.startUs ||
        l.endUs > p.durationUs ||
        l.sourceInUs < 0 ||
        l.sourceInUs > 86_400_000_000L
      ) throw new JSONException("Invalid clip range");
      l.text = j.getString("text");
      l.font = j.getString("font");
      l.align = j.getString("align");
      l.shape = j.getString("shape");
      l.color = j.getInt("color");
      l.strokeColor = j.getInt("strokeColor");
      l.fontStyle = j.getInt("fontStyle");
      l.sides = Math.max(3, Math.min(64, j.getInt("sides")));
      readProperties(j.getJSONObject("properties"), l.props);
      JSONArray es = j.getJSONArray("effects");
      for (int n = 0; n < es.length(); n++) {
        JSONObject e = es.getJSONObject(n);
        Project.Effect effect = new Project.Effect(e.getString("kind"), 0);
        effect.enabled = e.getBoolean("enabled");
        readProperties(e.getJSONObject("parameters"), effect.params);
        l.effects.add(effect);
      }
      if (j.has("mask")) {
        JSONObject m = j.getJSONObject("mask");
        l.mask = new Project.Mask();
        l.mask.kind = m.getString("kind");
        if (
          !Arrays.asList("RECTANGLE", "ELLIPSE").contains(l.mask.kind)
        ) throw new JSONException("Unsupported mask type");
        l.mask.invert = m.getBoolean("invert");
        readProperties(m.getJSONObject("properties"), l.mask.props);
      }
      p.layers.add(l);
    }
    for (Project.Layer l : p.layers) {
      Set<String> seen = new HashSet<>();
      Project.Layer at = l;
      while (!at.parentId.isEmpty()) {
        if (!seen.add(at.id)) throw new JSONException("Parent cycle");
        at = p.layer(at.parentId);
        if (at == null) throw new JSONException("Missing parent");
      }
    }
    return p;
  }

  private ProjectJson() {}
}
