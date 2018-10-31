/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.focus.widget

import android.content.Context
import android.support.design.widget.BottomSheetBehavior
import android.support.design.widget.CoordinatorLayout
import android.util.AttributeSet
import android.view.View

class ScrollPeekBottomSheetBehavior<V : View?>(context: Context, attributeSet: AttributeSet) :
    BottomSheetBehavior<V>(context, attributeSet) {

    override fun onNestedPreScroll(
        coordinatorLayout: CoordinatorLayout,
        child: V,
        target: View,
        dx: Int,
        dy: Int,
        consumed: IntArray,
        type: Int
    ) {
        return when {
            dy < 0 && state == STATE_HIDDEN -> {
                state = STATE_COLLAPSED
                consumed[1] = dy
            }
            dy > 0 && state == STATE_COLLAPSED -> {
                state = STATE_HIDDEN
                consumed[1] = dy
            }
            else -> super.onNestedPreScroll(coordinatorLayout, child, target, dx, dy, consumed, type)
        }
    }
}
