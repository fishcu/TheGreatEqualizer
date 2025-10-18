package com.example.thegreatequalizer

import android.os.Bundle
import android.util.Log
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.content.ContentValues
import android.provider.MediaStore
import android.widget.Toast
import android.graphics.Color as AndroidColor
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.Slider
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.example.thegreatequalizer.ui.theme.TheGreatEqualizerTheme
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfInt
import org.opencv.imgproc.Imgproc
import java.util.ArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.pow

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initOk = OpenCVLoader.initDebug()
        Log.i("OpenCV", "initDebug=${initOk} version=${org.opencv.core.Core.VERSION}")
        enableEdgeToEdge()
        setContent {
            TheGreatEqualizerTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    PickerScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
private fun PickerScreen(modifier: Modifier = Modifier) {
    var selectedUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var fullResBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var previewOriginalBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var previewProcessedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var showOriginal by remember { mutableStateOf(false) }
    // Per-parameter HSV wheels (produce RGB vectors normalized by max component)
    var hueS by remember { mutableStateOf(0f) }
    var satS by remember { mutableStateOf(0f) }
    var hueT by remember { mutableStateOf(0f) }
    var satT by remember { mutableStateOf(0f) }
    var hueG by remember { mutableStateOf(0f) }
    var satG by remember { mutableStateOf(0f) }
    var heSmoothing by remember { mutableStateOf(0.02f) }
    // Histogram specification parameters (raw in [-2,2], effective via exp2)
    var rawS by remember { mutableStateOf(0f) }
    var rawT by remember { mutableStateOf(0f) }
    var rawG by remember { mutableStateOf(0f) }
    var displayWidthPx by remember { mutableStateOf(0) }
    var displayHeightPx by remember { mutableStateOf(0) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var progressiveJob by remember { mutableStateOf<Job?>(null) }

    fun computePreviewBitmap(src: Bitmap, displayW: Int, displayH: Int): Bitmap {
        val origW = src.width
        val origH = src.height
        val origArea = origW.toLong() * origH.toLong()
        val displayArea = (displayW.toLong() * displayH.toLong()).coerceAtLeast(0L)
        val targetArea = kotlin.math.max(displayArea, 1_000_000L) // at least 1MP for preview
        if (origArea <= targetArea) return src
        val scale = kotlin.math.sqrt(targetArea.toDouble() / origArea.toDouble())
        var newW = kotlin.math.max(1, kotlin.math.round(origW * scale).toInt())
        var newH = kotlin.math.max(1, kotlin.math.round(origH * scale).toInt())
        if (newW > origW || newH > origH) {
            newW = origW
            newH = origH
        }
        return Bitmap.createScaledBitmap(src, newW, newH, true)
    }

    fun computePreviewTargetSize(src: Bitmap, displayW: Int, displayH: Int): Pair<Int, Int> {
        val origW = src.width
        val origH = src.height
        val origArea = origW.toLong() * origH.toLong()
        val displayArea = (displayW.toLong() * displayH.toLong()).coerceAtLeast(0L)
        val targetArea = kotlin.math.max(displayArea, 1_000_000L)
        if (origArea <= targetArea) return Pair(origW, origH)
        val scale = kotlin.math.sqrt(targetArea.toDouble() / origArea.toDouble())
        var newW = kotlin.math.max(1, kotlin.math.round(origW * scale).toInt())
        var newH = kotlin.math.max(1, kotlin.math.round(origH * scale).toInt())
        if (newW > origW || newH > origH) {
            newW = origW
            newH = origH
        }
        return Pair(newW, newH)
    }

    fun computeLevelSizes(srcW: Int, srcH: Int, topW: Int, topH: Int): List<Pair<Int, Int>> {
        val aspectW = srcW.toDouble()
        val aspectH = srcH.toDouble()
        val longTop = kotlin.math.max(topW, topH)
        val longMin = kotlin.math.min(256, longTop)
        val sizes = ArrayList<Pair<Int, Int>>()
        var current = longMin
        while (true) {
            val size = if (aspectW >= aspectH) {
                val w = current
                val h = kotlin.math.max(1, kotlin.math.round(current * (aspectH / aspectW)).toInt())
                Pair(w, h)
            } else {
                val h = current
                val w = kotlin.math.max(1, kotlin.math.round(current * (aspectW / aspectH)).toInt())
                Pair(w, h)
            }
            if (sizes.isEmpty() || sizes.last() != size) sizes.add(size)
            if (current >= longTop) break
            val next = current * 2
            current = if (next >= longTop) longTop else next
        }
        return sizes
    }

    fun startProgressivePreview() {
        val src = fullResBitmap ?: return
        val (topW, topH) = computePreviewTargetSize(src, displayWidthPx, displayHeightPx)
        val sizes = computeLevelSizes(src.width, src.height, topW, topH)
        progressiveJob?.cancel()
        progressiveJob = scope.launch(Dispatchers.Default) {
            for ((w, h) in sizes) {
                if (!isActive) break
                val scaled = Bitmap.createScaledBitmap(src, w, h, true)
                val sVec = hsvToRgbVectorMaxNormalized(hueS, satS)
                val tVec = hsvToRgbVectorMaxNormalized(hueT, satT)
                val gVec = hsvToRgbVectorMaxNormalized(hueG, satG)
                val sEff = Triple(
                    2.0.pow(rawS.toDouble() * sVec.first),
                    2.0.pow(rawS.toDouble() * sVec.second),
                    2.0.pow(rawS.toDouble() * sVec.third)
                )
                val tEff = Triple(
                    2.0.pow(rawT.toDouble() * tVec.first),
                    2.0.pow(rawT.toDouble() * tVec.second),
                    2.0.pow(rawT.toDouble() * tVec.third)
                )
                val gEff = Triple(
                    2.0.pow(rawG.toDouble() * gVec.first),
                    2.0.pow(rawG.toDouble() * gVec.second),
                    2.0.pow(rawG.toDouble() * gVec.third)
                )
                val out = processBitmapRgbPerChannelSmoothstep(
                    scaled,
                    heSmoothing.toDouble(),
                    sEff,
                    tEff,
                    gEff
                )
                withContext(Dispatchers.Main) {
                    previewOriginalBitmap = scaled
                    previewProcessedBitmap = out
                }
            }
        }
    }

    fun updatePreviewBitmaps() {
        startProgressivePreview()
    }
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        selectedUri = uri
        Log.i("Picker", "uri=${uri}")
        if (uri != null) {
            try {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                val srcBitmap = ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.isMutableRequired = true
                }

                fullResBitmap = srcBitmap
                // Build initial preview (will use 1MP if display size not known yet)
                updatePreviewBitmaps()
            } catch (e: Exception) {
                Log.e("Processor", "Failed to process image", e)
                previewProcessedBitmap = null
            }
        } else {
            previewProcessedBitmap = null
        }
    }

    Column(modifier = modifier) {
        Row {
            Button(onClick = {
                launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }) {
                Text("Import")
            }

            Button(onClick = {
            val srcFull = fullResBitmap
            if (srcFull == null) {
                Toast.makeText(context, "No image to export", Toast.LENGTH_SHORT).show()
            } else {
                val sVec = hsvToRgbVectorMaxNormalized(hueS, satS)
                val tVec = hsvToRgbVectorMaxNormalized(hueT, satT)
                val gVec = hsvToRgbVectorMaxNormalized(hueG, satG)
                val sEff = Triple(
                    2.0.pow(rawS.toDouble() * sVec.first),
                    2.0.pow(rawS.toDouble() * sVec.second),
                    2.0.pow(rawS.toDouble() * sVec.third)
                )
                val tEff = Triple(
                    2.0.pow(rawT.toDouble() * tVec.first),
                    2.0.pow(rawT.toDouble() * tVec.second),
                    2.0.pow(rawT.toDouble() * tVec.third)
                )
                val gEff = Triple(
                    2.0.pow(rawG.toDouble() * gVec.first),
                    2.0.pow(rawG.toDouble() * gVec.second),
                    2.0.pow(rawG.toDouble() * gVec.third)
                )
                val processedFull = processBitmapRgbPerChannelSmoothstep(
                    srcFull,
                    heSmoothing.toDouble(),
                    sEff,
                    tEff,
                    gEff
                )
                val filename = "TGE_" + System.currentTimeMillis().toString() + ".jpg"
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/TheGreatEqualizer")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                try {
                    val resolver = context.contentResolver
                    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    if (uri != null) {
                        resolver.openOutputStream(uri)?.use { out ->
                            processedFull.compress(Bitmap.CompressFormat.JPEG, 95, out)
                        }
                        val clearPending = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
                        resolver.update(uri, clearPending, null, null)
                        Toast.makeText(context, "Saved: ${filename}", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Failed to create output URI", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Log.e("Export", "Failed to save JPEG", e)
                    Toast.makeText(context, "Save failed", Toast.LENGTH_SHORT).show()
                }
            }
            }) {
                Text("Export")
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxSize()
                .onSizeChanged { sz ->
                    val w = sz.width
                    val h = sz.height
                    if (w != displayWidthPx || h != displayHeightPx) {
                        displayWidthPx = w
                        displayHeightPx = h
                        if (fullResBitmap != null) updatePreviewBitmaps()
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            showOriginal = true
                            tryAwaitRelease()
                            showOriginal = false
                        }
                    )
                }
        ) {
            val bmpToShow = if (showOriginal) previewOriginalBitmap else previewProcessedBitmap
            if (bmpToShow != null) {
                Image(
                    bitmap = bmpToShow.asImageBitmap(),
                    contentDescription = if (showOriginal) "Original image" else "Processed image",
                    modifier = Modifier.fillMaxSize()
                )
            } else if (selectedUri != null) {
                Text("Image selected. Processing failed or pending.")
            }
        }

        Row(modifier = Modifier.padding(top = 12.dp)) {
            Text("HE smoothing")
        }
        Slider(
            value = heSmoothing,
            onValueChange = { v ->
                heSmoothing = v
                updatePreviewBitmaps()
            },
            valueRange = 0f..1f
        )

        // t controls with its HSV wheel
        Row(modifier = Modifier.padding(top = 12.dp)) {
            HsvWheel(
                hue = hueT,
                saturation = satT,
                onChange = { h, s ->
                    hueT = h
                    satT = s
                    updatePreviewBitmaps()
                },
                modifier = Modifier.padding(end = 12.dp),
                sizeDp = 120.dp
            )
            Column(modifier = Modifier.weight(1f)) {
                Text("t (raw) [-2,2]  eff=" + String.format("%.3f", 2.0.pow(rawT.toDouble())))
                Slider(
                    value = rawT,
                    onValueChange = { v ->
                        rawT = v
                        updatePreviewBitmaps()
                    },
                    valueRange = -2f..2f
                )
            }
        }

        // s controls with its HSV wheel
        Row(modifier = Modifier.padding(top = 12.dp)) {
            HsvWheel(
                hue = hueS,
                saturation = satS,
                onChange = { h, s ->
                    hueS = h
                    satS = s
                    updatePreviewBitmaps()
                },
                modifier = Modifier.padding(end = 12.dp),
                sizeDp = 120.dp
            )
            Column(modifier = Modifier.weight(1f)) {
                Text("s (raw) [-2,2]  eff=" + String.format("%.3f", 2.0.pow(rawS.toDouble())))
                Slider(
                    value = rawS,
                    onValueChange = { v ->
                        rawS = v
                        updatePreviewBitmaps()
                    },
                    valueRange = -2f..2f
                )
            }
        }

        // g controls with its HSV wheel
        Row(modifier = Modifier.padding(top = 12.dp)) {
            HsvWheel(
                hue = hueG,
                saturation = satG,
                onChange = { h, s ->
                    hueG = h
                    satG = s
                    updatePreviewBitmaps()
                },
                modifier = Modifier.padding(end = 12.dp),
                sizeDp = 120.dp
            )
            Column(modifier = Modifier.weight(1f)) {
                Text("g (raw) [-2,2]  eff=" + String.format("%.3f", 2.0.pow(rawG.toDouble())))
                Slider(
                    value = rawG,
                    onValueChange = { v ->
                        rawG = v
                        updatePreviewBitmaps()
                    },
                    valueRange = -2f..2f
                )
            }
        }
    }
}

@Composable
private fun HsvWheel(
    hue: Float,
    saturation: Float,
    onChange: (Float, Float) -> Unit,
    modifier: Modifier = Modifier,
    sizeDp: Dp = 200.dp
) {
    var sizePx by remember { mutableStateOf(0) }
    val wheelBitmap = remember(sizePx) {
        if (sizePx > 0) createHsvWheelBitmap(sizePx) else null
    }
    Box(
        modifier = modifier
            .padding(12.dp)
            .size(sizeDp)
            .onSizeChanged { sizePx = kotlin.math.min(it.width, it.height) }
            .pointerInput(sizePx) {
                if (sizePx <= 0) return@pointerInput
                detectDragGestures(
                    onDragStart = { offset ->
                        val (h, s) = hsvFromPosition(offset, sizePx)
                        onChange(h, s)
                    },
                    onDrag = { change, _ ->
                        val (h, s) = hsvFromPosition(change.position, sizePx)
                        onChange(h, s)
                    }
                )
            }
    ) {
        val bmp = wheelBitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "HSV wheel",
                modifier = Modifier.fillMaxSize()
            )
            Canvas(modifier = Modifier.fillMaxSize()) {
                val radius = size.minDimension / 2f
                val theta = Math.toRadians(hue.toDouble()).toFloat()
                val r = saturation * radius
                val cx = size.width / 2f + r * kotlin.math.cos(theta)
                val cy = size.height / 2f + r * kotlin.math.sin(theta)
				val outerRadiusPx = 12.dp.toPx()
				val innerRadiusPx = 8.dp.toPx()
				drawCircle(Color.Black, radius = outerRadiusPx, center = Offset(cx, cy))
				drawCircle(Color.White, radius = innerRadiusPx, center = Offset(cx, cy))
            }
        }
    }
}

