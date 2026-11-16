package com.wukong.phone

import android.os.Looper
import com.wukong.core.*
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.net.ServerSocket
import java.net.InetAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.*
import java.security.MessageDigest

/** Loaded by app_process, never installed. All planning/model traffic stays on Android. */
object StandaloneMain {
    @JvmStatic fun main(args:Array<String>) {
        check(android.os.Process.myUid()==2000)
        require(args.size==2)
        Looper.prepareMainLooper()
        val tokenFile=File(args[0]);val token=tokenFile.readText().trim();require(token.matches(Regex("[a-f0-9]{64}")))
        check(tokenFile.delete())
        val directory=File(args[1]).apply { mkdirs();android.system.Os.chmod(this.path,448) }
        File(directory.parentFile,"runtime.pid").apply { writeText(android.os.Process.myPid().toString());android.system.Os.chmod(this.path,384) }
        File(directory.parentFile,"access.token").apply { writeText(token);android.system.Os.chmod(this.path,384) }
        val internalToken=MessageDigest.getInstance("SHA-256").digest((token+":shell").toByteArray()).joinToString("") { "%02x".format(it) }
        Thread({ com.wukong.shell.Main.startStandalone(43229,internalToken) },"wukong-device-executor").start()
        val rpc=Rpc(internalToken)
        var ready=false
        repeat(50) { if(!ready)try { rpc.request("status");ready=true } catch(_:Exception) { Thread.sleep(100) } }
        check(ready) { "Device executor unavailable" }
        StandaloneServer(token,directory,rpc).serve()
        kotlin.system.exitProcess(0)
    }
}

