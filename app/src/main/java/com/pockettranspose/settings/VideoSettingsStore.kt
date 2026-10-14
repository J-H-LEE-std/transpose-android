package com.pockettranspose.settings

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executors

/**
 * Per-video audio settings (transpose, speed, gain) in a local SQLite database,
 * keyed by YouTube video ID. Platform SQLite only, no extra dependency. All disk
 * I/O runs on one background thread; results are delivered on the main thread.
 *
 * Stays on the device: the database is excluded from cloud backup and device
 * transfer (res/xml/data_extraction_rules.xml, backup_rules.xml).
 */
class VideoSettingsStore(context: Context) {
    data class VideoSettings(val pitchSemitone: Int, val playbackRate: Float, val gain: Float) {
        val isDefault: Boolean get() = this == DEFAULT

        companion object {
            val DEFAULT = VideoSettings(pitchSemitone = 0, playbackRate = 1f, gain = 1f)
        }
    }

    private val helper = Helper(context.applicationContext)
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** Delivers the saved settings for [videoId], or null when none are saved. */
    fun load(videoId: String, callback: (VideoSettings?) -> Unit) {
        io.execute {
            val saved = runCatching {
                helper.readableDatabase.query(
                    TABLE, arrayOf(COL_PITCH, COL_RATE, COL_GAIN), "$COL_ID = ?", arrayOf(videoId),
                    null, null, null
                ).use { cursor ->
                    if (cursor.moveToFirst()) {
                        VideoSettings(cursor.getInt(0), cursor.getFloat(1), cursor.getFloat(2))
                    } else {
                        null
                    }
                }
            }.onFailure { Log.w(TAG, "load failed: ${it.javaClass.simpleName}") }.getOrNull()
            main.post { callback(saved) }
        }
    }

    /** Saves [settings] for [videoId]; default values delete the row instead. */
    fun save(videoId: String, settings: VideoSettings) {
        io.execute {
            runCatching {
                val db = helper.writableDatabase
                if (settings.isDefault) {
                    db.delete(TABLE, "$COL_ID = ?", arrayOf(videoId))
                } else {
                    val values = ContentValues().apply {
                        put(COL_ID, videoId)
                        put(COL_PITCH, settings.pitchSemitone)
                        put(COL_RATE, settings.playbackRate)
                        put(COL_GAIN, settings.gain)
                        put(COL_UPDATED, System.currentTimeMillis())
                    }
                    db.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE)
                }
            }.onFailure { Log.w(TAG, "save failed: ${it.javaClass.simpleName}") }
        }
    }

    fun close() {
        io.execute { helper.close() }
        io.shutdown()
    }

    private class Helper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE $TABLE (" +
                    "$COL_ID TEXT PRIMARY KEY NOT NULL, " +
                    "$COL_PITCH INTEGER NOT NULL, " +
                    "$COL_RATE REAL NOT NULL, " +
                    "$COL_GAIN REAL NOT NULL, " +
                    "$COL_UPDATED INTEGER NOT NULL)"
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    private companion object {
        const val TAG = "PocketTransposeJS"
        const val DB_NAME = "video_settings.db"
        const val DB_VERSION = 1
        const val TABLE = "video_settings"
        const val COL_ID = "video_id"
        const val COL_PITCH = "pitch_semitone"
        const val COL_RATE = "playback_rate"
        const val COL_GAIN = "gain"
        const val COL_UPDATED = "updated_at"
    }
}
