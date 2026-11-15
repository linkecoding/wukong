package com.wukong.phone

import android.app.ActivityOptions
import android.content.Intent
import android.os.SystemClock
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wukong.core.Phase
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real installed applications, real saved model, standard TaskRuntime and unrestricted AUTO tools. */
@RunWith(AndroidJUnit4::class)
class RealWorldVirtualTest {
    @Test fun runMultiStepTasksInRealAppsOnVirtualDisplay() {
        val i=InstrumentationRegistry.getInstrumentation();val c=i.targetContext
        assumeTrue(InstrumentationRegistry.getArguments().getString("live_model")=="true")
        assumeTrue(!TaskRuntime.active)
        val cases=linkedMapOf(
            "calculator" to ("com.google.android.calculator" to "用手机计算器核对购物预算：128元的商品买3件，59元的商品买2件，合计打八五折（支付原价的85%）。请在计算器实际输入并得到折后金额，再用500减去折后金额，得到剩余预算。告诉我两个结果，最后让计算器停在剩余预算的结果页。不要只心算回答。"),
            "files" to ("com.google.android.apps.nbu.files" to "在文件极客中找到 Download/Wukong-QA-20261115 文件夹，比较里面三个文件的大小，按大小从大到小排列，再打开最大文件的详细信息。告诉我最大文件的名称、大小和所在位置，最后停在详细信息页。不要打开文件内容，不要改名、删除、移动或分享任何文件。"),
            "stopwatch" to ("com.google.android.deskclock" to "在时钟 App 使用秒表：开始计时，至少3秒后按一次计次，再继续至少2秒后暂停。读取总用时和计次结果告诉我，最后重置归零并保持未运行状态。不要创建或修改闹钟，不要使用倒计时。"),
            "device_info" to ("com.android.settings" to "帮我核对手机信息：进入关于手机里的 Android 版本页面，读出 Android 版本和 Android 安全更新日期；再回到设置找到存储空间，读出已用容量和总容量。汇总这四项，最后停在存储空间页面。不修改任何设置。")
        )
        val selected=InstrumentationRegistry.getArguments().getString("task_case")
        val enabled=VirtualScreenState.enabled(c);val logs=RunLogStore(c);val logging=logs.enabled;logs.enabled=true
        val main=i.startActivitySync(Intent(c,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle())
        val report=JSONArray()
        File(c.filesDir,"real-world-report${selected?.let { "-$it" }.orEmpty()}.json").delete()
        fun ui(block: () -> Unit)=i.runOnMainSync(block)
        fun await(label: String,condition: () -> Boolean) {
            val until=SystemClock.elapsedRealtime()+30000
            while(!condition()&&SystemClock.elapsedRealtime()<until)Thread.sleep(100)
            assertTrue(label,condition())
        }
        try {
            ui { main.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);VirtualScreenState.enabled(c,true);VirtualScreenState.start(c) }
            await("Virtual display ready") { VirtualScreenState.displayId>0&&VirtualScreenState.bitmap!=null }
            val display=VirtualScreenState.displayId
            val settings=SettingsStore(c).load()!!
            for((name,spec) in cases) {
                if(selected!=null&&selected!=name)continue
                val started=SystemClock.elapsedRealtime()
                val seen=StringBuilder();var approval=false;var discountObserved=false;var exactRemainder=false
                ui { TaskRuntime.start(c,spec.second,spec.first,settings) }
                ShellSession(c,display).use { monitoring ->
                    val observer=AndroidDriver(monitoring)
                    while(TaskRuntime.active&&SystemClock.elapsedRealtime()-started<240000) {
                        if(TaskRuntime.pending!=null) { approval=true;TaskRuntime.stop();break }
                        runCatching {
                            val current=observer.observeNow()
                            if(current.nodes.any { (it.resourceId.endsWith("/result_final")||it.resourceId.endsWith("/result_preview"))&&it.rawText=="426.7" })discountObserved=true
                            current.nodes.joinToString("\n") { it.text }
                        }.onSuccess {
                            if(seen.length<120000)seen.append(it).append('\n')
                        }
                        Thread.sleep(450)
                    }
                }
                if(TaskRuntime.active)TaskRuntime.stop()
                await("Task stops") { !TaskRuntime.active }
                var finalText="";var finalPackage=""
                ShellSession(c,display).use { session ->
                    runCatching {
                        val screen=AndroidDriver(session).observe();finalPackage=screen.packageName;finalText=screen.nodes.joinToString("\n") { it.text }
                        exactRemainder=screen.nodes.any { it.resourceId.endsWith("/result_final")&&it.rawText=="73.3" }
                        val packet=session.request("capture",JSONObject().put("package",screen.packageName).put("fingerprint",screen.fingerprint)) as JSONObject
                        File(c.filesDir,"real-world-$name.jpg").writeBytes(android.util.Base64.decode(packet.getString("jpeg"),android.util.Base64.NO_WRAP))
                    }
                }
                val record=HistoryStore(c).read().first();val trace=record.logName?.let { logs.read(it) }.orEmpty()
                val result=TaskRuntime.event.message
                val acceptedTaps=mutableListOf<Pair<Long,String>>()
                var target:JSONObject?=null
                for(event in trace) {
                    val data=event.getJSONObject("data")
                    if(event.optString("type")=="device_action")target=data.optJSONObject("target")
                    if(event.optString("type")=="device_error")target=null
                    if(event.optString("type")=="device_result"&&data.optJSONObject("action")?.optString("kind")=="tap") {
                        target?.let { node -> acceptedTaps+=event.getLong("time") to node.optString("rawText").ifBlank { node.optString("text") } };target=null
                    }
                }
                val start=acceptedTaps.lastOrNull { Regex("^开始$|^start$",RegexOption.IGNORE_CASE).matches(it.second) }
                val laps=acceptedTaps.filter { it.first>(start?.first ?: Long.MAX_VALUE)&&Regex("跑圈|计次|^lap$",RegexOption.IGNORE_CASE).containsMatchIn(it.second) }
                val startedClock=start
                val stoppedClock=laps.singleOrNull()?.let { lap -> acceptedTaps.firstOrNull { it.first>lap.first&&Regex("^停止$|^暂停$|^stop$|^pause$",RegexOption.IGNORE_CASE).matches(it.second) } }
                val clockSequence=laps.size==1&&startedClock!=null&&stoppedClock!=null&&laps.single().first-startedClock.first>=3000&&stoppedClock.first-laps.single().first>=2000
                val objective=when(name) {
                    "calculator" -> finalPackage==spec.first&&exactRemainder&&discountObserved&&result.contains("426.7")&&result.contains("73.3")
                    "files" -> finalPackage==spec.first&&finalText.contains("wukong-meeting.txt")&&result.contains("wukong-meeting.txt")&&finalText.contains("Wukong-QA-20261115")
                    "stopwatch" -> finalPackage==spec.first&&Regex("0[:：]00").containsMatchIn(finalText)&&(seen.contains("计次")||seen.contains("跑圈"))&&clockSequence
                    else -> finalPackage==spec.first&&finalText.contains("存储")&&result.contains(android.os.Build.VERSION.RELEASE)&&result.contains(android.os.Build.VERSION.SECURITY_PATCH.substringBefore("-"))
                }
                val row=JSONObject().put("case",name).put("phase",TaskRuntime.event.phase.name).put("result",result).put("objectivePassed",objective).put("approvalRequested",approval)
                    .put("durationMs",SystemClock.elapsedRealtime()-started).put("finalPackage",finalPackage).put("finalText",finalText.take(10000)).put("logName",record.logName)
                    .put("acceptedLapPresses",laps.size).put("clockSequence",clockSequence).put("discountObserved",discountObserved).put("exactRemainder",exactRemainder)
                    .put("toolCalls",trace.count { it.optString("type")=="tool_call" }).put("scriptCalls",trace.count { it.optString("type").startsWith("script_") })
                report.put(row);File(c.filesDir,"real-world-report${selected?.let { "-$it" }.orEmpty()}.json").writeText(report.toString(2))
            }
            assertTrue("At least one selected case must run",report.length()>0)
            val failures=(0 until report.length()).map { report.getJSONObject(it) }.filter { it.getString("phase")!="COMPLETED"||!it.getBoolean("objectivePassed")||it.getBoolean("approvalRequested") }
            assertTrue(failures.joinToString { "${it.getString("case")}: ${it.getString("result")}" },failures.isEmpty())
        } finally {
            TaskRuntime.stop();await("Stopped") { !TaskRuntime.active }
            ui { VirtualScreenState.stop(c);main.finish() };VirtualScreenState.enabled(c,enabled);logs.enabled=logging
        }
    }
}
