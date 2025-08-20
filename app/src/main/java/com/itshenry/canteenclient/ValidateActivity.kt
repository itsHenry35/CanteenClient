package com.itshenry.canteenclient

import android.Manifest
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
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
import com.itshenry.canteenclient.utils.NetworkHelper
import com.itshenry.canteenclient.utils.NfcHelper
import com.itshenry.canteenclient.utils.PreferenceManager
import com.itshenry.canteenclient.viewmodels.LoginViewModel
import com.itshenry.canteenclient.viewmodels.ScanViewModel
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ValidateActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScanBinding
    private val scanViewModel: ScanViewModel by viewModels()
    private val loginViewModel: LoginViewModel by viewModels()
    private lateinit var preferenceManager: PreferenceManager
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var barcodeScanner: BarcodeScanner
    private lateinit var vibrator: Vibrator

    // NFC相关
    private var nfcAdapter: NfcAdapter? = null
    private var pendingIntent: PendingIntent? = null
    private var intentFilters: Array<IntentFilter>? = null
    private var techLists: Array<Array<String>>? = null
    private var isNfcMode: Boolean = false
    private var isProcessingNfc: Boolean = false
    private var currentNfcTag: Tag? = null
    private var isOfflineMode: Boolean = false // 添加离线模式标记
    private var currNfcCardData: NfcHelper.Companion.NfcCardData? = null

    // 用于防抖动的变量
    private var lastScannedQrData: String = ""
    private var lastScanTime: Long = 0
    private val DEBOUNCE_TIME = 5000 // 5秒内不重复扫描同一个码

    // 错误/成功提示部分
    private fun showError(message: String) {
        displayResult(
            message,
            R.color.error_red
        )
        showAnimation()
        vibrateError()
    }
    private fun showSuccess(message: String) {
        displayResult(
            message,
            R.color.success_green
        )
        showAnimation()
        vibrateSuccess()
    }

    private fun showWarning(message: String) {
        displayResult(
            message,
            R.color.warning_amber
        )
    }

    private fun showInfo(message: String) {
        displayResult(
            message,
            R.color.secondary_text
        )
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

    // 摄像头权限请求
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            // 权限授予，启动相机
            if (!isNfcMode) {
                startCamera()
            }
        } else {
            // 权限被拒绝，显示在名字区域
            showWarning(getString(R.string.no_camera_permission))
            // 隐藏相机预览
            binding.cardViewCamera.visibility = View.GONE
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScanBinding.inflate(layoutInflater)
        setContentView(binding.root)

        preferenceManager = PreferenceManager(this)

        // 获取扫码模式和离线模式状态
        isNfcMode = preferenceManager.isNfcMode()
        isOfflineMode = intent.getBooleanExtra("isOfflineMode", false)

        // 初始化振动器
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        // 初始化NFC
        if (isNfcMode) {
            initializeNfc()
        } else {
            // 初始化ML Kit条码扫描器
            val options = BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
            barcodeScanner = BarcodeScanning.getClient(options)

            // 初始化相机执行器
            cameraExecutor = Executors.newSingleThreadExecutor()
        }

        // 检查登录状态
        val username = preferenceManager.getUsername()
        val password = preferenceManager.getPassword()

        if (username == null || password == null) {
            navigateToLoginActivity()
            return
        }
        // 尝试刷新token
        if (!isOfflineMode) refreshToken(username, password)

        setupUI()

        if (!isNfcMode) {
            // 检查摄像头权限
            checkCameraPermission()
        }

        setupListeners()
        observeViewModel()
    }

    private fun initializeNfc() {
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        if (nfcAdapter == null) {
            Toast.makeText(this, getString(R.string.nfc_not_supported), Toast.LENGTH_LONG).show()
            return
        }

        // 创建PendingIntent
        val intent = Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // 设置Intent过滤器
        val ndefFilter = IntentFilter(NfcAdapter.ACTION_NDEF_DISCOVERED)
        val techFilter = IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED)
        val tagFilter = IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED)

        intentFilters = arrayOf(ndefFilter, techFilter, tagFilter)
        techLists = arrayOf(arrayOf(Ndef::class.java.name))
    }

    private fun setupUI() {
        // 显示操作员信息
        val fullName = preferenceManager.getFullName() ?: getString(R.string.unknown_user)
        binding.textViewUserInfo.text = getString(R.string.operator_label, fullName)

        // 显示窗口类型，如果是离线模式则添加红色标识
        val windowType = preferenceManager.getWindowType()
        if (isOfflineMode) {
            val windowTypeText = getString(R.string.window_type_display, windowType) + " " + getString(R.string.offline_mode)
            binding.textViewWindowType.text = windowTypeText
            binding.textViewWindowType.setTextColor(ContextCompat.getColor(this, R.color.error_red))
        } else {
            binding.textViewWindowType.text = getString(R.string.window_type_display, windowType)
        }

        // 根据扫码模式设置UI
        if (isNfcMode) {
            binding.cardViewCamera.visibility = View.GONE
        }
        resetScanResult()
    }

    override fun onResume() {
        super.onResume()
        if (isNfcMode && nfcAdapter != null) {
            val options = Bundle()
            // 禁用NFC读取时的系统声音
            options.putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250)

            nfcAdapter?.enableForegroundDispatch(this, pendingIntent, intentFilters, techLists)

            // 禁用系统的NFC声音和振动
            try {
                nfcAdapter?.enableReaderMode(
                    this,
                    { tag ->
                        // 处理NFC标签
                        val intent = Intent().apply {
                            putExtra(NfcAdapter.EXTRA_TAG, tag)
                        }
                        handleNfcIntent(intent)
                    },
                    NfcAdapter.FLAG_READER_NFC_A or
                            NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS, // 禁用系统声音
                    options
                )
            } catch (e: Exception) {
                // 如果enableReaderMode失败，回退到原来的方法
                nfcAdapter?.enableForegroundDispatch(this, pendingIntent, intentFilters, techLists)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (isNfcMode && nfcAdapter != null) {
            try {
                nfcAdapter?.disableReaderMode(this)
            } catch (e: Exception) {
                nfcAdapter?.disableForegroundDispatch(this)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (isNfcMode && !isProcessingNfc) {
            handleNfcIntent(intent)
        }
    }

    private fun handleNfcIntent(intent: Intent) {
        val tag: Tag? = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        if (tag != null) {
            currentNfcTag = tag
            isProcessingNfc = true

            // 显示"请不要移开卡片"
            runOnUiThread {
                showWarning(getString(R.string.nfc_keep_card))
            }

            // 读取NFC数据
            val cardData = NfcHelper.readFromTag(tag)
            if (cardData != null) {
                handleNfcData(cardData)
            } else {
                runOnUiThread {
                    showError(getString(R.string.nfc_read_error))
                    isProcessingNfc = false
                }
            }
        }
    }

    private fun handleNfcData(cardData: NfcHelper.Companion.NfcCardData) {
        val currentTime = System.currentTimeMillis()
        currNfcCardData = cardData

        // 更新最后扫描记录
        lastScannedQrData = cardData.qrData
        lastScanTime = currentTime

        // 显示"正在处理"状态
        runOnUiThread {
            showInfo(getString(R.string.scan_in_progress))
        }

        // 如果是离线模式，直接进行离线验证
        if (isOfflineMode) {
            handleNfcOfflineMode(getString(R.string.offline_mode))
        } else {
            // 在线模式，尝试网络验证
            lifecycleScope.launch {
                scanViewModel.scanQrCode(
                    preferenceManager.getToken() ?: "",
                    cardData.qrData,
                    preferenceManager.getWindowType()
                )
            }
        }
    }

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
                Toast.makeText(this@ValidateActivity, getString(R.string.login_error), Toast.LENGTH_LONG).show()
                preferenceManager.clearAll()
                navigateToLoginActivity()
            }
        }
    }

    private fun startCamera() {
        // 确认有摄像头权限
        if (!hasCameraPermission()) {
            showWarning(getString(R.string.no_camera_permission))
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
                showWarning(getString(R.string.camera_error))
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
            showInfo(getString(R.string.scan_in_progress))
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
                    if (isOfflineMode) {
                        // 离线模式下不需要处理错误
                        return@observe
                    }
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
                            showSuccess(getString(R.string.meal_type_display, scanData.meal_type))
                        } else {
                            showError(getString(R.string.meal_type_display, getString(R.string.scan_error_not_selected)))
                        }
                        if (isNfcMode) {
                            isProcessingNfc = false
                        }
                        return@observe  // 提前返回，不执行后续逻辑
                    }

                    // 根据扫描状态显示结果和触发振动
                    when (result.status) {
                        ScanViewModel.ScanStatus.SUCCESS -> {
                            // 先检查是否离线模式领过餐
                            if (!NfcHelper.canCollectOffline(currNfcCardData?.lastCollectedDate)) {
                                showError(getString(R.string.scan_error_collected))
                                if (isNfcMode) {
                                    isProcessingNfc = false
                                }
                                return@observe
                            }
                            // 如果是NFC模式，更新卡片数据
                            if (isNfcMode && currentNfcTag != null) {
                                val success = updateNfcCard()
                                if (!success) {
                                    // 在线模式没写入问题不大
                                    runOnUiThread {
                                        Toast.makeText(this, getString(R.string.nfc_write_error), Toast.LENGTH_SHORT).show()
                                    }
                                }
                            } else if (isNfcMode) {
                                isProcessingNfc = false
                            }

                            // 显示成功信息（绿色）并添加视觉效果
                            showSuccess(getString(R.string.scan_success))
                        }
                        ScanViewModel.ScanStatus.ALREADY_COLLECTED -> {
                            // 显示已领过餐错误（红色）
                            showError(getString(R.string.scan_error_collected))
                            if (isNfcMode) {
                                isProcessingNfc = false
                            }
                        }
                        ScanViewModel.ScanStatus.WRONG_WINDOW -> {
                            // 显示窗口错误（红色）
                            showError(getString(R.string.scan_error_wrong_window))
                            if (isNfcMode) {
                                isProcessingNfc = false
                            }
                        }
                        ScanViewModel.ScanStatus.NOT_SELECTED -> {
                            // 显示未选餐错误（红色）
                            showError(getString(R.string.scan_error_not_selected))
                            if (isNfcMode) {
                                isProcessingNfc = false
                            }
                        }
                    }
                }
                is ScanViewModel.ScanResult.Error -> {
                    // 网络错误，如果是NFC模式，尝试离线验证
                    if (isNfcMode && currentNfcTag != null && NetworkHelper.isNetworkError(result.message)) {
                        handleNfcOfflineMode(result.message)
                    } else {
                        // 显示错误信
                        showError(result.message)
                        if (isNfcMode) {
                            isProcessingNfc = false
                        }
                    }
                }
            }
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
        if (isNfcMode) {
            binding.textViewStudentName.text = getString(R.string.nfc_prompt)
            binding.textViewScanResult.text = getString(R.string.nfc_waiting)
        } else {
            binding.textViewStudentName.text = getString(R.string.scan_prompt)
            binding.textViewScanResult.text = getString(R.string.scan_waiting)
        }
        binding.textViewScanResult.setTextColor(ContextCompat.getColor(this, R.color.secondary_text))

        // 重置防抖变量
        lastScannedQrData = ""
    }

    private fun navigateToLoginActivity() {
        val intent = Intent(this, MainActivity::class.java)
        startActivity(intent)
        finish()
    }

    private fun updateNfcCard(): Boolean {
        runOnUiThread {
            showWarning(getString(R.string.nfc_keep_card))
        }
        var success = false

        val tag = currentNfcTag
        if (tag != null && currNfcCardData != null) {
            success = NfcHelper.writeToTag(
                tag,
                currNfcCardData!!.qrData,
                NfcHelper.getTodayDateString()
            )
        }

        isProcessingNfc = false
        currentNfcTag = null
        return success
    }


    private fun handleNfcOfflineMode(errorMessage: String) {
        val tag = currentNfcTag
        if (tag != null && currNfcCardData != null) {
            // 显示网络错误Toast
            if (!isOfflineMode) Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show()

            // 基于卡片中的日期进行离线验证
            if (NfcHelper.canCollectOffline(currNfcCardData!!.lastCollectedDate)) {
                // 更新卡片日期
                val success = updateNfcCard()
                // 若写失败，拒绝领餐
                if (!success) showError(getString(R.string.nfc_write_error))
                else showSuccess(getString(R.string.scan_success))
            } else {
                // 今日已领餐
                showError(getString(R.string.scan_error_collected))
            }
        }

        isProcessingNfc = false
        currentNfcTag = null
    }

    override fun onDestroy() {
        super.onDestroy()
        // 只有在非NFC模式下才关闭相机执行器
        if (!isNfcMode && ::cameraExecutor.isInitialized) {
            cameraExecutor.shutdown()
        }
    }
}