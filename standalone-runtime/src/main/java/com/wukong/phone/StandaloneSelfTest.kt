package com.wukong.phone

import com.wukong.core.*
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Explicit offline regression entry point, run by adb shell without installing a test APK. */
object StandaloneSelfTest {
    @JvmStatic fun main(args:Array<String>) {
        check(android.os.Process.myUid()==2000)
        val directory=File(args[1]).apply { mkdirs();android.system.Os.chmod(path,448) }
        if(args[0]=="power") {
            @Suppress("DEPRECATION")
            val wake=com.wukong.shell.Main.shellContext().getSystemService(android.os.PowerManager::class.java).newWakeLock(android.os.PowerManager.SCREEN_DIM_WAKE_LOCK,"wukong:offline-power-test")
            wake.acquire(1000);check(wake.isHeld);wake.release();println("{\"shellWakeLock\":true}");kotlin.system.exitProcess(0)
        }
        if(args[0] in setOf("storage-write","storage-read")) {
            val store=HeadlessModelStore(directory,"wukong.standalone.model.selftest")
            val key="wukong-offline-fixture-not-a-provider-key"
            if(args[0]=="storage-write")store.save(ModelSettings("https://example.invalid","fixture",key))
            check(store.load()?.apiKey==key)
            check(!File(directory,"model-selftest.enc").readText(Charsets.ISO_8859_1).contains(key))
            println(JSONObject().put("uid",2000).put("encryptedPersistence",true).put("plaintextAbsent",true));return
        }
        val tokenFile=File(args[2]);val token=tokenFile.readText().trim();check(tokenFile.delete())
        val internal=MessageDigest.getInstance("SHA-256").digest((token+":shell").toByteArray()).joinToString("") { "%02x".format(it) }
        val rpc=Rpc(internal);val id=UUID.randomUUID().toString();val cancel=Cancellation();val control=ExecutionControl()
        val display=(rpc.request("virtualStart",JSONObject().put("ownerId","offline-test")) as JSONObject).getInt("displayId")
        rpc.request("setControl",JSONObject().put("manual",false));rpc.request("beginTask",JSONObject().put("taskId",id).put("displayId",display))
        val evidence=TaskEvidence();val log=RunLog(File(directory,"offline.jsonl"),"fixture-secret-never-real")
        val driver=HeadlessDriver(rpc,id,cancel,control,evidence,log)
        var stage=0;var coordinate=false
        val fixture=Model { _,screen,_ ->
            when(stage++) {
                0 -> Action.Screenshot
                1 -> {
                    val target=screen.nodes.firstOrNull { it.enabled&&it.bounds!=null&&Regex("网络|Network|连接|Connected",RegexOption.IGNORE_CASE).containsMatchIn(it.text) }
                        ?:error("No suitable settings target")
                    val bounds=target.bounds!!;coordinate=true
                    Action.TapAt((bounds.left+bounds.right)/2,(bounds.top+bounds.bottom)/2,screen.visual!!.id)
                }
                2 -> Action.Home
                3 -> Action.Launch("com.android.chrome")
                else -> Action.Finish("Offline native fixture completed",evidence=listOf(ScreenCondition(packageName="com.android.chrome")))
            }
        }
        try {
            val result=Agent(fixture,driver,Approval { _,_->error("Unexpected high-risk request") },completionVerifier=CompletionVerifier { _,_,screen,_ -> CompletionCheck(screen.packageName=="com.android.chrome","native package verified") }).run("Offline settings screenshot, coordinate tap, return, then launch Chrome",cancel) { }
            check(result.phase==Phase.COMPLETED&&coordinate) { result.message }
            rpc.request("setControl",JSONObject().put("manual",true))
            rpc.request("manualInput",JSONObject().put("kind","home"))
            val frame=rpc.request("virtualFrame") as JSONObject
            check(frame.getString("jpeg").isNotBlank())
            rpc.request("setControl",JSONObject().put("manual",false))
            println(JSONObject().put("offlineFixture",true).put("plannerLocation","phone").put("uid",2000).put("virtualDisplay",display)
                .put("snapshotBoundCoordinateTap",coordinate).put("manualHome",true).put("frame",true).put("installedAppRequired",false))
        } finally { rpc.request("finishTask",JSONObject().put("taskId",id)) }
    }
}
