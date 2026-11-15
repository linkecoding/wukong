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

    @Test fun explicitSelectorsAndNamedOutcomesKeepSnapshotEvidence() {
        JsSession(context, Cancellation(), ExecutionControl()).use { js ->
            assertEquals("one", js.run("return wukong.waitAny({success:wukong.text('absent'),error:wukong.text('结果就绪')}).fingerprint;", host))
            val outcome = JSONObject(js.run("return wukong.waitAny({success:wukong.text('absent'),error:wukong.text('结果就绪')});", host))
            assertEquals("error", outcome.getString("key")); assertFalse(outcome.getBoolean("timedOut"))
            assertEquals("结果就绪", outcome.getJSONArray("matches").getJSONObject(0).getString("text"))
            assertEquals("true", js.run("return wukong.exists(wukong.select({enabled:true}).text('结果就绪'));", host))
            assertEquals("false", js.run("return wukong.exists(wukong.text('absent'));", host))
            val timeout = JSONObject(js.run("return wukong.waitAny({missing:wukong.text('absent')},{timeoutMs:200});", host))
            assertTrue(timeout.getBoolean("timedOut")); assertTrue(timeout.isNull("key"))
            val unknownHost = object : ScriptHost {
                override fun observe() = host.observe().copy(truncated = true)
                override fun execute(action: Action, expected: Screen): String = error("No mutation")
            }
            assertEquals("null", js.run("return wukong.exists(wukong.text('absent'));", unknownHost))
            val missing = JSONObject(js.run("return wukong.waitGone(wukong.text('absent'),{timeoutMs:200});", unknownHost))
            assertEquals("unknown", missing.getJSONObject("verification").getString("state"))
        }
    }
    @Test fun malformedSelectorsOptionsAndPredicatesFailBeforeHostMutation() {
        JsSession(context, Cancellation(), ExecutionControl()).use { js ->
            for (code in listOf(
                "wukong.query({recourceId:'demo:id/value'});",
                "wukong.query(wukong.text('x'),{offest:1});",
                "wukong.tap(wukong.text('x'),{timeuotMs:1});",
                "wukong.waitUntil(()=>true);",
                "wukong.query({enabled:'true'});",
                "wukong.waitUntil({conditions:[{textEquals:'x'}],timeuotMs:100});"
            )) assertNotNull(code, runCatching { js.run(code, host) }.exceptionOrNull())
            val wrongType = runCatching { js.run("wukong.tap(wukong.text('结果就绪'),{timeoutMs:'3000'});", host) }.exceptionOrNull()
            assertTrue(wrongType?.message.orEmpty().contains("INVALID_ARGUMENT"))
        }
    }
    @Test fun actionsWaitForReadinessAndDesiredStatesAreNotAppliedTwice() {
        var reads = 0; var injections = 0; var value = "old"; var checked = true
        val changing = object : ScriptHost {
            override fun observe(): Screen {
                reads++
                return Screen("demo.app", "f$reads", listOf(
                    UiNode(1, "$value hint", editable = true, resourceId = "demo.app:id/input", rawText = value),
                    UiNode(2, "Continue", clickable = true, enabled = reads >= 3, resourceId = "demo.app:id/next"),
                    UiNode(3, "Opt in", clickable = true, checked = checked, resourceId = "demo.app:id/check")
                ))
            }
            override fun execute(action: Action, expected: Screen): String {
                injections++
                when (action) { is Action.Input -> value = action.text; is Action.Tap -> if (action.nodeId == 3) checked = !checked; else -> error("Unexpected action") }
                return "ok"
            }
        }
        JsSession(context, Cancellation(), ExecutionControl()).use { js ->
            js.run("wukong.tap(wukong.id('demo.app:id/next').enabled(true),{timeoutMs:1000});", changing)
            assertEquals(1, injections); assertTrue(reads >= 3)
            assertEquals("true", js.run("return wukong.setText(wukong.id('demo.app:id/input'),'new').verified;", changing))
            assertEquals("true", js.run("return wukong.setText(wukong.id('demo.app:id/input'),'new').noOp;", changing))
            assertEquals("true", js.run("return wukong.setChecked(wukong.id('demo.app:id/check'),false).verified;", changing))
            assertEquals("true", js.run("return wukong.setChecked(wukong.id('demo.app:id/check'),false).noOp;", changing))
            assertEquals(3, injections); assertEquals("new", value); assertFalse(checked)
            assertEquals("true", js.run("return wukong.setText(wukong.id('demo.app:id/input'),'').verified;", changing))
            assertEquals(4, injections); assertEquals("", value)
        }
    }
    @Test fun duplicateTargetsFailWithoutInputAndPartialEffectsAreReported() {
        var injections = 0
        val duplicate = object : ScriptHost {
            override fun observe() = Screen("demo.app", "f", listOf(UiNode(1,"Same",clickable=true), UiNode(2,"Same",clickable=true)))
            override fun execute(action: Action, expected: Screen): String { injections++; return "ok" }
        }
        JsSession(context, Cancellation(), ExecutionControl()).use { js ->
            val error = runCatching { js.run("wukong.tap(wukong.text('Same'));", duplicate) }.exceptionOrNull() as ScriptCallFailure
            val detail = JSONObject(error.diagnostic)
            assertEquals("AMBIGUOUS_TARGET", detail.getString("code")); assertFalse(detail.getBoolean("accepted")); assertEquals(0, injections)
            val partial = object : ScriptHost {
                override fun observe() = Screen("demo.app", "f", listOf(UiNode(1,"Ready",clickable=true)))
                override fun execute(action: Action, expected: Screen): String { injections++; return "ok" }
            }
            val failure = runCatching { js.run("wukong.tap('Ready'); wukong.tap('Missing',{timeoutMs:0});", partial) }.exceptionOrNull() as ScriptCallFailure
            val report = JSONObject(failure.diagnostic)
            assertEquals("NOT_FOUND", report.getString("code")); assertEquals(1, report.getInt("acceptedCount"))
            assertEquals(1, report.getJSONArray("completed").length()); assertEquals(1, injections)
        }
    }
    @Test fun acceptedButUnverifiedStateStopsWithoutRepeatingTheMutation() {
        var injections = 0
        val unchanged = object : ScriptHost {
            override fun observe() = Screen("demo.app", "f", listOf(UiNode(1,"Old hint",editable=true,resourceId="demo.app:id/input",rawText="Old")))
            override fun execute(action: Action, expected: Screen): String { injections++; return "ok" }
        }
        JsSession(context, Cancellation(), ExecutionControl()).use { js ->
            val failure = runCatching { js.run("try { wukong.setText(wukong.id('demo.app:id/input'),'New'); } catch(e) {} wukong.home();", unchanged) }.exceptionOrNull() as ScriptCallFailure
            val report = JSONObject(failure.diagnostic)
            assertEquals("OUTCOME_UNKNOWN", report.getString("code")); assertTrue(report.getBoolean("accepted")); assertEquals(1, injections)
        }
    }
    @Test fun takeoverDuringReadinessWaitStopsBeforeInjection() {
        val control = ExecutionControl(); var injections = 0
        val disabled = object : ScriptHost {
            override fun observe() = Screen("demo.app", "f", listOf(UiNode(1,"Wait",clickable=true,enabled=false)))
            override fun execute(action: Action, expected: Screen): String { injections++; return "ok" }
        }
        JsSession(context, Cancellation(), control).use { js ->
            val thread = Thread { Thread.sleep(200); control.pause(true) }.apply { start() }
            try { assertNotNull(runCatching { js.run("wukong.tap('Wait',{timeoutMs:5000});", disabled) }.exceptionOrNull()) }
            finally { thread.join(); control.pause(false) }
            assertEquals(0, injections)
        }
    }

    @Test fun legacyEngineValueAbsenceRefusesBeforeSendingAnyInput() {
        var injections = 0
        val legacy = object : ScriptHost {
            override fun observe() = Screen("demo.app", "f", listOf(UiNode(1,"Old hint",editable=true,resourceId="demo.app:id/input")))
            override fun execute(action: Action, expected: Screen): String { injections++; return "ok" }
        }
        JsSession(context, Cancellation(), ExecutionControl()).use { js ->
            val failure = runCatching { js.run("wukong.setText(wukong.id('demo.app:id/input'),'New');", legacy) }.exceptionOrNull() as ScriptCallFailure
            assertEquals("ENGINE_UPDATE_REQUIRED", JSONObject(failure.diagnostic).getString("code")); assertEquals(0, injections)
        }
    }
    @Test fun javascriptFailuresRetainEarlierAcceptedEffectsInDiagnostics() {
        var injections = 0
        val target = object : ScriptHost {
            override fun observe() = Screen("demo.app", "f", listOf(UiNode(1,"Ready",clickable=true)))
            override fun execute(action: Action, expected: Screen): String { injections++; return "ok" }
        }
        JsSession(context, Cancellation(), ExecutionControl()).use { js ->
            val failure = runCatching { js.run("wukong.tap('Ready'); const s=wukong.id('demo.app:id/x'); Object.assign(s,{enabled:true});", target) }.exceptionOrNull() as ScriptCallFailure
            val detail = JSONObject(failure.diagnostic)
            assertEquals("SCRIPT_ERROR", detail.getString("code")); assertEquals(1, detail.getInt("acceptedCount")); assertEquals(1, injections)
        }
    }

    @Test fun staleUnexecutedSelectorIsResolvedAgainWithoutReplayingAcceptedInput() {
        var reads=0;var attempts=0;val injected=mutableListOf<Int>()
        val changing=object:ScriptHost {
            override fun observe():Screen {
                reads++;val id=if(attempts==0)1 else 2
                return Screen("demo.app","page-$id",listOf(UiNode(id,"继续",clickable=true,resourceId="demo.app:id/continue")))
            }
            override fun execute(action:Action,expected:Screen):String {
                if(attempts++==0)throw ActionNotExecuted("界面已更新，本次操作未执行",true)
                injected+=(action as Action.Tap).nodeId;return "accepted"
            }
        }
        JsSession(context,Cancellation(),ExecutionControl()).use { js ->
            js.run("wukong.tap(wukong.id('demo.app:id/continue')); return true;",changing)
            assertEquals(2,attempts);assertEquals(listOf(2),injected);assertTrue(reads>=2)
        }
    }
    @Test fun aKnownValidationRejectionIsNotReportedAsUnknownInjection() {
        val rejecting=object:ScriptHost {
            override fun observe()=Screen("demo.app","a",listOf(UiNode(1,"按钮",clickable=true)))
            override fun execute(action:Action,expected:Screen):String=throw ActionNotExecuted("Rejected before injection")
        }
        JsSession(context,Cancellation(),ExecutionControl()).use { js ->
            val failure=runCatching { js.run("wukong.tap('按钮');",rejecting) }.exceptionOrNull() as ScriptCallFailure
            assertFalse(JSONObject(failure.diagnostic).getBoolean("accepted"))
            assertEquals("INVALID_ARGUMENT",JSONObject(failure.diagnostic).getString("code"))
        }
    }

}
