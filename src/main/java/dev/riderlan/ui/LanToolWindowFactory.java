package dev.riderlan.ui;

import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentManager;
import org.jetbrains.annotations.NotNull;

/** Small stable IDE integration boundary; the UI and networking use ordinary Java/Swing. */
public final class LanToolWindowFactory implements ToolWindowFactory, DumbAware {
    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        LanPanel panel = new LanPanel();
        ContentManager manager = toolWindow.getContentManager();
        Content content = manager.getFactory().createContent(panel, "IDEle", false);
        // Session sockets should close whenever the tool-window content is disposed,
        // not just when the entire project exits.
        content.setDisposer(panel::close);
        manager.addContent(content);
    }
}
