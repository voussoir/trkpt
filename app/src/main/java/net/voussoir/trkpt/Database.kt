package net.voussoir.trkpt
import android.content.ContentValues
import android.database.Cursor
import io.requery.android.database.sqlite.SQLiteDatabase
import io.requery.android.database.sqlite.SQLiteDatabase.openOrCreateDatabase
import android.util.Log
import java.io.File

class Database(val trackbook: Trackbook)
{
    private var _connection: SQLiteDatabase? = null
    val connection: SQLiteDatabase
        get() = _connection ?: throw IllegalStateException("Database not ready, call connect() first.")

    var ready_at: Long = 0
    val ready: Boolean
        get() = this._connection != null
    lateinit var file: File

    // DATABASE CONNECTION METHODS /////////////////////////////////////////////////////////////////

    fun close()
    {
        Log.i("VOUSSOIR", "Database.close")
        if (this._connection == null)
        {
            Log.i("VOUSSOIR", "Database.close but the connection is already null.")
            return
        }
        val connection = this.connection
        this._connection = null
        this.ready_at = 0
        try
        {
            connection.close()
        }
        catch (exc: Exception)
        {
            Log.e("VOUSSOIR", "Database.close: raised ${exc}")
        }
        finally
        {
            this.trackbook.call_database_changed_listeners()
        }
    }

