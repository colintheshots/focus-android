/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */
@file:Suppress("TooManyFunctions")

package org.mozilla.focus.session.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Build
import android.support.v7.widget.RecyclerView
import android.util.DisplayMetrics
import android.view.View
import android.widget.ImageView
import mozilla.components.browser.session.Session
import org.mozilla.focus.R
import org.mozilla.focus.ext.requireComponents
import org.mozilla.focus.ext.shouldRequestDesktopSite
import org.mozilla.focus.fragment.BrowserFragment
import org.mozilla.focus.session.removeAndCloseSession
import org.mozilla.focus.telemetry.TelemetryWrapper
import org.mozilla.focus.utils.asActivity
import org.mozilla.focus.web.Download
import org.mozilla.focus.web.IWebView
import org.mozilla.focus.web.WebViewProvider
import java.lang.ref.WeakReference

class SessionViewHolder internal constructor(val fragment: BrowserFragment, val view: View) :
    RecyclerView.ViewHolder(view), View.OnClickListener {

    companion object {
        internal const val LAYOUT_ID = R.layout.item_tab
        private const val THUMBNAIL_SCALE = 0.3F
    }

    private var sessionReference: WeakReference<Session> = WeakReference<Session>(null)
    private var webView: View? = null
    private var webViewInstance: IWebView? = null
    private var canvas: Canvas? = null
    private var result: Bitmap? = null
    private var lastPosition: Int = -1

    fun bind(session: Session) {
        this.sessionReference = WeakReference(session)
        val webImageView = view.findViewById<ImageView>(R.id.webview_tab)

        val outline: Drawable? =
            if (session.id == fragment.requireComponents.sessionManager.selectedSession?.id) {
                fragment.context?.getDrawable(R.drawable.tab_outline)
            } else {
                null
            }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            webImageView.foreground = outline
        } else {
            webImageView.background = outline
        }

        val thumbnail = loadThumbnail(view, session)
        webImageView.setImageBitmap(thumbnail)
        webImageView.setOnClickListener(this)

        val closeImageView = view.findViewById<ImageView>(R.id.close_button)
        closeImageView.setOnClickListener(this)
    }

    override fun onClick(view: View) {
        when (view.id) {
            R.id.webview_tab -> {
                val session = sessionReference.get() ?: return
                selectSession(session)
                fragment.closeBottomSheet()
            }
            R.id.close_button -> {
                val session = sessionReference.get() ?: return
                closeSession(session)
            }
        }
    }

    private fun selectSession(session: Session) {
        fragment.requireComponents.sessionManager.select(session)

        TelemetryWrapper.switchTabInTabsTrayEvent()
    }

    private fun closeSession(session: Session) {
        fragment.requireComponents.sessionManager.removeAndCloseSession(session)

        TelemetryWrapper.eraseSingleTabEvent()
    }

    private var widthSpec: Int? = null
    private var heightSpec: Int? = null

    private fun loadThumbnail(windowView: View, session: Session): Bitmap {
        if (result == null || lastPosition != adapterPosition) {
            lastPosition = adapterPosition
            if (webView == null) {
                WebViewProvider.preload(windowView.context)
                webView = WebViewProvider.create(windowView.context, null)
                webViewInstance = webView as IWebView

                val displayMetrics = DisplayMetrics()
                windowView.context.asActivity()!!.windowManager
                    .defaultDisplay
                    .getMetrics(displayMetrics)

                result = Bitmap.createBitmap(
                    (displayMetrics.widthPixels * THUMBNAIL_SCALE).toInt(),
                    (displayMetrics.heightPixels * THUMBNAIL_SCALE).toInt(),
                    Bitmap.Config.RGB_565 // RGB_565 to save memory
                )
                canvas = Canvas(result!!)
                canvas!!.scale(THUMBNAIL_SCALE, THUMBNAIL_SCALE)
                widthSpec = View.MeasureSpec.makeMeasureSpec(displayMetrics.widthPixels, View.MeasureSpec.EXACTLY)
                heightSpec = View.MeasureSpec.makeMeasureSpec(displayMetrics.heightPixels, View.MeasureSpec.EXACTLY)
                webViewInstance!!.setCallback(callback)
                webView!!.measure(widthSpec!!, heightSpec!!)
                val width = webView!!.measuredWidth
                val height = webView!!.measuredHeight
                webView!!.layout(0, 0, width, height)
            }

            webViewInstance!!.setBlockingEnabled(session.trackerBlockingEnabled)
            webViewInstance!!.setRequestDesktop(session.shouldRequestDesktopSite)
            webViewInstance!!.restoreWebViewState(session)
        }
        return result!!
    }

    val callback = object : IWebView.Callback {
        override fun onPageStarted(url: String?) {
        }

        override fun onPageFinished(isSecure: Boolean) {
            webView!!.draw(canvas)
        }

        override fun onSecurityChanged(isSecure: Boolean, host: String?, organization: String?) {
        }

        override fun onProgress(progress: Int) {
            webView!!.draw(canvas)
        }

        override fun onURLChanged(url: String?) {
        }

        override fun onTitleChanged(title: String?) {
        }

        override fun onRequest(isTriggeredByUserGesture: Boolean) {
        }

        override fun onDownloadStart(download: Download?) {
        }

        override fun onLongPress(hitTarget: IWebView.HitTarget?) {
        }

        override fun onEnterFullScreen(callback: IWebView.FullscreenCallback, view: View?) {
        }

        override fun onExitFullScreen() {
        }

        override fun countBlockedTracker() {
        }

        override fun resetBlockedTrackers() {
        }

        override fun onBlockingStateChanged(isBlockingEnabled: Boolean) {
        }

        override fun onHttpAuthRequest(callback: IWebView.HttpAuthCallback, host: String?, realm: String?) {
        }

        override fun onRequestDesktopStateChanged(shouldRequestDesktop: Boolean) {
        }
    }
}
