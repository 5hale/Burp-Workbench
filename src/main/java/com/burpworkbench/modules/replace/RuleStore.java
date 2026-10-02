package com.burpworkbench.modules.replace;

import burp.api.montoya.persistence.PersistedObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

/** Project extension data only. The caller supplies api.persistence().extensionData(). */
final class RuleStore {
    static final String STATE_KEY = "burpworkbench.replace.v1.state";
    static final String BACKUP_KEY = "burpworkbench.replace.v1.backup";
    static final int SCHEMA_VERSION = 2;
    static final int MAX_RULES = 10_000;
    static final int MAX_FIELD_CHARS = 1_048_576;
    static final int MAX_PAYLOAD_BYTES = 16 * 1024 * 1024;
    private static final int MAX_ENCODED_CHARS = ((MAX_PAYLOAD_BYTES + 2) / 3) * 4;
    private static final int MAGIC = 0x42575250; // BWRP

    private final PersistedObject data;
    private final String stateKey, backupKey;
    private final State defaults;
    private boolean loaded;
    private String loadedRaw;

    RuleStore(PersistedObject data) {
        this(data, STATE_KEY, BACKUP_KEY, new State(DefaultRules.rules(), true));
    }

    RuleStore(PersistedObject data, String stateKey, String backupKey, State defaults) {
        this.data = Objects.requireNonNull(data, "Project extension data");
        this.stateKey = stateKey; this.backupKey = backupKey; this.defaults = defaults;
    }

    record State(List<RuleDraft> rules, boolean enabled) {
        State {
            rules = List.copyOf(rules);
        }

        static State empty() {
            return new State(List.of(), true);
        }
    }

    enum Failure { READ, WRITE, INVALID_DATA, UNSUPPORTED_VERSION, NOT_LOADED, CONCURRENT_CHANGE }

    static final class StoreException extends RuntimeException {
        private final Failure kind;

        StoreException(Failure kind, String message) {
            super(message);
            this.kind = kind;
        }

        StoreException(Failure kind, String message, Throwable cause) {
            super(message, cause);
            this.kind = kind;
        }

        Failure kind() {
            return kind;
        }
    }

    /** Does not write, reset, or silently recover malformed or unsupported stored data. */
    synchronized State load() {
        loaded = false;
        String raw = readCurrent();
        State state = raw == null ? defaults : decode(raw);
        loadedRaw = raw;
        loaded = true;
        return state;
    }

    /** Stores one complete snapshot and retains the immediately preceding valid snapshot. */
    synchronized void save(State state) {
        if (!loaded) {
            throw new StoreException(Failure.NOT_LOADED,
                    "Project rules were not loaded successfully; existing data was left unchanged.");
        }
        String encoded = encode(Objects.requireNonNull(state, "State"));
        String current = readCurrent();
        if (!Objects.equals(current, loadedRaw)) {
            loaded = false;
            throw new StoreException(Failure.CONCURRENT_CHANGE,
                    "Project rules changed outside this instance; reload before saving.");
        }
        if (Objects.equals(current, encoded)) return;
        try {
            if (current != null) data.setString(backupKey, current);
            data.setString(stateKey, encoded);
        } catch (RuntimeException failure) {
            // A failed API call may have partially completed; require a fresh load before another write.
            loaded = false;
            throw new StoreException(Failure.WRITE, "Project rules could not be saved.", failure);
        }
        loadedRaw = encoded;
    }

    private String readCurrent() {
        try {
            return data.getString(stateKey);
        } catch (RuntimeException failure) {
            loaded = false;
            throw new StoreException(Failure.READ, "Project rules could not be read.", failure);
        }
    }

