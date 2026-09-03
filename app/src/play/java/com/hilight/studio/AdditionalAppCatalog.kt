package com.hilight.studio

import android.content.pm.PackageManager

/** Play builds intentionally rely on scoped launcher visibility and locally learned packages. */
internal object AdditionalAppCatalog {
    fun load(pm: PackageManager): List<InstalledApp> = emptyList()
}
