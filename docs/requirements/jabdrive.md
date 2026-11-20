---
parent: Requirements
---
# JabDrive

## A library must synchronize incrementally with a JabDrive remote
`req~jabdrive.incremental-sync~1`

Pull returns only changes since the stored checkpoint, those changes are merged, and local changes are then pushed.

Needs: impl, utest

## A dirty entry must not be overwritten by a newer remote version
`req~jabdrive.dirty-entry-kept~1`

When the remote version is higher and the local entry has unsynchronized edits, the local field values stay in place and the cycle reports a conflict.

Needs: impl, utest

## An identical resend must keep the version already assigned
`req~jabdrive.identical-resend~1`

Sending the same entry id, version, and hash again, after the remote has already accepted that write, returns the assigned version.

Needs: impl, utest

<!-- markdownlint-disable-file MD022 -->
