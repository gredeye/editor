package app.vynox.engine;

import app.vynox.model.Project;
import java.util.*;
import java.util.function.Consumer;

/** Transactional snapshots; a failed edit never corrupts the current project. */
public final class EditorState {

  public Project project;
  public long playheadUs;
  public String selectedId = "";
  private final Deque<Project> undo = new ArrayDeque<>(),
    redo = new ArrayDeque<>();
  public Runnable onChange = () -> {};

  public EditorState(Project project) {
    this.project = project;
  }

  public Project.Layer selected() {
    return project.layer(selectedId);
  }

  public void edit(Consumer<Project> edit) {
    Project next = project.copy();
    edit.accept(next);
    undo.push(project);
    while (undo.size() > 60) undo.removeLast();
    redo.clear();
    project = next;
    onChange.run();
  }

  public void undo() {
    if (!undo.isEmpty()) {
      redo.push(project);
      project = undo.pop();
      playheadUs = Math.min(playheadUs, project.durationUs - 1);
      onChange.run();
    }
  }

  public void redo() {
    if (!redo.isEmpty()) {
      undo.push(project);
      project = redo.pop();
      onChange.run();
    }
  }

  public void seek(long t) {
    playheadUs = Math.max(0, Math.min(project.durationUs - 1, t));
  }
}
