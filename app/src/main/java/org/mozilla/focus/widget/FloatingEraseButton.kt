/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.focus.widget

import android.content.Context
import android.support.design.widget.BottomSheetBehavior
import android.support.design.widget.CoordinatorLayout
import android.support.design.widget.FloatingActionButton
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.view.animation.AnimationUtils
import org.mozilla.focus.R

class FloatingEraseButton : FloatingActionButton, View.OnTouchListener {
    private var dX = 0F
    private var dY = 0F
    private var downRawX = 0F
    private var downRawY = 0F

    private var tabCount = 0
    private var state = BottomSheetBehavior.STATE_COLLAPSED
    private val keepHidden
        get() = tabCount < 1 || state == BottomSheetBehavior.STATE_EXPANDED

    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    init {
        setOnTouchListener(this)
    }

    fun updateSessionsCount(tabCount: Int) {
        val params = layoutParams as CoordinatorLayout.LayoutParams
        val behavior = params.behavior as FloatingActionButtonBehavior?
        val accessibilityManager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager

        this.tabCount = tabCount

        if (behavior != null) {
            if (accessibilityManager.isTouchExplorationEnabled) {
                // Always display erase button if Talk Back is enabled
                behavior.setEnabled(false)
            } else {
                behavior.setEnabled(!keepHidden)
            }
        }

        handleState()
    }

    override fun setVisibility(visibility: Int) {
        // do nothing
    }

    override fun onFinishInflate() {
        if (!keepHidden) {
            this.visibility = View.VISIBLE
            startAnimation(AnimationUtils.loadAnimation(context, R.anim.fab_reveal))
        }
        super.onFinishInflate()
    }

    fun handleState(state: Int = this.state) {
        this.state = state
        when {
            keepHidden -> hide()
            else -> {
                show()
                invalidate()
            }
        }
    }

    override fun onTouch(view: View?, motionEvent: MotionEvent?): Boolean {
        requireNotNull(view)
        requireNotNull(motionEvent)
        return when (motionEvent.action) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = motionEvent.rawX
                downRawY = motionEvent.rawY
                dX = view.x - downRawX
                dY = view.y - downRawY
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val viewWidth = view.width
                val viewHeight = view.height
                val viewParent = view.parent as View
                val parentWidth = viewParent.width
                val parentHeight = viewParent.height

                var newX = motionEvent.rawX + dX
                newX = Math.max(0f, newX)
                newX = Math.min(
                    (parentWidth - viewWidth).toFloat(),
                    newX
                )

                var newY = motionEvent.rawY + dY
                newY = Math.max(0f, newY)
                newY = Math.min(
                    (parentHeight - viewHeight).toFloat(),
                    newY
                )

                view.animate()
                    .x(newX)
                    .y(newY)
                    .setDuration(0)
                    .start()
                true
            }
            MotionEvent.ACTION_UP -> {
                val upRawX = motionEvent.rawX
                val upRawY = motionEvent.rawY
                val upDX = upRawX - downRawX
                val upDY = upRawY - downRawY

                if (Math.abs(upDX) < CLICK_DRAG_TOLERANCE && Math.abs(upDY) < CLICK_DRAG_TOLERANCE) {
                    performClick()
                } else {
                    true
                }
            }
            else -> super.onTouchEvent(motionEvent)
        }
    }

    companion object {
        private const val CLICK_DRAG_TOLERANCE = 10F
    }
}
