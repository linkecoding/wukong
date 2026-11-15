package com.wukong.ocrbenchmark

import android.content.Intent
import android.content.IntentFilter
import android.content.Context
import android.graphics.*
import android.os.PowerManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.android.gms.tasks.Tasks
import com.paddle.ocr.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class V6ComparisonTest {
 @Test fun compareOnPhysicalDevice() = runBlocking {
  val ctx=InstrumentationRegistry.getInstrumentation().targetContext
  val engine=InstrumentationRegistry.getArguments().getString("engine") ?: error("engine required")
  require(engine in listOf("mlkit","v5-mobile","v6-tiny","v6-small"))
  fun temperature()=ctx.registerReceiver(null,IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.getIntExtra("temperature",-1)
  val power=ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
  val report=JSONObject().put("engine",engine).put("device",android.os.Build.MODEL)
   .put("build",android.os.Build.FINGERPRINT).put("threads",4).put("detLimit",960)
   .put("batteryDeciCStart",temperature()).put("thermalStart",power.currentThermalStatus)
  val initStart=SystemClock.elapsedRealtimeNanos()
  val google=if(engine=="mlkit") TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) else null
  val paddle=if(engine!="mlkit") {
   System.loadLibrary("opencv_java4")
   PaddleOCR.create(ctx,PaddleOCRConfig(detLimitSideLen=960,detLimitType="max"),EngineConfig(numThreads=4),
    "models/$engine/det/inference.onnx","models/$engine/rec/inference.onnx","models/$engine/rec/inference.yml")
  } else null
  report.put("createMs",(SystemClock.elapsedRealtimeNanos()-initStart)/1e6)
  paddle?.let { report.put("modelLoadMs",it.coldLoadTimeMs) }
  val samples=JSONArray();report.put("samples",samples)
  fun save()=ctx.openFileOutput("comparison-$engine.json",0).use { it.write(report.toString(2).toByteArray()) }
  val fixed=listOf("悟空手机助手","在虚拟屏执行","接管并继续操作","网络连接已恢复","模型设置 DeepSeek","Base URL 自定义地址","Token 用量 123456","返回桌面 交还 Agent")
  val real=listOf("悟空","Android · 0.4","任务","历史","设置","日志","把这件事，","交给悟空。","说清楚目标，悟空可以跨应用帮你完成。","执行引擎已连接","在虚拟屏执行","手机内激活","从哪里开始（可选）","桌面 · 整机操作","你想做什么","例如：搜索无线耳机，告诉我前几条结果","普通操作自动执行；支付、删除等敏感操作需你确认，可随时停止。","开始任务","停止当前任务","执行记录")
  try {
   for((name,size,dark) in listOf(Triple("ui-normal",42f,false),Triple("ui-small",24f,false),Triple("ui-dark",32f,true),Triple("wukong-task",0f,false))) {
    val bitmap=if(size==0f) {
     val raw=ctx.assets.open("wukong-task.png").use { BitmapFactory.decodeStream(it) }
     Bitmap.createScaledBitmap(raw,720,1600,true).also { if(it!==raw) raw.recycle() }
    } else Bitmap.createBitmap(1080,1600,Bitmap.Config.ARGB_8888).also { image ->
     val canvas=Canvas(image);canvas.drawColor(if(dark) Color.rgb(24,24,24) else Color.WHITE)
     val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=if(dark) Color.WHITE else Color.BLACK;textSize=size;typeface=Typeface.DEFAULT }
     fixed.forEachIndexed { i,text -> canvas.drawText(text,70f,130f+i*170f,paint) }
    }
    val sample=JSONObject().put("name",name).put("width",bitmap.width).put("height",bitmap.height)
     .put("expected",JSONArray(if(size==0f) real else fixed))
    val runs=JSONArray();sample.put("runs",runs);samples.put(sample)
    repeat(6) { index ->
     val start=SystemClock.elapsedRealtimeNanos()
     val lines=JSONArray()
     var stages: JSONObject?=null
     if(google!=null) {
      val result=Tasks.await(google.process(InputImage.fromBitmap(bitmap,0)),30,TimeUnit.SECONDS)
      result.textBlocks.flatMap { it.lines }.forEach { line -> lines.put(JSONObject().put("text",line.text).put("rect",line.boundingBox.toString())) }
     } else {
      val result=paddle!!.recognize(bitmap)
      result.results.forEach { line -> lines.put(JSONObject().put("text",line.text).put("confidence",line.confidence).put("points",line.box.points.toString())) }
      stages=JSONObject().put("detectionMs",result.detectionTimeMs).put("recognitionMs",result.recognitionTimeMs)
     }
     runs.put(JSONObject().put("ms",(SystemClock.elapsedRealtimeNanos()-start)/1e6).put("lines",lines).put("stages",stages))
     save()
     android.util.Log.i("WukongOcrBenchmark","$engine $name run=$index completed")
    }
    bitmap.recycle()
   }
   report.put("batteryDeciCEnd",temperature()).put("thermalEnd",power.currentThermalStatus)
   save()
  } finally { paddle?.release();google?.close() }
 }
}
