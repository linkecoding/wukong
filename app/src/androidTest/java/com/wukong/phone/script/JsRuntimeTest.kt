package com.wukong.phone.script

import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wukong.core.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class JsRuntimeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val host = object : ScriptHost {
        override fun observe() = Screen("demo.app", "one", listOf(UiNode(1, "结果就绪"), UiNode(2, "桥接😀")))
        override fun execute(action: Action, expected: Screen): String = error("Must not mutate")
    }
    @Test fun isolatedQuickJsPersistsOnlyJsonStateAndExposesNoJavaOrNode() {
        JsSession(context, Cancellation(), ExecutionControl()).use { js ->
            val first = JSONObject(js.run("state.count = 1; return {sum: [1,2,3].reduce((a,b)=>a+b,0), java: typeof Java, packages: typeof Packages, node: typeof require, network: typeof fetch};", host))
            assertEquals(6, first.getInt("sum"))
            assertEquals("object", js.run("return typeof wukong;", host))
            assertEquals("undefined", js.run("return typeof phone;", host))
            for (key in listOf("java", "packages", "node", "network")) assertEquals("undefined", first.getString(key))
            assertNotEquals(Process.myPid(), js.enginePid); assertTrue(js.engineUid % 100000 >= 99000)
            assertEquals("2", js.run("state.count++; return state.count;", host))
            assertEquals("undefined", js.run("return typeof first;", host))
            val query = JSONObject(js.run("return wukong.query({textEquals:'结果就绪'});", host))
            assertEquals(1, query.getInt("totalMatches"))
            assertEquals("结果就绪", js.run("return wukong.query({textEquals:'结果就绪'},1).map(n=>n.text).join(',');", host))
            assertEquals("1", js.run("return wukong.query({textEquals:'结果就绪'},1).length;", host))
            assertEquals("met", js.run("return wukong.waitUntil({selector:{textEquals:'结果就绪'}}).verification.state;", host))
            assertEquals("met", js.run("return wukong.waitUntil({conditions:[{selector:{textEquals:'结果就绪'}}],timeoutMs:500}).verification.state;", host))
            assertEquals("met", js.run("return wukong.verify([{textEquals:'结果就绪'}]).verification.state;", host))
        }
    }
    @Test fun runawayLoopIsTerminatedAndTheAppAndFreshContextSurvive() {
        val pid = Process.myPid(); val start = SystemClock.elapsedRealtime()
        JsSession(context, Cancellation(), ExecutionControl(), timeoutMs = 1200).use { js ->
            assertNotNull(runCatching { js.run("while(true) {}", host) }.exceptionOrNull())
            assertTrue("Infinite JS must not hang the app", SystemClock.elapsedRealtime() - start < 10000)
            assertEquals(pid, Process.myPid())
            assertEquals("42", js.run("return 6 * 7;", host))
        }
    }
    @Test fun caughtHostFailuresCannotContinueToMorePhoneOperations() {
        var injections = 0
        val dangerous = object : ScriptHost {
            override fun observe() = Screen("demo.app", "one", listOf(UiNode(1, "测试", clickable = true)))
            override fun execute(action: Action, expected: Screen): String { injections++; error("结果未知，必须停止") }
        }
        JsSession(context, Cancellation(), ExecutionControl()).use { js ->
            assertNotNull(runCatching { js.run("try { wukong.tap('测试'); } catch(e) {} wukong.tap('测试'); return 'fake success';", dangerous) }.exceptionOrNull())
            assertEquals(1, injections)
        }
    }
    @Test fun controlEpochStopsCpuOnlyJsAndClearsState() {
        val control = ExecutionControl()
        JsSession(context, Cancellation(), control).use { js ->
            js.run("state.old = true; return true;", host)
            val thread = Thread { Thread.sleep(200); control.pause(true) }.apply { start() }
            try { assertNotNull(runCatching { js.run("while(true) {}", host) }.exceptionOrNull()) }
            finally { thread.join(); control.pause(false) }
            assertEquals("undefined", js.run("return typeof state.old;", host))
        }
    }
    @Test fun nativeHeapLimitReturnsAnErrorAndLeavesTheServiceUsable() {
        val pid = Process.myPid()
        JsSession(context, Cancellation(), ExecutionControl(), timeoutMs = 5000).use { js ->
            val failure = runCatching { js.run("const arrays=[]; while(true) arrays.push(new Array(1000000).fill(Math.random()));", host) }.exceptionOrNull()
            assertTrue("Expected a bounded heap error, got: ${failure?.message}", failure?.message.orEmpty().contains("memory", ignoreCase = true))
            val enginePid = js.enginePid
            assertEquals("42", js.run("return 42;", host))
            assertEquals(enginePid, js.enginePid)
        }
        assertEquals(pid, Process.myPid())
        JsSession(context, Cancellation(), ExecutionControl()).use { js -> assertEquals("42", js.run("return 42;", host)) }
    }

    @Test fun utf8BridgeStackLimitAndBlockingAtomicsAreHandled() {
        JsSession(context, Cancellation(), ExecutionControl()).use { js ->
            assertEquals("中文😀", js.run("return '中文😀';", host))
            val result = JSONObject(js.run("return wukong.query({textEquals:'桥接😀'});", host))
            assertEquals("桥接😀", result.getJSONArray("nodes").getJSONObject(0).getString("text"))
            val recursion = runCatching { js.run("function recurse(){ return recurse(); } return recurse();", host) }.exceptionOrNull()
            assertTrue(recursion?.message.orEmpty().contains("stack", ignoreCase = true))
            val blocking = runCatching { js.run("return Atomics.wait(new Int32Array(new SharedArrayBuffer(4)),0,0);", host) }.exceptionOrNull()
            assertNotNull("Blocking Atomics must not bypass cancellation", blocking)
            assertEquals("42", js.run("return 42;", host))
        }
    }

}
