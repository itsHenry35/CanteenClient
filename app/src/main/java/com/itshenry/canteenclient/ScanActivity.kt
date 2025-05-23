package com.itshenry.canteenclient

import android.Manifest
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.itshenry.canteenclient.databinding.ActivityScanBinding
import com.itshenry.canteenclient.utils.PreferenceManager
import com.itshenry.canteenclient.viewmodels.LoginViewModel
import com.itshenry.canteenclient.viewmodels.ScanViewModel
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ScanActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScanBinding
    private val scanViewModel: ScanViewModel by viewModels()
    private val loginViewModel: LoginViewModel by viewModels()
    private lateinit var preferenceManager: PreferenceManager
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var barcodeScanner: BarcodeScanner
    private lateinit var vibrator: Vibrator

    // 用于防抖动的变量
    private var lastScannedQrData: String = ""
    private var lastScanTime: Long = 0
    private val DEBOUNCE_TIME = 5000 // 5秒内不重复扫描同一个码

    // 摄像头权限请求
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            // 权限授予，启动相机
            startCamera()
        } else {
            // 权限被拒绝，显示在名字区域
            binding.textViewStudentName.text = "无摄像头权限"
            // 隐藏相机预览
            binding.cardViewCamera.visibility = View.GONE
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScanBinding.inflate(layoutInflater)
        setContentView(binding.root)

        preferenceManager = PreferenceManager(this)

        // 初始化振动器
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        // 初始化ML Kit条码扫描器
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        barcodeScanner = BarcodeScanning.getClient(options)

        // 初始化相机执行器
        cameraExecutor = Executors.newSingleThreadExecutor()

        // 检查登录状态
        val username = preferenceManager.getUsername()
        val password = preferenceManager.getPassword()

        if (username == null || password == null) {
            navigateToLoginActivity()
            return
        }

        // 尝试刷新token
        refreshToken(username, password)

        setupUI()

        // 检查摄像头权限
        checkCameraPermission()

        setupListeners()
        observeViewModel()
    }

    // 检查摄像头权限
    private fun checkCameraPermission() {
        when {
            hasCameraPermission() -> {
                // 有权限，启动相机
                startCamera()
            }
            ActivityCompat.shouldShowRequestPermissionRationale(
                this,
                Manifest.permission.CAMERA
            ) -> {
                // 显示权限说明，然后请求权限
                Toast.makeText(this, getString(R.string.camera_permission_required), Toast.LENGTH_LONG).show()
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
            else -> {
                // 直接请求权限
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
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

    private fun refreshToken(username: String, password: String) {
        lifecycleScope.launch {
            try {
                loginViewModel.login(username, password)
            } catch (e: Exception) {
                Toast.makeText(this@ScanActivity, getString(R.string.login_error), Toast.LENGTH_LONG).show()
                preferenceManager.clearAll()
                navigateToLoginActivity()
            }
        }
    }

    private fun setupUI() {
        // 显示操作员信息
        val fullName = preferenceManager.getFullName() ?: getString(R.string.unknown_user)
        binding.textViewUserInfo.text = getString(R.string.operator_label, fullName)

        // 显示窗口类型
        val windowType = preferenceManager.getWindowType()
        binding.textViewWindowType.text = getString(R.string.window_type_display, windowType)
    }

    private fun startCamera() {
        // 确认有摄像头权限
        if (!hasCameraPermission()) {
            binding.textViewStudentName.text = getString(R.string.no_camera_permission)
            binding.cardViewCamera.visibility = View.GONE
            return
        }

        binding.cardViewCamera.visibility = View.VISIBLE

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            // 用于绑定相机生命周期的ProcessCameraProvider
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // 预览用例
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
                }

            // 图像分析用例
            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, BarcodeAnalyzer())
                }

            // 选择后置摄像头
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                // 解绑所有用例
                cameraProvider.unbindAll()

                // 绑定用例到相机
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalysis)

            } catch(exc: Exception) {
                Toast.makeText(this, getString(R.string.camera_start_failed), Toast.LENGTH_SHORT).show()
                binding.textViewStudentName.text = getString(R.string.camera_start_failed)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private inner class BarcodeAnalyzer : ImageAnalysis.Analyzer {
        @SuppressLint("UnsafeOptInUsageError")
        override fun analyze(imageProxy: ImageProxy) {
            val mediaImage = imageProxy.image
            if (mediaImage != null) {
                val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

                barcodeScanner.process(image)
                    .addOnSuccessListener { barcodes ->
                        // 处理扫描到的条码
                        if (barcodes.isNotEmpty()) {
                            val qrData = barcodes[0].displayValue ?: ""
                            if (qrData.isNotEmpty()) {
                                handleQrCode(qrData)
                            }
                        }
                    }
                    .addOnFailureListener {
                        // 处理错误
                    }
                    .addOnCompleteListener {
                        // 关闭imageProxy，让相机继续处理下一帧
                        imageProxy.close()
                    }
            } else {
                imageProxy.close()
            }
        }
    }

    private fun handleQrCode(qrData: String) {
        val currentTime = System.currentTimeMillis()

        // 防抖动：如果是同一个码且在设定的时间内，则忽略
        if (qrData == lastScannedQrData && currentTime - lastScanTime < DEBOUNCE_TIME) {
            return
        }

        // 更新最后扫描记录
        lastScannedQrData = qrData
        lastScanTime = currentTime

        // 显示"正在扫描"状态
        runOnUiThread {
            binding.textViewScanResult.setTextColor(ContextCompat.getColor(this, R.color.primary_text))
            binding.textViewScanResult.text = getString(R.string.scan_in_progress)
        }

        // 处理扫码数据，传入当前窗口类型
        lifecycleScope.launch {
            scanViewModel.scanQrCode(
                preferenceManager.getToken() ?: "",
                qrData,
                preferenceManager.getWindowType() // 传入窗口类型
            )
        }
    }

    private fun setupListeners() {
        binding.buttonLogout.setOnClickListener {
            showLogoutConfirmationDialog()
        }
    }

    private fun showLogoutConfirmationDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.logout_confirm_title))
            .setMessage(getString(R.string.logout_confirm_message))
            .setPositiveButton(getString(R.string.btn_confirm)) { _, _ ->
                // 清除用户数据并返回登录界面
                preferenceManager.clearAll()
                navigateToLoginActivity()
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun observeViewModel() {
        // 监听登录结果
        loginViewModel.loginResult.observe(this) { result ->
            when (result) {
                is LoginViewModel.LoginResult.Success -> {
                    // 仅更新token
                    preferenceManager.saveToken(result.response.data!!.token)
                }
                is LoginViewModel.LoginResult.Error -> {
                    // 登录失败，返回登录页面
                    Toast.makeText(this, getString(R.string.login_expired), Toast.LENGTH_LONG).show()
                    preferenceManager.clearAll()
                    navigateToLoginActivity()
                }
            }
        }

        // 监听扫描结果
        scanViewModel.scanResult.observe(this) { result ->
            when (result) {
                is ScanViewModel.ScanResult.Success -> {
                    val scanData = result.response.data!!
                    val studentName = scanData.student_name

                    // 显示学生姓名
                    binding.textViewStudentName.text = studentName

                    val role = preferenceManager.getRole() ?: ""
                    // 对于canteen_test角色，只显示餐食类型并提前返回
                    if (role == "canteen_test") {
                        if (scanData.has_selected) {
                            displayResult(
                                getString(R.string.meal_type_display, scanData.meal_type),
                                R.color.success_green
                            )
                            showAnimation()
                            vibrateSuccess()
                        } else {
                            displayResult(
                                getString(R.string.meal_type_display, getString(R.string.scan_error_not_selected)),
                                R.color.error_red
                            )
                            showAnimation()
                            vibrateError()
                        }
                        return@observe  // 提前返回，不执行后续逻辑
                    }

                    // 根据扫描状态显示结果和触发振动
                    when (result.status) {
                        ScanViewModel.ScanStatus.SUCCESS -> {
                            // 显示成功信息（绿色）并添加视觉效果
                            displayResult(
                                getString(R.string.scan_success),
                                R.color.success_green
                            )

                            // 添加成功视觉效果和振动反馈
                            showAnimation()
                            vibrateSuccess()
                        }
                        ScanViewModel.ScanStatus.ALREADY_COLLECTED -> {
                            // 显示已领过餐错误（红色）
                            displayResult(
                                getString(R.string.scan_error_collected),
                                R.color.error_red
                            )

                            // 添加错误视觉效果和振动反馈
                            showAnimation()
                            vibrateError()
                        }
                        ScanViewModel.ScanStatus.WRONG_WINDOW -> {
                            // 显示窗口错误（红色）
                            displayResult(
                                getString(R.string.scan_error_wrong_window),
                                R.color.error_red
                            )

                            // 添加错误视觉效果和振动反馈
                            showAnimation()
                            vibrateError()
                        }
                        ScanViewModel.ScanStatus.NOT_SELECTED -> {
                            // 显示未选餐错误（红色）
                            displayResult(
                                getString(R.string.scan_error_not_selected),
                                R.color.error_red
                            )

                            // 添加错误视觉效果和振动反馈
                            showAnimation()
                            vibrateError()
                        }
                    }
                }
                is ScanViewModel.ScanResult.Error -> {
                    // 显示错误信息
                    Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                    resetScanResult()

                    // 添加错误视觉效果和振动反馈
                    showAnimation()
                    vibrateError()
                }
            }
        }
    }

    private fun vibrateSuccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // 成功振动模式: 短-停-短
            vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 100), -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(longArrayOf(0, 100), -1)
        }
    }

    private fun vibrateError() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // 错误振动模式: 长-停-长
            vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 150, 100, 150), -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(longArrayOf(0, 150, 100, 150), -1)
        }
    }

    private fun showAnimation() {
        // 创建放大缩小动画
        val scaleX = ObjectAnimator.ofFloat(binding.textViewScanResult, "scaleX", 1f, 1.2f, 1f)
        val scaleY = ObjectAnimator.ofFloat(binding.textViewScanResult, "scaleY", 1f, 1.2f, 1f)

        // 组合动画
        val animatorSet = AnimatorSet()
        animatorSet.playTogether(scaleX, scaleY)
        animatorSet.duration = 500
        animatorSet.interpolator = AccelerateDecelerateInterpolator()
        animatorSet.start()
    }

    private fun displayResult(text: String, colorResId: Int) {
        binding.textViewScanResult.text = text
        binding.textViewScanResult.setTextColor(ContextCompat.getColor(this, colorResId))
    }

    private fun resetScanResult() {
        binding.textViewStudentName.text = getString(R.string.scan_prompt)
        binding.textViewScanResult.text = getString(R.string.scan_waiting)
        binding.textViewScanResult.setTextColor(ContextCompat.getColor(this, R.color.primary_text))

        // 重置防抖变量
        lastScannedQrData = ""
    }

    private fun navigateToLoginActivity() {
        val intent = Intent(this, MainActivity::class.java)
        startActivity(intent)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}