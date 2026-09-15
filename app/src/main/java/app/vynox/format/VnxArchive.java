package app.vynox.format;

import app.vynox.model.Project;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.*;

/** Portable ZIP: project.json + media/*. Missing media remains explicitly relinkable. */
public final class VnxArchive {

  public static void write(Project project, File root, OutputStream output)
    throws Exception {
    try (ZipOutputStream zip = new ZipOutputStream(output)) {
      zip.putNextEntry(new ZipEntry("project.json"));
      zip.write(ProjectJson.encode(project).getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
      Set<String> written = new HashSet<>();
      for (Project.Asset a : project.assets.values())
        if (!a.file.isEmpty() && written.add(a.file)) {
          File f = resolve(root, a.file);
          if (f.isFile()) {
            zip.putNextEntry(new ZipEntry(a.file));
            Files.copy(f.toPath(), zip);
            zip.closeEntry();
          }
        }
    }
  }

  public static Project read(InputStream input, File destination)
    throws Exception {
    if (
      !destination.mkdirs() && !destination.isDirectory()
    ) throw new IOException("Cannot create project storage");
    Set<String> names = new HashSet<>();
    long total = 0;
    int entries = 0;
    byte[] buffer = new byte[65536];
    try (ZipInputStream zip = new ZipInputStream(input)) {
      ZipEntry e;
      while ((e = zip.getNextEntry()) != null) {
        if (++entries > 501 || !names.add(e.getName())) throw new IOException(
          "Duplicate or excessive entries"
        );
        if (e.isDirectory()) continue;
        if (
          !e.getName().equals("project.json") &&
          !e.getName().matches("media/[A-Za-z0-9._-]+")
        ) throw new IOException("Unexpected archive entry");
        File f = resolve(destination, e.getName());
        File parent = f.getParentFile();
        if (
          parent != null && !parent.isDirectory() && !parent.mkdirs()
        ) throw new IOException("Cannot create asset folder");
        long size = 0;
        try (OutputStream out = new FileOutputStream(f)) {
          int n;
          while ((n = zip.read(buffer)) != -1) {
            total += n;
            size += n;
            if (
              total > 4L * 1024 * 1024 * 1024 ||
              (e.getName().equals("project.json") && size > 8 * 1024 * 1024)
            ) throw new IOException("Archive exceeds size limit");
            out.write(buffer, 0, n);
          }
        }
      }
    }
    File manifest = new File(destination, "project.json");
    if (!manifest.isFile()) throw new IOException("project.json is missing");
    return ProjectJson.decode(
      new String(Files.readAllBytes(manifest.toPath()), StandardCharsets.UTF_8)
    );
  }

  public static File resolve(File root, String relative) throws IOException {
    File f = new File(root, relative).getCanonicalFile();
    if (
      !f.getPath().startsWith(root.getCanonicalPath() + File.separator)
    ) throw new IOException("Unsafe path");
    return f;
  }

  private VnxArchive() {}
}
