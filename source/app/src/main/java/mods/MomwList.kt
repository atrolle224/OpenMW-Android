/*
    This file is part of OpenMW-Android.

    OpenMW-Android is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/

package mods

import android.content.Context
import android.util.Log
import android.preference.PreferenceManager
import constants.Constants
import java.io.File

/**
 * A modding-openmw.com list installed with umo and momw-configurator on a PC and copied to
 * the game folder as momw/ (see RG477M-Morrowind.ps1 in GammaOSNext):
 *
 *  - momw/openmw.cfg: its data=, content=, groundcover= and fallback-archive= lines, with
 *    Android paths, are used instead of the mod manager's, in that order.
 *  - momw/settings.cfg: copied into the user settings.cfg once per new file, except the
 *    window and display keys this launcher manages itself.
 *  - momw/navmesh.db: copied to the user data folder once per new file.
 *  - momw/list.txt: the list's name, shown in Settings.
 */
object MomwList {
    private const val TAG = "MomwList"
    const val PREF_ENABLED = "pref_momw_list"
    private const val PREF_SETTINGS_STAMP = "momw_settings_stamp"
    private const val PREF_NAVMESH_STAMP = "momw_navmesh_stamp"

    private val cfgKeys = setOf("data", "content", "groundcover", "fallback-archive")

    // the launcher sets these from its own preferences
    private val skippedSettings = mapOf(
        "Video" to setOf("resolution x", "resolution y", "window mode", "fullscreen", "window border",
            "vsync mode", "framerate limit", "screen", "minimize on focus loss"),
        "Window" to null,
        "Input" to null,
    )

    fun folder(ctx: Context): File = File(ModsPaths.gameRoot(ctx), "momw")

    /** The list's openmw.cfg when there is one and it is switched on, else null. */
    fun cfg(ctx: Context): File? {
        val file = File(folder(ctx), "openmw.cfg")
        if (!file.isFile)
            return null
        val on = PreferenceManager.getDefaultSharedPreferences(ctx).getBoolean(PREF_ENABLED, true)
        return if (on) file else null
    }

    /** The list's load order lines, as in its openmw.cfg. */
    fun lines(file: File): List<String> =
        file.readLines().map { it.trim() }.filter { cfgKeys.contains(it.substringBefore('=', "").trim()) }

    /** "Expanded Vanilla: 737 folders, 412 plugins", or null without a list. */
    fun describe(ctx: Context): String? {
        val file = File(folder(ctx), "openmw.cfg")
        if (!file.isFile)
            return null
        val all = lines(file)
        val name = File(folder(ctx), "list.txt").takeIf { it.isFile }?.readText()?.trim()?.ifEmpty { null }
            ?: "Mod list"
        val folders = all.count { it.startsWith("data=") }
        val plugins = all.count { it.startsWith("content=") }
        return "$name: $folders folders, $plugins plugins"
    }

    /** Puts the list's settings.cfg and navmesh.db in place when they are new. */
    fun installUserFiles(ctx: Context) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val dir = folder(ctx)

        val settings = File(dir, "settings.cfg")
        if (settings.isFile && prefs.getString(PREF_SETTINGS_STAMP, "") != stamp(settings)) {
            val target = File(Constants.USER_CONFIG, "settings.cfg")
            target.parentFile?.mkdirs()
            target.writeText(merge(if (target.isFile) target.readLines() else emptyList(), settings.readLines()))
            prefs.edit().putString(PREF_SETTINGS_STAMP, stamp(settings)).apply()
            Log.i(TAG, "settings from $settings")
        }

        val navmesh = File(dir, "navmesh.db")
        if (navmesh.isFile && prefs.getString(PREF_NAVMESH_STAMP, "") != stamp(navmesh)) {
            val target = File(Constants.USER_FILE_STORAGE, "navmesh.db")
            val tmp = File(Constants.USER_FILE_STORAGE, "navmesh.db.tmp")
            navmesh.copyTo(tmp, overwrite = true)
            if (tmp.renameTo(target) || (target.delete() && tmp.renameTo(target))) {
                prefs.edit().putString(PREF_NAVMESH_STAMP, stamp(navmesh)).apply()
                Log.i(TAG, "navmesh from $navmesh")
            } else {
                tmp.delete()
                Log.w(TAG, "could not install $navmesh")
            }
        }
    }

    private fun stamp(file: File) = "${file.length()}:${file.lastModified()}"

    /** [base] with every key of [list] set, section by section, except [skippedSettings]. */
    private fun merge(base: List<String>, list: List<String>): String {
        val out = base.toMutableList()
        var section = ""
        for (raw in list) {
            val line = raw.trim()
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length - 1).trim()
                continue
            }
            if (line.isEmpty() || line.startsWith("#") || !line.contains('='))
                continue
            val key = line.substringBefore('=').trim()
            val skip = skippedSettings.entries.firstOrNull { it.key.equals(section, ignoreCase = true) }
            if (skip != null && (skip.value == null || skip.value!!.contains(key.lowercase())))
                continue
            set(out, section, key, line.substringAfter('=').trim())
        }
        return out.joinToString("\n", postfix = "\n")
    }

    private fun set(lines: MutableList<String>, section: String, key: String, value: String) {
        var start = lines.indexOfFirst { it.trim().equals("[$section]", ignoreCase = true) }
        if (start < 0) {
            if (lines.isNotEmpty() && lines.last().isNotBlank())
                lines.add("")
            lines.add("[$section]")
            start = lines.lastIndex
        }
        var end = lines.size
        for (i in start + 1 until lines.size) {
            val t = lines[i].trim()
            if (t.startsWith("[") && t.endsWith("]")) {
                end = i
                break
            }
        }
        for (i in start + 1 until end) {
            if (lines[i].substringBefore('=', "").trim().equals(key, ignoreCase = true)) {
                lines[i] = "$key = $value"
                return
            }
        }
        lines.add(end, "$key = $value")
    }
}
