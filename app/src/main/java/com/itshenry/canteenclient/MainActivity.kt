package com.itshenry.canteenclient

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.itshenry.canteenclient.api.RetrofitClient
import com.itshenry.canteenclient.databinding.ActivityMainBinding
import com.itshenry.canteenclient.utils.NetworkHelper
import com.itshenry.canteenclient.utils.PreferenceManager
import com.itshenry.canteenclient.viewmodels.LoginViewModel

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val loginViewModel: LoginViewModel by viewModels()
    private lateinit var preferenceManager: PreferenceManager

    // 标记是否是自动登录
    private var isAutoLogin = false
    private var nfcAdapter: NfcAdapter? = null
    private var isOfflineMode = false // 添加离线模式标记

    // 摄像头权限请求
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (!isGranted) {
            // 权限被拒绝，显示提示
            Toast.makeText(this, getString(R.string.camera_permission_required), Toast.LENGTH_LONG)
                .show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        preferenceManager = PreferenceManager(this)

        // 初始化NFC
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        checkNfcSupport()

        // 请求摄像头权限
        requestCameraPermission()

        // 加载保存的API端点和扫码模式
        val savedApiEndpoint = preferenceManager.getApiEndpoint()
        if (!savedApiEndpoint.isNullOrEmpty()) {
            binding.editTextApiEndpoint.setText(savedApiEndpoint)
            RetrofitClient.setBaseUrl(savedApiEndpoint)

            // 加载保存的扫码模式
            if (preferenceManager.isNfcMode()) {
                binding.radioNfc.isChecked = true
            } else {
                binding.radioQrCode.isChecked = true
            }

            // 尝试刷新token
            val username = preferenceManager.getUsername()
            val password = preferenceManager.getPassword()

            if (username != null && password != null) {
                // 标记为自动登录
                isAutoLogin = true
                // 尝试用保存的用户名密码刷新token
                showLoading(true)
                try {
                    loginViewModel.login(username, password)
                } catch (e: Exception) {
                    showLoading(false)
                    Toast.makeText(
                        this,
                        getString(R.string.login_error) + ": ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        setupListeners()
        observeViewModel()
    }

    private fun checkNfcSupport() {
        if (nfcAdapter == null) {
            // 设备不支持NFC，隐藏NFC选项
            binding.radioNfc.visibility = View.GONE
        } else {
            // 设备支持NFC，显示选项
            binding.radioNfc.visibility = View.VISIBLE
        }
    }

    private fun setupListeners() {
        binding.buttonLogin.setOnClickListener {
            val username = binding.editTextUsername.text.toString().trim()
            val password = binding.editTextPassword.text.toString().trim()
            val apiEndpoint = binding.editTextApiEndpoint.text.toString().trim()
            val isNfcMode = binding.radioNfc.isChecked

            if (apiEndpoint.isEmpty()) {
                Toast.makeText(
                    this,
                    getString(R.string.error_empty_api_endpoint),
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }

            if (username.isEmpty() || password.isEmpty()) {
                Toast.makeText(
                    this,
                    getString(R.string.error_empty_credentials),
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }

            // 如果选择NFC模式，检查NFC状态
            if (isNfcMode) {
                if (nfcAdapter == null) {
                    Toast.makeText(this, getString(R.string.nfc_not_supported), Toast.LENGTH_SHORT)
                        .show()
                    return@setOnClickListener
                }
                if (!nfcAdapter!!.isEnabled) {
                    Toast.makeText(this, getString(R.string.nfc_disabled), Toast.LENGTH_SHORT)
                        .show()
                    return@setOnClickListener
                }
            } else {
                // 二维码模式需要检查摄像头权限
                if (!hasCameraPermission()) {
                    requestPermissionLauncher.launch(Manifest.permission.CAMERA)
                    return@setOnClickListener
                }
            }

            // 保存并设置API端点
            preferenceManager.saveApiEndpoint(apiEndpoint)

            try {
                RetrofitClient.setBaseUrl(apiEndpoint)

                // 标记为手动登录
                isAutoLogin = false
                showLoading(true)
                loginViewModel.login(username, password)
            } catch (e: Exception) {
                Toast.makeText(
                    this,
                    getString(R.string.login_error) + ": ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // 检查是否有摄像头权限
    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    // 请求摄像头权限
    private fun requestCameraPermission() {
        when {
            hasCameraPermission() -> {
                // 已经有权限，无需处理
            }

            ActivityCompat.shouldShowRequestPermissionRationale(
                this,
                Manifest.permission.CAMERA
            ) -> {
                // 显示权限说明，然后请求权限
                Toast.makeText(
                    this,
                    getString(R.string.camera_permission_required),
                    Toast.LENGTH_LONG
                ).show()
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            }

            else -> {
                // 直接请求权限
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
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

                    // 如果是手动登录，才保存密码和扫码模式
                    if (!isAutoLogin) {
                        val password = binding.editTextPassword.text.toString().trim()
                        if (password.isNotEmpty()) {
                            preferenceManager.savePassword(password)
                        }
                        // 保存扫码模式
                        preferenceManager.saveScanMode(binding.radioNfc.isChecked)
                    }

                    // 重置离线模式
                    isOfflineMode = false

                    // 导航到扫码界面
                    navigateToScanActivity()
                }

                is LoginViewModel.LoginResult.Error -> {
                    // 如果是自动登录且选择了NFC模式，检查是否为网络错误
                    if (isAutoLogin && preferenceManager.isNfcMode()) {
                        // 检查是否为网络错误（非账号密码错误）且是否不是测试角色
                        if (NetworkHelper.isNetworkError(result.message) && !preferenceManager.getRole()
                                .equals("canteen_test", ignoreCase = true)
                        ) {
                            // 设置离线模式
                            isOfflineMode = true
                            Toast.makeText(this, "网络连接失败，切换到离线模式", Toast.LENGTH_LONG)
                                .show()

                            // 强行继续到扫码界面
                            navigateToScanActivity()
                            return@observe
                        }
                    }

                    Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun navigateToScanActivity() {
        val intent = Intent(this, ValidateActivity::class.java)
        intent.putExtra("isOfflineMode", isOfflineMode)
        startActivity(intent)
        finish()
    }

    private fun showLoading(isLoading: Boolean) {
        binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
        binding.buttonLogin.isEnabled = !isLoading
        binding.editTextUsername.isEnabled = !isLoading
        binding.editTextPassword.isEnabled = !isLoading
        binding.editTextApiEndpoint.isEnabled = !isLoading
    }
}