private fun hsvFromPosition(pos: Offset, sizePx: Int): Pair<Float, Float> {
    val c = sizePx / 2f
    val dx = pos.x - c
    val dy = pos.y - c
    val r = kotlin.math.sqrt(dx * dx + dy * dy)
    val radius = c
    val s = kotlin.math.min(1f, kotlin.math.max(0f, r / radius))
    var angle = Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble()))
    if (angle < 0) angle += 360.0
    return Pair(angle.toFloat(), s)
}

private fun createHsvWheelBitmap(size: Int): Bitmap {
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = size / 2f
    val radius = c
    val hsv = floatArrayOf(0f, 0f, 1f)
    val pixels = IntArray(size * size)
    var i = 0
    for (y in 0 until size) {
        val dy = y - c
        for (x in 0 until size) {
            val dx = x - c
            val r = kotlin.math.sqrt(dx * dx + dy * dy)
            if (r <= radius) {
                var angle = Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble()))
                if (angle < 0) angle += 360.0
                hsv[0] = angle.toFloat()
                hsv[1] = (r / radius)
                hsv[2] = 1f
                pixels[i] = AndroidColor.HSVToColor(hsv)
            } else {
                pixels[i] = 0x00000000
            }
            i++
        }
    }
    bmp.setPixels(pixels, 0, size, 0, 0, size, size)
    return bmp
}

