package org.jabref.gui.cloudsync;

import javafx.beans.property.ReadOnlyBooleanProperty;

import org.jspecify.annotations.NullMarked;

@NullMarked
public interface ConnectivityMonitor {

    ReadOnlyBooleanProperty onlineProperty();
}
