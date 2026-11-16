package com.wukong.phone

import com.wukong.core.*
import org.json.JSONObject
import java.net.Socket
import java.net.InetSocketAddress
import java.io.File

class RunLog(private val file: File, private val secret: String) {
    @Volatile var step = 0
    @Synchronized fun record(type: String, data: JSONObject = JSONObject()) {
        if (file.length() >= 512*1024) return
        data.put("step", step)
        val text = JSONObject().put("time",System.currentTimeMillis()).put("type",type).put("data",data).toString().replace(secret,"[redacted]")
        file.appendText(text+"\n")
    }
}
internal class Rpc(private val token: String) {
    fun request(method: String, fields: JSONObject = JSONObject()): Any = Socket().use { s ->
        s.connect(InetSocketAddress("127.0.0.1",43229),1500); s.soTimeout=30000
        s.getOutputStream().write((fields.put("method",method).put("token",token).toString()+"\n").toByteArray())
        val input=s.getInputStream().buffered(); val out=java.io.ByteArrayOutputStream()
        while(out.size()<4*1024*1024) { val b=input.read(); if(b<0||b==10)break;out.write(b) }
        check(out.size()<4*1024*1024)
        val value=JSONObject(out.toString("UTF-8"));check(value.getBoolean("ok")) { value.optString("error") };value.get("result")
    }
}
internal class HeadlessDriver(private val rpc:Rpc, private val id:String, private val cancellation:Cancellation,
    private val control:ExecutionControl, private val evidence:TaskEvidence, private val log:RunLog):DeviceDriver {
    private val settler=ScreenSettler()
    private var visual:VisualFrame?=null
    private fun read():Screen { cancellation.check();return (rpc.request("observe",JSONObject().put("taskId",id)) as JSONObject).toScreen() }
    override fun observeNow():Screen=read().let { screen -> if(visualFingerprint!=screen.fingerprint)visual=null;screen.copy(visual=visual) }
    private var visualFingerprint=""
    override fun observe():Screen=settler.observe(::read,cancellation::check).also(evidence::read).let { screen ->
        if(visualFingerprint!=screen.fingerprint)visual=null;screen.copy(visual=visual)
    }
    override fun execute(action:Action, expected:Screen):String {
        cancellation.check()
        if(action is Action.Query||action is Action.WaitUntil||action is Action.Verify) {
            val epoch=control.epoch
            return Inspector(::read,{ cancellation.check();check(!control.paused&&control.epoch==epoch) { "用户正在接管" } }).run(action).toJson().toString()
        }
        if(action is Action.Wait) { Thread.sleep(action.milliseconds);return "waited" }
        val fields=JSONObject().put("taskId",id).put("package",expected.packageName).put("fingerprint",expected.fingerprint)
        if(action is Action.Screenshot) {
            val packet=rpc.request("capture",fields) as JSONObject
            visual=VisualFrame(packet.getString("captureId"),packet.getInt("width"),packet.getInt("height"),emptyList(),packet.getString("jpeg"))
            visualFingerprint=expected.fingerprint
            return JSONObject().put("captureId",visual!!.id).put("width",visual!!.width).put("height",visual!!.height).toString()
        }
        check(action !is Action.Ocr&&action !is Action.Script) { "Standalone runtime does not provide local OCR or JavaScript" }
        fields.put("action",action.toJson())
        if(expected.interactionFingerprint.isNotBlank()&&!SensitiveActions.requiresApproval(action,expected)&&action !is Action.TapAt&&action !is Action.Swipe&&action !is Action.LongPress)
            fields.put("interactionFingerprint",expected.interactionFingerprint)
        log.record("device_action",JSONObject().put("action",action.toJson()).put("package",expected.packageName))
        val result=rpc.request("execute",fields).toString();evidence.accepted(action,expected);visual=null
        log.record("device_result",JSONObject().put("result",result));return result
    }
}
fun Bounds.toJson()=JSONObject().put("left",left).put("top",top).put("right",right).put("bottom",bottom)
