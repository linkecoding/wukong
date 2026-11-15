package com.wukong.core

import org.junit.Assert.*
import org.junit.Test

class TapTargetsTest {
    private val row=UiNode(1,"",clickable=true,bounds=Bounds(0,100,1000,200))
    private val label=UiNode(2,"Download",bounds=Bounds(40,120,300,170),parentId=1)
    @Test fun labelResolvesToNearestContainingClickableRow() {
        val screen=Screen("files.app","a",listOf(row,label))
        assertEquals(listOf(row),TapTargets.find(screen,NodeSelector(textEquals="Download")))
        assertTrue(TapTargets.find(screen,NodeSelector(textEquals="Download",clickable=true)).isEmpty())
    }
    @Test fun twoDistinctRowsRemainAmbiguousButDuplicateLabelsInOneRowDoNot() {
        val screen=Screen("files.app","a",listOf(row,label,label.copy(id=3)))
        assertEquals(listOf(row),TapTargets.find(screen,NodeSelector(textEquals="Download")))
        val another=row.copy(id=4);val otherLabel=label.copy(id=5,parentId=4)
        assertEquals(2,TapTargets.find(screen.copy(nodes=screen.nodes+another+otherLabel),NodeSelector(textEquals="Download")).size)
    }
    @Test fun neverUsesUnrelatedAncestorOrCyclesAndPreservesDisabledState() {
        val screen=Screen("files.app","a",listOf(row.copy(bounds=Bounds(0,0,10,10)),label))
        assertTrue(TapTargets.find(screen,NodeSelector(textEquals="Download")).isEmpty())
        val disabled=label.copy(enabled=false)
        assertEquals(listOf(disabled),TapTargets.find(screen.copy(nodes=listOf(row,disabled)),NodeSelector(textEquals="Download")))
        assertTrue(TapTargets.find(screen.copy(nodes=listOf(label.copy(parentId=2))),NodeSelector(textEquals="Download")).isEmpty())
    }
    @Test fun aSelectedTabCannotResolveToItsScrollingContainer() {
        val scroller=row.copy(scrollable=true,className="android.widget.HorizontalScrollView")
        assertTrue(TapTargets.find(Screen("files.app","a",listOf(scroller,label.copy(selected=true))),NodeSelector(textEquals="Download")).isEmpty())
    }

    @Test fun repeatedActionCanBeScopedToItsOwnRowWithoutCrossingTheList() {
        val list=UiNode(10,"",scrollable=true,className="RecyclerView")
        val a=UiNode(11,"",parentId=10);val b=UiNode(12,"",parentId=10)
        val titleA=UiNode(13,"meeting.txt",parentId=11);val titleB=UiNode(14,"budget.txt",parentId=12)
        val moreA=UiNode(15,"More",clickable=true,parentId=11);val moreB=UiNode(16,"More",clickable=true,parentId=12)
        val screen=Screen("files.app","a",listOf(list,a,b,titleA,titleB,moreA,moreB))
        assertEquals(listOf(moreA),Conditions.find(screen,NodeSelector(textEquals="More",nearText="meeting.txt")))
        assertTrue(Conditions.find(screen,NodeSelector(textEquals="More",nearText="missing.txt")).isEmpty())
        assertEquals(2,Conditions.find(screen,NodeSelector(textEquals="More",nearText=".txt")).size)
    }

}