    fun connect(file: File)
    {
        Log.i("VOUSSOIR", "Database.connect: " + file.absolutePath)
        this.file = file

        this.close()

        // We hold this temporary connection variable before assigning it back to self, so that if
        // anything goes wrong during its creation we are not left with half of a connection.
        val connection = openOrCreateDatabase(file, null)

        try
        {
            // The pragmas don't seem to execute unless you call moveToNext.
            val cursor: Cursor = connection.rawQuery("PRAGMA journal_mode = DELETE", null)
            cursor.moveToNext()
            cursor.close()

            Log.i("VOUSSOIR", "Database.connect: Current version is ${connection.version}.")
            if (connection.version == 0)
            {
                initialize_tables(connection)
            }
            else if (connection.version < Keys.DATABASE_VERSION)
            {
                upgrade_schemas(connection)
            }
            else
            {
                Log.i("VOUSSOIR", "Database.connect: No schema upgrades necessary.")
            }
            this._connection = connection
            this.ready_at = System.currentTimeMillis()
        }
        catch (exc: Exception)
        {
            connection.close()
            throw exc
        }
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

    // TRKPT METHODS ///////////////////////////////////////////////////////////////////////////////

    fun select_trkpt_start_end(device_id: String, start_time: Long, end_time: Long, max_accuracy: Float=Keys.DEFAULT_MAX_ACCURACY, order: String="ASC"): Iterator<Trkpt>
    {
        Log.i("VOUSSOIR", "Track.trkpt_generator: Querying points between ${start_time} -- ${end_time}.")
        return _trkpt_generator(this.connection.rawQuery(
            """
            SELECT device_id, lat, lon, time, provider, ele, accuracy, sat
            FROM trkpt
            WHERE device_id = ? AND time >= ? AND time <= ? AND accuracy <= ? AND deleted = 0
            ORDER BY time ${order}
            """,
            arrayOf(device_id, start_time.toString(), end_time.toString(), max_accuracy.toString())
        ))
    }

    fun select_trkpt_bounding_box(device_id: String?, north: Double, south: Double, east: Double, west: Double, max_accuracy: Float=Keys.DEFAULT_MAX_ACCURACY): Iterator<Trkpt>
    {
        Log.i("VOUSSOIR", "Track.trkpt_generator: Querying points between $north, $south, $east, $west.")
        if (device_id == null)
        {
            return _trkpt_generator(this.connection.rawQuery(
                """
                SELECT trkpt.device_id, trkpt.lat, trkpt.lon, trkpt.time, trkpt.provider, trkpt.ele, trkpt.accuracy, trkpt.sat
                FROM trkpt
                JOIN trkpt_rtree ON trkpt.id = trkpt_rtree.id
                WHERE   trkpt_rtree.max_lon >= ?
                    AND trkpt_rtree.min_lon <= ?
                    AND trkpt_rtree.max_lat >= ?
                    AND trkpt_rtree.min_lat <= ?
                    AND trkpt.lon >= ?
                    AND trkpt.lon <= ?
                    AND trkpt.lat >= ?
                    AND trkpt.lat <= ?
                    AND trkpt.deleted = 0
                    AND trkpt.accuracy < ?
                ORDER BY device_id ASC, time ASC
                """,
                arrayOf(
                    west.toString(),
                    east.toString(),
                    south.toString(),
                    north.toString(),
                    west.toString(),
                    east.toString(),
                    south.toString(),
                    north.toString(),
                    max_accuracy.toString()
                )
            ))
        }
        else
        {
            return _trkpt_generator(this.connection.rawQuery(
                """
                SELECT trkpt.device_id, trkpt.lat, trkpt.lon, trkpt.time, trkpt.provider, trkpt.ele, trkpt.accuracy, trkpt.sat
                FROM trkpt
                JOIN trkpt_rtree ON trkpt.id = trkpt_rtree.id
                WHERE   device_id = ?
                    AND trkpt_rtree.max_lon >= ?
                    AND trkpt_rtree.min_lon <= ?
                    AND trkpt_rtree.max_lat >= ?
                    AND trkpt_rtree.min_lat <= ?
                    AND trkpt.lon >= ?
                    AND trkpt.lon <= ?
                    AND trkpt.lat >= ?
                    AND trkpt.lat <= ?
                    AND trkpt.deleted = 0
                    AND trkpt.accuracy < ?
                ORDER BY time ASC
                """,
                arrayOf(
                    device_id,
                    west.toString(),
                    east.toString(),
                    south.toString(),
                    north.toString(),
                    west.toString(),
                    east.toString(),
                    south.toString(),
                    north.toString(),
                    max_accuracy.toString()
                )
            ))
        }
    }

    fun insert_trkpt(trkpt: Trkpt)
    {
        Log.i("VOUSSOIR", "Database.insert_trkpt ${trkpt}")
        val values = ContentValues().apply {
            put("device_id", trkpt.device_id)
            put("time", trkpt.time)
            put("lat", trkpt.latitude)
            put("lon", trkpt.longitude)
            put("provider", trkpt.provider)
            put("accuracy", trkpt.accuracy)
            put("sat", trkpt.numberSatellites)
            put("ele", trkpt.altitude)
        }
        begin_transaction()
        connection.insert("trkpt", null, values)
    }

    fun update_trkpt(trkpt: Trkpt)
    {
        Log.i("VOUSSOIR", "Database.update_trkpt")
        val values = ContentValues().apply {
            put("lat", trkpt.latitude)
            put("lon", trkpt.longitude)
            put("provider", trkpt.provider)
            put("accuracy", trkpt.accuracy)
            put("sat", trkpt.numberSatellites)
            put("ele", trkpt.altitude)
        }
        begin_transaction()
        connection.update("trkpt", values, "device_id = ? AND time = ?", arrayOf(trkpt.device_id, trkpt.time.toString()))
    }

    fun delete_trkpt(trkpt: Trkpt)
    {
        Log.i("VOUSSOIR", "Database.delete_trkpt")
        val values = ContentValues().apply {
            put("deleted", 1)
        }
        begin_transaction()
        connection.update("trkpt", values, "device_id = ? AND time = ?", arrayOf(trkpt.device_id, trkpt.time.toString()))
    }

    fun delete_trkpt_start_end(device_id: String, start_time: Long, end_time: Long)
    {
        Log.i("VOUSSOIR", "Track.delete ${device_id} ${start_time} -- ${end_time}.")
        val values = ContentValues().apply {
            put("deleted", 1)
        }
        this.begin_transaction()
        this.connection.update("trkpt", values, "device_id = ? AND time >= ? AND time <= ?", arrayOf(device_id, start_time.toString(), end_time.toString()))
    }

    private fun _trkpt_generator(cursor: Cursor) = iterator<Trkpt>
    {
        val COLUMN_DEVICE = cursor.getColumnIndex("device_id")
        val COLUMN_LAT = cursor.getColumnIndex("lat")
        val COLUMN_LON = cursor.getColumnIndex("lon")
        val COLUMN_PROVIDER = cursor.getColumnIndex("provider")
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
                    provider=cursor.getString(COLUMN_PROVIDER),
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

    // HOMEPOINT METHODS ///////////////////////////////////////////////////////////////////////////

    fun select_homepoints() = iterator<Homepoint>
    {
        if (! ready)
        {
            Log.i("VOUSSOIR", "Trackbook.homepoint_generator: database is not ready.")
            return@iterator
        }
        val cursor: Cursor = connection.rawQuery(
            "SELECT id, lat, lon, radius, name, prune_level FROM homepoints",
            arrayOf()
        )
        Log.i("VOUSSOIR", "Trackbook.homepoint_generator: Got ${cursor.count} homepoints.")
        val COLUMN_ID = cursor.getColumnIndex("id")
        val COLUMN_LAT = cursor.getColumnIndex("lat")
        val COLUMN_LON = cursor.getColumnIndex("lon")
        val COLUMN_RADIUS = cursor.getColumnIndex("radius")
        val COLUMN_NAME = cursor.getColumnIndex("name")
        val COLUMN_PRUNE_LEVEL = cursor.getColumnIndex("prune_level")
        try
        {
            while (cursor.moveToNext())
            {
                val homepoint = Homepoint(
                    id=cursor.getLong(COLUMN_ID),
                    latitude=cursor.getDouble(COLUMN_LAT),
                    longitude=cursor.getDouble(COLUMN_LON),
                    radius=cursor.getDouble(COLUMN_RADIUS),
                    name=cursor.getString(COLUMN_NAME),
                    prune_level=cursor.getInt(COLUMN_PRUNE_LEVEL),
                )
                yield(homepoint)
            }
        }
        finally
        {
            cursor.close()
        }
    }

    fun insert_homepoint(id: Long, name: String, latitude: Double, longitude: Double, radius: Double, prune_level: Int)
    {
        Log.i("VOUSSOIR", "Database.insert_homepoint")
        val values = ContentValues().apply {
            put("id", id)
            put("lat", latitude)
            put("lon", longitude)
            put("radius", radius)
            put("name", name)
            put("prune_level", prune_level)
        }
        begin_transaction()
        connection.insert("homepoints", null, values)
    }

    fun update_homepoint(id: Long, name: String, radius: Double, prune_level: Int)
    {
        Log.i("VOUSSOIR", "Database.update_homepoint")
        val values = ContentValues().apply {
            put("name", name)
            put("radius", radius)
            put("prune_level", prune_level)
        }
        begin_transaction()
        connection.update("homepoints", values, "id = ?", arrayOf(id.toString()))
    }

    fun delete_homepoint(id: Long)
    {
        Log.i("VOUSSOIR", "Database.delete_homepoint")
        begin_transaction()
        connection.delete("homepoints", "id = ?", arrayOf(id.toString()))
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////
}

fun execute_script(connection: SQLiteDatabase, script: String)
{
    val lines = script.split(Regex(";(:?\n|$)")).map { it.trim() }.filter { it.isNotEmpty() }
    for (line in lines)
    {
        Log.i("VOUSSOIR", "Database.${line}")
        connection.execSQL(line)
    }
}

private fun initialize_tables(connection: SQLiteDatabase)
{
    Log.i("VOUSSOIR", "Database.initialize_tables")
    connection.beginTransaction()
    execute_script(connection, """
        CREATE TABLE meta(name TEXT PRIMARY KEY, value TEXT);

        CREATE TABLE trkpt(
            id INTEGER PRIMARY KEY,
            device_id TEXT NOT NULL,
            time INTEGER NOT NULL,
            lat REAL NOT NULL,
            lon REAL NOT NULL,
            provider TEXT,
            accuracy REAL,
            ele INTEGER,
            sat INTEGER,
            deleted INTEGER DEFAULT 0,
            UNIQUE (device_id, time)
        );
        CREATE INDEX index_trkpt_device_id_time on trkpt(device_id, time);

        CREATE TABLE homepoints(
            id INTEGER PRIMARY KEY,
            lat REAL NOT NULL,
            lon REAL NOT NULL,
            radius REAL NOT NULL,
            name TEXT NOT NULL CHECK (length(trim(name)) > 0),
            prune_level INTEGER NOT NULL CHECK (prune_level IN (0, 1, 2))
        );

        CREATE VIRTUAL TABLE trkpt_rtree USING rtree(
            id,
            min_lon, max_lon,
            min_lat, max_lat,
            min_time, max_time
        );
        """)

    connection.execSQL("""
        CREATE TRIGGER trkpt_rtree_insert AFTER INSERT ON trkpt BEGIN
            INSERT INTO trkpt_rtree(id, min_lon, max_lon, min_lat, max_lat, min_time, max_time)
            VALUES (new.id, new.lon, new.lon, new.lat, new.lat, new.time, new.time);
        END;
        """)

    connection.execSQL("""
        CREATE TRIGGER trkpt_rtree_update AFTER UPDATE ON trkpt BEGIN
        UPDATE trkpt_rtree SET
            min_lon=new.lon,
            max_lon=new.lon,
            min_lat=new.lat,
            max_lat=new.lat,
            min_time=new.time,
            max_time=new.time
        WHERE id = old.id;
        END;
        """)

    connection.execSQL("""
        CREATE TRIGGER trkpt_rtree_delete AFTER DELETE ON trkpt BEGIN
            DELETE FROM trkpt_rtree WHERE id = old.id;
        END;
        """)

    connection.version = Keys.DATABASE_VERSION
    connection.setTransactionSuccessful()
    connection.endTransaction()
}


private fun migrator(
    connection: SQLiteDatabase,
    new_version: Int,
    block: () -> Unit
)
{
    if (connection.version >= new_version)
    {
        return
    }
    Log.i("VOUSSOIR", "Database.migrator ${new_version}")
    if (connection.inTransaction())
    {
        throw IllegalStateException("Cannot start migrator inside a transaction.")
    }

    val had_foreign_keys = pragma_foreign_keys(connection)
    if (had_foreign_keys)
    {
        connection.execSQL("PRAGMA foreign_keys = OFF")
    }

    try
    {
        connection.beginTransaction()
        try
        {
            block()

            // Check once, after every table rebuild for this version is finished.
            if (had_foreign_keys && pragma_foreign_key_check(connection))
            {
                throw IllegalStateException(
                    "Migration to ${new_version} left foreign key violations."
                )
            }

            connection.version = new_version
            connection.setTransactionSuccessful()
        }
        finally
        {
            connection.endTransaction()
        }
    }
    finally
    {
        if (had_foreign_keys)
        {
            connection.execSQL("PRAGMA foreign_keys = ON")
        }
    }
}
private fun rebuild_table(
    connection: SQLiteDatabase,
    table_name: String,
    create_sql: String,
    transfer_sql: String,
)
{
    val temp_table_name = "${table_name}__rebuild"
    val table_name_escaped = sql_escape(table_name)
    val temp_table_name_escaped = sql_escape(temp_table_name)

    val indexes_triggers = get_indexes_triggers(connection, table_name)

    connection.execSQL(create_sql.replace("{table}", temp_table_name_escaped))
    connection.execSQL(transfer_sql.replace("{table}", temp_table_name_escaped))
    connection.execSQL("DROP TABLE ${table_name_escaped}")
    connection.execSQL("ALTER TABLE ${temp_table_name_escaped} RENAME TO ${table_name_escaped}")

    for (sql in indexes_triggers)
    {
        connection.execSQL(sql)
    }
}

private fun pragma_foreign_keys(connection: SQLiteDatabase): Boolean
{
    val cursor = connection.rawQuery("PRAGMA foreign_keys", null)
    try
    {
        return cursor.moveToFirst() && cursor.getInt(0) != 0
    }
    finally
    {
        cursor.close()
    }
}

private fun pragma_foreign_key_check(connection: SQLiteDatabase): Boolean
{
    val cursor = connection.rawQuery("PRAGMA foreign_key_check", null)
    try
    {
        return cursor.count > 0
    }
    finally
    {
        cursor.close()
    }
}

private fun get_indexes_triggers(connection: SQLiteDatabase, table_name: String): ArrayList<String>
{
    val indexes_triggers = ArrayList<String>()
    val cursor = connection.rawQuery("""
            SELECT sql
            FROM sqlite_master
            WHERE tbl_name = ? AND type IN ('index', 'trigger') AND sql IS NOT NULL
            ORDER BY type, name
            """,
        arrayOf(table_name)
    )
    try
    {
        while (cursor.moveToNext())
        {
            indexes_triggers.add(cursor.getString(0))
        }
    }
    finally
    {
        cursor.close()
    }
    return indexes_triggers
}

private fun sql_escape(identifier: String): String
{
    return "\"" + identifier.replace("\"", "\"\"") + "\""
}

fun upgrade_schemas(connection: SQLiteDatabase)
{
    Log.i("VOUSSOIR", "Database.upgrade_schemas")
    migrator(connection, new_version=2, { upgrade_1_to_2(connection) })
    migrator(connection, new_version=3, { upgrade_2_to_3(connection) })
}

fun upgrade_1_to_2(connection: SQLiteDatabase)
{
    Log.i("VOUSSOIR", "Database.upgrade_1_to_2")

    rebuild_table(
        connection,
        table_name="homepoints",
        create_sql="""
            CREATE TABLE {table} (
                id INTEGER PRIMARY KEY,
                lat REAL NOT NULL,
                lon REAL NOT NULL,
                radius REAL NOT NULL,
                name TEXT NOT NULL CHECK (length(trim(name)) > 0),
                prune_level INTEGER NOT NULL CHECK (prune_level IN (0, 1, 2))
            )
            """,
        transfer_sql="""
            INSERT INTO {table} (
                id,
                lat,
                lon,
                radius,
                name,
                prune_level
            )
            SELECT
                id,
                lat,
                lon,
                radius,
                CASE
                    WHEN name IS NULL OR length(trim(name)) = 0 THEN 'untitled'
                    ELSE name
                END,
                0
            FROM homepoints
            """,
    )
}

fun upgrade_2_to_3(connection: SQLiteDatabase)
{
    Log.i("VOUSSOIR", "Database.upgrade_2_to_3")

    rebuild_table(
        connection,
        table_name="trkpt",
        create_sql="""
            CREATE TABLE {table}(
                id INTEGER PRIMARY KEY,
                device_id TEXT NOT NULL,
                time INTEGER NOT NULL,
                lat REAL NOT NULL,
                lon REAL NOT NULL,
                provider TEXT,
                accuracy REAL,
                ele INTEGER,
                sat INTEGER,
                deleted INTEGER DEFAULT 0,
                UNIQUE (device_id, time)
            )
            """,
        transfer_sql="""
            INSERT INTO {table} (
                id,
                device_id,
                time,
                lat,
                lon,
                provider,
                accuracy,
                ele,
                sat,
                deleted
            )
            SELECT
                rowid,
                device_id,
                time,
                lat,
                lon,
                provider,
                accuracy,
                ele,
                sat,
                0
            FROM trkpt
            """
    )

    execute_script(connection, """
    CREATE VIRTUAL TABLE trkpt_rtree USING rtree(
        id,
        min_lon, max_lon,
        min_lat, max_lat,
        min_time, max_time
    );

    INSERT INTO trkpt_rtree(id, min_lon, max_lon, min_lat, max_lat, min_time, max_time)
    SELECT id, lon, lon, lat, lat, time, time FROM trkpt;
    """)

    connection.execSQL("""
    CREATE TRIGGER trkpt_rtree_insert AFTER INSERT ON trkpt BEGIN
        INSERT INTO trkpt_rtree(id, min_lon, max_lon, min_lat, max_lat, min_time, max_time)
        VALUES (new.id, new.lon, new.lon, new.lat, new.lat, new.time, new.time);
    END;
    """)

    connection.execSQL("""
    CREATE TRIGGER trkpt_rtree_update AFTER UPDATE ON trkpt BEGIN
    UPDATE trkpt_rtree SET
        min_lon=new.lon,
        max_lon=new.lon,
        min_lat=new.lat,
        max_lat=new.lat,
        min_time=new.time,
        max_time=new.time
    WHERE id = old.id;
    END;
    """)

    connection.execSQL("""
    CREATE TRIGGER trkpt_rtree_delete AFTER DELETE ON trkpt BEGIN
        DELETE FROM trkpt_rtree WHERE id = old.id;
    END;
    """)
}
