/// Incremental pull-merge-push synchronization of one library with a JabDrive remote.
///
/// [SynchronizationManager] is the entry point. It merges a [SyncTransport] feed with the open
/// library and pushes local edits. Shared SQL databases stay in [org.jabref.logic.shared].
/// Git remotes stay in [org.jabref.logic.git].
package org.jabref.logic.jabdrive;
