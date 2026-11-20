package org.jabref.logic.jabdrive;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.StringJoiner;

import org.jabref.model.entry.BibEntry;
import org.jabref.model.entry.field.Field;
import org.jabref.model.entry.field.InternalField;

import org.jspecify.annotations.NullMarked;

/// SHA-256 of an entry's type, citation key, and fields.
///
/// The version and any hash stored beside the entry are not part of the input, so a sync
/// bookkeeping change does not look like an edit. BibTeX strings are hashed as stored.
@NullMarked
public final class EntrySyncHasher {

    private static final String ALGORITHM = "SHA-256";

    public String hash(BibEntry entry) {
        try {
            MessageDigest digest = MessageDigest.getInstance(ALGORITHM);
            digest.update(canonical(entry).getBytes(StandardCharsets.UTF_8));
            return hex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private String canonical(BibEntry entry) {
        StringJoiner joiner = new StringJoiner("\n");
        joiner.add(entry.getType().getName());
        joiner.add(entry.getCitationKey().orElse(""));
        entry.getFields().stream()
             .filter(field -> !InternalField.KEY_FIELD.equals(field))
             .sorted(Comparator.comparing(Field::getName))
             .forEach(field -> entry.getField(field).ifPresent(value -> joiner.add(field.getName() + "=" + value)));
        return joiner.toString();
    }

    private String hex(byte[] bytes) {
        StringJoiner joiner = new StringJoiner("");
        for (byte value : bytes) {
            joiner.add("%02x".formatted(value & 0xff));
        }
        return joiner.toString();
    }
}
