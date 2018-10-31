/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.focus.widget

import android.content.Context
import android.graphics.drawable.Animatable2
import android.graphics.drawable.AnimatedVectorDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.support.design.widget.BottomSheetBehavior
import android.support.design.widget.FloatingActionButton
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import org.mozilla.focus.fragment.BrowserFragment

class FloatingAddTabButton : FloatingActionButton, View.OnTouchListener {

    var tabCount = 0
        set(value) {
            field = value
            handleState()
        }

    private var downRawY: Float = 0F
    private var originY: Float = 0F
    private var dY: Float = 0F
    private var state: Int = BottomSheetBehavior.STATE_COLLAPSED

    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    init {
        setOnTouchListener(this)
    }

    override fun setVisibility(visibility: Int) {
        handleState()
    }

    fun handleState(state: Int = this.state) {
        if (state == BottomSheetBehavior.STATE_EXPANDED && tabCount < MAX_TAB_COUNT) {
            show()
            invalidate()
            animateDrawable()
        } else {
            hide()
        }
        this.state = state
    }

    private fun animateDrawable() {
        var animationCycle = 0
        val drawable = drawable as AnimatedVectorDrawable
        with(drawable) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                drawable.registerAnimationCallback(object : Animatable2.AnimationCallback() {
                    override fun onAnimationEnd(draw: Drawable?) {
                        if (animationCycle == 0) {
                            start()
                            animationCycle++
                        }
                    }
                })
            }
            scheduleSelf({ start() }, BrowserFragment.NEW_TAB_ANIMATION_DURATION)
        }
    }

    override fun onTouch(view: View?, motionEvent: MotionEvent?): Boolean {
        requireNotNull(view)
        requireNotNull(motionEvent)
        return when (motionEvent.action) {
            MotionEvent.ACTION_DOWN -> {
                parent.requestDisallowInterceptTouchEvent(true)
                originY = view.y
                downRawY = motionEvent.rawY
                dY = view.y - downRawY
                true
            }
            MotionEvent.ACTION_MOVE -> {
                var newY = motionEvent.rawY + dY
                newY = Math.min(originY, newY)

                view.animate()
                    .y(newY)
                    .setDuration(0)
                    .start()

                if (originY - newY > SWIPE_UP_TOLERANCE_PX) performClick() else true
            }
            MotionEvent.ACTION_UP -> {
                val upDY = motionEvent.rawY - downRawY
                if (Math.abs(upDY) < CLICK_TOLERANCE_PX) performClick() else true
            }
            else -> super.onTouchEvent(motionEvent)
        }
    }

    companion object {
        const val CLICK_TOLERANCE_PX = 10
        const val SWIPE_UP_TOLERANCE_PX = 200
        const val MAX_TAB_COUNT = 9
    }
}
