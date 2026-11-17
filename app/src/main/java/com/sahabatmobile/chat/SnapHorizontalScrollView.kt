package com.sahabatmobile.chat

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.HorizontalScrollView

/** HorizontalScrollView yang berhenti tepat di halaman (tidak setengah geser). */
class SnapHorizontalScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : HorizontalScrollView(context, attrs) {

    override fun fling(velocityX: Int) {
        // Dinonaktifkan supaya geseran tidak melaju dan berhenti di tengah.
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val handled = super.onTouchEvent(ev)
        if (ev.action == MotionEvent.ACTION_UP || ev.action == MotionEvent.ACTION_CANCEL) {
            post {
                val page = width
                if (page > 0) smoothScrollTo(if (scrollX > page / 2) page else 0, 0)
            }
        }
        return handled
    }
}
