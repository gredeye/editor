package app.vynox.storage;

import android.content.Context;
import android.util.AtomicFile;
import app.vynox.format.ProjectJson;
import app.vynox.model.Project;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class ProjectStore {

  private final File base;

  public ProjectStore(Context context) {
    base = new File(context.getFilesDir(), "projects");
    base.mkdirs();
  }

  public File root(String id) {
    if (!id.matches("[A-Za-z0-9-]{1,80}")) throw new IllegalArgumentException(
      "Invalid project ID"
    );
    File f = new File(base, id);
    f.mkdirs();
    return f;
  }

  public synchronized void save(Project p) throws Exception {
    AtomicFile file = new AtomicFile(new File(root(p.id), "project.json"));
    FileOutputStream out = null;
    try {
      byte[] data = ProjectJson.encode(p).getBytes(StandardCharsets.UTF_8);
      out = file.startWrite();
      out.write(data);
      file.finishWrite(out);
    } catch (Exception e) {
      if (out != null) file.failWrite(out);
      throw e;
    }
  }

  public synchronized Project load(String id) throws Exception {
    AtomicFile f = new AtomicFile(new File(root(id), "project.json"));
    return ProjectJson.decode(
      new String(f.readFully(), StandardCharsets.UTF_8)
    );
  }

  public List<File> list() {
    File[] files = base.listFiles(
      f -> f.isDirectory() && new File(f, "project.json").isFile()
    );
    List<File> result = new ArrayList<>();
    if (files != null) Collections.addAll(result, files);
    result.sort((a, b) ->
      Long.compare(
        new File(b, "project.json").lastModified(),
        new File(a, "project.json").lastModified()
      )
    );
    return result;
  }
}
