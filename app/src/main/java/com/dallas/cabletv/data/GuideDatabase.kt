package com.dallas.cabletv.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * The guide kept on the device, in SQLite. Four tables:
 *
 * - program: every programme in the window around now, one per channel and start time.
 * - staged: the programmes of a download while it is being read. It is emptied once the download is
 *   committed, so readers only ever see a whole guide.
 * - alias: normalized channel names and ids mapped to the id the programmes are stored under.
 * - meta: which logins the copy belongs to (a hash) and when it was last saved in full.
 *
 * Reading from SQLite keeps the listings out of memory, so the heap is not filled by the whole guide.
 */
internal class GuideDatabase(context: Context) : SQLiteOpenHelper(context, FILE, null, VERSION) {

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        createTables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS program")
        db.execSQL("DROP TABLE IF EXISTS staged")
        db.execSQL("DROP TABLE IF EXISTS alias")
        db.execSQL("DROP TABLE IF EXISTS meta")
        createTables(db)
    }

    private fun createTables(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE program (ch TEXT NOT NULL, start INTEGER NOT NULL, stop INTEGER NOT NULL, " +
                "title TEXT NOT NULL, desc TEXT NOT NULL, PRIMARY KEY (ch, start)) WITHOUT ROWID"
        )
        db.execSQL("CREATE INDEX program_stop ON program (stop)")
        db.execSQL(
            "CREATE TABLE staged (ch TEXT NOT NULL, start INTEGER NOT NULL, stop INTEGER NOT NULL, " +
                "title TEXT NOT NULL, desc TEXT NOT NULL, PRIMARY KEY (ch, start)) WITHOUT ROWID"
        )
        db.execSQL("CREATE TABLE alias (name TEXT PRIMARY KEY, ch TEXT NOT NULL) WITHOUT ROWID")
        db.execSQL("CREATE TABLE meta (k TEXT PRIMARY KEY, v TEXT NOT NULL) WITHOUT ROWID")
    }

    companion object {
        const val FILE = "guide.db"
        const val VERSION = 1
    }
}
