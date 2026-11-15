package com.wukong.phone

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TaskLogLinkTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val model = ModelSettings("https://example.com", "test", "fake-test-key")
    @Test fun exactLinksPersistAndMissingLogsNeverPointAtAnotherRun() {
        val logs = RunLogStore(context,"-association"); val history = HistoryStore(context,"-association")
        logs.clear(); history.clear(); logs.enabled = true
        try {
            val first = logs.start("task","same goal",model)!!; first.record("end",JSONObject().put("phase","COMPLETED"))
            val second = logs.start("task","same goal",model)!!; second.record("end",JSONObject().put("phase","COMPLETED"))
            val record = TaskRecord("same goal","phone","COMPLETED","done",System.currentTimeMillis(),first.name,true)
            history.append(record)
            assertEquals(record,history.read().single())
            assertEquals(first.name,logs.forTask(history.read().single()))
            logs.delete(first.name)
            assertNull(logs.forTask(history.read().single()))
            assertTrue(logs.files().any { it.name == second.name })
            assertEquals("done",history.read().single().result)
            assertNull("Logging-off tasks cannot adopt another trace",logs.forTask(record.copy(logName=null)))
        } finally { logs.clear(); history.clear() }
    }
    @Test fun legacyMatchingRequiresAnUnambiguousTaskAndDoesNotChangeOldRecords() {
        val logs = RunLogStore(context,"-association"); val history = HistoryStore(context,"-association")
        logs.clear(); history.clear(); logs.enabled = true
        try {
            val old = JSONObject().put("goal","legacy").put("app","phone").put("phase","COMPLETED").put("result","done").put("timestamp",System.currentTimeMillis())
            context.getSharedPreferences("history-association",0).edit().putString("records",JSONArray().put(old).toString()).commit()
            val record = history.read().single()
            assertNull(record.logName); assertFalse(record.logAssociationKnown)
            val first = logs.start("task","legacy",model)!!; first.record("end",JSONObject().put("phase","COMPLETED"))
            val connection = logs.start("connection_test","legacy",model)!!; connection.record("end",JSONObject().put("phase","COMPLETED"))
            assertEquals(first.name,logs.forTask(record))
            val second = logs.start("task","legacy",model)!!; second.record("end",JSONObject().put("phase","COMPLETED"))
            assertNull("Ambiguous old traces must not be guessed",logs.forTask(record))
            history.append(TaskRecord("new","phone","COMPLETED","done",System.currentTimeMillis(),null,true))
            assertEquals(record,history.read().last())
            assertNull(logs.forTask(record.copy(timestamp=record.timestamp-60000)))
        } finally { logs.clear(); history.clear() }
    }
}
