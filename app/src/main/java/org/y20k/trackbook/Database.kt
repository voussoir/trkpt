package org.y20k.trackbook
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabase.openOrCreateDatabase
import android.util.Log
import java.io.File

class Database()
{
    var ready: Boolean = false
    lateinit var file: File
    lateinit var connection: SQLiteDatabase

    fun close()
    {
        this.connection.close()
        this.ready = false
    }

    fun connect(file: File)
    {
        Log.i("VOUSSOIR", "Connecting to database " + file.absolutePath)
        this.file = file
        this.connection = openOrCreateDatabase(file, null)
        this.initialize_tables()
        this.ready = true
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

    private fun initialize_tables()
    {
        this.connection.beginTransaction()
        this.connection.execSQL("CREATE TABLE IF NOT EXISTS meta(name TEXT PRIMARY KEY, value TEXT)")
        this.connection.execSQL("CREATE TABLE IF NOT EXISTS trkpt(lat REAL NOT NULL, lon REAL NOT NULL, time INTEGER NOT NULL, accuracy REAL, device_id INTEGER NOT NULL, ele INTEGER, sat INTEGER, star INTEGER, PRIMARY KEY(lat, lon, time, device_id))")
        this.connection.execSQL("CREATE TABLE IF NOT EXISTS homepoints(lat REAL NOT NULL, lon REAL NOT NULL, radius REAL NOT NULL, name TEXT, PRIMARY KEY(lat, lon))")
        this.connection.setTransactionSuccessful()
        this.connection.endTransaction()
    }
}