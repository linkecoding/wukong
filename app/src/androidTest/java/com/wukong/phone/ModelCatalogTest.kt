package com.wukong.phone

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ModelCatalogTest {
    private val i get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = i.targetContext
    private fun all(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
    private val fixture get() = ModelSettings("https://example.com/proxy/v7/", "keep-manual", "fixture-only-key", providerId = "custom")
    private class Response(url: URL, private val status: Int, private val body: String) : HttpURLConnection(url) {
        var disconnected = false
        override fun connect() = Unit
        override fun usingProxy() = false
        override fun disconnect() { disconnected = true }
        override fun getResponseCode() = status
        override fun getInputStream() = body.byteInputStream()
    }
    private fun await(condition: () -> Boolean) {
        var ready = false; val end = System.currentTimeMillis() + 20_000
        while (!ready && System.currentTimeMillis() < end) {
            i.runOnMainSync { ready = condition() }; if (!ready) Thread.sleep(50)
        }
        assertTrue("Expected UI state did not arrive", ready)
    }
    @Test fun discoveryPreservesPrefixesUsesBearerAndRejectsRedirectsAndMalformedLists() {
        var request: Response? = null
        val result = ModelCatalog { url -> Response(url, 200, """{"data":[{"id":"z-model"},{"id":"a-model"},{"id":"z-model"},{"id":42},{"id":""}]}""").also { request = it } }.fetch(fixture)
        assertEquals(listOf("a-model", "z-model"), result)
        assertEquals("https://example.com/proxy/v7/models", request!!.url.toString())
        assertEquals("GET", request!!.requestMethod)
        assertFalse(request!!.instanceFollowRedirects)
        assertTrue("Correct auth without exposing credentials", request!!.getRequestProperty("Authorization") == "Bearer ${fixture.apiKey}")
        assertTrue(request!!.disconnected)
        for ((status, reason) in listOf(401 to ModelCatalog.Failure.AUTH, 403 to ModelCatalog.Failure.AUTH, 404 to ModelCatalog.Failure.UNAVAILABLE, 429 to ModelCatalog.Failure.RATE_LIMIT, 302 to ModelCatalog.Failure.HTTP)) {
            val error = runCatching { ModelCatalog { Response(it, status, "") }.fetch(fixture) }.exceptionOrNull()
            assertEquals(reason, (error as ModelCatalog.CatalogException).reason)
        }
        for (body in listOf("not-json", "{}", "{\"data\":null}", "{\"data\":[{\"id\":\"x\"}]}".repeat(2))) {
            assertEquals(ModelCatalog.Failure.INVALID_RESPONSE, (runCatching { ModelCatalog { Response(it, 200, body) }.fetch(fixture) }.exceptionOrNull() as ModelCatalog.CatalogException).reason)
        }
        assertTrue(ModelCatalog { Response(it, 200, "{\"data\":[]}") }.fetch(fixture).isEmpty())
        assertTrue(runCatching { ModelCatalog { Response(it, 200, "x".repeat(2 * 1024 * 1024 + 1)) }.fetch(fixture) }.isFailure)
    }
    @Test fun discoveredListAndFailuresKeepManualModelAndNeverSaveDrafts() {
        val before = SettingsStore(context).load()?.toJson()?.toString()
        val a = i.startActivitySync(Intent(context, SetupActivity::class.java).putExtra("model_only", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        lateinit var form: ModelConfigurationView
        var status = 200
        try {
            i.runOnMainSync {
                form = ModelConfigurationView(a, fixture) { ModelCatalog { Response(it, status, "{\"data\":[{\"id\":\"a-model\"},{\"id\":\"b-model\"}]}") } }
                a.setContentView(ScrollView(a).apply { addView(form) })
                form.findViewById<View>(R.id.model_catalog_open).performClick()
            }
            await { form.model.adapter?.count == 2 && form.model.isPopupShowing }
            i.waitForIdleSync()
            i.runOnMainSync {
                assertEquals("keep-manual", form.model.text.toString())
                val list = WindowInspector.getGlobalWindowViews().flatMap(::all).filterIsInstance<ListView>().last { it.adapter.count == 2 }
                list.performItemClick(list.getChildAt(0), 0, list.adapter.getItemId(0))
                assertEquals("a-model", form.model.text.toString())
                form.model.setText("custom-manual", false)
                assertEquals("custom-manual", form.candidate().model)
                form.model.dismissDropDown()
                status = 404
                form.findViewById<View>(R.id.model_catalog_refresh).performClick()
            }
            await { all(form).filterIsInstance<TextView>().any { it.text.toString() == a.getString(R.string.model_list_unsupported) } }
            i.runOnMainSync {
                assertEquals("custom-manual", form.model.text.toString())
                form.key.setText("new-fixture-key")
                assertNull(form.model.adapter)
                assertEquals(View.GONE, form.findViewById<View>(R.id.model_catalog_refresh).visibility)
                form.fullEndpoint.isChecked = true
                form.findViewById<View>(R.id.model_catalog_open).performClick()
                assertTrue(all(form).filterIsInstance<TextView>().any { it.text.toString() == a.getString(R.string.model_unknown_route) })
                form.key.setText("")
                form.findViewById<View>(R.id.model_catalog_open).performClick()
                assertTrue(all(form).filterIsInstance<TextView>().any { it.text.toString() == a.getString(R.string.model_key_first) })
                assertEquals("custom-manual", form.model.text.toString())
            }
            assertTrue("Discovery must not save settings", before == SettingsStore(context).load()?.toJson()?.toString())
        } finally { i.runOnMainSync { a.finish() } }
    }
    @Test fun editingConnectionDiscardsAnOlderResponse() {
        val started = CountDownLatch(1); val release = CountDownLatch(1)
        val a = i.startActivitySync(Intent(context, SetupActivity::class.java).putExtra("model_only", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        lateinit var form: ModelConfigurationView
        try {
            i.runOnMainSync {
                form = ModelConfigurationView(a, fixture) { ModelCatalog { url ->
                    started.countDown()
                    while (release.count > 0) { try { release.await(100, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) {} }
                    Response(url, 200, "{\"data\":[{\"id\":\"obsolete\"}]}")
                } }
                a.setContentView(ScrollView(a).apply { addView(form) })
                form.findViewById<View>(R.id.model_catalog_open).performClick()
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            i.runOnMainSync { form.endpoint.setText("https://example.com/new-base") }
            release.countDown(); Thread.sleep(300); i.waitForIdleSync()
            i.runOnMainSync { assertNull(form.model.adapter); assertEquals("keep-manual", form.model.text.toString()) }
        } finally { release.countDown(); i.runOnMainSync { a.finish() } }
    }
    @Test fun clearingConfigurationLivesOnlyInModelPageAndCancelPreservesIt() {
        val before = SettingsStore(context).load()?.toJson()?.toString()
        val main = i.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            i.runOnMainSync {
                val views = all(main.findViewById(android.R.id.content))
                views.filterIsInstance<Button>().single { it.tag == 2 }.performClick()
                assertFalse(all(main.findViewById(android.R.id.content)).filterIsInstance<Button>().any { it.text.toString() == main.getString(R.string.ui_clear_model_configuration) })
            }
        } finally { i.runOnMainSync { main.finish() } }
        if (before == null) return
        val a = i.startActivitySync(Intent(context, SetupActivity::class.java).putExtra("model_only", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            i.runOnMainSync { a.findViewById<View>(R.id.model_page_menu).performClick() }; i.waitForIdleSync()
            i.runOnMainSync {
                val list = WindowInspector.getGlobalWindowViews().flatMap(::all).filterIsInstance<ListView>().last()
                list.performItemClick(list.getChildAt(0), 0, list.adapter.getItemId(0))
            }; i.waitForIdleSync()
            i.runOnMainSync {
                WindowInspector.getGlobalWindowViews().flatMap(::all).filterIsInstance<Button>().single { it.text.toString() == a.getString(R.string.ui_cancel) }.performClick()
            }
            assertTrue("Cancel preserves saved config", before == SettingsStore(context).load()?.toJson()?.toString())
        } finally { i.runOnMainSync { a.finish() } }
    }
    @Test fun realSavedProviderListsModelsWithoutChangingSelection() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live_model") == "true")
        val saved = SettingsStore(context).load() ?: error("No configured model")
        val models = ModelCatalog().fetch(saved)
        assertTrue("Saved provider must return model IDs", models.isNotEmpty())
        val a = i.startActivitySync(Intent(context, SetupActivity::class.java).putExtra("model_only", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            lateinit var form: ModelConfigurationView
            i.runOnMainSync {
                form = all(a.findViewById(android.R.id.content)).filterIsInstance<ModelConfigurationView>().single()
                form.findViewById<View>(R.id.model_catalog_open).performClick()
            }
            await { form.model.adapter?.count == models.size }
            if (InstrumentationRegistry.getArguments().getString("capture_model_catalog") == "true") {
                Thread.sleep(600)
                val request = java.io.File(context.filesDir, "screen-review-request")
                val done = java.io.File(context.filesDir, "screen-review-done")
                done.delete(); request.writeText("model-picker-final")
                val deadline = System.currentTimeMillis() + 15000
                while (!done.exists() && System.currentTimeMillis() < deadline) Thread.sleep(100)
                assertTrue("Host must capture the model picker", done.exists()); request.delete()
            }
            i.runOnMainSync { assertEquals(saved.model, form.model.text.toString()); form.model.dismissDropDown() }
            assertTrue("Discovery leaves encrypted configuration unchanged", saved == SettingsStore(context).load())
        } finally { i.runOnMainSync { a.finish() } }
    }
}
