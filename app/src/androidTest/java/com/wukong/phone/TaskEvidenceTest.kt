package com.wukong.phone

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wukong.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TaskEvidenceTest {
    @Test fun nativePressReceiptsAndReadingsSurviveResetWithoutCountingGeneratedRowsAsPresses() {
        val memory=TaskEvidence()
        val paused=Screen("clock.app","paused",listOf(UiNode(1,"跑圈",clickable=true,resourceId="clock.app:id/lap"),
            UiNode(2,"00:03.69 stale description",rawText="00:03.69"),UiNode(3,"00:02.93",rawText="00:02.93")))
        memory.accepted(Action.Tap(1),paused);memory.read(paused)
        memory.read(Screen("clock.app","reset",listOf(UiNode(4,"00:00.00",rawText="00:00.00"))))
        val json=memory.snapshot()
        assertEquals(1,json.getJSONArray("acceptedActions").length())
        assertEquals("跑圈",json.getJSONArray("acceptedActions").getJSONObject(0).getJSONObject("target").getString("label"))
        assertEquals(2,json.getJSONArray("readings").length())
        val old=json.getJSONArray("readings").getJSONObject(0).getString("visibleText")
        assertTrue(old.contains("00:03.69"));assertFalse(old.contains("stale description"))
    }
    @Test fun workingEvidenceIsBoundedAndNeverStoresProtectedNodes() {
        val memory=TaskEvidence()
        repeat(145) { index ->
            val page=Screen("demo.app","$index",listOf(UiNode(1,"value $index",clickable=true)))
            memory.read(page);memory.accepted(Action.Tap(1),page)
        }
        memory.read(Screen("demo.app","protected",listOf(UiNode(2,"private sentinel",sensitive=true))))
        val json=memory.snapshot();assertEquals(128,json.getJSONArray("acceptedActions").length());assertEquals(17,json.getInt("actionsDropped"))
        assertEquals(16,json.getJSONArray("readings").length());assertFalse(json.toString().contains("private sentinel"))
    }
}
