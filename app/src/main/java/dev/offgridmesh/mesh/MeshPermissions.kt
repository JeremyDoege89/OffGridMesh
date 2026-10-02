package dev.offgridmesh.mesh

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** Runtime permission groups, each with its own rationale screen before the system dialog (§3.5, §7.1). */
enum class PermissionGroup(val permissions: List<String>) {
    NEARBY(
        buildList {
            if (Build.VERSION.SDK_INT >= 31) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
            }
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
        },
    ),
    LOCATION(listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)),
    NOTIFICATIONS(if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()),
    ;

    fun granted(context: Context): Boolean = permissions.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    /** Groups with nothing to ask on this API level are skipped entirely. */
    val applicable: Boolean get() = permissions.isNotEmpty()
}

object MeshPermissions {
    /** What discovery itself is gated on. Location is only needed for discovery on API ≤ 32. */
    fun meshGroups(): List<PermissionGroup> = buildList {
        if (PermissionGroup.NEARBY.applicable) add(PermissionGroup.NEARBY)
        if (Build.VERSION.SDK_INT <= 32) add(PermissionGroup.LOCATION)
    }

    fun canRunMesh(context: Context): Boolean = meshGroups().all { it.granted(context) }
}
