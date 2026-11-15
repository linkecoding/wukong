package com.wukong.phone

import android.content.Context
import android.graphics.Bitmap
import android.os.Looper
import com.paddle.ocr.EngineConfig
import com.paddle.ocr.PaddleOCRConfig
import com.paddle.ocr.engine.OCREngine
import com.wukong.core.Bounds
import com.wukong.core.OcrLine
import kotlin.math.ceil
import kotlin.math.floor

/** Owns each input bitmap until synchronous local inference has finished. */
object ScreenOcr {
    const val ENGINE = "PP-OCRv6_tiny"
    private var engine: OCREngine? = null

    // Serialize inference: ONNX sessions and native OpenCV resources are reused,
    // while cancellation cannot recycle an image that native code still owns.
    @Synchronized fun recognize(context: Context, bitmap: Bitmap): List<OcrLine> {
        try {
            check(Looper.myLooper() != Looper.getMainLooper()) { "OCR 必须在后台线程运行" }
            check(!Thread.currentThread().isInterrupted) { "识别已停止" }
            val recognizer = engine ?: run {
                System.loadLibrary("opencv_java4")
                OCREngine(context.applicationContext,
                    PaddleOCRConfig(detLimitSideLen = 960, detLimitType = "max"),
                    EngineConfig(numThreads = 4)).also { engine = it }
            }
            val width = bitmap.width; val height = bitmap.height
            val result = recognizer.run(bitmap)
            check(!Thread.currentThread().isInterrupted) { "识别已停止" }
            val lines = result.results.asSequence().filter { it.text.isNotBlank() }.take(250).mapNotNull { line ->
                val points = line.box.points
                if (points.any { !it.x.isFinite() || !it.y.isFinite() }) return@mapNotNull null
                val bounds = Bounds(
                    floor(points.minOf { it.x }.toDouble() * 1000 / width).toInt().coerceIn(0, 1000),
                    floor(points.minOf { it.y }.toDouble() * 1000 / height).toInt().coerceIn(0, 1000),
                    ceil(points.maxOf { it.x }.toDouble() * 1000 / width).toInt().coerceIn(0, 1000),
                    ceil(points.maxOf { it.y }.toDouble() * 1000 / height).toInt().coerceIn(0, 1000))
                if (bounds.right <= bounds.left || bounds.bottom <= bounds.top) null
                else OcrLine(line.text.take(500), bounds)
            }.toList()
            require(lines.none { protected.containsMatchIn(it.text) }) { "图像中包含受保护输入提示，请手动处理" }
            return lines
        } finally { bitmap.recycle() }
    }
    private val protected = Regex("(?:请输入|输入|确认|验证).{0,4}(?:密码|验证码)|one.time.code|enter.{0,8}password", RegexOption.IGNORE_CASE)
}
