package org.y20k.trackbook.helpers
import java.lang.Math.abs
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.*
import kotlin.random.Random

val iso8601_format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US)
private val RNG = SecureRandom()

fun iso8601(datetime: Date): String
{
    return iso8601_format.format(datetime)
}

fun random_int(): Int
{
    return abs(RNG.nextInt())
}

fun random_device_id(): String
{
    return "myphone" + random_int()
}