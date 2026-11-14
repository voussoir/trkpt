/*
 * HomepointHelper.kt
 * Shared homepoint dialogs and map overlays for RecordFragment and TrackFragment.
 *
 * This file is part of trkpt.
 */

package net.voussoir.trkpt.helpers

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.util.Log
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import net.voussoir.trkpt.Homepoint
import net.voussoir.trkpt.Keys
import net.voussoir.trkpt.R
import net.voussoir.trkpt.Trackbook
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.ItemizedIconOverlay
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.OverlayItem
import org.osmdroid.views.overlay.Polygon
import java.lang.Math.floor

fun create_pruning_spinner(context: Context, spinner: Spinner, prune_level: Int)
{
    val adapter = ArrayAdapter.createFromResource(
        context,
        R.array.homepoint_pruning_levels,
        android.R.layout.simple_spinner_item
    )
    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
    spinner.adapter = adapter
    val position = if (prune_level in 0 until adapter.count) prune_level else Keys.HOMEPOINT_KEEP_ALL
    spinner.setSelection(position)
}

fun update_homepoint_save_button(save_button: Button, name_input: EditText, radius_input: EditText)
{
    save_button.isEnabled = !name_input.text.isNullOrBlank() && !radius_input.text.isNullOrBlank()
}

fun create_homepoint_radius_overlay(homepoint: Homepoint): Polygon
{
    val p = Polygon()
    p.points = Polygon.pointsAsCircle(
        GeoPoint(homepoint.latitude, homepoint.longitude),
        homepoint.radius
    )
    p.fillPaint.color = Color.argb(64, 255, 193, 7)
    p.outlinePaint.color = Color.argb(0, 0, 0, 0)
    // The radius circle is decorative. Polygon's default click handler consumes single taps even
    // without an info window, which would block track-point selection underneath.
    p.setOnClickListener { _, _, _ -> false }
    return p
}

fun create_homepoint_marker_overlay(
    context: Context,
    trackbook: Trackbook,
    homepoint: Homepoint,
    on_long_press: (Homepoint) -> Boolean,
): ItemizedIconOverlay<OverlayItem>
{
    val overlayItems: ArrayList<OverlayItem> = ArrayList<OverlayItem>()
    val overlayItem: OverlayItem = createOverlayItem(
        homepoint.latitude,
        homepoint.longitude,
        title=homepoint.name,
        description="Radius ${homepoint.radius}"
    )
    overlayItem.setMarker(ContextCompat.getDrawable(context, R.drawable.ic_homepoint_24dp)!!)
    overlayItems.add(overlayItem)
    return ItemizedIconOverlay<OverlayItem>(context, overlayItems,
        object : ItemizedIconOverlay.OnItemGestureListener<OverlayItem> {
            override fun onItemSingleTapUp(index: Int, item: OverlayItem): Boolean
            {
                return false
            }
            override fun onItemLongPress(index: Int, item: OverlayItem): Boolean
            {
                if (trackbook.database.read_only)
                {
                    Toast.makeText(context, homepoint.name, Toast.LENGTH_LONG).show()
                    return true
                }
                return on_long_press(homepoint)
            }
        }
    )
}

fun build_homepoint_overlays(
    context: Context,
    trackbook: Trackbook,
    homepoints: Iterable<Homepoint>,
    on_long_press: (Homepoint) -> Boolean,
): ArrayList<Overlay>
{
    val overlays = ArrayList<Overlay>()
    for (homepoint in homepoints)
    {
        overlays.add(create_homepoint_radius_overlay(homepoint))
        overlays.add(create_homepoint_marker_overlay(context, trackbook, homepoint, on_long_press))
    }
    return overlays
}

