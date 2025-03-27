package com.itshenry.canteenclient.viewmodels

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itshenry.canteenclient.api.RetrofitClient
import com.itshenry.canteenclient.api.models.ScanRequest
import com.itshenry.canteenclient.api.models.ScanResponse
import kotlinx.coroutines.launch

class ScanViewModel : ViewModel() {

    private val _scanResult = MutableLiveData<ScanResult>()
    val scanResult: LiveData<ScanResult> = _scanResult

    fun scanQrCode(token: String, qrData: String, windowType: String) {
        viewModelScope.launch {
            try {
                val response = RetrofitClient.apiService.scanQrCode(
                    "Bearer $token",
                    ScanRequest(qrData)
                )

                if (response.code == 200 && response.data != null) {
                    val scanData = response.data

                    val resultStatus = when {
                        scanData.has_collected -> ScanStatus.ALREADY_COLLECTED
                        !scanData.has_selected -> ScanStatus.NOT_SELECTED
                        // 检查服务器返回的meal_type与当前窗口类型是否匹配
                        scanData.meal_type != windowType -> ScanStatus.WRONG_WINDOW
                        else -> ScanStatus.SUCCESS
                    }

                    _scanResult.value = ScanResult.Success(response, resultStatus)
                } else {
                    _scanResult.value = ScanResult.Error(response.message)
                }
            } catch (e: Exception) {
                _scanResult.value = ScanResult.Error("扫描失败: ${e.message}")
            }
        }
    }

    sealed class ScanResult {
        data class Success(val response: ScanResponse, val status: ScanStatus) : ScanResult()
        data class Error(val message: String) : ScanResult()
    }

    enum class ScanStatus {
        SUCCESS,             // 可以领取餐食
        ALREADY_COLLECTED,   // 已领过餐
        WRONG_WINDOW,        // 窗口错误（A/B餐不匹配）
        NOT_SELECTED         // 未选餐
    }
}