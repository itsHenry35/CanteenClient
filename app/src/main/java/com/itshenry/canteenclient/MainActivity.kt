package com.itshenry.canteenclient

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.itshenry.canteenclient.databinding.ActivityMainBinding
import com.itshenry.canteenclient.utils.PreferenceManager
import com.itshenry.canteenclient.viewmodels.LoginViewModel

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val loginViewModel: LoginViewModel by viewModels()
    private lateinit var preferenceManager: PreferenceManager
    // 标记是否是自动登录
    private var isAutoLogin = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        preferenceManager = PreferenceManager(this)

        // 尝试刷新token
        val username = preferenceManager.getUsername()
        val password = preferenceManager.getPassword()

        if (username != null && password != null) {
            // 标记为自动登录
            isAutoLogin = true
            // 尝试用保存的用户名密码刷新token
            showLoading(true)
            loginViewModel.login(username, password)
        }

        setupListeners()
        observeViewModel()
    }

    private fun setupListeners() {
        binding.buttonLogin.setOnClickListener {
            val username = binding.editTextUsername.text.toString().trim()
            val password = binding.editTextPassword.text.toString().trim()

            if (username.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "请输入用户名和密码", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // 标记为手动登录
            isAutoLogin = false
            showLoading(true)
            loginViewModel.login(username, password)
        }
    }

    private fun observeViewModel() {
        loginViewModel.loginResult.observe(this) { result ->
            showLoading(false)

            when (result) {
                is LoginViewModel.LoginResult.Success -> {
                    val data = result.response.data!!

                    // 保存用户信息
                    preferenceManager.saveToken(data.token)
                    preferenceManager.saveUsername(data.user.username)
                    preferenceManager.saveFullName(data.user.full_name)
                    preferenceManager.saveRole(data.user.role)

                    // 如果是手动登录，才保存密码
                    if (!isAutoLogin) {
                        val password = binding.editTextPassword.text.toString().trim()
                        if (password.isNotEmpty()) {
                            preferenceManager.savePassword(password)
                        }
                    }

                    // 导航到扫码界面
                    navigateToScanActivity()
                }
                is LoginViewModel.LoginResult.Error -> {
                    Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun navigateToScanActivity() {
        val intent = Intent(this, ScanActivity::class.java)
        startActivity(intent)
        finish()
    }

    private fun showLoading(isLoading: Boolean) {
        binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
        binding.buttonLogin.isEnabled = !isLoading
        binding.editTextUsername.isEnabled = !isLoading
        binding.editTextPassword.isEnabled = !isLoading
    }
}