/*
 * FileHelper.kt
 * Implements the FileHelper object
 * A FileHelper provides helper methods for reading and writing files from and to device storage
 *
 * This file is part of
 * TRACKBOOK - Movement Recorder for Android
 *
 * Copyright (c) 2016-22 - Y20K.org
 * Licensed under the MIT-License
 * http://opensource.org/licenses/MIT
 *
 * Trackbook uses osmdroid - OpenStreetMap-Tools for Android
 * https://github.com/osmdroid/osmdroid
 */


package org.y20k.trackbook.helpers

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.net.toUri
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.*
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import org.y20k.trackbook.Keys
import org.y20k.trackbook.core.Track


/*
 * FileHelper object
 */
object FileHelper {

    /* Define log tag */
    private val TAG: String = LogHelper.makeLogTag(FileHelper::class.java)

    fun delete_temp_file(context: Context)
    {
        val temp: File = get_temp_file(context)
        if (temp.isFile())
        {
            temp.delete()
        }
    }
    fun get_temp_file(context: Context): File
    {
        return File(context.getExternalFilesDir(Keys.FOLDER_TEMP), Keys.TEMP_FILE)
    }

    /* Suspend function: Wrapper for renameTrack */
    suspend fun renameTrackSuspended(context: Context, track: Track, newName: String) {
        return suspendCoroutine { cont ->
            track.name = newName
            track.save_both(context)
            cont.resume(Unit)
        }
    }



    /* Suspend function: Wrapper for copyFile */
    suspend fun saveCopyOfFileSuspended(context: Context, originalFileUri: Uri, targetFileUri: Uri, deleteOriginal: Boolean = false) {
        return suspendCoroutine { cont ->
            cont.resume(copyFile(context, originalFileUri, targetFileUri, deleteOriginal))
        }
    }


    /* Copies file to specified target */
    private fun copyFile(context: Context, originalFileUri: Uri, targetFileUri: Uri, deleteOriginal: Boolean = false) {
        val inputStream = context.contentResolver.openInputStream(originalFileUri)
        val outputStream = context.contentResolver.openOutputStream(targetFileUri)
        if (outputStream != null) {
            inputStream?.copyTo(outputStream)
        }
        if (deleteOriginal) {
            context.contentResolver.delete(originalFileUri, null, null)
        }
    }

    fun getCustomGson(): Gson
    {
        val gsonBuilder = GsonBuilder()
        gsonBuilder.setDateFormat("yyyy-MM-dd-HH-mm-ss")
        gsonBuilder.excludeFieldsWithoutExposeAnnotation()
        return gsonBuilder.create()
    }


    /* Reads InputStream from file uri and returns it as String */
    fun readTextFile(context: Context, file: File): String {
        // todo read https://commonsware.com/blog/2016/03/15/how-consume-content-uri.html
        // https://developer.android.com/training/secure-file-sharing/retrieve-info
        if (!file.exists()) {
            return String()
        }
        // read until last line reached
        val stream: InputStream = file.inputStream()
        val reader: BufferedReader = BufferedReader(InputStreamReader(stream))
        val builder: StringBuilder = StringBuilder()
        reader.forEachLine {
            builder.append(it)
            builder.append("\n") }
        stream.close()
        return builder.toString()
    }


    /* Writes given text to file on storage */
    fun write_text_file_noblank(text: String, file: File)
    {
        if (text.isNotEmpty()) {
            file.writeText(text)
        } else {
            LogHelper.w(TAG, "Writing text file ${file.toUri()} failed. Empty text string was provided.")
        }
    }
}
