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
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
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
    var processedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var originalBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var showOriginal by remember { mutableStateOf(false) }
    var wheelHue by remember { mutableStateOf(0f) }
    var wheelSat by remember { mutableStateOf(0f) }
    val context = LocalContext.current
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

                val defaultWeights = Triple(0.2126, 0.7152, 0.0722)
                val weights = if (wheelSat <= 0f) defaultWeights else hsvToLinearRgbWeights(wheelHue, wheelSat)
                val outBitmap = processBitmapLinearLumaSmoothstep(srcBitmap, weights)
                originalBitmap = srcBitmap
                processedBitmap = outBitmap
            } catch (e: Exception) {
                Log.e("Processor", "Failed to process image", e)
                processedBitmap = null
            }
        } else {
            processedBitmap = null
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
            val bmp = processedBitmap
            if (bmp == null) {
                Toast.makeText(context, "No processed image to export", Toast.LENGTH_SHORT).show()
            } else {
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
                            bmp.compress(Bitmap.CompressFormat.JPEG, 95, out)
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

        val bmpToShow = if (showOriginal) originalBitmap else processedBitmap
        if (bmpToShow != null) {
            Image(
                bitmap = bmpToShow.asImageBitmap(),
                contentDescription = if (showOriginal) "Original image" else "Processed image",
                modifier = Modifier.pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            showOriginal = true
                            tryAwaitRelease()
                            showOriginal = false
                        }
                    )
                }
            )
        } else if (selectedUri != null) {
            Text("Image selected. Processing failed or pending.")
        }

        // HSV wheel below image
        HsvWheel(
            hue = wheelHue,
            saturation = wheelSat,
            onChange = { h, s ->
                wheelHue = h
                wheelSat = s
                val src = originalBitmap
                if (src != null) {
                    val weights = if (s <= 0f) Triple(0.2126, 0.7152, 0.0722) else hsvToLinearRgbWeights(h, s)
                    processedBitmap = processBitmapLinearLumaSmoothstep(src, weights)
                }
            },
            modifier = Modifier.padding(top = 12.dp)
        )
    }
}

@Composable
private fun HsvWheel(
    hue: Float,
    saturation: Float,
    onChange: (Float, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var sizePx by remember { mutableStateOf(0) }
    val wheelBitmap = remember(sizePx) {
        if (sizePx > 0) createHsvWheelBitmap(sizePx) else null
    }
    Box(
        modifier = modifier
            .padding(12.dp)
            .size(200.dp)
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

private fun processBitmapLinearLumaSmoothstep(
    srcBitmap: Bitmap,
    weights: Triple<Double, Double, Double>
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

    // Manual histogram equalization on luminance using 256-bin CDF-based LUT
    val lum8 = Mat()
    lum.convertTo(lum8, CvType.CV_8UC1, 255.0)
    val hist = Mat()
    Imgproc.calcHist(listOf(lum8), MatOfInt(0), Mat(), hist, MatOfInt(256), MatOfFloat(0f, 256f))
    // Build CDF and LUT
    var cdf = 0.0
    var cdfMin = -1.0
    val totalPix = lum8.rows() * lum8.cols()
    val lut = Mat(1, 256, CvType.CV_8UC1)
    var iBin = 0
    while (iBin < 256) {
        val h = hist.get(iBin, 0)[0]
        cdf += h
        if (cdfMin < 0.0 && cdf > 0.0) cdfMin = cdf
        val valEq = if (cdfMin > 0.0) ((cdf - cdfMin) * 255.0 / (totalPix - cdfMin)) else 0.0
        lut.put(0, iBin, kotlin.math.max(0.0, kotlin.math.min(255.0, valEq)))
        iBin++
    }
    val lum8Eq = Mat()
    Core.LUT(lum8, lut, lum8Eq)
    val lumEqF = Mat()
    lum8Eq.convertTo(lumEqF, CvType.CV_32FC1, 1.0 / 255.0)
    // Scale RGB (linear) to match equalized luminance
    val eps = 1e-6
    val denom = Mat()
    Core.add(lum, Scalar(eps), denom)
    val scale = Mat()
    Core.divide(lumEqF, denom, scale)
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
    src8.release(); srcF.release(); out8.release(); lum.release(); lum8.release(); lum8Eq.release(); lumEqF.release(); lut.release(); hist.release(); denom.release(); scale.release()
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