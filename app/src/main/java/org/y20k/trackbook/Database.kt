package org.y20k.trackbook
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabase.openOrCreateDatabase
import android.util.Log
import java.io.File
import java.util.*

class Database(trackbook: Trackbook)
{
    val trackbook = trackbook
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

    fun insert_trkpt(device_id: String, trkpt: Trkpt)
    {
        Log.i("VOUSSOIR", "Database.insert_trkpt")
        val values = ContentValues().apply {
            put("device_id", device_id)
            put("lat", trkpt.latitude)
            put("lon", trkpt.longitude)
            put("time", GregorianCalendar.getInstance().time.time)
            put("accuracy", trkpt.accuracy)
            put("sat", trkpt.numberSatellites)
            put("ele", trkpt.altitude)
        }
        if (! connection.inTransaction())
        {
            connection.beginTransaction()
        }
        connection.insert("trkpt", null, values)
    }

    fun insert_homepoint(name: String, latitude: Double, longitude: Double, radius: Double)
    {
        Log.i("VOUSSOIR", "Database.insert_homepoint")
        val values = ContentValues().apply {
            put("lat", latitude)
            put("lon", longitude)
            put("radius", radius)
            put("name", name)
        }
        if (! connection.inTransaction())
        {
            connection.beginTransaction()
        }
        connection.insert("homepoints", null, values)
        commit()
        trackbook.load_homepoints()
    }

    private fun initialize_tables()
    {
        this.connection.beginTransaction()
        this.connection.execSQL("CREATE TABLE IF NOT EXISTS meta(name TEXT PRIMARY KEY, value TEXT)")
        this.connection.execSQL("CREATE TABLE IF NOT EXISTS trkpt(lat REAL NOT NULL, lon REAL NOT NULL, time INTEGER NOT NULL, accuracy REAL, device_id INTEGER NOT NULL, ele INTEGER, sat INTEGER, PRIMARY KEY(lat, lon, time, device_id))")
        this.connection.execSQL("CREATE TABLE IF NOT EXISTS homepoints(lat REAL NOT NULL, lon REAL NOT NULL, radius REAL NOT NULL, name TEXT, PRIMARY KEY(lat, lon))")
        this.connection.setTransactionSuccessful()
        this.connection.endTransaction()
    }
}