private fun targetCdf(y: Double, s: Double, t: Double, g: Double): Double {
    if (y <= 0.0) return 0.0
    if (y >= 1.0) return 1.0
    val a = y.pow(t)
    val b = (1.0 - y).pow(s)
    val frac = if ((a + b) <= 0.0) 0.0 else a / (a + b)
    val v = frac.pow(g)
    return when {
        v < 0.0 -> 0.0
        v > 1.0 -> 1.0
        else -> v
    }
}

private fun hsvToLinearRgbWeights(hue: Float, saturation: Float): Triple<Double, Double, Double> {
    val hsv = floatArrayOf(hue, saturation, 1f)
    val color = AndroidColor.HSVToColor(hsv)
    val r = ((color shr 16) and 0xFF) / 255.0
    val g = ((color shr 8) and 0xFF) / 255.0
    val b = (color and 0xFF) / 255.0
    val rl = srgbToLinearScalar(r)
    val gl = srgbToLinearScalar(g)
    val bl = srgbToLinearScalar(b)
    val sum = rl + gl + bl
    return if (sum > 1e-9) Triple(rl / sum, gl / sum, bl / sum) else Triple(1.0 / 3.0, 1.0 / 3.0, 1.0 / 3.0)
}

private fun srgbToLinearScalar(c: Double): Double {
    return if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
}

