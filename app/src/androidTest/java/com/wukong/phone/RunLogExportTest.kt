package com.wukong.phone

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class RunLogExportTest {
    private val i get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = i.targetContext
    private fun all(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()

    @Test fun archiveContainsOnlySelectedRunAndRedactsNestedCredentials() {
        val store = RunLogStore(context, "-exporttest"); store.clear(); store.enabled = true
        val exporter = RunLogExporter(context)
        try {
            val settings = ModelSettings("https://example.com", "test-model", "fixture-key")
            store.start("test", "unrelated-run-must-stay-private", settings)
            val log = store.start("task", "selected-run", settings)!!
            log.record("tool_call", JSONObject().put("nested", JSONObject().put("api_key", "another-secret").put("authorization", "Bearer test")).put("safe", "preserved"))
            repeat(1005) { log.record("progress", JSONObject().put("index", it)) }
            log.record("model_response", JSONObject().put("usage", JSONObject().put("total_tokens", 123)))
            val before = store.read(log.name).toString()
            val file = exporter.create(log.name, store)
            try {
                ZipFile(file).use { zip ->
                    assertEquals(setOf("manifest.json", "events.jsonl"), zip.entries().asSequence().map { it.name }.toSet())
                    val events = zip.getInputStream(zip.getEntry("events.jsonl")).bufferedReader().use { it.readText() }
                    assertTrue(events.contains("selected-run")); assertTrue(events.contains("preserved")); assertTrue(events.contains("123"))
                    assertFalse(events.contains("unrelated-run")); assertFalse(events.contains("another-secret")); assertFalse(events.contains("Bearer test"))
                    val manifest = JSONObject(zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader().use { it.readText() })
                    assertEquals(1008, manifest.getInt("eventCount")); assertFalse(manifest.has("serial")); assertFalse(manifest.has("apiKey"))
                }
                val uri = exporter.uri(file)
                assertEquals("content", uri.scheme)
                context.contentResolver.openInputStream(uri)!!.use { assertEquals(file.length(), it.readBytes().size.toLong()) }
                assertEquals(before, store.read(log.name).toString())
                assertNull(exporter.cached("../outside.zip"))
                store.delete(log.name)
                assertTrue("Snapshot survives original log removal", file.isFile)
                assertTrue(runCatching { exporter.create(log.name, store) }.isFailure)
            } finally { file.delete() }
        } finally { store.clear() }
    }

    @Test fun modelSaveStaysOnPageAndEditingClearsSuccess() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live_model") == "true")
        val before = SettingsStore(context).load() ?: error("No configured model")
        val activity = i.startActivitySync(Intent(context, SetupActivity::class.java).putExtra("model_only", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            i.runOnMainSync {
                val views = all(activity.findViewById(android.R.id.content))
                val back = views.filterIsInstance<Button>().single { it.contentDescription == activity.getString(R.string.setup_back) }
                assertEquals("", back.text.toString())
                val picker = views.filterIsInstance<Spinner>().first()
                val selected = picker.selectedView as TextView
                assertEquals(View.TEXT_ALIGNMENT_VIEW_END, selected.textAlignment)
                activity.findViewById<Button>(R.id.setup_primary).performClick()
            }
            val deadline = System.currentTimeMillis() + 90000
            var saved = false
            while (!saved && System.currentTimeMillis() < deadline) {
                Thread.sleep(200)
                i.runOnMainSync { saved = all(activity.findViewById(android.R.id.content)).filterIsInstance<ModelConfigurationView>().single().message.text.toString() == activity.getString(R.string.setup_saved_success) }
            }
            assertTrue("Live model test must save successfully", saved)
            assertFalse(activity.isFinishing)
            assertTrue("Saved settings remain equal without exposing key", before == SettingsStore(context).load())
            i.runOnMainSync {
                val form = all(activity.findViewById(android.R.id.content)).filterIsInstance<ModelConfigurationView>().single()
                form.model.append("-edited")
                assertEquals("", form.message.text.toString())
            }
        } finally { i.runOnMainSync { activity.finish() } }
    }

    @Test fun exportMenuOpensCancelableDialogWithoutChangingLogs() {
        val store = RunLogStore(context)
        val file = store.files().firstOrNull() ?: return
        val before = store.read(file.name).toString()
        val activity = i.startActivitySync(Intent(context, RunDetailActivity::class.java).putExtra("log",file.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            i.runOnMainSync {
                activity.findViewById<View>(R.id.page_menu).performClick()
            }
            i.waitForIdleSync()
            i.runOnMainSync {
                val list = WindowInspector.getGlobalWindowViews().flatMap(::all).filterIsInstance<ListView>().last()
                val index = (0 until list.adapter.count).single { list.adapter.getItem(it).toString() == activity.getString(R.string.export_run) }
                list.performItemClick(list.getChildAt(index), index, list.adapter.getItemId(index))
            }
            i.waitForIdleSync()
            i.runOnMainSync {
                val views = WindowInspector.getGlobalWindowViews().flatMap(::all)
                assertTrue(views.filterIsInstance<TextView>().any { it.text.toString() == activity.getString(R.string.export_description) })
                views.filterIsInstance<Button>().single { it.text.toString() == activity.getString(android.R.string.cancel) }.performClick()
                assertEquals(before, store.read(file.name).toString())
            }
        } finally { i.runOnMainSync { activity.finish() } }
    }
}
