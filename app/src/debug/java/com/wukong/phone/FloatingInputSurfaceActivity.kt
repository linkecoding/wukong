package com.wukong.phone

import android.app.Activity
import android.os.Bundle
import android.view.MotionEvent
import android.widget.*
import java.util.concurrent.CopyOnWriteArrayList

/** Debug-only receiver: native targets and scroll containers on the actual virtual display. */
class FloatingInputSurfaceActivity : Activity() {
    data class Touch(val downX: Float,val downY: Float,val upX: Float,val upY: Float)
    val touches=CopyOnWriteArrayList<Touch>()
    val targets=mutableListOf<Button>()
    lateinit var vertical: ScrollView
    lateinit var horizontal: HorizontalScrollView
    @Volatile var selected=-1
    private var downX=0f;private var downY=0f
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setBackgroundColor(getColor(R.color.paper)) }
        val grid=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        repeat(3) { row ->
            val line=LinearLayout(this)
            repeat(3) { col ->
                val index=row*3+col
                val target=Button(this).apply { text="Target $index";setOnClickListener { selected=index } }
                targets+=target;line.addView(target,LinearLayout.LayoutParams(0,-1,1f))
            }
            grid.addView(line,LinearLayout.LayoutParams(-1,0,1f))
        }
        root.addView(grid,LinearLayout.LayoutParams(-1,0,4f))
        vertical=ScrollView(this).apply { isVerticalScrollBarEnabled=true }
        val rows=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        repeat(50) { rows.addView(TextView(this).apply { text="Vertical row $it";textSize=20f;setPadding(24,24,24,24) },LinearLayout.LayoutParams(-1,120)) }
        vertical.addView(rows);root.addView(vertical,LinearLayout.LayoutParams(-1,0,4f))
        horizontal=HorizontalScrollView(this)
        val columns=LinearLayout(this)
        repeat(12) { columns.addView(TextView(this).apply { text="Column $it";textSize=20f;setPadding(30,20,20,20) },LinearLayout.LayoutParams(300,-1)) }
        horizontal.addView(columns);root.addView(horizontal,LinearLayout.LayoutParams(-1,0,2f))
        setContentView(root)
    }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if(event.actionMasked==MotionEvent.ACTION_DOWN) { downX=event.rawX;downY=event.rawY }
        val handled=super.dispatchTouchEvent(event)
        if(event.actionMasked==MotionEvent.ACTION_UP)touches+=Touch(downX,downY,event.rawX,event.rawY)
        return handled
    }
}
