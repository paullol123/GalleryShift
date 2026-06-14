package com.gallerysift.app

import android.content.Context
import android.content.SharedPreferences

object PreferenceHelper {
    private const val PREFS_NAME = "gallery_prefs"

    fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun markAsSeen(context: Context, id: String) {
        val prefs = getPrefs(context)
        val seenIds = prefs.getStringSet("seen_media_ids", emptySet())?.toMutableSet() ?: mutableSetOf()
        seenIds.add(id)
        prefs.edit().putStringSet("seen_media_ids", seenIds).apply()
    }
}