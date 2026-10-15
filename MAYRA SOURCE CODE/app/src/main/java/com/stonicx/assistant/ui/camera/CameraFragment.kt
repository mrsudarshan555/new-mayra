package com.stonicx.assistant.ui.camera

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageProxy
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.stonicx.assistant.background.StonicXConversationService
import com.stonicx.assistant.ui.home.MainActivity
import com.stonicx.assistant.ui.home.MainViewModel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import com.stonicx.assistant.databinding.FragmentCameraBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * In-app camera tab. The camera runs only while this tab is visible.
 * The shutter lives in the bottom dock (it replaces the mic button on this tab).
 */
class CameraFragment : Fragment() {

    private var _binding: FragmentCameraBinding? = null
    private val binding get() = _binding!!

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var useBackCamera = true
    private val viewModel: MainViewModel by activityViewModels()
    private var analysisExecutor: ExecutorService? = null
    private var lastFrameAt = 0L

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startCamera()
        } else {
            Toast.makeText(requireContext(), "Camera permission is required.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCameraBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnFlipCamera.setOnClickListener {
            useBackCamera = !useBackCamera
            startCamera()
        }
        binding.btnCloseCamera.setOnClickListener {
            (activity as? MainActivity)?.selectTab(MainActivity.TAB_HOME)
        }
        // Live captions: whatever is being said shows softly at the top.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(viewModel.liveUserTranscript, viewModel.liveAssistantResponse) { u, a ->
                    listOf(if (u.isNotBlank()) "You: $u" else "", if (a.isNotBlank()) "Mayra: $a" else "")
                        .filter { it.isNotEmpty() }.joinToString("\n")
                }.collect { text ->
                    val view = _binding?.tvCameraCaptions ?: return@collect
                    if (text.isBlank()) {
                        view.visibility = View.GONE
                    } else {
                        if (view.visibility != View.VISIBLE) { view.alpha = 0f; view.visibility = View.VISIBLE }
                        view.text = text
                        view.animate().alpha(1f).setDuration(350L).start()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!isHidden) ensureCamera()
    }

    override fun onPause() {
        super.onPause()
        stopCamera()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) stopCamera() else ensureCamera()
    }

    override fun onDestroyView() {
        stopCamera()
        analysisExecutor?.shutdown()
        analysisExecutor = null
        _binding = null
        super.onDestroyView()
    }

    private fun ensureCamera() {
        val granted = ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) startCamera() else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        val ctx = context ?: return
        val future = ProcessCameraProvider.getInstance(ctx)
        future.addListener({
            if (_binding == null || isHidden) return@addListener
            try {
                val provider = future.get()
                cameraProvider = provider
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(binding.previewView.surfaceProvider)
                }
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                imageCapture = capture
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                val executor = analysisExecutor ?: Executors.newSingleThreadExecutor().also { analysisExecutor = it }
                analysis.setAnalyzer(executor) { image -> streamFrame(image) }
                val selector = if (useBackCamera) CameraSelector.DEFAULT_BACK_CAMERA
                else CameraSelector.DEFAULT_FRONT_CAMERA
                provider.unbindAll()
                provider.bindToLifecycle(viewLifecycleOwner, selector, preview, capture, analysis)
            } catch (e: Exception) {
                Log.e("CameraFragment", "Camera start failed: ${e.message}", e)
                Toast.makeText(ctx, "Could not open the camera.", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(ctx))
    }

    /** Video share: while a live session is on, send about one camera frame per second to Gemini. */
    private fun streamFrame(image: ImageProxy) {
        try {
            val now = SystemClock.elapsedRealtime()
            if (now - lastFrameAt < 1000L || !StonicXConversationService.isSessionOn.value) return
            lastFrameAt = now
            var bitmap = image.toBitmap()
            if (image.imageInfo.rotationDegrees != 0) {
                val m = Matrix().apply { postRotate(image.imageInfo.rotationDegrees.toFloat()) }
                bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
            }
            val longest = maxOf(bitmap.width, bitmap.height)
            if (longest > 768) {
                val scale = 768f / longest
                bitmap = Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
            }
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 60, out)
            StonicXConversationService.sendCameraFrame(out.toByteArray())
        } catch (e: Exception) {
            Log.w("CameraFragment", "Frame skipped: ${e.message}")
        } finally {
            image.close()
        }
    }

    private fun stopCamera() {
        try {
            cameraProvider?.unbindAll()
        } catch (_: Exception) {
        }
        imageCapture = null
    }

    /** Called from the dock shutter button. */
    fun capturePhoto() {
        val ctx = context ?: return
        val capture = imageCapture ?: run {
            Toast.makeText(ctx, "Camera is not ready yet.", Toast.LENGTH_SHORT).show()
            return
        }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
        val options = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "MAYRA_$stamp")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/MAYRA")
            }
            ImageCapture.OutputFileOptions.Builder(
                ctx.contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
            ).build()
        } else {
            val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: ctx.filesDir
            ImageCapture.OutputFileOptions.Builder(File(dir, "MAYRA_$stamp.jpg")).build()
        }

        capture.takePicture(
            options,
            ContextCompat.getMainExecutor(ctx),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    Toast.makeText(ctx, "Photo saved", Toast.LENGTH_SHORT).show()
                }

                override fun onError(exc: ImageCaptureException) {
                    Log.e("CameraFragment", "Capture failed: ${exc.message}", exc)
                    Toast.makeText(ctx, "Could not save the photo.", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }
}