private fun hsvToRgbVectorMaxNormalized(hue: Float, saturation: Float): Triple<Double, Double, Double> {
    val hsv = floatArrayOf(hue, saturation, 1f)
    val color = AndroidColor.HSVToColor(hsv)
    val r = ((color shr 16) and 0xFF) / 255.0
    val g = ((color shr 8) and 0xFF) / 255.0
    val b = (color and 0xFF) / 255.0
    val m = kotlin.math.max(r, kotlin.math.max(g, b))
    return if (m <= 1e-9) Triple(0.0, 0.0, 0.0) else Triple(r / m, g / m, b / m)
}

private fun processBitmapRgbPerChannelSmoothstep(
    srcBitmap: Bitmap,
    heSmoothing: Double,
    sEff: Triple<Double, Double, Double>,
    tEff: Triple<Double, Double, Double>,
    gEff: Triple<Double, Double, Double>
): Bitmap {
    val src8 = Mat()
    Utils.bitmapToMat(srcBitmap, src8)
    val srcF = Mat()
    src8.convertTo(srcF, CvType.CV_32FC4, 1.0 / 255.0)
    val ch = ArrayList<Mat>(4)
    Core.split(srcF, ch)

    fun srgbToLinear(channel: Mat) {
        val mask = Mat()
        val thr = Mat(channel.rows(), channel.cols(), channel.type(), Scalar(0.04045))
        Core.compare(channel, thr, mask, Core.CMP_LE)
        val low = Mat()
        Core.divide(channel, Scalar(12.92), low)
        val highTmp = Mat()
        Core.add(channel, Scalar(0.055), highTmp)
        Core.divide(highTmp, Scalar(1.055), highTmp)
        Core.pow(highTmp, 2.4, highTmp)
        low.copyTo(channel, mask)
        val invMask = Mat()
        val zero = Mat(mask.rows(), mask.cols(), mask.type(), Scalar(0.0))
        Core.compare(mask, zero, invMask, Core.CMP_EQ)
        highTmp.copyTo(channel, invMask)
        mask.release(); thr.release(); low.release(); highTmp.release(); invMask.release(); zero.release()
    }

    fun linearToSrgb(channel: Mat) {
        val mask = Mat()
        val thr = Mat(channel.rows(), channel.cols(), channel.type(), Scalar(0.0031308))
        Core.compare(channel, thr, mask, Core.CMP_LE)
        val low = Mat()
        Core.multiply(channel, Scalar(12.92), low)
        val highTmp = Mat()
        Core.pow(channel, 1.0 / 2.4, highTmp)
        Core.multiply(highTmp, Scalar(1.055), highTmp)
        Core.subtract(highTmp, Scalar(0.055), highTmp)
        low.copyTo(channel, mask)
        val invMask = Mat()
        val zero = Mat(mask.rows(), mask.cols(), mask.type(), Scalar(0.0))
        Core.compare(mask, zero, invMask, Core.CMP_EQ)
        highTmp.copyTo(channel, invMask)
        mask.release(); thr.release(); low.release(); highTmp.release(); invMask.release(); zero.release()
    }

    // Work in linear light
    srgbToLinear(ch[0])
    srgbToLinear(ch[1])
    srgbToLinear(ch[2])

    fun eqAndShapeChannel(channel: Mat, s: Double, t: Double, g: Double) {
        val bins = 1024
        val hist = Mat()
        Imgproc.calcHist(listOf(channel), MatOfInt(0), Mat(), hist, MatOfInt(bins), MatOfFloat(0f, 1f))
        val alpha = kotlin.math.min(1.0, kotlin.math.max(0.0, heSmoothing))
        val totalPix = (channel.rows() * channel.cols()).toDouble()
        val meanCount = totalPix / bins.toDouble()
        val histSmoothed = Mat(hist.rows(), hist.cols(), hist.type())
        Core.multiply(hist, Scalar(1.0 - alpha), histSmoothed)
        Core.add(histSmoothed, Scalar(alpha * meanCount), histSmoothed)
        val cdfRow = Mat(1, bins, CvType.CV_32FC1)
        var cdf = 0.0
        var cdfMin = -1.0
        val totalSmoothed = (1.0 - alpha) * totalPix + alpha * (meanCount * bins)
        var b = 0
        while (b < bins) {
            val h = histSmoothed.get(b, 0)[0]
            cdf += h
            if (cdfMin < 0.0 && cdf > 0.0) cdfMin = cdf
            val v = if (cdfMin > 0.0) ((cdf - cdfMin) / (totalSmoothed - cdfMin)) else 0.0
            val clamped = if (v < 0.0) 0.0f else if (v > 1.0) 1.0f else v.toFloat()
            cdfRow.put(0, b, clamped.toDouble())
            b++
        }
        val idx = Mat()
        Core.multiply(channel, Scalar((bins - 1).toDouble()), idx)
        val zeroMap = Mat(channel.rows(), channel.cols(), CvType.CV_32FC1, Scalar(0.0))
        val map = Mat()
        Core.merge(listOf(idx, zeroMap), map)
        val chEq = Mat(channel.rows(), channel.cols(), CvType.CV_32FC1)
        Imgproc.remap(cdfRow, chEq, map, Mat(), Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE, Scalar(0.0))

        val invS = 1.0 / s
        val invT = 1.0 / t
        val invG = 1.0 / g
        val invRow = Mat(1, bins, CvType.CV_32FC1)
        var iBin = 0
        while (iBin < bins) {
            val u = iBin.toDouble() / (bins - 1).toDouble()
            val y = targetCdf(u, invS, invT, invG)
            invRow.put(0, iBin, y)
            iBin++
        }
        val idx2 = Mat()
        Core.multiply(chEq, Scalar((bins - 1).toDouble()), idx2)
        val map2 = Mat()
        Core.merge(listOf(idx2, zeroMap), map2)
        val chTarget = Mat(channel.rows(), channel.cols(), CvType.CV_32FC1)
        Imgproc.remap(invRow, chTarget, map2, Mat(), Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE, Scalar(0.0))

        chTarget.copyTo(channel)

        // release
        hist.release(); histSmoothed.release(); cdfRow.release(); idx.release(); zeroMap.release(); map.release(); chEq.release(); invRow.release(); idx2.release(); map2.release(); chTarget.release()
    }

    eqAndShapeChannel(ch[0], sEff.first, tEff.first, gEff.first)
    eqAndShapeChannel(ch[1], sEff.second, tEff.second, gEff.second)
    eqAndShapeChannel(ch[2], sEff.third, tEff.third, gEff.third)

    linearToSrgb(ch[0])
    linearToSrgb(ch[1])
    linearToSrgb(ch[2])

    Core.max(ch[0], Scalar(0.0), ch[0])
    Core.min(ch[0], Scalar(1.0), ch[0])
    Core.max(ch[1], Scalar(0.0), ch[1])
    Core.min(ch[1], Scalar(1.0), ch[1])
    Core.max(ch[2], Scalar(0.0), ch[2])
    Core.min(ch[2], Scalar(1.0), ch[2])

    Core.merge(ch, srcF)
    ch.forEach { it.release() }

    val out8 = Mat()
    srcF.convertTo(out8, CvType.CV_8UC4, 255.0)
    val outBitmap = Bitmap.createBitmap(srcBitmap.width, srcBitmap.height, Bitmap.Config.ARGB_8888)
    Utils.matToBitmap(out8, outBitmap)
    src8.release(); srcF.release(); out8.release()
    return outBitmap
}

