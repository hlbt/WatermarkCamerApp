package com.example.watermarkcamera

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var tvHint: TextView
    private lateinit var btnTakePhoto: FloatingActionButton

    private lateinit var locationManager: LocationManager
    private lateinit var ioExecutor: ExecutorService

    private var imageCapture: ImageCapture? = null
    private var currentLocation: Location? = null
    private var currentAddress: String = "定位中..."
    private val isCapturing = AtomicBoolean(false)
    private val isLocating = AtomicBoolean(false)
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private var activeLocationListener: LocationListener? = null
    private var locationTimeoutTask: Runnable? = null
    private var locationRetryLeft = 0

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val cameraGranted = result[Manifest.permission.CAMERA] == true || hasCameraPermission()
        if (cameraGranted) {
            startCamera()
        } else {
            toast("需要相机权限")
        }

        if (hasLocationPermission()) {
            updateLocation()
        } else {
            currentLocation = null
            currentAddress = "请允许定位权限"
            tvHint.text = "经度: 未知\n纬度: 未知\n地址: $currentAddress"
            refreshShootButton()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        tvHint = findViewById(R.id.tvHint)
        btnTakePhoto = findViewById(R.id.btnTakePhoto)

        ioExecutor = Executors.newSingleThreadExecutor()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        btnTakePhoto.setOnClickListener {
            takePhotoWithWatermark()
        }
        refreshShootButton()

        val needRequestPermission = !hasCameraPermission() ||
            !hasLocationPermission() ||
            !hasStoragePermissionIfNeeded()
        if (needRequestPermission) {
            permissionLauncher.launch(REQUIRED_PERMISSIONS)
        }

        if (hasCameraPermission()) {
            startCamera()
        }

        if (hasLocationPermission()) {
            updateLocation()
        } else {
            currentLocation = null
            currentAddress = "请允许定位权限"
            tvHint.text = "经度: 未知\n纬度: 未知\n地址: $currentAddress"
            refreshShootButton()
        }
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture)
            } catch (e: Exception) {
                toast("启动相机失败: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun updateLocation() {
        if (!hasLocationPermission()) {
            currentLocation = null
            currentAddress = "请允许定位权限"
            tvHint.text = "经度: 未知\n纬度: 未知\n地址: $currentAddress"
            refreshShootButton()
            return
        }

        val provider = bestProvider()
        if (provider == null) {
            currentLocation = null
            currentAddress = "请开启系统定位和Wi-Fi扫描"
            tvHint.text = "经度: 未知\n纬度: 未知\n地址: $currentAddress"
            refreshShootButton()
            return
        }

        try {
            val lastKnownLocation = getBestCachedLocation()
            if (lastKnownLocation != null) {
                handleLocationResult(lastKnownLocation)
                if (isLocationFresh(lastKnownLocation, 30_000L)) {
                    return
                }
            }

            locationRetryLeft = 1
            currentAddress = "定位中..."
            tvHint.text = "经度: 未知\n纬度: 未知\n地址: $currentAddress"
            requestFreshGpsFix(provider)
        } catch (_: SecurityException) {
            currentLocation = null
            currentAddress = "定位权限异常"
            tvHint.text = "经度: 未知\n纬度: 未知\n地址: $currentAddress"
            refreshShootButton()
        } catch (_: IllegalArgumentException) {
            currentLocation = null
            currentAddress = "定位服务异常"
            tvHint.text = "经度: 未知\n纬度: 未知\n地址: $currentAddress"
            refreshShootButton()
        } catch (_: Exception) {
            currentLocation = null
            currentAddress = "定位失败"
            tvHint.text = "经度: 未知\n纬度: 未知\n地址: $currentAddress"
            refreshShootButton()
        }
    }

    private fun requestFreshGpsFix(provider: String) {
        clearLocationWatcher()
        if (!isLocating.compareAndSet(false, true)) {
            return
        }
        refreshShootButton()
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                handleLocationResult(location)
                clearLocationWatcher()
            }
        }
        activeLocationListener = listener

        locationTimeoutTask = Runnable {
            if (isLocating.get()) {
                clearLocationWatcher()
                if (currentLocation == null) {
                    if (locationRetryLeft > 0) {
                        locationRetryLeft -= 1
                        currentAddress = "定位中，正在重试..."
                        tvHint.text = "经度: 未知\n纬度: 未知\n地址: $currentAddress"
                        requestFreshGpsFix(nextProvider(provider) ?: provider)
                    } else {
                        currentLocation = null
                        currentAddress = if (provider == LocationManager.NETWORK_PROVIDER) {
                            "Wi-Fi定位超时，请开启Wi-Fi扫描/提高位置精度"
                        } else {
                            "定位超时，请到室外重试"
                        }
                        tvHint.text = "经度: 未知\n纬度: 未知\n地址: $currentAddress"
                        refreshShootButton()
                    }
                }
            }
        }
        mainHandler.postDelayed(locationTimeoutTask!!, GPS_TIMEOUT_MS)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                locationManager.getCurrentLocation(
                    provider,
                    null,
                    ContextCompat.getMainExecutor(this)
                ) { location ->
                    if (location != null && isLocating.get()) {
                        handleLocationResult(location)
                        clearLocationWatcher()
                    }
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                locationManager.requestLocationUpdates(
                    provider,
                    500L,
                    0f,
                    ContextCompat.getMainExecutor(this),
                    listener
                )
            } else {
                @Suppress("DEPRECATION")
                locationManager.requestLocationUpdates(
                    provider,
                    500L,
                    0f,
                    listener,
                    mainLooper
                )
            }
        } catch (_: Exception) {
            clearLocationWatcher()
            if (currentLocation == null) {
                currentLocation = null
                currentAddress = "定位失败"
                tvHint.text = "经度: 未知\n纬度: 未知\n地址: $currentAddress"
                refreshShootButton()
            }
        }
    }

    private fun clearLocationWatcher() {
        val listener = activeLocationListener
        if (listener != null) {
            try {
                locationManager.removeUpdates(listener)
            } catch (_: Exception) {
            }
        }
        activeLocationListener = null
        locationTimeoutTask?.let { mainHandler.removeCallbacks(it) }
        locationTimeoutTask = null
        isLocating.set(false)
        refreshShootButton()
    }

    private fun bestProvider(): String? {
        val gpsEnabled = try {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        } catch (_: Exception) {
            false
        }
        val networkEnabled = try {
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (_: Exception) {
            false
        }
        return when {
            networkEnabled -> LocationManager.NETWORK_PROVIDER
            gpsEnabled && hasFineLocationPermission() -> LocationManager.GPS_PROVIDER
            gpsEnabled -> LocationManager.GPS_PROVIDER
            else -> null
        }
    }

    private fun nextProvider(current: String): String? {
        val gpsEnabled = try {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        } catch (_: Exception) {
            false
        }
        val networkEnabled = try {
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (_: Exception) {
            false
        }

        return when (current) {
            LocationManager.NETWORK_PROVIDER -> if (gpsEnabled) LocationManager.GPS_PROVIDER else null
            LocationManager.GPS_PROVIDER -> if (networkEnabled) LocationManager.NETWORK_PROVIDER else null
            else -> null
        }
    }

    private fun getBestCachedLocation(): Location? {
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )
        val candidates = providers.mapNotNull { provider ->
            try {
                @Suppress("DEPRECATION")
                locationManager.getLastKnownLocation(provider)
            } catch (_: Exception) {
                null
            }
        }
        if (candidates.isEmpty()) return null

        return candidates.minWithOrNull(
            compareBy<Location> { !isLocationFresh(it, CACHE_MAX_AGE_MS) }
                .thenBy { it.accuracy }
        )
    }

    private fun handleLocationResult(location: Location) {
        currentLocation = location
        resolveAddress(location) { address ->
            currentAddress = address
            val lng = String.format(Locale.US, "%.6f", location.longitude)
            val lat = String.format(Locale.US, "%.6f", location.latitude)
            tvHint.text = "经度: $lng\n纬度: $lat\n地址: $address"
            refreshShootButton()
        }
    }

    private fun hasLocationPermission(): Boolean {
        val fineGranted = hasFineLocationPermission()
        val coarseGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fineGranted || coarseGranted
    }

    private fun hasFineLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasStoragePermissionIfNeeded(): Boolean {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
            return true
        }
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun resolveAddress(location: Location, onDone: (String) -> Unit) {
        if (!Geocoder.isPresent()) {
            onDone("地址服务不可用")
            return
        }

        val geocoder = Geocoder(this, Locale.SIMPLIFIED_CHINESE)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                geocoder.getFromLocation(location.latitude, location.longitude, 1,
                    object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) {
                            val address = addresses.firstOrNull()?.getAddressLine(0) ?: "未知地址"
                            runOnUiThread { onDone(address) }
                        }

                        override fun onError(errorMessage: String?) {
                            runOnUiThread { onDone("未知地址") }
                        }
                    })
                return
            }

            ioExecutor.execute {
                try {
                    @Suppress("DEPRECATION")
                    val addresses =
                        geocoder.getFromLocation(location.latitude, location.longitude, 1)
                    val address = addresses?.firstOrNull()?.getAddressLine(0) ?: "未知地址"
                    runOnUiThread { onDone(address) }
                } catch (_: IOException) {
                    runOnUiThread { onDone("未知地址") }
                } catch (_: IllegalArgumentException) {
                    runOnUiThread { onDone("未知地址") }
                }
            }
        } catch (_: Exception) {
            onDone("未知地址")
        }
    }

    private fun takePhotoWithWatermark() {
        if (isLocating.get()) {
            toast("定位中，请稍后拍照")
            return
        }
        if (currentLocation == null) {
            toast("等待定位成功后才可拍照")
            return
        }
        if (!hasCameraPermission()) {
            toast("请先授权相机权限")
            permissionLauncher.launch(REQUIRED_PERMISSIONS)
            return
        }
        if (!hasStoragePermissionIfNeeded()) {
            toast("请先授权存储权限")
            permissionLauncher.launch(REQUIRED_PERMISSIONS)
            return
        }

        if (!isCapturing.compareAndSet(false, true)) {
            toast("正在处理上一张照片")
            return
        }

        val imageCapture = imageCapture
        if (imageCapture == null) {
            finishCapture(null, "相机未就绪")
            return
        }
        updateLocation()
        refreshShootButton()

        val tempFile = try {
            File.createTempFile("origin_", ".jpg", cacheDir)
        } catch (_: Exception) {
            finishCapture(null, "创建临时文件失败")
            return
        }
        val outputOptions = ImageCapture.OutputFileOptions.Builder(tempFile).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    ioExecutor.execute {
                        val savedUri = try {
                            processAndSave(tempFile)
                        } catch (_: Throwable) {
                            null
                        }
                        tempFile.delete()
                        finishCapture(savedUri)
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    tempFile.delete()
                    finishCapture(null, "拍照失败: ${exception.message}")
                }
            }
        )
    }

    private fun processAndSave(sourceFile: File): Uri? {
        val original = decodeBitmap(sourceFile) ?: return null
        val mutable =
            if (original.isMutable) original else original.copy(Bitmap.Config.ARGB_8888, true)
                ?: return null
        return try {
            val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())
            val lng = currentLocation?.longitude?.let { String.format(Locale.US, "%.6f", it) } ?: "未知"
            val lat = currentLocation?.latitude?.let { String.format(Locale.US, "%.6f", it) } ?: "未知"

            val lines = listOf(
                "经度: $lng",
                "纬度: $lat",
                "地址: $currentAddress",
                "时间: $now",
                "现场拍照水印"
            )

            val watermarked = WatermarkProcessor.addWatermark(mutable, lines)
            saveBitmapToGallery(watermarked)
        } catch (_: Throwable) {
            null
        }
    }

    private fun decodeBitmap(file: File): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(file)
                ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.isMutableRequired = true
                }
            } else {
                BitmapFactory.decodeFile(file.absolutePath)
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun finishCapture(savedUri: Uri?, failMsg: String = "保存失败") {
        runOnUiThread {
            isCapturing.set(false)
            refreshShootButton()
            if (savedUri != null) {
                toast("保存成功: $savedUri")
            } else {
                toast(failMsg)
            }
        }
    }

    private fun refreshShootButton() {
        runOnUiThread {
            btnTakePhoto.isEnabled = !isCapturing.get() && !isLocating.get() && currentLocation != null
        }
    }

    private fun saveBitmapToGallery(bitmap: Bitmap): Uri? {
        return try {
            val filename = "watermark_${System.currentTimeMillis()}.jpg"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(
                        MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/WatermarkCamera"
                    )
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }

            val resolver = contentResolver
            val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val uri = resolver.insert(collection, values) ?: return null

            val writeOk = resolver.openOutputStream(uri)?.use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)
            } ?: false

            if (!writeOk) {
                resolver.delete(uri, null, null)
                return null
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }

            uri
        } catch (_: Exception) {
            null
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        clearLocationWatcher()
        ioExecutor.shutdown()
    }

    companion object {
        private const val GPS_TIMEOUT_MS = 12_000L
        private const val CACHE_MAX_AGE_MS = 120_000L

        private val REQUIRED_PERMISSIONS: Array<String> by lazy {
            buildList {
                add(Manifest.permission.CAMERA)
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }.toTypedArray()
        }
    }

    private fun isLocationFresh(location: Location, maxAgeMs: Long): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            val ageMs =
                (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000L
            ageMs in 0..maxAgeMs
        } else {
            val ageMs = System.currentTimeMillis() - location.time
            ageMs in 0..maxAgeMs
        }
    }
}