    private static String encode(State state) {
        if (state.rules().size() > MAX_RULES) throw invalid("Too many stored rules.");
        long size = 4L + 4L + 1L + 4L;
        for (RuleDraft rule : state.rules()) {
            size += 3L;
            for (String value : fields(rule)) {
                if (value.length() > MAX_FIELD_CHARS) throw invalid("A rule field is too long to store.");
                size += 4L + 2L * value.length();
                if (size > MAX_PAYLOAD_BYTES) throw invalid("Stored rules exceed the project storage limit.");
            }
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream((int) size);
            DataOutputStream output = new DataOutputStream(bytes);
            output.writeInt(MAGIC);
            output.writeInt(SCHEMA_VERSION);
            output.writeBoolean(state.enabled());
            output.writeInt(state.rules().size());
            for (RuleDraft rule : state.rules()) {
                output.writeBoolean(rule.enabled());
                for (String field : fields(rule)) writeString(output, field);
                output.writeBoolean(rule.regex());
                output.writeBoolean(rule.caseSensitive());
            }
            output.flush();
            return Base64.getEncoder().encodeToString(bytes.toByteArray());
        } catch (IOException impossibleForMemoryBuffer) {
            throw new StoreException(Failure.WRITE, "Rules could not be encoded.", impossibleForMemoryBuffer);
        }
    }

    private static State decode(String raw) {
        if (raw.isEmpty() || raw.length() > MAX_ENCODED_CHARS) throw invalid("Invalid stored rules size.");
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(raw);
        } catch (IllegalArgumentException malformedBase64) {
            throw new StoreException(Failure.INVALID_DATA, "Stored rules are not valid Base64.", malformedBase64);
        }
        if (bytes.length > MAX_PAYLOAD_BYTES) throw invalid("Stored rules exceed the project storage limit.");
        try {
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes));
            if (input.readInt() != MAGIC) throw invalid("Stored rules have an invalid format marker.");
            int version = input.readInt();
            if (version != 1 && version != SCHEMA_VERSION) {
                throw new StoreException(Failure.UNSUPPORTED_VERSION, "Unsupported project rule schema: " + version);
            }
            boolean enabled = readBoolean(input);
            int count = input.readInt();
            if (count < 0 || count > MAX_RULES) throw invalid("Invalid stored rule count.");
            List<RuleDraft> rules = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                boolean ruleEnabled = readBoolean(input);
                String name = readString(input);
                String target = readString(input);
                String url = readString(input);
                String path = readString(input);
                String match = readString(input);
                String replacement = readString(input);
                boolean regex = readBoolean(input);
                boolean caseSensitive = version >= 2 && readBoolean(input);
                rules.add(new RuleDraft(ruleEnabled, name, target, url, path, match, replacement, regex, caseSensitive));
            }
            if (input.available() != 0) throw invalid("Stored rules have unexpected trailing data.");
            return new State(rules, enabled);
        } catch (IOException truncated) {
            throw new StoreException(Failure.INVALID_DATA, "Stored rules are incomplete.", truncated);
        }
    }

    private static String[] fields(RuleDraft rule) {
        return new String[] {rule.name(), rule.target(), rule.url(), rule.path(), rule.match(), rule.replacement()};
    }

    // Length-prefixed UTF-16 code units preserve Java strings, including NUL, without writeUTF's 64 KiB limit.
    private static void writeString(DataOutputStream output, String value) throws IOException {
        output.writeInt(value.length());
        for (int index = 0; index < value.length(); index++) output.writeChar(value.charAt(index));
    }

    private static String readString(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > MAX_FIELD_CHARS || (long) length * 2L > input.available()) {
            throw invalid("Invalid stored rule field length.");
        }
        char[] value = new char[length];
        for (int index = 0; index < length; index++) value[index] = input.readChar();
        return new String(value);
    }

    private static boolean readBoolean(DataInputStream input) throws IOException {
        int flag = input.readUnsignedByte();
        if (flag > 1) throw invalid("Invalid stored rule flag.");
        return flag == 1;
    }

    private static StoreException invalid(String message) {
        return new StoreException(Failure.INVALID_DATA, message);
    }
}
