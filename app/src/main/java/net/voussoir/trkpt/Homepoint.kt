package net.voussoir.trkpt
import android.location.Location
import java.util.*

class Homepoint(val id: Long, val latitude: Double, val longitude: Double, val radius: Double, val name: String)
{
    val location: Location = this.to_location()

    private fun to_location(): Location
    {
        val location = Location("homepoint")
        location.latitude = latitude
        location.longitude = longitude
        location.altitude = 0.0
        location.accuracy = radius.toFloat()
        location.time = System.currentTimeMillis()
        return location
    }
}
