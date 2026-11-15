package com.wukong.phone

import android.os.SystemClock
import com.wukong.core.*
import org.json.JSONArray
import org.json.JSONObject

/** Task-local, bounded native receipts/readings; independent of optional persisted logging. */
class TaskEvidence internal constructor() {
    private val started=SystemClock.elapsedRealtime()
    private val readings=linkedMapOf<String,JSONObject>()
    private val actions=ArrayDeque<JSONObject>()
    private var dropped=0
    @Synchronized internal fun read(screen:Screen) {
        if(screen.nodes.any { it.sensitive })return
        val text=screen.nodes.map { it.rawText?.takeIf(String::isNotBlank) ?: it.text }.filter { it.isNotBlank() }.distinct().joinToString("\n").take(1800)
        if(text.isBlank())return
        val key=screen.packageName+"|"+text
        readings.remove(key)
        readings[key]=JSONObject().put("package",screen.packageName).put("readAfterMs",SystemClock.elapsedRealtime()-started)
            .put("visibleText",text).put("historical",true)
        while(readings.size>16)readings.remove(readings.keys.first())
    }
    @Synchronized internal fun accepted(action:Action,screen:Screen) {
        val id=when(action) { is Action.Tap->action.nodeId;is Action.Input->action.nodeId;is Action.Scroll->action.nodeId;else->null }
        val event=JSONObject().put("kind",action.toJson().getString("kind")).put("package",screen.packageName)
            .put("acceptedAfterMs",SystemClock.elapsedRealtime()-started).put("status","accepted")
        screen.nodes.firstOrNull { it.id==id }?.let { node -> event.put("target",JSONObject().put("resourceId",node.resourceId)
            .put("label",node.rawText?.takeIf(String::isNotBlank) ?: node.text)) }
        actions.addLast(event)
        while(actions.size>128) { actions.removeFirst();dropped++ }
    }
    @Synchronized internal fun snapshot()=JSONObject().put("acceptedActions",JSONArray(actions.toList()))
        .put("actionsDropped",dropped).put("readings",JSONArray(readings.values.toList()))
}
