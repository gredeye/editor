package app.vynox;

import static org.junit.Assert.*;

import android.content.Context;
import android.graphics.*;
import android.media.*;
import android.net.Uri;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import app.vynox.media.AssetManager;
import app.vynox.model.Project;
import app.vynox.render.*;
import java.io.*;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Runs on emulator/device; exercises platform decoding, compositing and AVC encoding. */
@RunWith(AndroidJUnit4.class)
public class RenderTest {

  private Context getContext() {
    return InstrumentationRegistry.getInstrumentation().getTargetContext();
  }

  @Test
  public void testShapeCompositionAndMask() throws Exception {
    File root = getContext().getCacheDir();
    Project p = new Project();
    p.width = 320;
    p.height = 240;
    p.background = Color.BLACK;
    Project.Layer l = new Project.Layer();
    l.set("x", 160);
    l.set("y", 120);
    l.set("width", 100);
    l.set("height", 100);
    l.color = Color.RED;
    p.layers.add(l);
    try (CompositionRenderer r = new CompositionRenderer(root)) {
      Bitmap b = r.frame(p, 0, 320, 240);
      assertEquals(Color.RED, b.getPixel(160, 120));
      assertEquals(Color.BLACK, b.getPixel(0, 0));
      b.recycle();
      l.mask = new Project.Mask();
      l.mask.props.get("width").value = 20;
      l.mask.props.get("height").value = 20;
      b = r.frame(p, 0, 320, 240);
      assertEquals(Color.RED, b.getPixel(160, 120));
      assertEquals(Color.BLACK, b.getPixel(190, 120));
      b.recycle();
    }
  }

  @Test
  public void testRealMp4Export() throws Exception {
    Project p = new Project();
    p.width = 320;
    p.height = 240;
    p.durationUs = 500000;
    Project.Layer l = new Project.Layer();
    l.endUs = p.durationUs;
    l.set("x", 160);
    l.set("y", 120);
    l.color = Color.RED;
    p.layers.add(l);
    File out = new File(getContext().getCacheDir(), "test-export.mp4");
    VideoExporter.Options o = new VideoExporter.Options();
    o.width = 320;
    o.height = 240;
    o.fps = 24;
    o.bitrate = 1000000;
    try {
      VideoExporter.export(
        p,
        getContext().getCacheDir(),
        out,
        getContext().getCacheDir(),
        o,
        () -> false,
        n -> {}
      );
      assertTrue(out.length() > 100);
      try (MediaMetadataRetriever r = new MediaMetadataRetriever()) {
        r.setDataSource(out.getPath());
        assertEquals(
          "320",
          r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
        );
        Bitmap b = r.getFrameAtTime(0);
        assertNotNull(b);
        int c = b.getPixel(160, 120);
        assertTrue(Color.red(c) > 200);
        assertTrue(Color.green(c) < 60);
        b.recycle();
      }
    } finally {
      out.delete();
    }
  }

