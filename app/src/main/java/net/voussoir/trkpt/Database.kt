package net.voussoir.trkpt
import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabase.openOrCreateDatabase
import android.util.Log
import java.io.File

class Database(val trackbook: net.voussoir.trkpt.Trackbook)
{
    var ready: Boolean = false
    lateinit var file: File
    lateinit var connection: SQLiteDatabase

    fun close()
    {
        this.connection.close()
        this.ready = false
        this.trackbook.call_database_changed_listeners()
    }

    fun connect(file: File)
    {
        Log.i("VOUSSOIR", "Connecting to database " + file.absolutePath)
        this.file = file
        this.connection = openOrCreateDatabase(file, null)
        this.initialize_tables()
        this.ready = true
        Log.i("VOUSSOIR", "Database.open: Calling all listeners")
    }

    fun begin_transaction()
    {
        if (! connection.inTransaction())
        {
            connection.beginTransaction()
        }
    }

    fun commit()
    {
        if (! this.ready)
        {
            return
        }
        if (! this.connection.inTransaction())
        {
            return
        }
        Log.i("VOUSSOIR", "Committing.")
        this.connection.setTransactionSuccessful()
        this.connection.endTransaction()
    }

    fun delete_trkpt(device_id: String, time: Long)
    {
        Log.i("VOUSSOIR", "Database.delete_trkpt")
        begin_transaction()
        connection.delete("trkpt", "device_id = ? AND time = ?", arrayOf(device_id, time.toString()))
        commit()
    }

    fun delete_trkpt_start_end(device_id: String, start_time: Long, end_time: Long)
    {
        Log.i("VOUSSOIR", "Track.delete ${device_id} ${start_time} -- ${end_time}.")
        this.begin_transaction()
        this.connection.delete("trkpt", "device_id = ? AND time >= ? AND time <= ?", arrayOf(device_id, start_time.toString(), end_time.toString()))
        this.commit()
    }

    fun insert_trkpt(trkpt: net.voussoir.trkpt.Trkpt)
    {
        Log.i("VOUSSOIR", "Database.insert_trkpt")
        val values = ContentValues().apply {
            put("device_id", trkpt.device_id)
            put("lat", trkpt.latitude)
            put("lon", trkpt.longitude)
            put("time", trkpt.time)
            put("accuracy", trkpt.accuracy)
            put("sat", trkpt.numberSatellites)
            put("ele", trkpt.altitude)
        }
        begin_transaction()
        connection.insert("trkpt", null, values)
    }

    fun select_trkpt_start_end(device_id: String, start_time: Long, end_time: Long): Iterator<Trkpt>
    {
        Log.i("VOUSSOIR", "Track.trkpt_generator: Querying points between ${start_time} -- ${end_time}.")
        return _trkpt_generator(this.connection.rawQuery(
            "SELECT device_id, lat, lon, time, ele, accuracy, sat FROM trkpt WHERE device_id = ? AND time >= ? AND time <= ? ORDER BY time ASC",
            arrayOf(device_id, start_time.toString(), end_time.toString())
        ))
    }

    fun select_trkpt_bounding_box(device_id: String, north: Double, south: Double, east: Double, west: Double): Iterator<Trkpt>
    {
        Log.i("VOUSSOIR", "Track.trkpt_generator: Querying points between $north, $south, $east, $west.")
        return _trkpt_generator(this.connection.rawQuery(
            "SELECT device_id, lat, lon, time, ele, accuracy, sat FROM trkpt WHERE device_id = ? AND lat >= ? AND lat <= ? AND lon >= ? AND lon <= ? ORDER BY time ASC",
            arrayOf(device_id, south.toString(), north.toString(), west.toString(), east.toString())
        ))
    }

    fun _trkpt_generator(cursor: Cursor) = iterator<Trkpt>
    {
        val COLUMN_DEVICE = cursor.getColumnIndex("device_id")
        val COLUMN_LAT = cursor.getColumnIndex("lat")
        val COLUMN_LON = cursor.getColumnIndex("lon")
        val COLUMN_ELE = cursor.getColumnIndex("ele")
        val COLUMN_SAT = cursor.getColumnIndex("sat")
        val COLUMN_ACCURACY = cursor.getColumnIndex("accuracy")
        val COLUMN_TIME = cursor.getColumnIndex("time")
        try
        {
            while (cursor.moveToNext())
            {
                val trkpt = Trkpt(
                    device_id=cursor.getString(COLUMN_DEVICE),
                    provider="",
                    latitude=cursor.getDouble(COLUMN_LAT),
                    longitude=cursor.getDouble(COLUMN_LON),
                    altitude=cursor.getDouble(COLUMN_ELE),
                    accuracy=cursor.getFloat(COLUMN_ACCURACY),
                    time=cursor.getLong(COLUMN_TIME),
                    numberSatellites=cursor.getInt(COLUMN_SAT),
                )
                yield(trkpt)
            }
        }
        finally
        {
            cursor.close()
        }
    }

    fun delete_homepoint(id: Long)
    {
        Log.i("VOUSSOIR", "Database.delete_homepoint")
        begin_transaction()
        connection.delete("homepoints", "id = ?", arrayOf(id.toString()))
        commit()
    }

    fun insert_homepoint(id: Long, name: String, latitude: Double, longitude: Double, radius: Double)
    {
        Log.i("VOUSSOIR", "Database.insert_homepoint")
        val values = ContentValues().apply {
            put("id", id)
            put("lat", latitude)
            put("lon", longitude)
            put("radius", radius)
            put("name", name)
        }
        begin_transaction()
        connection.insert("homepoints", null, values)
        commit()
    }

    fun update_homepoint(id: Long, name: String, radius: Double)
    {
        Log.i("VOUSSOIR", "Database.update_homepoint")
        val values = ContentValues().apply {
            put("name", name)
            put("radius", radius)
        }
        begin_transaction()
        connection.update("homepoints", values, "id = ?", arrayOf(id.toString()))
        commit()
    }

    private fun initialize_tables()
    {
        begin_transaction()
        this.connection.execSQL("CREATE TABLE IF NOT EXISTS meta(name TEXT PRIMARY KEY, value TEXT)")
        this.connection.execSQL("CREATE TABLE IF NOT EXISTS trkpt(lat REAL NOT NULL, lon REAL NOT NULL, time INTEGER NOT NULL, accuracy REAL, device_id INTEGER NOT NULL, ele INTEGER, sat INTEGER, PRIMARY KEY(device_id, time))")
        this.connection.execSQL("CREATE TABLE IF NOT EXISTS homepoints(id INTEGER PRIMARY KEY, lat REAL NOT NULL, lon REAL NOT NULL, radius REAL NOT NULL, name TEXT)")
        this.connection.execSQL("CREATE INDEX IF NOT EXISTS index_trkpt_device_id_time on trkpt(device_id, time)")
        // The pragmas don't seem to execute unless you call moveToNext.
        var cursor: Cursor
        cursor = this.connection.rawQuery("PRAGMA journal_mode = DELETE", null)
        cursor.moveToNext()
        cursor.close()
        cursor = this.connection.rawQuery("PRAGMA user_version = ${net.voussoir.trkpt.Keys.DATABASE_VERSION}", null)
        cursor.moveToNext()
        cursor.close()
        // Not using this.commit because this.ready is not true yet.
        this.connection.setTransactionSuccessful()
        this.connection.endTransaction()
    }
}