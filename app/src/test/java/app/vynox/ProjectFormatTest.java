package app.vynox;

import static org.junit.Assert.*;

import app.vynox.format.*;
import app.vynox.model.*;
import java.io.*;
import java.nio.file.*;
import java.util.zip.*;
import org.json.*;
import org.junit.*;

public class ProjectFormatTest {

  private Project rich() {
    Project p = new Project();
    p.name = "Motion α";
    p.metadata.put("author", "offline");
    Project.Asset a = new Project.Asset();
    a.file = "media/test.asset";
    a.name = "test.png";
    a.mime = "image/png";
    p.assets.put(a.id, a);
    for (Project.Type type : Project.Type.values()) {
      Project.Layer l = new Project.Layer();
      l.type = type;
      l.assetId = a.id;
      l.mask = new Project.Mask();
      l.mask.invert = true;
      l.effects.add(new Project.Effect("Hue", 90));
      l.props.get("rotation").put(0, 20, new Animation.Curve(.2, -.1, .8, 1.2));
      l.props.get("rotation").put(2000000, 360, Animation.Curve.linear());
      l.blend = Project.Blend.SCREEN;
      p.layers.add(l);
    }
    return p;
  }

  @Test
  public void roundTripEverything() throws Exception {
    Project a = rich(),
      b = ProjectJson.decode(ProjectJson.encode(a));
    assertEquals(a.name, b.name);
    assertEquals(a.layers.size(), b.layers.size());
    assertEquals("offline", b.metadata.get("author"));
    assertTrue(b.layers.get(0).mask.invert);
    assertEquals("Hue", b.layers.get(0).effects.get(0).kind);
    assertEquals(
      90,
      b.layers.get(0).effects.get(0).params.get("amount").value,
      0
    );
    assertEquals(
      a.layers.get(0).at("rotation", 1000000),
      b.layers.get(0).at("rotation", 1000000),
      0
    );
    assertEquals(Project.Blend.SCREEN, b.layers.get(0).blend);
    assertTrue(
      new JSONObject(ProjectJson.encode(a)).similar(
        new JSONObject(ProjectJson.encode(b))
      )
    );
  }

  @Test(expected = JSONException.class)
  public void futureVersionRejected() throws Exception {
    JSONObject o = new JSONObject(ProjectJson.encode(rich()));
    o.put("schema", 99);
    ProjectJson.decode(o.toString());
  }

  @Test(expected = JSONException.class)
  public void badCanvasRejected() throws Exception {
    Project p = rich();
    p.width = 0;
    ProjectJson.decode(ProjectJson.encode(p));
  }

  @Test(expected = JSONException.class)
  public void unsafeAssetRejected() throws Exception {
    Project p = rich();
    p.assets.values().iterator().next().file = "../../private";
    ProjectJson.decode(ProjectJson.encode(p));
  }

  @Test(expected = JSONException.class)
  public void cycleRejected() throws Exception {
    Project p = rich();
    p.layers.get(0).parentId = p.layers.get(1).id;
    p.layers.get(1).parentId = p.layers.get(0).id;
    ProjectJson.decode(ProjectJson.encode(p));
  }

  @Test(expected = JSONException.class)
  public void duplicateIdsRejected() throws Exception {
    Project p = rich();
    p.layers.get(1).id = p.layers.get(0).id;
    ProjectJson.decode(ProjectJson.encode(p));
  }

  @Test
  public void zipRoundTripIncludesMedia() throws Exception {
    Project p = rich();
    File root = Files.createTempDirectory("vnx-source").toFile(),
      dest = Files.createTempDirectory("vnx-dest").toFile();
    try {
      new File(root, "media").mkdir();
      Files.write(new File(root, "media/test.asset").toPath(), new byte[] {
        1,
        2,
        3,
      });
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      VnxArchive.write(p, root, bytes);
      Project q = VnxArchive.read(
        new ByteArrayInputStream(bytes.toByteArray()),
        dest
      );
      assertEquals(p.id, q.id);
      assertArrayEquals(
        new byte[] { 1, 2, 3 },
        Files.readAllBytes(new File(dest, "media/test.asset").toPath())
      );
    } finally {
      delete(root);
      delete(dest);
    }
  }

  @Test
  public void missingMediaDoesNotDestroyProject() throws Exception {
    Project p = rich();
    File root = Files.createTempDirectory("vnx-missing").toFile(),
      dest = Files.createTempDirectory("vnx-open").toFile();
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      VnxArchive.write(p, root, bytes);
      Project q = VnxArchive.read(
        new ByteArrayInputStream(bytes.toByteArray()),
        dest
      );
      assertEquals(p.assets.keySet(), q.assets.keySet());
      assertFalse(new File(dest, "media/test.asset").exists());
    } finally {
      delete(root);
      delete(dest);
    }
  }

  @Test
  public void zipTraversalRejected() throws Exception {
    File root = Files.createTempDirectory("vnx-safe").toFile();
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
        zip.putNextEntry(new ZipEntry("../escaped"));
        zip.write(1);
        zip.closeEntry();
      }
      try {
        VnxArchive.read(new ByteArrayInputStream(bytes.toByteArray()), root);
        fail();
      } catch (IOException expected) {
        assertEquals("Unexpected archive entry", expected.getMessage());
      }
    } finally {
      delete(root);
    }
  }

  private void delete(File f) {
    File[] list = f.listFiles();
    if (list != null) for (File c : list) delete(c);
    f.delete();
  }
}
