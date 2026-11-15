package com.wukong.phone

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import org.json.JSONObject
import org.json.JSONArray
import java.text.Normalizer
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PaddleOcrTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun image(text: List<String>, size: Float = 24f, dark: Boolean = false): Bitmap =
        Bitmap.createBitmap(1080,1600,Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap); canvas.drawColor(if (dark) Color.rgb(24,24,24) else Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (dark) Color.WHITE else Color.BLACK; textSize = size }
            text.forEachIndexed { i, line -> canvas.drawText(line,70f,130f+i*170f,paint) }
        }
    @Test fun tinyRecognizesSmallChineseAndReturnsCoordinatesOnOriginalImage() {
        val expected = listOf("悟空手机助手","在虚拟屏执行","接管并继续操作","网络连接已恢复","模型设置 DeepSeek","Base URL 自定义地址","Token 用量 123456","返回桌面 交还 Agent")
        fun normalized(value: String) = value.replace(Regex("\\s+"),"")
        repeat(3) {
            val bitmap = image(expected)
            val lines = ScreenOcr.recognize(context,bitmap)
            assertTrue(bitmap.isRecycled)
            expected.forEachIndexed { i,text ->
                val line = lines.single { normalized(it.text) == normalized(text) }
                val b = line.bounds
                assertTrue(b.left in 0..1000 && b.right in 0..1000 && b.top in 0..1000 && b.bottom in 0..1000)
                // Detection is resized to 960, but coordinates must map back to
                // the original 1080x1600 frame before normalization.
                val center = (b.top+b.bottom)/2
                val baseline = (130+i*170)*1000/1600
                assertTrue("Wrong vertical mapping for $text: $b",center in baseline-20..baseline+5)
                assertTrue("Wrong horizontal mapping: $b", b.left in 50..85)
            }
        }
    }
    @Test fun benchmarkBundledTinyOnReferenceImages() {
        val expected = listOf("悟空手机助手","在虚拟屏执行","接管并继续操作","网络连接已恢复","模型设置 DeepSeek","Base URL 自定义地址","Token 用量 123456","返回桌面 交还 Agent")
        val real = listOf("悟空","Android · 0.4","任务","历史","设置","日志","把这件事，","交给悟空。","说清楚目标，悟空可以跨应用帮你完成。","执行引擎已连接","在虚拟屏执行","手机内激活","从哪里开始（可选）","桌面 · 整机操作","你想做什么","例如：搜索无线耳机，告诉我前几条结果","普通操作自动执行；支付、删除等敏感操作需你确认，可随时停止。","开始任务","停止当前任务","执行记录")
        fun norm(value: String) = Normalizer.normalize(value,Normalizer.Form.NFKC).replace(Regex("[\\p{P}\\s]+"),"")
        val samples = JSONArray()
        val report = JSONObject().put("engine",ScreenOcr.ENGINE).put("device",android.os.Build.MODEL).put("samples",samples)
        for ((name,size,dark) in listOf(Triple("ui-normal",42f,false),Triple("ui-small",24f,false),Triple("ui-dark",32f,true),Triple("wukong-task",0f,false))) {
            val template = if (size > 0) image(expected,size,dark) else {
                val original = InstrumentationRegistry.getInstrumentation().context.assets.open("ocr-wukong-task.png").use { BitmapFactory.decodeStream(it) }
                Bitmap.createScaledBitmap(original,720,1600,true).also { if(it !== original) original.recycle() }
            }
            val wanted = if (size > 0) expected else real
            val runs = JSONArray()
            samples.put(JSONObject().put("name",name).put("expected",JSONArray(wanted)).put("runs",runs))
            try {
                repeat(6) {
                    val bitmap = template.copy(Bitmap.Config.ARGB_8888,false)
                    val start = SystemClock.elapsedRealtimeNanos()
                    val lines = ScreenOcr.recognize(context,bitmap)
                    val elapsed = (SystemClock.elapsedRealtimeNanos()-start)/1e6
                    val recognized = lines.map { norm(it.text) }.toSet()
                    val count = wanted.count { norm(it) in recognized }
                    runs.put(JSONObject().put("ms",elapsed).put("matched",count).put("lines",JSONArray(lines.map { JSONObject().put("text",it.text).put("bounds",it.bounds.toJson()) })))
                    context.openFileOutput("paddle-ocr-benchmark.json",0).use { file -> file.write(report.toString(2).toByteArray()) }
                    assertEquals("OCR reference lines: $name",wanted.size,count)
                }
            } finally { template.recycle() }
        }
    }
    @Test fun recognizedProtectedPromptIsRejectedAndOwnedBitmapIsReleased() {
        val bitmap = image(listOf("请输入密码"),48f)
        try {
            ScreenOcr.recognize(context,bitmap)
            fail("Protected prompt must not become model context")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message!!.contains("受保护输入"))
        }
        assertTrue(bitmap.isRecycled)
    }
}
