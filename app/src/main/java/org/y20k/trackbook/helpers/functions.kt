package org.y20k.trackbook.helpers

import java.text.SimpleDateFormat
import java.util.*
import kotlin.random.Random

val iso8601_format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US)

fun iso8601(datetime: Date): String
{
    return iso8601_format.format(datetime)
}

fun random_long(): Long
{
    return (Random.nextBits(31).toLong() shl 32) + Random.nextBits(32)
}

fun random_int(): Int
{
    return Random.nextBits(31)
}