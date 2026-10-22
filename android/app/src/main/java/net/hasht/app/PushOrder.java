package net.hasht.app;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Durable notification metadata only; never stores message text. */
final class PushOrder {
    static final long TTL = 24 * 60 * 60 * 1000L;
    static final long RETENTION = TTL + 2 * 60 * 60 * 1000L;
    static final int MAX_TOMBSTONES = 32;

    static final class Entry {
        String origin;
        String tag;
        String id = "";
        long created;
        long version;
        long expires;
        long cleared;
        boolean removed;
        final Set<String> tombstones = new HashSet<>();
    }

    static final class State {
        final Map<String, Entry> channels = new HashMap<>();
        final Map<String, Long> cutoffs = new HashMap<>();
        final Map<String, Long> recovery = new HashMap<>();
        final Map<String, Long> reads = new HashMap<>();
        final Set<String> dirty = new HashSet<>();
        long lastPruned;
        boolean metadataDirty = true;
        long generation;

        private static String key(String origin, String tag) { return origin + " " + tag; }

        void prune(long now) {
            channels.values().removeIf(e -> { if (e.expires >= now) return false; dirty.add(key(e.origin, e.tag)); return true; });
            if (cutoffs.values().removeIf(at -> at + RETENTION < now)) metadataDirty = true;
        }

        /** A batched recall must tombstone each independent message identity. */
        boolean acceptNotification(String origin, String tag, String kind, List<String> ids,
                                   long created, long version, long issued, long now) {
            if (!"remove".equals(kind) || ids.size() <= 1) {
                return accept(origin, tag, kind, ids, created, version, issued, now);
            }
            if (ids.size() > 32) return false;
            String channelTag = tag.split("/message:", 2)[0];
            boolean accepted = false;
            long before = generation;
            for (String id : ids) {
                accepted |= accept(origin, channelTag + "/message:" + id, kind,
                    java.util.Collections.singletonList(id), 0, 0, issued, now);
                if (generation != before) return false;
            }
            return accepted;
        }

        /** False means an expired, duplicate, withdrawn or superseded event. */
        boolean accept(String origin, String tag, String kind, List<String> ids,
                       long created, long version, long issued, long now) {
            if (now - lastPruned > 60_000) { prune(now); lastPruned = now; }
            if (origin.length() > 1024 || tag.length() > 256
                || ids.size() > 128 || ids.stream().anyMatch(id -> id.length() > 64)) return false;
            boolean ordered = created > 0 && version >= created && issued > 0 && !ids.isEmpty();
            if (issued > 0 && (issued + TTL < now || issued > now + 5 * 60 * 1000L)) return false;
            if (cutoffs.containsKey(origin) && (!ordered || issued <= cutoffs.get(origin))) return false;
            String channelTag = tag.split("/message:", 2)[0];
            if (created > 0 && created <= reads.getOrDefault(key(origin, channelTag), 0L)) return false;
            String key = key(origin, tag);
            Entry entry = channels.get(key);
            if (entry == null) {
                entry = new Entry(); entry.origin = origin; entry.tag = tag;
                channels.put(key, entry);
            }
            dirty.add(key);
            entry.expires = Math.max(entry.expires, (issued > 0 ? issued : now) + RETENTION);
            String id = ids.isEmpty() ? "" : ids.get(ids.size() - 1);
            if ("remove".equals(kind)) {
                if (ids.isEmpty()) {
                    entry.cleared = 0;
                    entry.removed = true;
                    return true;
                }
                // Legacy backends can remove an identity before its alert arrives.
                if (!ordered) {
                    entry.tombstones.addAll(ids);
                    if (entry.tombstones.size() > MAX_TOMBSTONES) {
                        overflow(java.util.Collections.singletonList(origin), now);
                        return false;
                    }
                }
                if (ordered && compare(created, id, entry) >= 0) {
                    entry.created = created; entry.id = id; entry.version = version;
                    entry.removed = true;
                } else if (ids.contains(entry.id)) {
                    entry.removed = true;
                }
                return true;
            }
            if (!java.util.Collections.disjoint(ids, entry.tombstones)) return false;
            if (ordered) {
                int comparison = compare(created, id, entry);
                if (comparison < 0) return false;
                if (comparison == 0 && (entry.removed || version <= entry.version)) return false;
                entry.created = created; entry.id = id; entry.version = version;
            } else {
                // Legacy messages have no comparable revision. Never overwrite
                // a channel already protected by the newer encrypted contract.
                if (entry.created > 0) return false;
                if (entry.removed && id.equals(entry.id)) return false;
                entry.id = id;
            }
            entry.removed = false;
            return true;
        }

        private static int compare(long created, String id, Entry entry) {
            int timestamp = Long.compare(created, entry.created);
            return timestamp == 0 ? id.compareTo(entry.id) : timestamp;
        }

