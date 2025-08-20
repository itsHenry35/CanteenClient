package com.itshenry.canteenclient.utils

import android.content.Context
import android.content.SharedPreferences

class PreferenceManager(context: Context) {
    private val preferences: SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private val editor: SharedPreferences.Editor = preferences.edit()

    companion object {
        private const val PREF_NAME = "CanteenClientPrefs"
        private const val KEY_TOKEN = "token"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_FULL_NAME = "full_name"
        private const val KEY_ROLE = "role"
        private const val KEY_API_ENDPOINT = "api_endpoint" // API端点键
        private const val KEY_SCAN_MODE = "scan_mode" // 扫码模式键，true为NFC，false为二维码
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

    // API端点存取
    fun saveApiEndpoint(endpoint: String) {
        editor.putString(KEY_API_ENDPOINT, endpoint)
        editor.apply()
    }

    fun getApiEndpoint(): String? {
        return preferences.getString(KEY_API_ENDPOINT, null)
    }

    // 扫码模式存取
    fun saveScanMode(isNfcMode: Boolean) {
        editor.putBoolean(KEY_SCAN_MODE, isNfcMode)
        editor.apply()
    }

    fun isNfcMode(): Boolean {
        return preferences.getBoolean(KEY_SCAN_MODE, false) // 默认为二维码模式
    }

    fun getWindowType(): String {
        val role = preferences.getString(KEY_ROLE, "")
        return when (role) {
            "canteen_b" -> "B"
            "canteen_test" -> "查询"
            else -> "A"
        }
    }

    fun clearAll() {
        // 获取API端点（保留）
        val apiEndpoint = getApiEndpoint()

        // 清除所有数据
        editor.clear()
        editor.apply()

        // 如果API端点不为空，则重新保存
        if (!apiEndpoint.isNullOrEmpty()) {
            saveApiEndpoint(apiEndpoint)
        }
    }
}