fun show_homepoint_dialog(
    activity: Activity,
    trackbook: Trackbook,
    point: GeoPoint,
    homepoint: Homepoint?,
    on_changed: () -> Unit,
): Boolean
{
    if (! trackbook.database.ready)
    {
        Log.i("VOUSSOIR", "HomepointHelper.show_homepoint_dialog: database is not ready.")
        return false
    }
    if (activity.isFinishing || activity.isDestroyed)
    {
        Log.i("VOUSSOIR", "HomepointHelper.show_homepoint_dialog: activity is finishing.")
        return false
    }
    if (trackbook.database.read_only)
    {
        Toast.makeText(activity, R.string.toast_read_only, Toast.LENGTH_SHORT).show()
        return true
    }

    val existing_homepoint = homepoint
    val dialog = Dialog(activity)
    dialog.setContentView(R.layout.dialog_homepoint)
    dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    dialog.setTitle("Homepoint")

    (dialog.findViewById(R.id.homepoint_dialog_title) as TextView).text =
        if (existing_homepoint == null) "Add a homepoint" else "Edit homepoint"

    val name_input: EditText = dialog.findViewById(R.id.homepoint_name_input)
    val radius_input: EditText = dialog.findViewById(R.id.homepoint_radius_input)
    val pruning_spinner: Spinner = dialog.findViewById(R.id.homepoint_pruning_spinner)
    create_pruning_spinner(
        activity,
        pruning_spinner,
        existing_homepoint?.prune_level ?: Keys.HOMEPOINT_KEEP_ALL
    )

    if (existing_homepoint != null)
    {
        name_input.setText(existing_homepoint.name)
        if (existing_homepoint.radius == floor(existing_homepoint.radius))
        {
            radius_input.setText(existing_homepoint.radius.toInt().toString())
        }
        else
        {
            radius_input.setText(existing_homepoint.radius.toString())
        }
    }

    val delete_cancel_button: Button = dialog.findViewById(R.id.homepoint_delete_cancel_button)
    val save_button: Button = dialog.findViewById(R.id.homepoint_save_button)
    name_input.doAfterTextChanged {
        update_homepoint_save_button(save_button, name_input, radius_input)
    }
    radius_input.doAfterTextChanged {
        update_homepoint_save_button(save_button, name_input, radius_input)
    }
    update_homepoint_save_button(save_button, name_input, radius_input)

    delete_cancel_button.text = if (existing_homepoint == null) "Cancel" else "Delete"
    delete_cancel_button.setOnClickListener {
        if (existing_homepoint == null)
        {
            dialog.cancel()
            return@setOnClickListener
        }
        if (! can_write(activity, trackbook))
        {
            return@setOnClickListener
        }
        trackbook.database.delete_homepoint(existing_homepoint.id)
        trackbook.database.commit()
        trackbook.load_homepoints()
        on_changed()
        dialog.dismiss()
    }

    save_button.setOnClickListener {
        if (! can_write(activity, trackbook))
        {
            return@setOnClickListener
        }
        val radius = radius_input.text.toString().toDoubleOrNull() ?: 25.0
        if (existing_homepoint == null)
        {
            trackbook.database.insert_homepoint(
                id=random_long(),
                name=name_input.text.toString(),
                latitude=point.latitude,
                longitude=point.longitude,
                radius=radius,
                prune_level=pruning_spinner.selectedItemPosition,
            )
        }
        else
        {
            trackbook.database.update_homepoint(
                existing_homepoint.id,
                name=name_input.text.toString(),
                radius=radius,
                prune_level=pruning_spinner.selectedItemPosition,
            )
        }
        trackbook.database.commit()
        trackbook.load_homepoints()
        on_changed()
        dialog.dismiss()
    }

    dialog.show()
    return true
}

private fun can_write(activity: Activity, trackbook: Trackbook): Boolean
{
    if (! trackbook.database.ready)
    {
        Log.e("VOUSSOIR", "HomepointHelper.can_write: database is not ready.")
        return false
    }
    if (trackbook.database.read_only)
    {
        Toast.makeText(activity, R.string.toast_read_only, Toast.LENGTH_SHORT).show()
        return false
    }
    return true
}