        void clear(String origin, String tag, long now) {
            for (Entry child : channels.values()) {
                if (origin.equals(child.origin) && child.tag.startsWith(tag + "/message:")) {
                    child.removed = true;
                    child.cleared = 0;
                    dirty.add(key(child.origin, child.tag));
                    child.expires = now + RETENTION;
                }
            }
            Entry entry = channels.get(key(origin, tag));
            dirty.add(key(origin, tag));
            if (entry != null) {
                entry.removed = true;
                entry.cleared = 0;
                entry.expires = now + RETENTION;
            } else {
                accept(origin, tag, "remove", java.util.Collections.emptyList(), 0, 0, 0, now);
            }
        }

        void read(String origin, String tag, long through) {
            if (through <= 0) return;
            String k = key(origin, tag);
            if (through <= reads.getOrDefault(k, 0L)) return;
            reads.put(k, through);
            metadataDirty = true;
        }

        void compactReads() {
            channels.values().removeIf(e -> {
                boolean covered = e.created > 0 && e.created <= reads.getOrDefault(key(e.origin, e.tag.split("/message:", 2)[0]), 0L);
                if (covered) dirty.add(key(e.origin, e.tag));
                return covered;
            });
        }

        void overflow(List<String> origins, long now) {
            metadataDirty = true;
            generation++;
            for (String origin : origins) {
                cutoffs.put(origin, now);
                recovery.put(origin, generation);
                channels.values().removeIf(e -> { if (!origin.equals(e.origin)) return false; dirty.add(key(e.origin,e.tag)); return true; });
            }
        }

        Set<String> affectedOrigins() {
            Set<String> origins = new HashSet<>();
            for (Map.Entry<String, Long> e : recovery.entrySet()) {
                if (e.getValue() == generation) origins.add(e.getKey());
            }
            return origins;
        }

        long recoveryGeneration(String origin) { return recovery.getOrDefault(origin, 0L); }

        boolean complete(String origin, long expected) {
            if (expected == 0 || expected != recoveryGeneration(origin)) return false;
            metadataDirty = true;
            recovery.remove(origin);
            return true;
        }

        void forget(String origin) {
            metadataDirty = true;
            channels.values().removeIf(e -> { if (!origin.equals(e.origin)) return false; dirty.add(key(e.origin,e.tag)); return true; });
            reads.keySet().removeIf(k -> k.startsWith(origin + " "));
            cutoffs.remove(origin);
            recovery.remove(origin);
        }

        byte[] encode() throws IOException {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(buffer)) {
                out.writeInt(2); out.writeLong(generation); out.writeInt(channels.size());
                for (Entry e : channels.values()) {
                    out.writeUTF(e.origin); out.writeUTF(e.tag); out.writeUTF(e.id);
                    out.writeLong(e.created); out.writeLong(e.version); out.writeLong(e.expires);
                    out.writeLong(e.cleared); out.writeBoolean(e.removed);
                    out.writeInt(e.tombstones.size());
                    for (String id : e.tombstones) out.writeUTF(id);
                }
                writeMap(out, cutoffs); writeMap(out, recovery); writeMap(out, reads);
            }
            if (buffer.size() > 16 * 1024 * 1024) throw new IOException("oversized push state");
            return buffer.toByteArray();
        }

        static State decode(byte[] bytes) throws IOException {
            if (bytes.length > 16 * 1024 * 1024) throw new IOException("oversized push state");
            State state = new State();
            try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
                int format = in.readInt();
                if (format != 1 && format != 2) throw new IOException("unsupported push state");
                state.generation = in.readLong();
                int count = count(in, 1_000_000);
                for (int i = 0; i < count; i++) {
                    Entry e = new Entry();
                    e.origin = in.readUTF(); e.tag = in.readUTF(); e.id = in.readUTF();
                    e.created = in.readLong(); e.version = in.readLong(); e.expires = in.readLong();
                    e.cleared = in.readLong(); e.removed = in.readBoolean();
                    int tombstones = count(in, MAX_TOMBSTONES);
                    for (int j = 0; j < tombstones; j++) e.tombstones.add(in.readUTF());
                    state.channels.put(key(e.origin, e.tag), e);
                }
                readMap(in, state.cutoffs); readMap(in, state.recovery);
                if (format >= 2) readMap(in, state.reads);
                if (in.available() != 0) throw new IOException("trailing push state");
            }
            return state;
        }

        private static int count(DataInputStream in, int max) throws IOException {
            int count = in.readInt();
            if (count < 0 || count > max) throw new IOException("invalid push state count");
            return count;
        }
        private static void writeMap(DataOutputStream out, Map<String, Long> values) throws IOException {
            out.writeInt(values.size());
            for (Map.Entry<String, Long> e : values.entrySet()) { out.writeUTF(e.getKey()); out.writeLong(e.getValue()); }
        }
        private static void readMap(DataInputStream in, Map<String, Long> values) throws IOException {
            int count = count(in, 1_000_000);
            for (int i = 0; i < count; i++) values.put(in.readUTF(), in.readLong());
        }
    }
}
