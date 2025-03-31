package com.itshenry.canteenclient.viewmodels

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itshenry.canteenclient.api.RetrofitClient
import com.itshenry.canteenclient.api.models.LoginRequest
import com.itshenry.canteenclient.api.models.LoginResponse
import kotlinx.coroutines.launch

class LoginViewModel : ViewModel() {

    private val _loginResult = MutableLiveData<LoginResult>()
    val loginResult: LiveData<LoginResult> = _loginResult

    fun login(username: String, password: String) {
        viewModelScope.launch {
            try {
                val response = RetrofitClient.apiService.login(
                    LoginRequest(username, password)
                )

                if (response.code == 200 && response.data != null) {
                    // 检查用户角色是否为canteen_a或canteen_b或canteen_test
                    if (response.data.user.role == "canteen_a" || response.data.user.role == "canteen_b" || response.data.user.role == "canteen_test") {
                        _loginResult.value = LoginResult.Success(response)
                    } else {
                        _loginResult.value = LoginResult.Error("账号没有食堂权限")
                    }
                } else {
                    _loginResult.value = LoginResult.Error(response.message)
                }
            } catch (e: Exception) {
                _loginResult.value = LoginResult.Error("登录失败: ${e.message}")
            }
        }
    }

    sealed class LoginResult {
        data class Success(val response: LoginResponse) : LoginResult()
        data class Error(val message: String) : LoginResult()
    }
}