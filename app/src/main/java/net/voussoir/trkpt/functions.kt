package net.voussoir.trkpt.helpers

import java.lang.Math.abs
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.*

private val RNG = SecureRandom()

fun iso8601(timestamp: Long): String
{
    val iso8601_format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
    iso8601_format.timeZone = TimeZone.getTimeZone("UTC")
    return iso8601_format.format(timestamp)
}

fun iso8601_local(timestamp: Long): String
{
    val iso8601_format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS")
    return iso8601_format.format(timestamp)
}

fun iso8601(datetime: Date): String
{
    return iso8601(datetime.time)
}

fun iso8601_parse(datetime: String): Date
{
    val iso8601_format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
    iso8601_format.timeZone = TimeZone.getTimeZone("UTC")
    return iso8601_format.parse(datetime)
}

fun random_int(): Int
{
    return abs(RNG.nextInt())
}

fun random_long(): Long
{
    return abs(RNG.nextLong())
}

fun random_device_id(): String
{
    return "myphone" + random_int()
}
