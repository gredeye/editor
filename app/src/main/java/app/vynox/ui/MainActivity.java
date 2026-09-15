package app.vynox.ui;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import app.vynox.BuildConfig;
import app.vynox.engine.*;
import app.vynox.format.*;
import app.vynox.media.AssetManager;
import app.vynox.media.PreviewPlayer;
import app.vynox.model.Project;
import app.vynox.render.*;
import app.vynox.storage.ProjectStore;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity {

  private static final int IMPORT_MEDIA = 10,
    OPEN_PROJECT = 11,
    SAVE_PROJECT = 12,
    EXPORT_VIDEO = 13,
    RELINK = 14;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final ExecutorService io = Executors.newSingleThreadExecutor();
  private ProjectStore store;
  private EditorState state;
  private Inspector inspector;
  private PreviewView preview;
  private TimelineView timeline;
  private PreviewPlayer player;
  private LinearLayout root;
  private TextView timeLabel, statusLabel;
  private Button playButton;
  private String relinkId;
  private boolean playing, preparing, destroyed;
  private VideoExporter.Options exportOptions;
  private Project pendingExport;
  private int playbackGeneration;
  private ProgressBar exportProgress;
  private final Runnable ticker = new Runnable() {
    public void run() {
      if (destroyed) return;
      if (playing && player != null && player.ready()) {
        state.seek(player.positionUs());
        if (state.playheadUs >= state.project.durationUs - 40000) {
          pause();
        }
        refreshPreview();
      }
      if (statusLabel != null) {
        android.content.SharedPreferences prefs = getSharedPreferences(
          ExportService.PREFS,
          0
        );
        statusLabel.setText(
          prefs.getString("status", "All editing stays on this device")
        );
        if (exportProgress != null) exportProgress.setProgress(
          prefs.getInt("progress", 0)
        );
      }
      handler.postDelayed(this, 33);
    }
  };

  @Override
  public void onCreate(Bundle saved) {
    super.onCreate(saved);
    store = new ProjectStore(this);
    if (
      !ExportService.running &&
      getSharedPreferences(ExportService.PREFS, 0).getBoolean("active", false)
    ) getSharedPreferences(ExportService.PREFS, 0)
      .edit()
      .putBoolean("active", false)
      .putString("status", "Previous export interrupted. Please export again.")
      .apply();
    getWindow().setStatusBarColor(0xff101217);
    getWindow().setNavigationBarColor(0xff101217);
    if (Build.VERSION.SDK_INT >= 35) {
      getWindow()
        .getDecorView()
        .setOnApplyWindowInsetsListener((view, insets) -> {
          android.graphics.Insets bars = insets.getInsets(
            WindowInsets.Type.systemBars()
          );
          view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
          return insets;
        });
    }
    if (saved != null && saved.getString("project") != null) {
      try {
        open(store.load(saved.getString("project")));
        state.seek(saved.getLong("playhead"));
      } catch (Exception e) {
        home();
        error(e);
      }
    } else home();
    handler.post(ticker);
    if (
      Intent.ACTION_VIEW.equals(getIntent().getAction()) &&
      getIntent().getData() != null
    ) importProject(getIntent().getData());
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    if (intent.getData() != null) importProject(intent.getData());
  }

  private int dp(float n) {
    return (int) (getResources().getDisplayMetrics().density * n);
  }

  private LinearLayout column() {
    LinearLayout l = new LinearLayout(this);
    l.setOrientation(LinearLayout.VERTICAL);
    return l;
  }

  private LinearLayout row() {
    LinearLayout l = new LinearLayout(this);
    l.setOrientation(LinearLayout.HORIZONTAL);
    l.setGravity(Gravity.CENTER_VERTICAL);
    return l;
  }

  private TextView label(String text, int size, int color) {
    TextView t = new TextView(this);
    t.setText(text);
    t.setTextColor(color);
    t.setTextSize(size);
    t.setPadding(dp(16), dp(8), dp(16), dp(8));
    return t;
  }

  private Button button(String text, Runnable action) {
    Button b = new Button(this);
    b.setText(text);
    b.setTextSize(12);
    b.setAllCaps(false);
    b.setTextColor(0xffe9eef5);
    b.setMinWidth(dp(54));
    b.setMinimumWidth(dp(54));
    b.setOnClickListener(v -> {
      try {
        action.run();
      } catch (Exception e) {
        error(e);
      }
    });
    return b;
  }

  private void base() {
    if (preview != null) {
      preview.close();
      preview = null;
    }
    root = column();
    root.setBackgroundColor(0xff101217);
    setContentView(root);
    statusLabel = null;
    exportProgress = null;
  }

  private void home() {
    pause();
    if (state != null) try {
      store.save(state.project);
    } catch (Exception e) {
      error(e);
    }
    base();
    LinearLayout heading = row();
    TextView brand = label("∨  VYNOX", 24, 0xffb3f56a);
    brand.setTypeface(null, Typeface.BOLD);
    heading.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
    heading.addView(button("Settings", this::settings));
    root.addView(heading);
    ScrollView scroll = new ScrollView(this);
    LinearLayout content = column();
    content.setPadding(dp(8), dp(18), dp(8), dp(24));
    scroll.addView(content);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    TextView title = label("Make your\nnext move.", 40, Color.WHITE);
    title.setTypeface(null, Typeface.BOLD);
    GradientDrawable gradient = new GradientDrawable(
      GradientDrawable.Orientation.TL_BR,
      new int[] { 0xff263729, 0xff171c2b }
    );
    gradient.setCornerRadius(dp(20));
    title.setBackground(gradient);
    title.setPadding(dp(24), dp(24), dp(24), dp(24));
    content.addView(title);
    content.addView(label("VIDEO  /  MOTION  /  YOUR DEVICE", 11, 0xffa1acb8));
    LinearLayout actions = row();
    actions.addView(
      button("＋ New project", this::newProject),
      new LinearLayout.LayoutParams(0, dp(58), 1)
    );
    actions.addView(
      button("Open .vnx", () -> pick(OPEN_PROJECT, "*/*")),
      new LinearLayout.LayoutParams(0, dp(58), 1)
    );
    content.addView(actions);
    content.addView(label("YOUR PROJECTS", 13, 0xffb3f56a));
    List<File> projects = store.list();
    if (projects.isEmpty()) content.addView(
      label(
        "A blank canvas. A new possibility.\nCreate a project or import a portable .vnx file.",
        16,
        0xff9da6b6
      )
    );
    for (File folder : projects) {
      try {
        Project p = store.load(folder.getName());
        Button card = button(
          p.name +
            "\n" +
            p.width +
            " × " +
            p.height +
            "  ·  " +
            p.fps +
            " fps  ·  " +
            String.format(Locale.US, "%.1f s", p.durationUs / 1e6),
          () -> open(p)
        );
        card.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        card.setPadding(dp(20), dp(12), dp(20), dp(12));
        content.addView(card, new LinearLayout.LayoutParams(-1, dp(90)));
      } catch (Exception e) {
        content.addView(
          label(
            "Recovery file could not be opened: " + folder.getName(),
            13,
            0xffffbb99
          )
        );
      }
    }
    content.addView(
      button("About Vynox", () ->
        new AlertDialog.Builder(this)
          .setTitle("Vynox " + BuildConfig.VERSION_NAME)
          .setMessage(
            "Offline video & motion editor\n\nNative Android composition, local assets and portable .vnx projects.\n\nThis is an initial implementation, not a device-certified production release. See the repository for tested status and limitations.\n\n© Vynox contributors"
          )
          .setPositiveButton("Repository", (d, n) ->
            startActivity(
              new Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://github.com/gredeye/editor")
              )
            )
          )
          .setNegativeButton("Close", null)
          .show()
      )
    );
  }

  private void newProject() {
    Forms f = new Forms(this);
    f.add("Name", "Untitled", false)
      .add("Width", 1280, true)
      .add("Height", 720, true)
      .add("FPS", 30, true)
      .add("Duration seconds", 10, true);
    f.show("New composition", () -> {
      Project p = new Project();
      p.name = f.text("Name");
      p.width = f.integer("Width");
      p.height = f.integer("Height");
      p.fps = f.integer("FPS");
      p.durationUs = (long) (f.number("Duration seconds") * 1e6);
      try {
        ProjectJson.decode(ProjectJson.encode(p));
        store.save(p);
      } catch (Exception e) {
        throw new IllegalArgumentException(e.getMessage());
      }
      open(p);
    });
  }

  private void open(Project p) {
    pause();
    state = new EditorState(p);
    inspector = new Inspector(this, state);
    state.onChange = () -> {
      pause();
      saveAsync();
      refreshPreview();
    };
    getSharedPreferences("settings", 0)
      .edit()
      .putString("lastProject", p.id)
      .apply();
    editor();
    missing();
  }

  private void editor() {
    base();
    LinearLayout header = row();
    header.addView(button("‹", this::home));
    TextView title = label(state.project.name, 18, Color.WHITE);
    title.setSingleLine(true);
    header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
    header.addView(
      button("Save / share", () -> {
        pause();
        create(
          SAVE_PROJECT,
          "application/vnd.vynox.project",
          safeName(state.project.name) + ".vnx"
        );
      })
    );
    header.addView(button("Export", this::exportScreen));
    root.addView(header);
    preview = new PreviewView(this, state, store.root(state.project.id));
    root.addView(preview, new LinearLayout.LayoutParams(-1, 0, 1));
    LinearLayout transport = row();
    transport.addView(button("↶", state::undo));
    transport.addView(button("↷", state::redo));
    transport.addView(
      button("|‹", () -> {
        pause();
        state.seek(0);
        refreshPreview();
      })
    );
    playButton = button("▶", this::togglePlay);
    transport.addView(playButton);
    timeLabel = label("", 12, 0xffc8d0de);
    transport.addView(timeLabel, new LinearLayout.LayoutParams(0, -2, 1));
    transport.addView(button("Fit / zoom", () -> preview.zoom()));
    root.addView(transport);
    timeline = new TimelineView(this, state);
    timeline.snapping = getSharedPreferences("settings", 0).getBoolean(
      "snapping",
      true
    );
    timeline.onSeek = () -> {
      pause();
      refreshPreview();
    };
    timeline.onSelect = () -> {
      pause();
      refreshPreview();
    };
    root.addView(timeline, new LinearLayout.LayoutParams(-1, dp(200)));
    HorizontalScrollView tools = new HorizontalScrollView(this);
    LinearLayout bar = row();
    bar.addView(button("＋ Layer", this::addLayer));
    bar.addView(
      button("Inspector", () -> {
        pause();
        layerMenu();
      })
    );
    bar.addView(
      button("◇ Animate", () -> {
        pause();
        inspector.animation();
      })
    );
    bar.addView(
      button("Split", () -> {
        pause();
        Project.Layer l = required();
        state.edit(p -> Timeline.split(p, p.layer(l.id), state.playheadUs));
      })
    );
    bar.addView(button("−", () -> timeline.zoom(false)));
    bar.addView(button("＋", () -> timeline.zoom(true)));
    bar.addView(button("Project", this::projectMenu));
    tools.addView(bar);
    root.addView(tools);
    refreshPreview();
  }

  private String safeName(String name) {
    return name.replaceAll("[^\\p{L}\\p{N} ._-]", "_");
  }

  private Project.Layer required() {
    Project.Layer l = state.selected();
    if (l == null) throw new IllegalStateException("Select a layer first");
    return l;
  }

  private void refreshPreview() {
    if (preview != null) {
      preview.requestFrame();
      timeline.invalidate();
      long t = state.playheadUs;
      timeLabel.setText(
        String.format(
          Locale.US,
          "%02d:%02d.%03d",
          t / 60000000,
          (t / 1000000) % 60,
          (t / 1000) % 1000
        )
      );
    }
  }

  private void addLayer() {
    pause();
    new AlertDialog.Builder(this)
      .setTitle("Add to composition")
      .setItems(
        new String[] {
          "Local video",
          "Local image",
          "Local audio",
          "Text",
          "Shape",
          "Null / transform parent",
        },
        (d, n) -> {
          if (n < 3) pick(
            IMPORT_MEDIA,
            n == 0 ? "video/*" : n == 1 ? "image/*" : "audio/*"
          );
          else {
            Project.Layer l = new Project.Layer();
            l.type =
              n == 3
                ? Project.Type.TEXT
                : n == 4
                  ? Project.Type.SHAPE
                  : Project.Type.NULL;
            l.name = n == 3 ? "Text" : n == 4 ? "Shape" : "Null";
            l.startUs = state.playheadUs;
            l.endUs = state.project.durationUs;
            l.set("x", n == 5 ? 0 : state.project.width / 2.0);
            l.set("y", n == 5 ? 0 : state.project.height / 2.0);
            state.selectedId = l.id;
            state.edit(p -> p.layers.add(l));
          }
        }
      )
      .show();
  }

  private void layerMenu() {
    Project.Layer selected = required();
    String[] choices = {
      "Properties / transform / volume",
      "Timing / move / duration",
      "Trim",
      "Text",
      "Shape",
      "Effects",
      "Mask",
      "Blend mode",
      "Transform parent",
      "Visibility",
      "Lock / unlock",
      "Mute / unmute",
      "Move forward",
      "Move backward",
      "Duplicate",
      "Delete",
    };
    new AlertDialog.Builder(this)
      .setTitle(selected.name)
      .setItems(choices, (d, n) -> {
        try {
          switch (n) {
            case 0:
              inspector.properties();
              break;
            case 1:
              inspector.timing();
              break;
            case 2:
              inspector.trim();
              break;
            case 3:
              inspector.text();
              break;
            case 4:
              inspector.shape();
              break;
            case 5:
              inspector.effects();
              break;
            case 6:
              inspector.mask();
              break;
            case 7:
              inspector.blend();
              break;
            case 8:
              inspector.parent();
              break;
            default:
              state.edit(p -> {
                Project.Layer l = p.layer(selected.id);
                if (n == 10) {
                  l.locked = !l.locked;
                  return;
                }
                if (l.locked) throw new IllegalStateException(
                  "Unlock the layer first"
                );
                switch (n) {
                  case 9:
                    l.visible = !l.visible;
                    break;
                  case 11:
                    l.muted = !l.muted;
                    break;
                  case 12:
                    Timeline.reorder(p, l, p.layers.indexOf(l) + 1);
                    break;
                  case 13:
                    Timeline.reorder(p, l, p.layers.indexOf(l) - 1);
                    break;
                  case 14:
                    Project.Layer copy = l.copy();
                    copy.id = UUID.randomUUID().toString();
                    copy.name += " copy";
                    p.layers.add(copy);
                    break;
                  case 15:
                    p.layers.remove(l);
                    for (Project.Layer child : p.layers)
                      if (child.parentId.equals(l.id)) child.parentId = "";
                    break;
                }
              });
          }
        } catch (Exception e) {
          error(e);
        }
      })
      .show();
  }

  private void projectMenu() {
    pause();
    new AlertDialog.Builder(this)
      .setTitle("Project")
      .setItems(
        new String[] {
          "Composition settings",
          "Assets / relink",
          "Save local now",
          "Back to projects",
        },
        (d, n) -> {
          if (n == 0) {
            Forms f = new Forms(this);
            f.add("Name", state.project.name, false)
              .add("Duration seconds", state.project.durationUs / 1e6, true)
              .add(
                "Background (#AARRGGBB)",
                String.format("#%08X", state.project.background),
                false
              );
            f.show("Composition settings", () -> {
              long duration = (long) (f.number("Duration seconds") * 1e6);
              int color = Color.parseColor(f.text("Background (#AARRGGBB)"));
              if (
                duration <= 0 || duration > 3600000000L
              ) throw new IllegalArgumentException(
                "Duration must be 0–3600 seconds"
              );
              for (Project.Layer l : state.project.layers)
                if (l.endUs > duration) throw new IllegalArgumentException(
                  "Trim layers before shortening composition"
                );
              state.edit(p -> {
                p.name = f.text("Name");
                p.durationUs = duration;
                p.background = color;
              });
            });
          } else if (n == 1) assets();
          else if (n == 2) saveAsync();
          else home();
        }
      )
      .show();
  }

  private void assets() {
    List<Project.Asset> list = new ArrayList<>(state.project.assets.values());
    Set<String> missing = new HashSet<>();
    AssetManager.missing(state.project, store.root(state.project.id)).forEach(
      a -> missing.add(a.id)
    );
    String[] names = list
      .stream()
      .map(a -> (missing.contains(a.id) ? "MISSING · " : "") + a.name)
      .toArray(String[]::new);
    new AlertDialog.Builder(this)
      .setTitle("Assets · tap to relink (ID preserved)")
      .setItems(names, (d, n) -> {
        relinkId = list.get(n).id;
        pick(
          RELINK,
          list.get(n).mime.startsWith("image/")
            ? "image/*"
            : list.get(n).mime.startsWith("audio/")
              ? "audio/*"
              : "video/*"
        );
      })
      .setNegativeButton("Close", null)
      .show();
  }

  private void missing() {
    if (
      !AssetManager.missing(
        state.project,
        store.root(state.project.id)
      ).isEmpty()
    ) new AlertDialog.Builder(this)
      .setTitle("Missing media")
      .setMessage(
        "The project remains editable. Relink missing assets to restore preview and enable export."
      )
      .setPositiveButton("Relink", (d, n) -> assets())
      .setNegativeButton("Later", null)
      .show();
  }

  private void saveAsync() {
    Project snapshot = state.project.copy();
    io.execute(() -> {
      try {
        store.save(snapshot);
      } catch (Exception e) {
        runOnUiThread(() -> error(e));
      }
    });
  }

  private void togglePlay() {
    if (playing || preparing) {
      pause();
      return;
    }
    if (state.playheadUs >= state.project.durationUs - 40000) state.seek(0);
    preparing = true;
    playButton.setText("…");
    int generation = ++playbackGeneration;
    player = new PreviewPlayer();
    player.play(
      state.project.copy(),
      store.root(state.project.id),
      new File(getCacheDir(), "preview-audio"),
      state.playheadUs,
      new PreviewPlayer.Listener() {
        public void ready() {
          runOnUiThread(() -> {
            if (generation != playbackGeneration || destroyed) return;
            playing = true;
            preparing = false;
            if (playButton != null) playButton.setText("Ⅱ");
          });
        }

        public void completed() {
          runOnUiThread(() -> {
            if (generation != playbackGeneration || destroyed) return;
            pause();
            state.seek(state.project.durationUs - 1);
            refreshPreview();
          });
        }

        public void failed(String message) {
          runOnUiThread(() -> {
            if (generation != playbackGeneration || destroyed) return;
            pause();
            error(new IOException(message));
          });
        }
      }
    );
  }

  private void pause() {
    playbackGeneration++;
    playing = false;
    preparing = false;
    if (player != null) {
      player.close();
      player = null;
    }
    if (playButton != null) playButton.setText("▶");
  }

  private void pick(int code, String mime) {
    pause();
    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT)
      .addCategory(Intent.CATEGORY_OPENABLE)
      .setType(mime);
    startActivityForResult(i, code);
  }

  private void create(int code, String mime, String name) {
    Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT)
      .addCategory(Intent.CATEGORY_OPENABLE)
      .setType(mime)
      .putExtra(Intent.EXTRA_TITLE, name);
    startActivityForResult(i, code);
  }

  private interface Task {
    void run() throws Exception;
  }

  private void task(String message, Task work) {
    ProgressDialog dialog = new ProgressDialog(this);
    dialog.setMessage(message);
    dialog.setCancelable(false);
    dialog.show();
    io.execute(() -> {
      try {
        work.run();
      } catch (Exception e) {
        runOnUiThread(() -> error(e));
      } finally {
        runOnUiThread(() -> {
          if (!isFinishing() && !destroyed) dialog.dismiss();
        });
      }
    });
  }

  @Override
  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (result != RESULT_OK || data == null || data.getData() == null) return;
    Uri uri = data.getData();
    if (request == OPEN_PROJECT) {
      importProject(uri);
      return;
    }
    if (state == null) return;
    if (request == IMPORT_MEDIA || request == RELINK) {
      String projectId = state.project.id,
        replace = request == RELINK ? relinkId : null;
      long position = state.playheadUs;
      task("Copying media to local project storage…", () -> {
        Project.Asset a = AssetManager.importLocal(
          this,
          uri,
          store.root(projectId),
          replace
        );
        runOnUiThread(() -> {
          if (state == null || !state.project.id.equals(projectId)) return;
          try {
            state.edit(p -> {
              p.assets.put(a.id, a);
              if (replace == null) {
                Project.Layer l = new Project.Layer();
                l.assetId = a.id;
                l.name = a.name;
                l.type = a.mime.startsWith("image/")
                  ? Project.Type.IMAGE
                  : a.mime.startsWith("audio/")
                    ? Project.Type.AUDIO
                    : Project.Type.VIDEO;
                l.startUs = position;
                l.endUs =
                  a.durationUs > 0
                    ? Math.min(p.durationUs, position + a.durationUs)
                    : p.durationUs;
                l.set("x", p.width / 2.0);
                l.set("y", p.height / 2.0);
                if (a.width > 0 && a.height > 0) {
                  double scale = Math.min(
                    (double) p.width / a.width,
                    (double) p.height / a.height
                  );
                  l.set("width", a.width * scale);
                  l.set("height", a.height * scale);
                }
                p.layers.add(l);
                state.selectedId = l.id;
              }
            });
          } catch (Exception e) {
            error(e);
          }
        });
      });
    } else if (request == SAVE_PROJECT) {
      Project snapshot = state.project.copy();
      task("Packaging project and local assets…", () -> {
        try (
          OutputStream out = getContentResolver().openOutputStream(uri, "wt")
        ) {
          if (out == null) throw new IOException("Cannot write project");
          VnxArchive.write(snapshot, store.root(snapshot.id), out);
        }
        runOnUiThread(() ->
          Toast.makeText(
            this,
            "Portable .vnx saved. Share it from your Files app.",
            Toast.LENGTH_LONG
          ).show()
        );
      });
    } else if (request == EXPORT_VIDEO) {
      Project snapshot = pendingExport;
      VideoExporter.Options o = exportOptions;
      if (snapshot == null || o == null) {
        error(new IOException("Export settings lost; please try again"));
        return;
      }
      task("Preparing export…", () -> {
        Files.write(
          new File(getFilesDir(), "export-snapshot.json").toPath(),
          ProjectJson.encode(snapshot).getBytes(
            java.nio.charset.StandardCharsets.UTF_8
          )
        );
        runOnUiThread(() -> {
          Intent i = new Intent(this, ExportService.class)
            .putExtra("uri", uri.toString())
            .putExtra("width", o.width)
            .putExtra("height", o.height)
            .putExtra("fps", o.fps)
            .putExtra("bitrate", o.bitrate);
          startForegroundService(i);
        });
      });
    }
  }

  private void importProject(Uri uri) {
    pause();
    task("Opening portable project…", () -> {
      String id = UUID.randomUUID().toString();
      File root = store.root(id);
      try (InputStream in = getContentResolver().openInputStream(uri)) {
        if (in == null) throw new IOException("Cannot open project");
        Project p = VnxArchive.read(in, root);
        p.id = id;
        store.save(p);
        runOnUiThread(() -> open(p));
      } catch (Exception e) {
        deleteTree(root);
        throw e;
      }
    });
  }

  private void deleteTree(File f) {
    File[] children = f.listFiles();
    if (children != null) for (File c : children) deleteTree(c);
    f.delete();
  }

  private void exportScreen() {
    pause();
    if (
      !AssetManager.missing(
        state.project,
        store.root(state.project.id)
      ).isEmpty()
    ) {
      missing();
      return;
    }
    base();
    root.addView(button("‹ Back to editor", this::editor));
    root.addView(label("Ready for\nthe big screen.", 36, Color.WHITE));
    root.addView(
      label(
        "MP4 / H.264 + AAC\nRendered locally from the actual composition.\nKeep Vynox open for best reliability on long exports.",
        15,
        0xffa5aebe
      )
    );
    root.addView(
      button("Configure & export MP4", () -> {
        if (ExportService.running) throw new IllegalStateException(
          "An export is already running"
        );
        Forms f = new Forms(this);
        f.add("Width", state.project.width, true)
          .add("Height", state.project.height, true)
          .add("FPS", state.project.fps, true)
          .add("Bitrate Mbps", 8, true);
        f.show("Export settings", () -> {
          VideoExporter.Options o = new VideoExporter.Options();
          o.width = f.integer("Width");
          o.height = f.integer("Height");
          o.fps = f.integer("FPS");
          o.bitrate = (int) (f.number("Bitrate Mbps") * 1e6);
          if (
            o.width < 16 ||
            o.height < 16 ||
            o.width > 3840 ||
            o.height > 3840 ||
            o.width % 2 != 0 ||
            o.height % 2 != 0 ||
            o.fps < 1 ||
            o.fps > 60 ||
            o.bitrate < 100000 ||
            o.bitrate > 100000000
          ) throw new IllegalArgumentException(
            "Use even dimensions 16–3840, FPS 1–60, bitrate 0.1–100 Mbps"
          );
          exportOptions = o;
          pendingExport = state.project.copy();
          if (
            Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(
              android.Manifest.permission.POST_NOTIFICATIONS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
          ) requestPermissions(
            new String[] { android.Manifest.permission.POST_NOTIFICATIONS },
            100
          );
          create(
            EXPORT_VIDEO,
            "video/mp4",
            safeName(state.project.name) + ".mp4"
          );
        });
      })
    );
    exportProgress = new ProgressBar(
      this,
      null,
      android.R.attr.progressBarStyleHorizontal
    );
    exportProgress.setMax(100);
    root.addView(exportProgress);
    statusLabel = label("", 15, 0xffb3f56a);
    root.addView(statusLabel);
    root.addView(
      button("Cancel active export", () ->
        startService(new Intent(this, ExportService.class).setAction("cancel"))
      )
    );
  }

  private void settings() {
    android.content.SharedPreferences prefs = getSharedPreferences(
      "settings",
      0
    );
    new AlertDialog.Builder(this)
      .setTitle("Settings")
      .setMultiChoiceItems(
        new String[] { "Timeline snapping" },
        new boolean[] { prefs.getBoolean("snapping", true) },
        (d, n, value) -> prefs.edit().putBoolean("snapping", value).apply()
      )
      .setMessage(
        "Local autosave is always enabled. Projects and copied media stay on this device. No analytics or internet permission."
      )
      .setPositiveButton("Done", null)
      .show();
  }

  private void error(Exception e) {
    if (destroyed || isFinishing()) return;
    new AlertDialog.Builder(this)
      .setTitle("Vynox")
      .setMessage(
        e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()
      )
      .setPositiveButton("OK", null)
      .show();
  }

  @Override
  protected void onPause() {
    pause();
    if (state != null) saveAsync();
    super.onPause();
  }

  @Override
  protected void onSaveInstanceState(Bundle out) {
    super.onSaveInstanceState(out);
    if (state != null) {
      out.putString("project", state.project.id);
      out.putLong("playhead", state.playheadUs);
    }
  }

  @Override
  public void onBackPressed() {
    if (preview != null || statusLabel != null) home();
    else super.onBackPressed();
  }

  @Override
  protected void onDestroy() {
    destroyed = true;
    pause();
    handler.removeCallbacksAndMessages(null);
    if (preview != null) preview.close();
    io.shutdown();
    super.onDestroy();
  }
}
