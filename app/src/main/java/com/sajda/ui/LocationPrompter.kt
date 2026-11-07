package com.sajda.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.sajda.R
import com.sajda.data.OfflineCities

/**
 * System / permission / city dialogs — kept out of MainActivity.
 */
class LocationPrompter(private val activity: Activity) {

    fun promptEnableSystemLocation() {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.location_off_title)
            .setMessage(R.string.location_off_message)
            .setPositiveButton(R.string.open_settings) { _, _ ->
                try {
                    activity.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                } catch (_: Exception) {}
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    fun promptAppPermissionSettings() {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.location_permission_denied)
            .setMessage(R.string.permission_pre_body)
            .setPositiveButton(R.string.open_settings) { _, _ ->
                try {
                    activity.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", activity.packageName, null)
                        }
                    )
                } catch (_: Exception) {}
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    fun showDeniedRecoveryDialog(
        onAllow: () -> Unit,
        onChooseCity: () -> Unit
    ) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.location_permission_denied)
            .setMessage(R.string.permission_pre_body)
            .setPositiveButton(R.string.permission_allow_location) { _, _ -> onAllow() }
            .setNeutralButton(R.string.permission_choose_city) { _, _ -> onChooseCity() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    fun showPermissionPreSheet(
        onContinue: () -> Unit,
        onCity: () -> Unit,
        onNotNow: () -> Unit
    ) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.permission_pre_title)
            .setMessage(R.string.permission_pre_body)
            .setPositiveButton(R.string.permission_pre_continue) { _, _ -> onContinue() }
            .setNeutralButton(R.string.permission_choose_city) { _, _ -> onCity() }
            .setNegativeButton(R.string.permission_pre_not_now) { _, _ -> onNotNow() }
            .show()
    }

    fun showCityPicker(
        onCitySelected: (lat: Double, lon: Double, nameResKey: String) -> Unit
    ) {
        val labels = OfflineCities.all.map { city ->
            val resId = activity.resources.getIdentifier(
                "city_${city.nameResKey}", "string", activity.packageName
            )
            if (resId != 0) activity.getString(resId) else city.nameResKey
        }.toTypedArray()
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.choose_city)
            .setItems(labels) { _, which ->
                val city = OfflineCities.all[which]
                onCitySelected(city.lat, city.lon, city.nameResKey)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
