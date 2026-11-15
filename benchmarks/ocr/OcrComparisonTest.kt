package com.wukong.ocrbenchmark
import android.graphics.*
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.android.gms.tasks.Tasks
import com.paddle.ocr.PaddleOCR
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class OcrComparisonTest {
 @Test fun compareSameImages() = runBlocking {
  val ctx=InstrumentationRegistry.getInstrumentation().targetContext
  val google=TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
  System.loadLibrary("opencv_java4")
  val tuned=InstrumentationRegistry.getArguments().getString("tuned")=="true"
  val paddle=if(tuned) PaddleOCR.create(ctx,com.paddle.ocr.PaddleOCRConfig(detLimitSideLen=960,detLimitType="max")) else PaddleOCR.create(ctx)
  val report=JSONObject().put("device",android.os.Build.MODEL).put("paddleColdLoadMs",paddle.coldLoadTimeMs).put("tuned960",tuned)
  val samples=JSONArray()
  val strings=listOf("悟空手机助手", "在虚拟屏执行", "接管并继续操作", "网络连接已恢复", "模型设置 DeepSeek", "Base URL 自定义地址", "Token 用量 123456", "返回桌面 交还 Agent")
  val cases=mutableListOf<Triple<String,Float,Boolean>>()
  cases.addAll(listOf(Triple("ui-normal",42f,false),Triple("ui-small",24f,false),Triple("ui-dark",32f,true)))
  cases.add(Triple("wukong-task",0f,false))
  for ((name,size,dark) in cases) {
   val original=if(size==0f) ctx.assets.open(name+".png").use { BitmapFactory.decodeStream(it) } else Bitmap.createBitmap(1080,1600,Bitmap.Config.ARGB_8888)
   val bitmap=if(tuned && original.height>1600) Bitmap.createScaledBitmap(original,original.width*1600/original.height,1600,true).also { original.recycle() } else original
   val canvas=if(size==0f) null else Canvas(bitmap); canvas?.drawColor(if(dark) Color.rgb(24,24,24) else Color.WHITE)
   val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=if(dark) Color.WHITE else Color.BLACK; textSize=size; typeface=Typeface.DEFAULT }
   strings.forEachIndexed { index,text -> canvas?.drawText(text,70f,130f+index*170f,paint) }
   ctx.openFileOutput(name+".png",0).use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
   val sample=JSONObject().put("name",name).put("expected",JSONArray(if(size==0f) emptyList<String>() else strings))
   for(engine in listOf("mlkit","paddle-v5-mobile")) {
    val times=JSONArray(); var text=listOf<String>(); var boxes=JSONArray()
    repeat(6) {
     val start=SystemClock.elapsedRealtimeNanos()
     if(engine=="mlkit") {
      val result=Tasks.await(google.process(InputImage.fromBitmap(bitmap,0)),30,TimeUnit.SECONDS)
      val lines=result.textBlocks.flatMap { it.lines }; text=lines.map { it.text }
      boxes=JSONArray(lines.map { line -> JSONObject().put("text",line.text).put("rect",line.boundingBox.toString()) })
     } else {
      val result=paddle.recognize(bitmap); text=result.results.map { it.text }
      boxes=JSONArray(result.results.map { line -> JSONObject().put("text",line.text).put("confidence",line.confidence).put("points",line.box.points.toString()) })
     }
     times.put((SystemClock.elapsedRealtimeNanos()-start)/1_000_000.0)
    }
    sample.put(engine,JSONObject().put("ms",times).put("text",JSONArray(text)).put("boxes",boxes))
   }
   samples.put(sample); bitmap.recycle()
  }
  report.put("samples",samples)
  ctx.openFileOutput(if(tuned) "comparison-tuned.json" else "comparison.json",0).use { it.write(report.toString(2).toByteArray()) }
  paddle.release(); google.close()
 }
}