internal class StandaloneServer(private val token:String,private val directory:File,private val rpc:Rpc) {
    private val modelStore=HeadlessModelStore(directory)
    @Volatile private var settings:ModelSettings?=modelStore.load()
    @Volatile private var activeId:String?=null
    @Volatile private var cancellation:Cancellation?=null
    @Volatile private var model:ChatModel?=null
    @Volatile private var worker:Thread?=null
    @Volatile private var control=ExecutionControl()
    @Volatile private var pending:CompletableFuture<Boolean>?=null
    @Volatile private var pendingId:String?=null
    @Volatile private var display=0
    @Volatile private var closed=false
    private val lock=Any()
    private val events=mutableListOf<JSONObject>()
    private val pool=ThreadPoolExecutor(4,4,0,TimeUnit.SECONDS,ArrayBlockingQueue(12))
    private val executor=Executors.newSingleThreadExecutor()
    private var listener:ServerSocket?=null
    private fun file(id:String):File { require(id.matches(Regex("[a-f0-9-]{36}")));UUID.fromString(id);return File(directory,"$id.json") }
    private fun save(id:String,value:JSONObject) {
        val temporary=File(directory,"$id.tmp");temporary.writeText(value.toString());android.system.Os.chmod(temporary.path,384)
        check(temporary.renameTo(file(id)))
    }
    private fun receipt(id:String):JSONObject=synchronized(lock) {
        if(!file(id).isFile)throw NoSuchElementException("Unknown task")
        val value=JSONObject(file(id).readText())
        if(value.optString("phase") in setOf("RUNNING","PAUSED","AWAITING_APPROVAL","STOPPING")&&activeId!=id) {
            value.put("phase","INTERRUPTED").put("result","Runtime restarted. Inspect the phone before creating a new request.");save(id,value)
        }
        if(activeId==id) value.put("events",JSONArray(events.takeLast(30))).put("displayId",display)
            .put("pendingApproval",pendingId?.let { JSONObject().put("id",it).put("message",value.optString("message")) } ?: JSONObject.NULL)
        value.remove("requestHash");value
    }
    private fun update(id:String,phase:String,message:String,step:Int=0) = synchronized(lock) {
        val value=JSONObject(file(id).readText()).put("phase",phase).put("message",message).put("step",step)
        if(phase in setOf("COMPLETED","FAILED","CANCELLED"))value.put("result",message).put("finishedAt",System.currentTimeMillis())
        save(id,value);events+=JSONObject().put("phase",phase).put("message",message).put("step",step);while(events.size>100)events.removeAt(0)
    }
    private fun hash(body:JSONObject):String {
        val request=JSONObject().put("goal",body.getString("goal").trim()).put("display",body.optString("display","virtual")).put("initialPackage",body.optString("initialPackage",""))
        return MessageDigest.getInstance("SHA-256").digest(request.toString().toByteArray()).joinToString("") { "%02x".format(it) }
    }
    private fun start(body:JSONObject):JSONObject=synchronized(lock) {
        check(!closed)
        val id=body.getString("id");val target=file(id);val requestHash=hash(body)
        if(target.exists()) {
            check(JSONObject(target.readText()).getString("requestHash")==requestHash) { "Request ID already belongs to another task" };return@synchronized receipt(id)
        }
        check(activeId==null) { "Another task is running" }
        val goal=body.getString("goal").trim();require(goal.length in 1..4000)
        val chosen=settings ?: error("Configure an execution model first")
        val state=rpc.request("deviceState") as JSONObject;require(state.getBoolean("interactive")&&!state.getBoolean("locked")) { "Wake and unlock the phone first" }
        val mode=body.optString("display","virtual");require(mode in setOf("virtual","physical"))
        require(mode!="virtual"||android.os.Build.VERSION.SDK_INT>=30) { "Virtual display requires Android 11+" }
        display=if(mode=="virtual") (rpc.request("virtualStart",JSONObject().put("ownerId","standalone")) as JSONObject).getInt("displayId") else 0
        if(display>0)rpc.request("setControl",JSONObject().put("manual",false))
        val app=body.optString("initialPackage","")
        require(app.isBlank()||app.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")))
        save(id,JSONObject().put("id",id).put("goal",goal).put("requestHash",requestHash).put("display",mode).put("createdAt",System.currentTimeMillis())
            .put("phase","RUNNING").put("result","").put("backend","standalone").put("plannerLocation","phone"))
        activeId=id;events.clear();control=ExecutionControl();val cancel=Cancellation();cancellation=cancel
        executor.submit {
            worker=Thread.currentThread()
            val logFile=File(directory,"$id.jsonl").apply { createNewFile();android.system.Os.chmod(this.path,384) }
            val trace=RunLog(logFile,chosen.apiKey)
            var wake:android.os.PowerManager.WakeLock?=null
            try {
                val apps=rpc.request("apps") as JSONArray
                val appList=(0 until apps.length()).map { apps.getJSONObject(it).let { it.getString("package") to it.getString("name") } }
                val evidence=TaskEvidence()
                val tools=setOf("phone_action","phone_query","phone_wait_until","phone_verify","phone_screenshot","phone_tap_at","phone_swipe","phone_long_press")
                val planner=ChatModel(chosen,appList,trace,PlanningMode.TOOLS_ONLY,taskEvidence=evidence,availableTools=tools);model=planner
                rpc.request("beginTask",JSONObject().put("taskId",id).put("displayId",display))
                val context=com.wukong.shell.Main.shellContext()
                @Suppress("DEPRECATION")
                val level=android.os.PowerManager.SCREEN_DIM_WAKE_LOCK
                wake=context.getSystemService(android.os.PowerManager::class.java).newWakeLock(level,"wukong:standalone-task").apply { setReferenceCounted(false);acquire(15*60*1000L) }
                val driver=HeadlessDriver(rpc,id,cancel,control,evidence,trace)
                control.awaitAgent(cancel)
                if(app.isNotBlank())driver.execute(Action.Launch(app),driver.observe())
                val result=Agent(planner,driver,Approval { action,screen ->
                    val permission=CompletableFuture<Boolean>();val nonce=UUID.randomUUID().toString()
                    pending=permission;pendingId=nonce
                    val label=(action as? Action.Tap)?.let { tap -> screen.nodes.firstOrNull { it.id==tap.nodeId }?.text }.orEmpty()
                    update(id,"AWAITING_APPROVAL",action.label()+" · "+label+" · "+screen.packageName,trace.step)
                    try { permission.get(120,TimeUnit.SECONDS) } catch(_:TimeoutException) { false }
                    finally { pending=null;pendingId=null }
                },maxSteps=50,control=control,completionVerifier=CompletionVerifier(planner::verifyCompletion)).run(goal,cancel) { event ->
                    update(id,event.phase.name,event.message,event.step);trace.record("agent_event",JSONObject().put("phase",event.phase.name).put("message",event.message))
                }
                update(id,result.phase.name,result.message,result.step)
            } catch(_:InterruptedException) { update(id,"CANCELLED","Task stopped") }
            catch(error:Exception) { update(id,"FAILED",error.message.orEmpty().replace(chosen.apiKey,"[redacted]")) }
            finally {
                Thread.interrupted();runCatching { rpc.request("finishTask",JSONObject().put("taskId",id)) }
                wake?.let { if(it.isHeld)it.release() }
                synchronized(lock) { pending=null;pendingId=null;activeId=null;cancellation=null;model=null;worker=null }
            }
        }
        receipt(id)
    }
    private fun cancel() { cancellation?.cancel();pending?.complete(false);worker?.interrupt();model?.cancel();activeId?.let { id -> Thread { runCatching { rpc.request("cancelTask",JSONObject().put("taskId",id)) } }.start() } }
    private fun modelSettings(body:JSONObject):ModelSettings {
        val old=settings
        val endpoint=body.getString("endpoint").trim();val provider=body.optString("providerId","custom")
        val key=body.optString("key").ifBlank { require(old!=null&&old.endpoint==endpoint&&old.providerId==provider) { "Enter an API key for this endpoint" };old.apiKey }
        return ModelSettings(endpoint,body.optString("model","discovery"),key,body.optBoolean("useFullEndpoint"),body.optBoolean("thinkingEnabled"),provider).also { it.validate() }
    }
    private fun modelInfo():JSONObject=JSONObject().put("configured",settings!=null).put("endpoint",settings?.endpoint ?: "").put("model",settings?.model ?: "")
        .put("providerId",settings?.providerId ?: "custom").put("thinkingEnabled",settings?.thinkingEnabled ?: false).put("effectiveThinking",settings?.effectiveThinking ?: false)
        .put("useFullEndpoint",settings?.useFullEndpoint ?: false).put("keyPersistence","android-keystore")
        .put("presets",JSONArray(ModelPresets.all.map { JSONObject().put("id",it.id).put("endpoint",it.endpoint).put("model",it.model).put("platform",it.platform) }))
    fun serve() {
        listener=ServerSocket(39744,12,InetAddress.getByName("127.0.0.1"))
        while(!closed) {
            val socket=try { listener!!.accept() } catch(_:Exception) { break }
            try { pool.execute { socket.use { handle(it) } } } catch(_:RejectedExecutionException) { socket.close() }
        }
        pool.shutdown();pool.awaitTermination(3,TimeUnit.SECONDS)
        executor.shutdownNow();executor.awaitTermination(3,TimeUnit.SECONDS)
        runCatching { rpc.request("virtualStop") }
    }
    private fun handle(socket:Socket) {
        var body=JSONObject()
        try {
            socket.soTimeout=5000
            val input=socket.getInputStream().buffered();var total=0
            fun line():String {
                val bytes=java.io.ByteArrayOutputStream()
                while(true) { val b=input.read();require(b>=0&&++total<=16384);if(b==10)return bytes.toString("US-ASCII").removeSuffix("\r");bytes.write(b) }
            }
            val start=line().split(' ');require(start.size==3&&start[2]=="HTTP/1.1")
            val method=start[0];val path=start[1];val headers=mutableMapOf<String,String>()
            while(true) { val value=line();if(value.isEmpty())break;val pieces=value.split(':',limit=2);require(pieces.size==2&&!headers.containsKey(pieces[0].lowercase()));headers[pieces[0].lowercase()]=pieces[1].trim() }
            if(headers.containsKey("origin")||!MessageDigest.isEqual(headers["authorization"].orEmpty().toByteArray(),"Bearer $token".toByteArray())) { reply(socket,401,JSONObject().put("error","Unauthorized"));return }
            require(!headers.containsKey("transfer-encoding"));val size=headers["content-length"]?.toInt() ?: 0;require(size in 0..65536)
            if(method=="POST")require(size>0&&headers["content-type"]?.substringBefore(';')=="application/json")
            val bytes=ByteArray(size);var offset=0;while(offset<size) { val n=input.read(bytes,offset,size-offset);require(n>0);offset+=n }
            if(size>0)body=JSONObject(String(bytes,Charsets.UTF_8))
            val result:Any=when {
                method=="GET"&&path=="/v1/status" -> {
                    val state=rpc.request("deviceState") as JSONObject
                    JSONObject().put("protocol",1).put("backend","standalone").put("buildId",RuntimeBuildId.VALUE).put("plannerLocation","phone").put("engineReady",true).put("overlayReady",true)
                        .put("busy",activeId!=null).put("interactive",state.getBoolean("interactive")).put("locked",state.getBoolean("locked")).put("virtualDisplayId",(rpc.request("virtualStatus") as JSONObject).optInt("displayId",0))
                        .put("model",modelInfo()).put("capabilities",JSONObject().put("virtualDisplay",true).put("browserManualInput",true).put("phoneFloatingWindow",false).put("javascript",false).put("ocr",false))
                }
                method=="GET"&&path=="/v1/model" -> modelInfo()
                method=="POST"&&path in setOf("/v1/model","/v1/model/test","/v1/models") -> {
                    check(activeId==null);val candidate=modelSettings(body)
                    when(path) {
                        "/v1/models" -> JSONObject().put("models",JSONArray(ModelCatalog().fetch(candidate)))
                        "/v1/model/test" -> JSONObject().put("message",ChatModel(candidate,mode=PlanningMode.TOOLS_ONLY).test())
                        else -> synchronized(lock) { check(activeId==null);modelStore.save(candidate);settings=candidate;modelInfo() }
                    }
                }
                method=="POST"&&path=="/v1/tasks" -> start(body)
                method=="GET"&&path=="/v1/preview" -> {
                    val frame=rpc.request("virtualFrame") as JSONObject
                    replyBytes(socket,200,android.util.Base64.decode(frame.getString("jpeg"),android.util.Base64.DEFAULT),"image/jpeg");return
                }
                method=="POST"&&path=="/v1/disconnect" -> { cancel();settings=null;File(directory.parentFile,"access.token").apply { writeText(ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) });android.system.Os.chmod(this.path,384) };closed=true;listener?.close();JSONObject().put("closed",true) }
                path.matches(Regex("/v1/tasks/[a-f0-9-]{36}(/(stop|takeover|resume|approve|input|logs))?")) -> {
                    val pieces=path.split('/');val id=pieces[3];val action=pieces.getOrNull(4)
                    if(method=="GET"&&action==null)receipt(id)
                    else if(method=="GET"&&action=="logs") {
                        receipt(id);val log=File(directory,"$id.jsonl");if(!log.exists())throw NoSuchElementException("No retained log")
                        val out=java.io.ByteArrayOutputStream()
                        java.util.zip.ZipOutputStream(out).use { zip -> zip.putNextEntry(java.util.zip.ZipEntry("events.jsonl"));zip.write(log.readBytes());zip.closeEntry() }
                        replyBytes(socket,200,out.toByteArray(),"application/zip");return
                    } else if(method=="POST")synchronized(lock) {
                        check(activeId==id&&receipt(id).optString("phase") !in setOf("COMPLETED","FAILED","CANCELLED","INTERRUPTED")) { "Task is not running" }
                        when(action) {
                            "stop" -> cancel()
                            "takeover" -> { control.pause(true);pending?.complete(false);if(display>0)rpc.request("setControl",JSONObject().put("manual",true));update(id,"PAUSED","Manual takeover") }
                            "resume" -> { if(display>0)rpc.request("setControl",JSONObject().put("manual",false));control.pause(false);update(id,"RUNNING","Resuming from fresh observation") }
                            "approve" -> { check(pendingId!=null&&body.getString("id")==pendingId);pending?.complete(body.getBoolean("allowed")) }
                            "input" -> { check(control.paused&&display>0);rpc.request("manualInput",body.put("taskId",id)) }
                            else -> throw NoSuchElementException("Unknown action")
                        };receipt(id)
                    } else throw NoSuchElementException("Unknown endpoint")
                }
                else -> throw NoSuchElementException("Unknown endpoint")
            }
            reply(socket,200,result as JSONObject)
        } catch(error:Exception) {
            var message=error.message.orEmpty().take(2000)
            listOf(body.optString("key"),settings?.apiKey.orEmpty()).filter(String::isNotBlank).forEach { message=message.replace(it,"[redacted]") }
            runCatching { reply(socket,when(error) { is NoSuchElementException->404;is IllegalArgumentException->400;is IllegalStateException->409;else->503 },JSONObject().put("error",message)) }
        }
    }
    private fun reply(s:Socket,code:Int,value:JSONObject)=replyBytes(s,code,value.toString().toByteArray(),"application/json")
    private fun replyBytes(s:Socket,code:Int,data:ByteArray,type:String) { s.getOutputStream().apply { write("HTTP/1.1 $code Response\r\nContent-Type: $type\r\nContent-Length: ${data.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray());write(data);flush() } }
}
