package dev.offgridmesh

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.offgridmesh.maps.MbtilesWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MbtilesWriterTest {
    /** Regression: puts from many IO threads used to deadlock on a thread-bound transaction. */
    @Test fun concurrentPutsFromManyThreads() = runBlocking {
        val f = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "t.mbtiles").apply { delete() }
        val w = MbtilesWriter(f)
        withTimeout(20_000) {
            (0 until 8).map { t ->
                launch(Dispatchers.IO) { for (i in 0 until 400) w.put(14, t * 1000 + i, 7, byteArrayOf(t.toByte())) }
            }.forEach { it.join() }
        }
        w.finish(mapOf("name" to "t", "format" to "pbf"))
        SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT COUNT(*) FROM tiles", null).use { it.moveToFirst(); assertEquals(3200, it.getInt(0)) }
            // TMS flip: y=7 at z14 → row 16376.
            db.rawQuery("SELECT DISTINCT tile_row FROM tiles", null).use { it.moveToFirst(); assertEquals(16376, it.getInt(0)) }
        }
    }
}
