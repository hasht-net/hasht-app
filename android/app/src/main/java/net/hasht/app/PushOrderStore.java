package net.hasht.app;

import android.content.Context;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

/** Indexed, transactional notification metadata. No message content is persisted. */
final class PushOrderStore extends SQLiteOpenHelper {
    PushOrderStore(Context context) { super(context, "push-order.db", null, 1); }
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE entries (identity TEXT PRIMARY KEY, expires INTEGER NOT NULL, data BLOB NOT NULL)");
        db.execSQL("CREATE INDEX entries_expiry ON entries(expires)");
        db.execSQL("CREATE TABLE metadata (id INTEGER PRIMARY KEY, data BLOB NOT NULL)");
    }
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {}
    PushOrder.State load() throws Exception {
        SQLiteDatabase db = getReadableDatabase();
        PushOrder.State state = new PushOrder.State();
        try (Cursor cursor = db.rawQuery("SELECT data FROM metadata WHERE id=1", null)) {
            if (cursor.moveToFirst()) state = PushOrder.State.decode(cursor.getBlob(0));
        }
        try (Cursor cursor = db.rawQuery("SELECT identity,data FROM entries", null)) {
            while (cursor.moveToNext()) state.channels.putAll(PushOrder.State.decode(cursor.getBlob(1)).channels);
        }
        state.metadataDirty = false;
        return state;
    }
    boolean initialized() {
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT 1 FROM metadata WHERE id=1", null)) { return cursor.moveToFirst(); }
    }
    void save(PushOrder.State state) throws Exception {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (String key : state.dirty) {
                PushOrder.Entry entry = state.channels.get(key);
                if (entry == null) { db.delete("entries", "identity=?", new String[]{key}); continue; }
                PushOrder.State row = new PushOrder.State(); row.channels.put(key, entry);
                ContentValues values = new ContentValues();
                values.put("identity", key); values.put("expires", entry.expires); values.put("data", row.encode());
                if (db.insertWithOnConflict("entries", null, values, SQLiteDatabase.CONFLICT_REPLACE) == -1)
                    throw new android.database.SQLException("could not persist notification entry");
            }
            if (state.metadataDirty) {
                PushOrder.State metadata = new PushOrder.State();
                metadata.generation = state.generation; metadata.cutoffs.putAll(state.cutoffs);
                metadata.recovery.putAll(state.recovery); metadata.reads.putAll(state.reads);
                ContentValues values = new ContentValues(); values.put("id", 1); values.put("data", metadata.encode());
                if (db.insertWithOnConflict("metadata", null, values, SQLiteDatabase.CONFLICT_REPLACE) == -1)
                    throw new android.database.SQLException("could not persist notification metadata");
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        state.dirty.clear();
        state.metadataDirty = false;
    }
}
