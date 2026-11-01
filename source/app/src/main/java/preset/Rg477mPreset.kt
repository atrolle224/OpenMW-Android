/*
    This file is part of OpenMW-Android.

    OpenMW-Android is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/

package preset

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import android.view.Display
import file.GameFilesLocation
import java.io.File

/**
 * This build's defaults for an Anbernic RG477M on GammaOS (1280x960, 120 Hz) and one
 * particular modded install in Documents/Morrowind Android.
 *
 *  - The game folder: chosen automatically on first run when one of [GAME_ROOTS] holds a
 *    "Data Files" folder. A folder the user picks later is never replaced.
 *  - The mod list: on the first launch (an empty mod database) every plugin and archive in
 *    assets/rg477m/load_order.txt that exists is enabled, in that order (ModsCollection).
 *  - zz Merged Leveled Lists.esp (assets/rg477m): copied into Data Files when missing. It
 *    merges the leveled lists that several of the install's mods change, which OpenMW does
 *    not do itself (the last plugin would otherwise win the whole list).
 *  - Resolution 1280x960, frame rate limit 120 when the display offers 120 Hz, and the
 *    display's highest refresh rate in game (GameActivity, "pref_refresh_rate").
 */
object Rg477mPreset {
    private const val TAG = "Rg477mPreset"
    private const val PREF_APPLIED = "rg477m_preset_applied_v1"
    const val PREF_REFRESH_RATE = "pref_refresh_rate"
    const val MERGED_LISTS = "zz Merged Leveled Lists.esp"

    /** Android names the folder "Documents"; both spellings are tried, as typed. */
    private val GAME_ROOTS = listOf(
        "/storage/emulated/0/Documents/Morrowind Android",
        "/storage/emulated/0/documents/Morrowind Android",
    )

    data class Order(val plugins: List<String>, val archives: List<String>)

    @Volatile
    private var cachedOrder: Order? = null

    /** The bundled load order, or null when the asset is missing. */
    fun order(ctx: Context): Order? {
        cachedOrder?.let { return it }
        return try {
            val plugins = mutableListOf<String>()
            val archives = mutableListOf<String>()
            ctx.assets.open("rg477m/load_order.txt").bufferedReader().useLines { lines ->
                lines.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.forEach {
                    when {
                        it.startsWith("plugin:") -> plugins += it.substringAfter(':').trim()
                        it.startsWith("archive:") -> archives += it.substringAfter(':').trim()
                    }
                }
            }
            Order(plugins, archives).also { cachedOrder = it }
        } catch (e: Exception) {
            Log.w(TAG, "no bundled load order: $e")
            null
        }
    }

    /**
     * Applies the defaults that are still unset. Safe to call on every resume: each step
     * only fills in what the user has not chosen, and the folder step waits for the storage
     * permission (the folder only "exists" once it is granted).
     */
    fun apply(activity: Activity, prefs: SharedPreferences) {
        val editor = prefs.edit()
        if (prefs.getString(GameFilesLocation.PREF_GAME_FILES, "").isNullOrEmpty()) {
            GAME_ROOTS.map(::File).firstOrNull { File(it, "Data Files").isDirectory }?.let { root ->
                Log.i(TAG, "game folder: $root")
                editor.putString(GameFilesLocation.PREF_GAME_FILES, root.path)
                editor.putString(GameFilesLocation.PREF_STORAGE_ROOT, "/storage/emulated/0")
            }
        }
        if (!prefs.getBoolean(PREF_APPLIED, false)) {
            if (prefs.getString("pref_customResolution", "").isNullOrEmpty())
                editor.putString("pref_customResolution", "1280x960")
            val maxHz = maxRefreshRate(activity)
            editor.putString("gs_framerate_limit", if (maxHz >= 119f) "120" else if (maxHz >= 89f) "90" else "60")
            if (!prefs.contains(PREF_REFRESH_RATE))
                editor.putString(PREF_REFRESH_RATE, "max")
            editor.putBoolean(PREF_APPLIED, true)
            Log.i(TAG, "display up to ${maxHz} Hz: resolution 1280x960, frame rate limit set")
        }
        editor.apply()
        installMergedLists(activity, prefs.getString(GameFilesLocation.PREF_GAME_FILES, "").orEmpty())
    }

    /** Copies the merged leveled lists plugin into Data Files when it is not there yet. */
    private fun installMergedLists(ctx: Context, gameRoot: String) {
        if (gameRoot.isEmpty())
            return
        val dataFiles = File(gameRoot, "Data Files").takeIf { it.isDirectory } ?: return
        val target = File(dataFiles, MERGED_LISTS)
        if (target.isFile)
            return
        try {
            val tmp = File(dataFiles, "$MERGED_LISTS.tmp")
            ctx.assets.open("rg477m/$MERGED_LISTS").use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            }
            if (!tmp.renameTo(target))
                tmp.delete()
            Log.i(TAG, "installed $target")
        } catch (e: Exception) {
            Log.w(TAG, "could not install $MERGED_LISTS: $e")
        }
    }

    /** The display's highest refresh rate in Hz (60 when it cannot be read). */
    fun maxRefreshRate(activity: Activity): Float {
        val display = displayOf(activity) ?: return 60f
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            display.supportedModes.maxOfOrNull { it.refreshRate } ?: display.refreshRate
        else
            display.refreshRate
    }

    /**
     * Asks for the display mode matching [choice] ("max", "120", "90", "60"; anything else
     * leaves the system's choice): the one with the current resolution whose refresh rate is
     * the highest, or the nearest to the number asked for.
     */
    fun applyRefreshRate(activity: Activity, choice: String?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || choice.isNullOrEmpty() || choice == "system")
            return
        val display = displayOf(activity) ?: return
        val current = display.mode
        val sameSize = display.supportedModes.filter {
            it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight
        }.ifEmpty { display.supportedModes.toList() }
        val mode = if (choice == "max") {
            sameSize.maxByOrNull { it.refreshRate }
        } else {
            val want = choice.toFloatOrNull() ?: return
            sameSize.minByOrNull { kotlin.math.abs(it.refreshRate - want) }
        } ?: return
        val params = activity.window.attributes
        params.preferredDisplayModeId = mode.modeId
        params.preferredRefreshRate = mode.refreshRate
        activity.window.attributes = params
        Log.i(TAG, "display mode ${mode.modeId}: ${mode.physicalWidth}x${mode.physicalHeight} @ ${mode.refreshRate} Hz ($choice)")
    }

    @Suppress("DEPRECATION")
    private fun displayOf(activity: Activity): Display? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) activity.display else activity.windowManager.defaultDisplay
}
