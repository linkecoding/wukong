package com.wukong.phone

import com.wukong.core.*
import org.json.JSONArray
import org.json.JSONObject

internal fun UiNode.toJson() = JSONObject().put("id", id).put("text", text).apply { if (editable && rawText != null) put("rawText", rawText) }.put("resourceId", resourceId).put("className", className)
    .put("parentId", parentId ?: JSONObject.NULL).put("editable", editable).put("clickable", clickable).put("scrollable", scrollable)
    .put("enabled", enabled).put("checked", checked ?: JSONObject.NULL).put("selected", selected).put("bounds", bounds?.toJson() ?: JSONObject.NULL)

internal fun JSONObject.requireKeys(vararg allowed: String) {
    val unknown = keys().asSequence().filter { it !in allowed }.toList()
    require(unknown.isEmpty()) { "Unknown argument(s): ${unknown.joinToString()}; allowed: ${allowed.joinToString()}" }
}
internal fun JSONObject.toSelector(): NodeSelector {
    requireKeys("textContains", "textEquals", "resourceId", "className", "editable", "clickable", "scrollable", "enabled", "checked", "selected", "ancestorResourceId")
    return NodeSelector(
    textContains = optionalString("textContains"), textEquals = optionalString("textEquals"), resourceId = optionalString("resourceId"),
    className = optionalString("className"), editable = optionalBoolean("editable"), clickable = optionalBoolean("clickable"), scrollable = optionalBoolean("scrollable"),
    enabled = optionalBoolean("enabled"), checked = optionalBoolean("checked"), selected = optionalBoolean("selected"), ancestorResourceId = optionalString("ancestorResourceId"),
).also { it.validate() }
}
private fun JSONObject.optionalString(key: String): String? = if (!has(key)) null else get(key).let { require(it is String) { "$key must be a string" }; it }
private fun JSONObject.optionalBoolean(key: String): Boolean? = if (!has(key)) null else get(key).let { require(it is Boolean) { "$key must be a boolean" }; it }
internal fun NodeSelector.toJson() = JSONObject().apply {
    mapOf("textContains" to textContains, "textEquals" to textEquals, "resourceId" to resourceId, "className" to className, "editable" to editable,
        "clickable" to clickable, "scrollable" to scrollable, "enabled" to enabled, "checked" to checked, "selected" to selected, "ancestorResourceId" to ancestorResourceId)
        .forEach { (key, value) -> if (value != null) put(key, value) }
}
internal fun ScreenCondition.toJson() = JSONObject().put("state", state.name.lowercase()).put("minCount", minCount).apply {
    selector?.let { put("selector", it.toJson()) }; packageName?.let { put("packageName", it) }
}
internal fun JSONObject.toConditions(): List<ScreenCondition> {
    val array = getJSONArray("conditions")
    return (0 until array.length()).map { index -> array.getJSONObject(index).let {
        it.requireKeys("selector", "packageName", "state", "minCount")
        if (it.has("minCount")) require(it.get("minCount") is Number && it.get("minCount").let { n -> (n as Number).toDouble() == n.toInt().toDouble() }) { "minCount must be an integer" }
        ScreenCondition(it.optJSONObject("selector")?.toSelector(), it.optionalString("packageName"), when (it.optString("state", "present")) {
            "present" -> Presence.PRESENT
            "absent" -> Presence.ABSENT
            else -> error("未知条件状态")
        }, it.optInt("minCount", 1))
    } }
}
internal fun ConditionReport.toJson(): JSONObject = JSONObject().put("state", state.name.lowercase()).put("package", screen.packageName)
    .put("fingerprint", screen.fingerprint).put("sourceTruncated", screen.truncated).put("checks", JSONArray(checks.map {
        JSONObject().put("condition", it.condition.toJson()).put("state", it.state.name.lowercase()).put("totalMatches", it.totalMatches)
            .put("matches", JSONArray(it.matches.map { node -> node.toJson() })).put("matchesTruncated", it.totalMatches > it.matches.size)
    }))
internal fun InspectionResult.toJson() = JSONObject().put("package", screen.packageName).put("fingerprint", screen.fingerprint)
    .put("sourceTruncated", screen.truncated).put("elapsedMs", elapsedMillis).put("samples", samples).put("timedOut", timedOut).apply {
        val checked = report
        if (checked != null) put("verification", checked.toJson()) else {
            put("nodes", JSONArray(nodes.map { it.toJson() })).put("totalMatches", totalMatches).put("offset", offset)
            put("hasMore", offset + nodes.size < totalMatches)
        }
    }

internal object InspectionSchemas {
    fun selector() = JSONObject().put("type", "object").put("additionalProperties", false).put("properties", JSONObject().apply {
        for (key in listOf("textContains", "textEquals", "resourceId", "className", "ancestorResourceId")) put(key, JSONObject().put("type", "string").put("minLength", 1).put("maxLength", 300))
        for (key in listOf("editable", "clickable", "scrollable", "enabled", "checked", "selected")) put(key, JSONObject().put("type", "boolean"))
    })
    fun conditions() = JSONObject().put("type", "array").put("minItems", 1).put("maxItems", 5).put("items", JSONObject()
        .put("type", "object").put("additionalProperties", false).put("properties", JSONObject()
            .put("selector", selector()).put("packageName", JSONObject().put("type", "string"))
            .put("state", JSONObject().put("type", "string").put("enum", JSONArray(listOf("present", "absent"))))
            .put("minCount", JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 100))))
    fun conditionProperties() = JSONObject().put("conditions", conditions()).put("matchAll", JSONObject().put("type", "boolean"))
}
