package com.intellij.ui.content;
public interface ContentManager {
    ContentFactory getFactory();
    void addContent(Content content);
}
