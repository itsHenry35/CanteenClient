package com.itshenry.canteenclient.utils

import android.content.Context
import android.content.SharedPreferences

class PreferenceManager(context: Context) {
    private val preferences: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private val editor: SharedPreferences.Editor = preferences.edit()

    companion object {
        private const val PREF_NAME = "CanteenClientPrefs"
        private const val KEY_TOKEN = "token"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_FULL_NAME = "full_name"
        private const val KEY_ROLE = "role"
    }

    fun saveToken(token: String) {
        editor.putString(KEY_TOKEN, token)
        editor.apply()
    }

    fun getToken(): String? {
        return preferences.getString(KEY_TOKEN, null)
    }

    fun saveUsername(username: String) {
        editor.putString(KEY_USERNAME, username)
        editor.apply()
    }

    fun getUsername(): String? {
        return preferences.getString(KEY_USERNAME, null)
    }

    fun savePassword(password: String) {
        editor.putString(KEY_PASSWORD, password)
        editor.apply()
    }

    fun getPassword(): String? {
        return preferences.getString(KEY_PASSWORD, null)
    }

    fun saveFullName(fullName: String) {
        editor.putString(KEY_FULL_NAME, fullName)
        editor.apply()
    }

    fun getFullName(): String? {
        return preferences.getString(KEY_FULL_NAME, null)
    }

    fun saveRole(role: String) {
        editor.putString(KEY_ROLE, role)
        editor.apply()
    }

    fun getRole(): String? {
        return preferences.getString(KEY_ROLE, null)
    }

    fun getWindowType(): String {
        val role = preferences.getString(KEY_ROLE, "")
        return when (role) {
            "canteen_b" -> "B"
            else -> "A"
        }
    }

    fun clearAll() {
        editor.clear()
        editor.apply()
    }
}