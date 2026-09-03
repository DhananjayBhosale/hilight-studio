package com.hilight.studio

import android.annotation.SuppressLint
import android.content.pm.PackageManager

/** Extra packages available only to the GitHub build, which declares broad package visibility. */
internal object AdditionalAppCatalog {
    @SuppressLint("QueryPermissionsNeeded")
    fun load(pm: PackageManager): List<InstalledApp> = runCatching {
        pm.getInstalledApplications(0).map { app ->
            InstalledApp(app.packageName, pm.getApplicationLabel(app).toString(), app)
        }
    }.getOrDefault(emptyList())
}
