package com.itshenry.canteenclient.utils

class NetworkHelper {
    companion object {
        // 判断是否为网络错误
        fun isNetworkError(errorMessage: String): Boolean {
            val networkErrorKeywords = listOf(
                "网络", "连接", "timeout", "connect", "network", "failed", "失败",
                "Unable to resolve host", "ConnectException", "SocketTimeoutException"
            )
            return networkErrorKeywords.any { keyword ->
                errorMessage.contains(keyword, ignoreCase = true)
            }
        }
    }
}