  @Test
  public void testImageImportCopiesBytes() throws Exception {
    File root = new File(getContext().getCacheDir(), "import-test");
    root.mkdirs();
    File original = new File(getContext().getCacheDir(), "source.png");
    Bitmap b = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888);
    b.eraseColor(Color.BLUE);
    try (FileOutputStream out = new FileOutputStream(original)) {
      b.compress(Bitmap.CompressFormat.PNG, 100, out);
    }
    b.recycle();
    try {
      Project.Asset asset = AssetManager.importLocal(
        getContext(),
        Uri.fromFile(original),
        root,
        null
      );
      assertTrue(new File(root, asset.file).isFile());
      assertEquals(20, asset.width);
      new File(root, asset.file).delete();
    } finally {
      original.delete();
    }
  }

  @Test
  public void testExportCancellationRemovesPartialOutput() throws Exception {
    File output = new File(getContext().getCacheDir(), "cancelled.mp4");
    Project p = new Project();
    VideoExporter.Options options = new VideoExporter.Options();
    options.width = 320;
    options.height = 240;
    try {
      VideoExporter.export(
        p,
        getContext().getCacheDir(),
        output,
        getContext().getCacheDir(),
        options,
        () -> true,
        n -> {}
      );
      fail("Expected cancellation");
    } catch (InterruptedIOException expected) {
      assertFalse(output.exists());
    }
  }

  @Test
  public void testAudioVolumeMuteAndPlacement() throws Exception {
    File root = getContext().getCacheDir();
    File media = new File(root, "media");
    media.mkdirs();
    File wav = new File(media, "test-tone.wav");
    int frames = 4800;
    java.nio.ByteBuffer bytes = java.nio.ByteBuffer.allocate(
      44 + frames * 4
    ).order(java.nio.ByteOrder.LITTLE_ENDIAN);
    bytes.put("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    bytes.putInt(36 + frames * 4);
    bytes.put("WAVEfmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    bytes.putInt(16);
    bytes.putShort((short) 1);
    bytes.putShort((short) 2);
    bytes.putInt(48000);
    bytes.putInt(192000);
    bytes.putShort((short) 4);
    bytes.putShort((short) 16);
    bytes.put("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    bytes.putInt(frames * 4);
    for (int i = 0; i < frames; i++) {
      bytes.putShort((short) 16000);
      bytes.putShort((short) 8000);
    }
    java.nio.file.Files.write(wav.toPath(), bytes.array());
    Project p = new Project();
    Project.Asset asset = new Project.Asset();
    asset.file = "media/test-tone.wav";
    asset.mime = "audio/wav";
    p.assets.put(asset.id, asset);
    Project.Layer layer = new Project.Layer();
    layer.type = Project.Type.AUDIO;
    layer.assetId = asset.id;
    layer.startUs = 100000;
    layer.endUs = 200000;
    layer.set("volume", .5);
    p.layers.add(layer);
    try (
      app.vynox.media.AudioMixer mixer = new app.vynox.media.AudioMixer(
        p,
        root,
        root,
        () -> false
      )
    ) {
      assertTrue(mixer.hasAudio());
      assertEquals(0, mixer.mix(0, 1)[0]);
      short[] samples = mixer.mix(4800, 10);
      assertEquals(8000, samples[0], 2);
      assertEquals(4000, samples[1], 2);
      layer.muted = true;
      assertEquals(0, mixer.mix(4800, 1)[0]);
    } finally {
      wav.delete();
    }
  }

  @Test
  public void testActivityOpensSavedProject() throws Exception {
    Project p = new Project();
    p.name = "UI smoke";
    new app.vynox.storage.ProjectStore(getContext()).save(p);
    android.app.Instrumentation instrumentation =
      InstrumentationRegistry.getInstrumentation();
    android.app.Activity activity = instrumentation.startActivitySync(
      new android.content.Intent(
        getContext(),
        app.vynox.ui.MainActivity.class
      ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    );
    instrumentation.waitForIdleSync();
    try {
      instrumentation.runOnMainSync(() -> {
        android.view.View button = findText(
          activity.getWindow().getDecorView(),
          "UI smoke",
          true
        );
        assertNotNull("Saved project must appear on Home", button);
        button.performClick();
        assertNotNull(
          "Editor controls must be visible",
          findText(activity.getWindow().getDecorView(), "Inspector", false)
        );
      });
    } finally {
      instrumentation.runOnMainSync(activity::finish);
    }
  }

  private android.view.View findText(
    android.view.View view,
    String text,
    boolean startsWith
  ) {
    if (view instanceof android.widget.TextView) {
      String value = ((android.widget.TextView) view).getText().toString();
      if (startsWith ? value.startsWith(text) : value.equals(text)) return view;
    }
    if (view instanceof android.view.ViewGroup) {
      android.view.ViewGroup group = (android.view.ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) {
        android.view.View result = findText(
          group.getChildAt(i),
          text,
          startsWith
        );
        if (result != null) return result;
      }
    }
    return null;
  }
}