private fun processBitmapLinearLumaSmoothstep(
    srcBitmap: Bitmap,
    weights: Triple<Double, Double, Double>,
    heSmoothing: Double,
    sEff: Double,
    tEff: Double,
    gEff: Double
): Bitmap {
    val src8 = Mat()
    Utils.bitmapToMat(srcBitmap, src8)
    val srcF = Mat()
    src8.convertTo(srcF, CvType.CV_32FC4, 1.0 / 255.0)
    val ch = ArrayList<Mat>(4)
    Core.split(srcF, ch)

    fun srgbToLinear(channel: Mat) {
        val mask = Mat()
        val thr = Mat(channel.rows(), channel.cols(), channel.type(), Scalar(0.04045))
        Core.compare(channel, thr, mask, Core.CMP_LE)
        val low = Mat()
        Core.divide(channel, Scalar(12.92), low)
        val highTmp = Mat()
        Core.add(channel, Scalar(0.055), highTmp)
        Core.divide(highTmp, Scalar(1.055), highTmp)
        Core.pow(highTmp, 2.4, highTmp)
        low.copyTo(channel, mask)
        val invMask = Mat()
        val zero = Mat(mask.rows(), mask.cols(), mask.type(), Scalar(0.0))
        Core.compare(mask, zero, invMask, Core.CMP_EQ)
        highTmp.copyTo(channel, invMask)
        mask.release(); thr.release(); low.release(); highTmp.release(); invMask.release(); zero.release()
    }

    srgbToLinear(ch[0])
    srgbToLinear(ch[1])
    srgbToLinear(ch[2])

    val (wr, wg, wb) = weights
    val lum = Mat()
    Core.addWeighted(ch[0], wr, ch[1], wg, 0.0, lum)
    Core.addWeighted(lum, 1.0, ch[2], wb, 0.0, lum)

	// Full-float mapping: 1024-bin histogram, smoothed CDF, and continuous float remap (no 8-bit LUT)
	val bins = 1024
	val hist = Mat()
	Imgproc.calcHist(listOf(lum), MatOfInt(0), Mat(), hist, MatOfInt(bins), MatOfFloat(0f, 1f))
	val alpha = kotlin.math.min(1.0, kotlin.math.max(0.0, heSmoothing))
	val totalPix = (lum.rows() * lum.cols()).toDouble()
	val meanCount = totalPix / bins.toDouble()
	val histSmoothed = Mat(hist.rows(), hist.cols(), hist.type())
	Core.multiply(hist, Scalar(1.0 - alpha), histSmoothed)
	Core.add(histSmoothed, Scalar(alpha * meanCount), histSmoothed)
	val cdfRow = Mat(1, bins, CvType.CV_32FC1)
	var cdf = 0.0
	var cdfMin = -1.0
	val totalSmoothed = (1.0 - alpha) * totalPix + alpha * (meanCount * bins)
	var b = 0
	while (b < bins) {
		val h = histSmoothed.get(b, 0)[0]
		cdf += h
		if (cdfMin < 0.0 && cdf > 0.0) cdfMin = cdf
		val v = if (cdfMin > 0.0) ((cdf - cdfMin) / (totalSmoothed - cdfMin)) else 0.0
		val clamped = if (v < 0.0) 0.0f else if (v > 1.0) 1.0f else v.toFloat()
		cdfRow.put(0, b, clamped.toDouble())
		b++
	}
	// Build per-pixel map into the 1xBins CDF row: x = lum * (bins-1), y = 0
	val idx = Mat()
	Core.multiply(lum, Scalar((bins - 1).toDouble()), idx)
	val zeroMap = Mat(lum.rows(), lum.cols(), CvType.CV_32FC1, Scalar(0.0))
	val map = Mat()
	Core.merge(listOf(idx, zeroMap), map)
    val lumEqF = Mat(lum.rows(), lum.cols(), CvType.CV_32FC1)
    Imgproc.remap(cdfRow, lumEqF, map, Mat(), Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE, Scalar(0.0))
    // Approximate inverse by flipping raw signs: use inverse-effective params 1/s, 1/t, 1/g
    val invS = 1.0 / sEff
    val invT = 1.0 / tEff
    val invG = 1.0 / gEff
    val invRow = Mat(1, bins, CvType.CV_32FC1)
    var iBin = 0
    while (iBin < bins) {
        val u = iBin.toDouble() / (bins - 1).toDouble()
        val y = targetCdf(u, invS, invT, invG)
        invRow.put(0, iBin, y)
        iBin++
    }
    val idx2 = Mat()
    Core.multiply(lumEqF, Scalar((bins - 1).toDouble()), idx2)
    val map2 = Mat()
    Core.merge(listOf(idx2, zeroMap), map2)
    val lumTargetF = Mat(lum.rows(), lum.cols(), CvType.CV_32FC1)
    Imgproc.remap(invRow, lumTargetF, map2, Mat(), Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE, Scalar(0.0))
    // Scale RGB (linear) to match equalized luminance
    val eps = 1e-6
    val denom = Mat()
    Core.add(lum, Scalar(eps), denom)
    val scale = Mat()
    Core.divide(lumTargetF, denom, scale)
    Core.multiply(ch[0], scale, ch[0])
    Core.multiply(ch[1], scale, ch[1])
    Core.multiply(ch[2], scale, ch[2])

    fun linearToSrgb(channel: Mat) {
        val mask = Mat()
        val thr = Mat(channel.rows(), channel.cols(), channel.type(), Scalar(0.0031308))
        Core.compare(channel, thr, mask, Core.CMP_LE)
        val low = Mat()
        Core.multiply(channel, Scalar(12.92), low)
        val highTmp = Mat()
        Core.pow(channel, 1.0 / 2.4, highTmp)
        Core.multiply(highTmp, Scalar(1.055), highTmp)
        Core.subtract(highTmp, Scalar(0.055), highTmp)
        low.copyTo(channel, mask)
        val invMask = Mat()
        val zero = Mat(mask.rows(), mask.cols(), mask.type(), Scalar(0.0))
        Core.compare(mask, zero, invMask, Core.CMP_EQ)
        highTmp.copyTo(channel, invMask)
        mask.release(); thr.release(); low.release(); highTmp.release(); invMask.release(); zero.release()
    }

    linearToSrgb(ch[0])
    linearToSrgb(ch[1])
    linearToSrgb(ch[2])

    Core.max(ch[0], Scalar(0.0), ch[0])
    Core.min(ch[0], Scalar(1.0), ch[0])
    Core.max(ch[1], Scalar(0.0), ch[1])
    Core.min(ch[1], Scalar(1.0), ch[1])
    Core.max(ch[2], Scalar(0.0), ch[2])
    Core.min(ch[2], Scalar(1.0), ch[2])

    Core.merge(ch, srcF)
    ch.forEach { it.release() }

    val out8 = Mat()
    srcF.convertTo(out8, CvType.CV_8UC4, 255.0)
    val outBitmap = Bitmap.createBitmap(srcBitmap.width, srcBitmap.height, Bitmap.Config.ARGB_8888)
    Utils.matToBitmap(out8, outBitmap)
    src8.release(); srcF.release(); out8.release(); lum.release(); idx.release(); zeroMap.release(); map.release(); lumEqF.release(); hist.release(); histSmoothed.release(); cdfRow.release(); denom.release(); scale.release(); invRow.release(); idx2.release(); map2.release(); lumTargetF.release()
    return outBitmap
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    TheGreatEqualizerTheme {
        Greeting("Android")
    }
}