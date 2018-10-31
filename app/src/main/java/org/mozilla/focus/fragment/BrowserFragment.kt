/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.focus.fragment

import android.Manifest
import android.animation.Animator
import android.app.DownloadManager
import android.app.PendingIntent
import android.arch.lifecycle.LifecycleObserver
import android.arch.lifecycle.Observer
import android.arch.lifecycle.ProcessLifecycleOwner
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.TransitionDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.preference.PreferenceManager
import android.support.annotation.RequiresApi
import android.support.design.widget.AppBarLayout
import android.support.design.widget.BottomSheetBehavior
import android.support.design.widget.BottomSheetBehavior.STATE_EXPANDED
import android.support.design.widget.BottomSheetBehavior.STATE_COLLAPSED
import android.support.design.widget.BottomSheetBehavior.from
import android.support.design.widget.CoordinatorLayout
import android.support.v4.app.ActivityCompat
import android.support.v4.content.ContextCompat
import android.support.v7.widget.GridLayoutManager
import android.support.v7.widget.RecyclerView
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewAnimationUtils
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Toast
import kotlinx.android.synthetic.main.bottom_sheet_sessions.*
import kotlinx.android.synthetic.main.browser_display_toolbar.*
import kotlinx.android.synthetic.main.find_in_page.*
import kotlinx.android.synthetic.main.fragment_browser.*
import kotlinx.android.synthetic.main.toolbar.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import mozilla.components.browser.session.Session
import mozilla.components.browser.session.SessionManager
import mozilla.components.lib.crash.Crash
import mozilla.components.support.utils.ColorUtils
import mozilla.components.support.utils.DownloadUtils
import mozilla.components.support.utils.DrawableUtils
import org.mozilla.focus.R
import org.mozilla.focus.activity.InstallFirefoxActivity
import org.mozilla.focus.activity.MainActivity
import org.mozilla.focus.animation.TransitionDrawableGroup
import org.mozilla.focus.biometrics.BiometricAuthenticationDialogFragment
import org.mozilla.focus.biometrics.BiometricAuthenticationHandler
import org.mozilla.focus.biometrics.Biometrics
import org.mozilla.focus.broadcastreceiver.DownloadBroadcastReceiver
import org.mozilla.focus.exceptions.ExceptionDomains
import org.mozilla.focus.ext.isSearch
import org.mozilla.focus.ext.requireComponents
import org.mozilla.focus.ext.shouldRequestDesktopSite
import org.mozilla.focus.findinpage.FindInPageCoordinator
import org.mozilla.focus.gecko.NestedGeckoView
import org.mozilla.focus.locale.LocaleAwareAppCompatActivity
import org.mozilla.focus.menu.browser.BrowserMenu
import org.mozilla.focus.menu.context.WebContextMenu
import org.mozilla.focus.observer.LoadTimeObserver
import org.mozilla.focus.open.OpenWithFragment
import org.mozilla.focus.popup.PopupUtils
import org.mozilla.focus.session.SessionCallbackProxy
import org.mozilla.focus.session.removeAndCloseAllSessions
import org.mozilla.focus.session.removeAndCloseSession
import org.mozilla.focus.session.ui.SessionsAdapter
import org.mozilla.focus.telemetry.CrashReporterWrapper
import org.mozilla.focus.telemetry.TelemetryWrapper
import org.mozilla.focus.utils.AppConstants
import org.mozilla.focus.utils.Browsers
import org.mozilla.focus.utils.Features
import org.mozilla.focus.utils.StatusBarUtils
import org.mozilla.focus.utils.SupportUtils
import org.mozilla.focus.utils.UrlUtils
import org.mozilla.focus.utils.ViewUtils
import org.mozilla.focus.utils.asActivity
import org.mozilla.focus.web.Download
import org.mozilla.focus.web.HttpAuthenticationDialogBuilder
import org.mozilla.focus.web.IWebView
import org.mozilla.focus.widget.AnimatedProgressBar
import org.mozilla.focus.widget.FloatingAddTabButton
import org.mozilla.focus.widget.FloatingEraseButton
import org.mozilla.focus.widget.FloatingSessionsButton
import java.lang.ref.WeakReference
import java.net.MalformedURLException
import java.net.URL
import kotlin.coroutines.CoroutineContext

/**
 * Fragment for displaying the browser UI.
 */
@Suppress("LargeClass", "TooManyFunctions")
class BrowserFragment : WebFragment(), LifecycleObserver, View.OnClickListener,
    DownloadDialogFragment.DownloadDialogListener, View.OnLongClickListener,
    BiometricAuthenticationDialogFragment.BiometricAuthenticationListener,
    CoroutineScope {

    private var pendingDownload: Download? = null
    private var backgroundTransitionGroup: TransitionDrawableGroup? = null
    private var progressView: AnimatedProgressBar? = null
    private var blockView: FrameLayout? = null
    private var menuView: ImageButton? = null
    private var menuWeakReference: WeakReference<BrowserMenu>? = WeakReference<BrowserMenu>(null)

    /**
     * Container for custom video views shown in fullscreen mode.
     */
    private var videoContainer: ViewGroup? = null

    private var isFullscreen: Boolean = false
    private var findInPageViewHeight = 0
    private var findInPageView: View? = null

    private var sessionsAdapter: SessionsAdapter? = null
    private var fullscreenCallback: IWebView.FullscreenCallback? = null

    private var manager: DownloadManager? = null

    private var downloadBroadcastReceiver: DownloadBroadcastReceiver? = null

    private val findInPageCoordinator = FindInPageCoordinator()

    private var biometricController: BiometricAuthenticationHandler? = null

    private var job = Job()
    override val coroutineContext: CoroutineContext
        get() = job + Dispatchers.Main

    // The url property is used for things like sharing the current URL. We could try to use the webview,
    // but sometimes it's null, and sometimes it returns a null URL. Sometimes it returns a data:
    // URL for error pages. The URL we show in the toolbar is (A) always correct and (B) what the
    // user is probably expecting to share, so lets use that here:
    val url: String
        get() = display_url.text.toString()

    var openedFromExternalLink: Boolean = false

    override lateinit var session: Session
        private set

    override val initialUrl: String?
        get() = session.url

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)

        val sessionUUID = arguments!!.getString(ARGUMENT_SESSION_UUID) ?: throw IllegalAccessError("No session exists")

        session = requireComponents.sessionManager.findSessionById(sessionUUID) ?: Session("about:blank")

        findInPageCoordinator.matches.observe(
            this,
            Observer { matches -> updateFindInPageResult(matches!!.first, matches.second) })
    }

    override fun onPause() {
        super.onPause()

        if (Biometrics.isBiometricsEnabled(requireContext())) {
            biometricController?.stopListening()
            view!!.alpha = 0f
        }

        requireContext().unregisterReceiver(downloadBroadcastReceiver)

        if (isFullscreen) {
            getWebView()?.exitFullscreen()
        }

        val menu = menuWeakReference!!.get()
        if (menu != null) {
            menu.dismiss()

            menuWeakReference!!.clear()
        }
    }

    override fun onStop() {
        job.cancel()
        super.onStop()
    }

    override fun inflateLayout(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        if (savedInstanceState != null && savedInstanceState.containsKey(RESTORE_KEY_DOWNLOAD)) {
            // If this activity was destroyed before we could start a download (e.g. because we were waiting for a
            // permission) then restore the download object.
            pendingDownload = savedInstanceState.getParcelable(RESTORE_KEY_DOWNLOAD)
        }

        return inflater.inflate(R.layout.fragment_browser, container, false)
    }

    @Suppress("LongMethod")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {

        videoContainer = view.findViewById(R.id.video_container)
        display_url.setOnLongClickListener(this)
        progressView = view.findViewById(R.id.progress)
        findInPageView = view.findViewById(R.id.find_in_page)

        swipe_refresh.setColorSchemeResources(R.color.colorAccent)
        swipe_refresh.isEnabled = Features.SWIPE_TO_REFRESH
        swipe_refresh.setOnRefreshListener {
            reload()

            TelemetryWrapper.swipeReloadEvent()
        }

        findInPageView = view.findViewById(R.id.find_in_page)

        queryText.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}

                override fun afterTextChanged(s: Editable) {}

                override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                    if (!TextUtils.isEmpty(s)) {
                        getWebView()?.findAllAsync(s.toString())
                    }
                }
            }
        )
        queryText.setOnClickListener(this)
        queryText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                ViewUtils.hideKeyboard(queryText)
                queryText.isCursorVisible = false
            }
            false
        }

        previousResult.setOnClickListener(this)
        nextResult.setOnClickListener(this)
        close_find_in_page.setOnClickListener(this)

        setShouldRequestDesktop(session.shouldRequestDesktopSite)

        LoadTimeObserver.addObservers(session, this)

        refresh?.setOnClickListener(this)
        stop?.setOnClickListener(this)
        forward?.setOnClickListener(this)
        back?.setOnClickListener(this)

        block_image.setImageResource(R.drawable.ic_tracking_protection_disabled)

        security_info.setImageResource(R.drawable.ic_internet)
        security_info.setOnClickListener(this)
        view.findViewById<ImageButton>(R.id.menuView).setOnClickListener(this)

        // Pre-calculate the height of the find in page UI so that we can accurately add padding
        // to the WebView when we present it.
        find_in_page.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        findInPageViewHeight = find_in_page.measuredHeight

        blockView = view.findViewById(R.id.block)
        menuView = view.findViewById(R.id.menuView)
        menuView!!.setOnClickListener(this)

        if (session.isCustomTabSession()) {
            initialiseCustomTabUi(view)
        } else {
            initialiseNormalBrowserUi(view)
        }

        session.register(sessionObserver, owner = this)

        // We need to update the views with the initial values. Other than LiveData an Observer doesn't get the initial
        // values automatically yet.
        // We want to change that in Android Components: https://github.com/mozilla-mobile/android-components/issues/665
        sessionObserver.apply {
            onTrackerBlockingEnabledChanged(session, session.trackerBlockingEnabled)
            onLoadingStateChanged(session, session.loading)
            onUrlChanged(session, session.url)
            onSecurityChanged(session, session.securityInfo)
        }
    }

    private fun initialiseNormalBrowserUi(view: View) {
        val eraseButton = view.findViewById<FloatingEraseButton>(R.id.erase)
        val addTabButton = view.findViewById<FloatingAddTabButton>(R.id.fab_add_tab_bottom_sheet)
        eraseButton.setOnClickListener(this)
        display_url.setOnClickListener(this)

        val sessionManager = requireComponents.sessionManager
        sessionManager.register(object : SessionManager.Observer {
            override fun onSessionAdded(session: Session) {
                eraseButton.updateSessionsCount(sessionManager.sessions.size)
                addTabButton.tabCount = sessionManager.sessions.size
            }

            override fun onSessionRemoved(session: Session) {
                eraseButton.updateSessionsCount(sessionManager.sessions.size)
                addTabButton.tabCount = sessionManager.sessions.size
            }

            override fun onAllSessionsRemoved() {
                eraseButton.updateSessionsCount(sessionManager.sessions.size)
                addTabButton.tabCount = sessionManager.sessions.size
            }
        })

        eraseButton.updateSessionsCount(sessionManager.sessions.size)
        addTabButton.tabCount = sessionManager.sessions.size

        initialiseBottomSheet(view, fab_add_tab_bottom_sheet)
    }

    private fun initialiseBottomSheet(
        view: View,
        addTabButton: FloatingAddTabButton?
    ) {
        bottom_sheet_sessions_layout?.visibility = View.VISIBLE
        handle_bottom_sheet?.setOnClickListener(this)
        val recyclerViewTabs: RecyclerView? = view.findViewById(R.id.recycler_tabs)
        recyclerViewTabs?.setHasFixedSize(true)
        sessionsAdapter = SessionsAdapter(this, requireComponents.sessionManager.sessions)
        recyclerViewTabs?.adapter = sessionsAdapter
        requireComponents.sessionManager.register(sessionsAdapter!!, owner = this)
        recyclerViewTabs?.layoutManager = GridLayoutManager(context, SPAN_COUNT)
        addTabButton?.setOnClickListener(this)
        bottomSheetBehavior.setBottomSheetCallback(bottomSheetBehaviorCallback)
    }

    private fun initialiseCustomTabUi(view: View) {
        bottom_sheet_sessions_layout?.visibility = View.GONE
        val customTabConfig = session.customTabConfig!!

        // Unfortunately there's no simpler way to have the FAB only in normal-browser mode.
        // - ViewStub: requires splitting attributes for the FAB between the ViewStub, and actual FAB layout file.
        //             Moreover, the layout behaviour just doesn't work unless you set it programatically.
        // - View.GONE: doesn't work because the layout-behaviour makes the FAB visible again when scrolling.
        // - Adding at runtime: works, but then we need to use a separate layout file (and you need
        //   to set some attributes programatically, same as ViewStub).
        val erase = view.findViewById<FloatingEraseButton>(R.id.erase)
        val eraseContainer = erase.parent as ViewGroup
        eraseContainer.removeView(erase)

        val sessions = view.findViewById<FloatingSessionsButton>(R.id.tabs)
        eraseContainer.removeView(sessions)

        val textColor: Int

        if (customTabConfig.toolbarColor != null) {
            urlbar.setBackgroundColor(customTabConfig.toolbarColor!!)

            textColor = ColorUtils.getReadableTextColor(customTabConfig.toolbarColor!!)
            display_url.setTextColor(textColor)
        } else {
            textColor = Color.WHITE
        }

        val closeButton = view.findViewById<View>(R.id.customtab_close) as ImageView

        closeButton.visibility = View.VISIBLE
        closeButton.setOnClickListener(this)

        if (customTabConfig.closeButtonIcon != null) {
            closeButton.setImageBitmap(customTabConfig.closeButtonIcon)
        } else {
            // Always set the icon in case it's been overridden by a previous CT invocation
            val closeIcon = DrawableUtils.loadAndTintDrawable(requireContext(), R.drawable.ic_close, textColor)

            closeButton.setImageDrawable(closeIcon)
        }

        if (customTabConfig.disableUrlbarHiding) {
            val params = urlbar.layoutParams as AppBarLayout.LayoutParams
            params.scrollFlags = 0
        }

        if (customTabConfig.actionButtonConfig != null) {
            val actionButton = view.findViewById<View>(R.id.customtab_actionbutton) as ImageButton
            actionButton.visibility = View.VISIBLE

            actionButton.setImageBitmap(customTabConfig.actionButtonConfig!!.icon)
            actionButton.contentDescription = customTabConfig.actionButtonConfig!!.description

            val pendingIntent = customTabConfig.actionButtonConfig!!.pendingIntent

            actionButton.setOnClickListener {
                try {
                    val intent = Intent()
                    intent.data = Uri.parse(url)

                    pendingIntent.send(context, 0, intent)
                } catch (e: PendingIntent.CanceledException) {
                    // There's really nothing we can do here...
                }

                TelemetryWrapper.customTabActionButtonEvent()
            }
        } else {
            // If the third-party app doesn't provide an action button configuration then we are
            // going to disable a "Share" button in the toolbar instead.

            val shareButton = view.findViewById<ImageButton>(R.id.customtab_actionbutton)
            shareButton.visibility = View.VISIBLE
            shareButton.setImageDrawable(
                DrawableUtils.loadAndTintDrawable(
                    requireContext(),
                    R.drawable.ic_share,
                    textColor
                )
            )
            shareButton.contentDescription = getString(R.string.menu_share)
            shareButton.setOnClickListener { shareCurrentUrl() }
        }

        // We need to tint some icons.. We already tinted the close button above. Let's tint our other icons too.
        security_info.setColorFilter(textColor)

        val menuIcon = DrawableUtils.loadAndTintDrawable(requireContext(), R.drawable.ic_menu, textColor)
        menuView!!.setImageDrawable(menuIcon)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        if (pendingDownload != null) {
            // We were not able to start this download yet (waiting for a permission). Save this download
            // so that we can start it once we get restored and receive the permission.
            outState.putParcelable(RESTORE_KEY_DOWNLOAD, pendingDownload)
        }
    }

    @Suppress("ComplexMethod")
    override fun createCallback(): IWebView.Callback {
        return SessionCallbackProxy(session, object : IWebView.Callback {
            override fun onPageStarted(url: String) {}

            override fun onPageFinished(isSecure: Boolean) {}

            override fun onSecurityChanged(isSecure: Boolean, host: String, organization: String) {}

            override fun onURLChanged(url: String) {}

            override fun onTitleChanged(title: String) {}

            override fun onRequest(isTriggeredByUserGesture: Boolean) {}

            override fun onProgress(progress: Int) {}

            override fun countBlockedTracker() {}

            override fun resetBlockedTrackers() {}

            override fun onBlockingStateChanged(isBlockingEnabled: Boolean) {}

            override fun onHttpAuthRequest(callback: IWebView.HttpAuthCallback, host: String, realm: String) {
                val builder = HttpAuthenticationDialogBuilder.Builder(activity, host, realm)
                    .setOkListener { _, _, username, password -> callback.proceed(username, password) }
                    .setCancelListener { callback.cancel() }
                    .build()

                builder.createDialog()
                builder.show()
            }

            override fun onRequestDesktopStateChanged(shouldRequestDesktop: Boolean) {}

            override fun onLongPress(hitTarget: IWebView.HitTarget) {
                WebContextMenu.show(requireActivity(), this, hitTarget, session)
            }

            override fun onEnterFullScreen(callback: IWebView.FullscreenCallback, view: View?) {
                fullscreenCallback = callback
                isFullscreen = true

                // View is passed in as null for GeckoView fullscreen
                if (view != null) {
                    // Hide browser UI and web content
                    browser_container.visibility = View.INVISIBLE

                    // Add view to video container and make it visible
                    val params = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    videoContainer!!.addView(view, params)
                    videoContainer!!.visibility = View.VISIBLE

                    // Switch to immersive mode: Hide system bars other UI controls
                    switchToImmersiveMode()
                } else {
                    appbar?.setExpanded(false, true)
                    (getWebView() as? NestedGeckoView)?.isNestedScrollingEnabled = false
                    // Hide status bar when entering fullscreen with GeckoView
                    status_bar_background.visibility = View.GONE
                    // Switch to immersive mode: Hide system bars other UI controls
                    switchToImmersiveMode()
                }
            }

            override fun onExitFullScreen() {
                if (AppConstants.isGeckoBuild) {
                    appbar?.setExpanded(true, true)
                    (getWebView() as? NestedGeckoView)?.isNestedScrollingEnabled = true
                }

                isFullscreen = false

                // Remove custom video views and hide container
                videoContainer!!.removeAllViews()
                videoContainer!!.visibility = View.GONE

                // Show browser UI and web content again
                browser_container.visibility = View.VISIBLE

                // Show status bar again (hidden in GeckoView versions)
                status_bar_background.visibility = View.VISIBLE

                exitImmersiveModeIfNeeded()

                // Notify renderer that we left fullscreen mode.
                if (fullscreenCallback != null) {
                    fullscreenCallback!!.fullScreenExited()
                    fullscreenCallback = null
                }
            }

            override fun onDownloadStart(download: Download) {
                if (PackageManager.PERMISSION_GRANTED == ContextCompat.checkSelfPermission(
                        requireContext(),
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                    )
                ) {
                    // Long press image displays its own dialog and we handle other download cases here
                    if (!isDownloadFromLongPressImage(download)) {
                        showDownloadPromptDialog(download)
                    } else {
                        // Download dialog has already been shown from long press on image. Proceed with download.
                        queueDownload(download)
                    }
                } else {
                    // We do not have the permission to write to the external storage. Request the permission and start
                    // the  download from onRequestPermissionsResult().
                    val activity = activity ?: return

                    pendingDownload = download

                    ActivityCompat.requestPermissions(
                        activity,
                        arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                        REQUEST_CODE_STORAGE_PERMISSION
                    )
                }
            }
        })
    }

    /**
     * Checks a download's destination directory to determine if it is being called from
     * a long press on an image or otherwise.
     */
    private fun isDownloadFromLongPressImage(download: Download): Boolean {
        return download.destinationDirectory == Environment.DIRECTORY_PICTURES
    }

    /**
     * Hide system bars. They can be revealed temporarily with system gestures, such as swiping from
     * the top of the screen. These transient system bars will overlay app’s content, may have some
     * degree of transparency, and will automatically hide after a short timeout.
     */
    private fun switchToImmersiveMode() {
        val activity = activity ?: return

        val window = activity.window
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
    }

    /**
     * Show the system bars again.
     */
    private fun exitImmersiveModeIfNeeded() {
        val activity = activity ?: return

        if (WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON and activity.window.attributes.flags == 0) {
            // We left immersive mode already.
            return
        }

        val window = activity.window
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
    }

    override fun onDestroy() {
        super.onDestroy()

        // This fragment might get destroyed before the user left immersive mode (e.g. by opening another URL from an
        // app). In this case let's leave immersive mode now when the fragment gets destroyed.
        exitImmersiveModeIfNeeded()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        if (requestCode != REQUEST_CODE_STORAGE_PERMISSION) {
            return
        }

        if (grantResults.isEmpty() || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
            // We didn't get the storage permission: We are not able to start this download.
            pendingDownload = null
        }

        // The actual download dialog will be shown from onResume(). If this activity/fragment is
        // getting restored then we need to 'resume' first before we can show a dialog (attaching
        // another fragment).
    }

    internal fun showDownloadPromptDialog(download: Download) {
        val fragmentManager = childFragmentManager

        if (fragmentManager.findFragmentByTag(DownloadDialogFragment.FRAGMENT_TAG) != null) {
            // We are already displaying a download dialog fragment (Probably a restored fragment).
            // No need to show another one.
            return
        }

        val downloadDialogFragment = DownloadDialogFragment.newInstance(download)

        try {
            downloadDialogFragment.show(fragmentManager, DownloadDialogFragment.FRAGMENT_TAG)
        } catch (e: IllegalStateException) {
            // It can happen that at this point in time the activity is already in the background
            // and onSaveInstanceState() has already been called. Fragment transactions are not
            // allowed after that anymore. It's probably safe to guess that the user might not
            // be interested in the download at this point. So we could just *not* show the dialog.
            // Unfortunately we can't call commitAllowingStateLoss() because committing the
            // transaction is happening inside the DialogFragment code. Therefore we just swallow
            // the exception here. Gulp!
        }
    }

    private fun showCrashReporter(crash: Crash) {
        val fragmentManager = requireActivity().supportFragmentManager

        Log.e("crash:", crash.toString())
        if (crashReporterIsVisible()) {
            // We are already displaying the crash reporter
            // No need to show another one.
            return
        }

        val crashReporterFragment = CrashReporterFragment.create()

        crashReporterFragment.onCloseTabPressed = { sendCrashReport ->
            if (sendCrashReport) { CrashReporterWrapper.submitCrash(crash) }
            erase(true)
            hideCrashReporter()
        }

        fragmentManager
                .beginTransaction()
                .addToBackStack(null)
                .add(R.id.crash_container, crashReporterFragment, CrashReporterFragment.FRAGMENT_TAG)
                .commit()

        crash_container.visibility = View.VISIBLE
        erase.hide()
        security_info?.setImageResource(R.drawable.ic_firefox)
        menuView?.visibility = View.GONE
        display_url?.text = requireContext().getString(R.string.tab_crash_report_title)
    }

    private fun hideCrashReporter() {
        val fragmentManager = requireActivity().supportFragmentManager
        val fragment = fragmentManager.findFragmentByTag(CrashReporterFragment.FRAGMENT_TAG)
                ?: return

        fragmentManager
                .beginTransaction()
                .remove(fragment)
                .commit()

        crash_container.visibility = View.GONE
        erase.show()
        security_info?.setImageResource(R.drawable.ic_internet)
        menuView?.visibility = View.VISIBLE
        display_url?.text = session.let {
            if (it.isSearch) it.searchTerms else it.url
        }
    }

    fun crashReporterIsVisible(): Boolean = requireActivity().supportFragmentManager.let {
        it.findFragmentByTag(CrashReporterFragment.FRAGMENT_TAG)?.isVisible ?: false
    }

    internal fun showAddToHomescreenDialog(url: String, title: String) {
        val fragmentManager = childFragmentManager

        if (fragmentManager.findFragmentByTag(AddToHomescreenDialogFragment.FRAGMENT_TAG) != null) {
            // We are already displaying a homescreen dialog fragment (Probably a restored fragment).
            // No need to show another one.
            return
        }

        val addToHomescreenDialogFragment = AddToHomescreenDialogFragment.newInstance(
            url,
            title,
            session.trackerBlockingEnabled,
            session.shouldRequestDesktopSite
        )

        try {
            addToHomescreenDialogFragment.show(
                fragmentManager,
                AddToHomescreenDialogFragment.FRAGMENT_TAG
            )
        } catch (e: IllegalStateException) {
            // It can happen that at this point in time the activity is already in the background
            // and onSaveInstanceState() has already been called. Fragment transactions are not
            // allowed after that anymore. It's probably safe to guess that the user might not
            // be interested in adding to homescreen now.
        }
    }

    override fun onFinishDownloadDialog(download: Download?, shouldDownload: Boolean) {
        if (shouldDownload) {
            queueDownload(download)
        }
    }

    override fun biometricCreateNewSessionWithLink() {
        for (session in requireComponents.sessionManager.sessions) {
            if (session != requireComponents.sessionManager.selectedSession) {
                requireComponents.sessionManager.remove(session)
            }
        }

        // Purposefully not calling onAuthSuccess in case we add to that function in the future
        view!!.alpha = 1f
    }

    override fun biometricCreateNewSession() {
        requireComponents.sessionManager.removeAndCloseAllSessions()
    }

    override fun onAuthSuccess() {
        view!!.alpha = 1f
    }

    override fun onCreateViewCalled() {
        manager = requireContext().getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        downloadBroadcastReceiver = DownloadBroadcastReceiver(browser_container, manager)

        val webView = getWebView()
        webView?.setFindListener(findInPageCoordinator)
    }

    override fun onResume() {
        super.onResume()

        if (job.isCancelled) {
            job = Job()
        }

        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        requireContext().registerReceiver(downloadBroadcastReceiver, filter)

        if (pendingDownload != null && PackageManager.PERMISSION_GRANTED == ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
        ) {
            // There's a pending download (waiting for the storage permission) and now we have the
            // missing permission: Show the dialog to ask whether the user wants to actually proceed
            // with downloading this file.
            showDownloadPromptDialog(pendingDownload!!)
            pendingDownload = null
        }

        StatusBarUtils.getStatusBarHeight(status_bar_background) { statusBarHeight ->
            status_bar_background.layoutParams.height = statusBarHeight
        }

        if (Biometrics.isBiometricsEnabled(requireContext())) {
            if (biometricController == null) {
                biometricController = BiometricAuthenticationHandler(context!!)
            }

            displayBiometricPromptIfNeeded()
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                biometricController?.stopListening()
            }

            biometricController = null
            view!!.alpha = 1f
        }

        updateFabVisibility(bottomSheetBehavior.state)
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    private fun displayBiometricPromptIfNeeded() {
        val fragmentManager = childFragmentManager

        // Check that we need to auth and that the fragment isn't already displayed
        if (biometricController!!.needsAuth || openedFromExternalLink) {
            view!!.alpha = 0f
            biometricController!!.startAuthentication(openedFromExternalLink)
            openedFromExternalLink = false

            // Are we already displaying the biometric fragment?
            if (fragmentManager.findFragmentByTag(BiometricAuthenticationDialogFragment.FRAGMENT_TAG) != null) {
                return
            }

            try {
                biometricController!!.biometricFragment!!.show(
                    fragmentManager,
                    BiometricAuthenticationDialogFragment.FRAGMENT_TAG
                )
            } catch (e: IllegalStateException) {
                // It can happen that at this point in time the activity is already in the background
                // and onSaveInstanceState() has already been called. Fragment transactions are not
                // allowed after that anymore.
            }
        } else {
            view!!.alpha = 1f
        }
    }

    /**
     * Use Android's Download Manager to queue this download.
     */
    private fun queueDownload(download: Download?) {
        if (download == null) {
            return
        }

        val fileName = if (!TextUtils.isEmpty(download.fileName))
            download.fileName
        else
            DownloadUtils.guessFileName(
                download.contentDisposition,
                download.url,
                download.mimeType
            )

        val request = DownloadManager.Request(Uri.parse(download.url))
            .addRequestHeader("Referer", url)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setMimeType(download.mimeType)

        if (!AppConstants.isGeckoBuild) {
            val cookie = CookieManager.getInstance().getCookie(download.url)
            request.addRequestHeader("Cookie", cookie)
                .addRequestHeader("User-Agent", download.userAgent)
        }

        try {
            request.setDestinationInExternalPublicDir(download.destinationDirectory, fileName)
        } catch (e: IllegalStateException) {
            Log.e(FRAGMENT_TAG, "Cannot create download directory")
            return
        }

        request.allowScanningByMediaScanner()

        @Suppress("TooGenericExceptionCaught")
        try {
            val downloadReference = manager!!.enqueue(request)
            downloadBroadcastReceiver!!.addQueuedDownload(downloadReference)
        } catch (e: RuntimeException) {
            Log.e(FRAGMENT_TAG, "Download failed: $e")
        }
    }

    @Suppress("ComplexMethod")
    fun onBackPressed(): Boolean {
        if (find_in_page.visibility == View.VISIBLE) {
            hideFindInPage()
        } else if (isFullscreen) {
            val webView = getWebView()
            webView?.exitFullscreen()
        } else if (bottomSheetBehavior.state == STATE_EXPANDED) {
            closeBottomSheet()
        } else if (canGoBack()) {
            // Go back in web history
            goBack()
        } else if (session.source == Session.Source.ACTION_VIEW || session.isCustomTabSession()) {
            TelemetryWrapper.eraseBackToAppEvent()

            // This session has been started from a VIEW intent. Go back to the previous app
            // immediately and erase the current browsing session.
            erase(false)

            // If there are no other sessions then we remove the whole task because otherwise
            // the old session might still be partially visible in the app switcher.
            if (requireComponents.sessionManager.sessions.isEmpty()) {
                requireActivity().finishAndRemoveTask()
            } else {
                requireActivity().finish()
            }
            // We can't show a snackbar outside of the app. So let's show a toast instead.
            Toast.makeText(context, R.string.feedback_erase_custom_tab, Toast.LENGTH_SHORT).show()
        } else if (requireComponents.sessionManager.sessions.size > 1) {
            requireComponents.sessionManager.remove(session)
        } else {
            // Just go back to the home screen.
            TelemetryWrapper.eraseBackToHomeEvent()

            erase(false)
        }

        return true
    }

    fun erase(allSessions: Boolean) {
        val webView = getWebView()
        val context = context

        // Notify the user their session has been erased if Talk Back is enabled:
        if (context != null) {
            val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            if (manager.isEnabled) {
                val event = AccessibilityEvent.obtain()
                event.eventType = AccessibilityEvent.TYPE_ANNOUNCEMENT
                event.className = javaClass.name
                event.packageName = getContext()!!.packageName
                event.text.add(getString(R.string.feedback_erase))
            }
        }

        webView?.cleanup()

        if (allSessions) {
            requireComponents.sessionManager.removeAndCloseAllSessions()
        } else {
            requireComponents.sessionManager.removeAndCloseSession(session)
        }
    }

    private fun shareCurrentUrl() {
        val url = url

        val shareIntent = Intent(Intent.ACTION_SEND)
        shareIntent.type = "text/plain"
        shareIntent.putExtra(Intent.EXTRA_TEXT, url)

        // Use title from webView if it's content matches the url
        val webView = getWebView()
        if (webView != null) {
            val contentUrl = webView.url
            if (contentUrl != null && contentUrl == url) {
                val contentTitle = webView.title
                shareIntent.putExtra(Intent.EXTRA_SUBJECT, contentTitle)
            }
        }

        startActivity(Intent.createChooser(shareIntent, getString(R.string.share_dialog_title)))

        TelemetryWrapper.shareEvent()
    }

    fun closeBottomSheet() {
        bottomSheetBehavior.state = STATE_COLLAPSED
    }

    fun openBottomSheet() {
        bottomSheetBehavior.state = STATE_EXPANDED
    }

    @Suppress("ComplexMethod")
    override fun onClick(view: View) {
        when (view.id) {
            R.id.menuView -> {
                val menu = BrowserMenu(activity, this, session.customTabConfig)
                menu.show(menuView)

                menuWeakReference = WeakReference(menu)
            }

            R.id.display_url -> if (
                    !crashReporterIsVisible() &&
                    requireComponents.sessionManager.findSessionById(session.id) != null) {
                val urlFragment = UrlInputFragment
                    .createWithSession(session, display_url)

                requireActivity().supportFragmentManager
                    .beginTransaction()
                    .add(R.id.container, urlFragment, UrlInputFragment.FRAGMENT_TAG)
                    .commit()
            }

            R.id.erase -> {
                TelemetryWrapper.eraseEvent()

                erase(true)
            }

            R.id.back -> {
                goBack()
            }

            R.id.forward -> {
                val webView = getWebView()
                webView?.goForward()
            }

            R.id.refresh -> {
                reload()

                TelemetryWrapper.menuReloadEvent()
            }

            R.id.stop -> {
                val webView = getWebView()
                webView?.stopLoading()
            }

            R.id.open_in_firefox_focus -> {
                session.customTabConfig = null

                requireComponents.sessionManager.select(session)

                val webView = getWebView()
                webView?.releaseGeckoSession()
                webView?.saveWebViewState(session)

                val intent = Intent(context, MainActivity::class.java)
                intent.action = Intent.ACTION_MAIN
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                startActivity(intent)

                TelemetryWrapper.openFullBrowser()

                val activity = activity
                activity?.finish()
            }

            R.id.share -> {
                shareCurrentUrl()
            }

            R.id.settings -> (activity as LocaleAwareAppCompatActivity).openPreferences()

            R.id.open_default -> {
                val browsers = Browsers(requireContext(), url)

                val defaultBrowser = browsers.defaultBrowser
                    ?: // We only add this menu item when a third party default exists, in
                    // BrowserMenuAdapter.initializeMenu()
                    throw IllegalStateException("<Open with \$Default> was shown when no default browser is set")

                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                intent.setPackage(defaultBrowser.packageName)
                startActivity(intent)

                if (browsers.isFirefoxDefaultBrowser) {
                    TelemetryWrapper.openFirefoxEvent()
                } else {
                    TelemetryWrapper.openDefaultAppEvent()
                }
            }

            R.id.open_select_browser -> {
                val browsers = Browsers(requireContext(), url)

                val apps = browsers.installedBrowsers
                val store = if (browsers.hasFirefoxBrandedBrowserInstalled())
                    null
                else
                    InstallFirefoxActivity.resolveAppStore(requireContext())

                val fragment = OpenWithFragment.newInstance(
                    apps,
                    url,
                    store
                )
                fragment.show(fragmentManager!!, OpenWithFragment.FRAGMENT_TAG)

                TelemetryWrapper.openSelectionEvent()
            }

            R.id.customtab_close -> {
                erase(false)
                requireActivity().finish()

                TelemetryWrapper.closeCustomTabEvent()
            }

            R.id.help -> {
                val session = Session(SupportUtils.HELP_URL, source = Session.Source.MENU)
                requireComponents.sessionManager.add(session, selected = true)
            }

            R.id.help_trackers -> {
                val url = SupportUtils.getSumoURLForTopic(context!!, SupportUtils.SumoTopic.TRACKERS)
                val session = Session(url, source = Session.Source.MENU)

                requireComponents.sessionManager.add(session, selected = true)
            }

            R.id.add_to_homescreen -> {
                val webView = getWebView() ?: return

                val url = webView.url
                val title = webView.title
                showAddToHomescreenDialog(url, title)
            }

            R.id.security_info -> if (!crashReporterIsVisible()) { showSecurityPopUp() }

            R.id.report_site_issue -> {
                val reportUrl = String.format(SupportUtils.REPORT_SITE_ISSUE_URL, url)
                val session = Session(reportUrl, source = Session.Source.MENU)
                requireComponents.sessionManager.add(session, selected = true)

                TelemetryWrapper.reportSiteIssueEvent()
            }

            R.id.find_in_page -> {
                showFindInPage()
                ViewUtils.showKeyboard(queryText)
                TelemetryWrapper.findInPageMenuEvent()
            }

            R.id.queryText -> queryText.isCursorVisible = true

            R.id.nextResult -> {
                ViewUtils.hideKeyboard(queryText)
                queryText.isCursorVisible = false

                getWebView()?.findNext(true)
            }

            R.id.previousResult -> {
                ViewUtils.hideKeyboard(queryText)
                queryText.isCursorVisible = false

                getWebView()?.findNext(false)
            }

            R.id.close_find_in_page -> {
                hideFindInPage()
            }

            R.id.handle_bottom_sheet -> {
                when (bottomSheetBehavior.state) {
                    STATE_EXPANDED -> {
                        closeBottomSheet()
                    }
                    STATE_COLLAPSED ->
                        openBottomSheet()
                    else -> {
                    }
                }
            }

            R.id.fab_add_tab_bottom_sheet -> {
                val displayMetrics = DisplayMetrics()
                view.context.asActivity()!!.windowManager.defaultDisplay.getMetrics(displayMetrics)
                val circView = view.rootView.findViewById<View>(R.id.circular_reveal_view)
                val midCircX = (view.x + view.width / 2).toInt()
                val midCircY = (view.y + view.height / 2 + bottom_sheet_sessions_layout.y).toInt()
                val circularReveal = ViewAnimationUtils.createCircularReveal(
                    circView, midCircX, midCircY, 0F,
                    Math.hypot(displayMetrics.widthPixels.toDouble(),
                        displayMetrics.heightPixels.toDouble()).toFloat()
                )
                circularReveal.interpolator = AccelerateDecelerateInterpolator()
                circularReveal.duration = NEW_TAB_ANIMATION_DURATION
                if (context == null) return // handle multiple clicks

                circularReveal.addListener(object : Animator.AnimatorListener {
                    override fun onAnimationRepeat(animation: Animator?) {
                    }

                    override fun onAnimationEnd(animation: Animator?) {
                        requireActivity().supportFragmentManager
                            .beginTransaction()
                            .addToBackStack(null)
                            .replace(R.id.container,
                                UrlInputFragment.createWithBackground(),
                                UrlInputFragment.FRAGMENT_TAG)
                            .commit()
                    }

                    override fun onAnimationCancel(animation: Animator?) {
                    }

                    override fun onAnimationStart(animation: Animator?) {
                        circView.visibility = View.VISIBLE
                    }
                })
                circularReveal.start()
            }

            else -> throw IllegalArgumentException("Unhandled menu item in BrowserFragment")
        }
    }

    @Suppress("MagicNumber")
    private fun updateToolbarButtonStates(isLoading: Boolean) {
        @Suppress("ComplexCondition")
        if (forward == null || back == null || refresh == null || stop == null) {
            return
        }

        val webView = getWebView() ?: return

        val canGoForward = webView.canGoForward()
        val canGoBack = webView.canGoBack()

        forward!!.isEnabled = canGoForward
        forward!!.alpha = if (canGoForward) 1.0f else 0.5f
        back!!.isEnabled = canGoBack
        back!!.alpha = if (canGoBack) 1.0f else 0.5f

        refresh!!.visibility = if (isLoading) View.GONE else View.VISIBLE
        stop!!.visibility = if (isLoading) View.VISIBLE else View.GONE
    }

    fun canGoForward(): Boolean = getWebView()?.canGoForward() ?: false

    fun canGoBack(): Boolean = getWebView()?.canGoBack() ?: false

    fun goBack() = getWebView()?.goBack()

    fun loadUrl(url: String) {
        val webView = getWebView()
        if (webView != null && !TextUtils.isEmpty(url)) {
            webView.loadUrl(url)
        }
    }

    fun reload() = getWebView()?.reload()

    fun setBlockingUI(enabled: Boolean) {
        val webView = getWebView()
        webView?.setBlockingEnabled(enabled)

        status_bar_background.setBackgroundResource(
            if (enabled)
                R.drawable.animated_background
            else
                R.drawable.animated_background_disabled
        )

        backgroundTransitionGroup = if (!session.isCustomTabSession()) {
            // Only update the toolbar background if this is not a custom tab. Custom tabs set their
            // own color and we do not want to override this here.
            urlbar.setBackgroundResource(
                if (enabled)
                    R.drawable.animated_background
                else
                    R.drawable.animated_background_disabled
            )

            TransitionDrawableGroup(
                urlbar.background as TransitionDrawable,
                status_bar_background.background as TransitionDrawable
            )
        } else {
            TransitionDrawableGroup(
                status_bar_background.background as TransitionDrawable
            )
        }
    }

    fun setShouldRequestDesktop(enabled: Boolean) {
        if (enabled) {
            PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putBoolean(
                    requireContext().getString(R.string.has_requested_desktop),
                    true
                ).apply()
        }

        getWebView()?.setRequestDesktop(enabled)
    }

    private fun showSecurityPopUp() {
        // Don't show Security Popup if the page is loading
        if (session.loading) {
            return
        }
        val securityPopup = PopupUtils.createSecurityPopup(requireContext(), session)
        if (securityPopup != null) {
            securityPopup.setOnDismissListener { popup_tint.visibility = View.GONE }
            securityPopup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            securityPopup.animationStyle = android.R.style.Animation_Dialog
            securityPopup.isTouchable = true
            securityPopup.isFocusable = true
            securityPopup.elevation = resources.getDimension(R.dimen.menu_elevation)
            val offsetY = requireContext().resources.getDimensionPixelOffset(R.dimen.doorhanger_offsetY)
            securityPopup.showAtLocation(urlbar, Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, offsetY)
            popup_tint.visibility = View.VISIBLE
        }
    }

    // In the future, if more badging icons are needed, this should be abstracted
    fun updateBlockingBadging(enabled: Boolean) {
        blockView!!.visibility = if (enabled) View.GONE else View.VISIBLE
    }

    override fun onLongClick(view: View): Boolean {
        // Detect long clicks on display_url
        if (view.id == R.id.display_url) {
            val context = activity ?: return false

            if (session.isCustomTabSession()) {
                val clipBoard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val uri = Uri.parse(url)
                clipBoard.primaryClip = ClipData.newRawUri("Uri", uri)
                Toast.makeText(context, getString(R.string.custom_tab_copy_url_action), Toast.LENGTH_SHORT).show()
            }
        }

        return false
    }

    private fun showFindInPage() {
        find_in_page.visibility = View.VISIBLE
        queryText.requestFocus()

        val params = swipe_refresh.layoutParams as CoordinatorLayout.LayoutParams
        params.bottomMargin = findInPageViewHeight
        swipe_refresh.layoutParams = params
    }

    private fun hideFindInPage() {
        val webView = getWebView() ?: return

        webView.clearMatches()
        findInPageCoordinator.reset()
        find_in_page.visibility = View.GONE
        queryText.setText("")
        queryText.clearFocus()

        val params = swipe_refresh.layoutParams as CoordinatorLayout.LayoutParams
        params.bottomMargin = 0
        swipe_refresh.layoutParams = params
        ViewUtils.hideKeyboard(queryText)
    }

    override fun applyLocale() {
        activity?.supportFragmentManager
            ?.beginTransaction()
            ?.replace(
                R.id.container,
                BrowserFragment.createForSession(requireNotNull(session)),
                BrowserFragment.FRAGMENT_TAG
            )
            ?.commit()
    }

    private fun updateSecurityIcon(session: Session, securityInfo: Session.SecurityInfo = session.securityInfo) {
        if (crashReporterIsVisible()) return
        val securityView = security_info ?: return

        if (!session.loading) {
            if (securityInfo.secure) {
                securityView.setImageResource(R.drawable.ic_lock)
            } else {
                if (URLUtil.isHttpUrl(url)) {
                    // HTTP site
                    securityView.setImageResource(R.drawable.ic_internet)
                } else {
                    // Certificate is bad
                    securityView.setImageResource(R.drawable.ic_warning)
                }
            }
        } else {
            securityView.setImageResource(R.drawable.ic_internet)
        }
    }

    @Suppress("DEPRECATION", "MagicNumber")
    private fun updateFindInPageResult(activeMatchOrdinal: Int, numberOfMatches: Int) {
        var actualActiveMatchOrdinal = activeMatchOrdinal
        val context = context ?: return

        if (numberOfMatches > 0) {
            nextResult.setColorFilter(resources.getColor(R.color.photonWhite))
            nextResult.alpha = 1.0f
            previousResult.setColorFilter(resources.getColor(R.color.photonWhite))
            previousResult.alpha = 1.0f
            // We don't want the presentation of the activeMatchOrdinal to be zero indexed. So let's
            // increment it by one for WebView.
            if (!AppConstants.isGeckoBuild) {
                actualActiveMatchOrdinal++
            }

            val visibleString = String.format(
                context.getString(R.string.find_in_page_result),
                actualActiveMatchOrdinal,
                numberOfMatches
            )

            val accessibleString = String.format(
                context.getString(R.string.find_in_page_result),
                actualActiveMatchOrdinal,
                numberOfMatches
            )

            resultText.text = visibleString
            resultText.contentDescription = accessibleString
        } else {
            nextResult.setColorFilter(resources.getColor(R.color.photonGrey10))
            nextResult.alpha = 0.4f
            previousResult.setColorFilter(resources.getColor(R.color.photonWhite))
            previousResult.alpha = 0.4f
            resultText.text = ""
            resultText.contentDescription = ""
        }
    }

    private val sessionObserver = object : Session.Observer {
        override fun onLoadingStateChanged(session: Session, loading: Boolean) {
            if (loading) {
                backgroundTransitionGroup!!.resetTransition()

                progressView!!.progress = INITIAL_PROGRESS
                progressView!!.visibility = View.VISIBLE
            } else {
                if (progressView!!.visibility == View.VISIBLE) {
                    // We start a transition only if a page was just loading before
                    // allowing to avoid issue #1179
                    backgroundTransitionGroup!!.startTransition(ANIMATION_DURATION)
                    progressView!!.visibility = View.GONE
                }
                swipe_refresh?.isRefreshing = false

                updateSecurityIcon(session)
            }

            updateBlockingBadging(loading || session.trackerBlockingEnabled)

            updateToolbarButtonStates(loading)

            val menu = menuWeakReference!!.get()
            menu?.updateLoading(loading)

            if (findInPageView?.visibility == View.VISIBLE) {
                hideFindInPage()
            }
        }

        override fun onUrlChanged(session: Session, url: String) {
            sessionsAdapter?.onURLChanged(session)
            val host = try {
                URL(url).host
            } catch (_: MalformedURLException) {
                url
            }

            val isException =
                host != null && ExceptionDomains.load(requireContext()).contains(host)
            getWebView()?.setBlockingEnabled(!isException)

            display_url?.text = UrlUtils.stripUserInfo(url)
        }

        override fun onProgress(session: Session, progress: Int) {
            progressView?.progress = progress
        }

        override fun onTrackerBlocked(session: Session, blocked: String, all: List<String>) {
            menuWeakReference?.let {
                val menu = it.get()

                menu?.updateTrackers(all.size)
            }
        }

        override fun onTrackerBlockingEnabledChanged(session: Session, blockingEnabled: Boolean) {
            setBlockingUI(blockingEnabled)
        }

        override fun onSecurityChanged(session: Session, securityInfo: Session.SecurityInfo) {
            updateSecurityIcon(session, securityInfo)
        }
    }

    fun handleTabCrash(crash: Crash) {
        showCrashReporter(crash)
    }

    private val bottomSheetBehavior
        get() = from(bottom_sheet_sessions_layout)

    private val bottomSheetBehaviorCallback = object : BottomSheetBehavior.BottomSheetCallback() {
        override fun onStateChanged(bottomSheet: View, newState: Int) {
            updateFabVisibility(newState)
        }

        override fun onSlide(bottomSheet: View, slideOffset: Float) {
            if (isAdded && slideOffset in VISIBLE_TINT_SLIDE_RANGE) {
                popup_tint?.visibility = View.VISIBLE
                popup_tint?.setOnClickListener {
                    bottomSheetBehavior.state = STATE_COLLAPSED
                }
                handle_bottom_sheet?.visibility = View.GONE
                popup_tint?.alpha = slideOffset * FULL_TINT_ALPHA
            } else {
                popup_tint?.visibility = View.GONE
                popup_tint?.setOnClickListener(null)
                handle_bottom_sheet?.visibility = View.VISIBLE
                popup_tint?.alpha = FULL_TINT_ALPHA
            }
        }
    }

    private fun updateFabVisibility(newState: Int) {
        fab_add_tab_bottom_sheet?.handleState(newState)
        erase?.handleState(newState)
        handle_bottom_sheet?.visibility = if (newState == STATE_COLLAPSED) View.VISIBLE else View.GONE
    }

    companion object {
        const val FRAGMENT_TAG = "browser"

        private const val SPAN_COUNT = 3
        private const val REQUEST_CODE_STORAGE_PERMISSION = 101
        private const val ANIMATION_DURATION = 300
        const val NEW_TAB_ANIMATION_DURATION = 500L
        private val VISIBLE_TINT_SLIDE_RANGE = 0.1F..1F
        private const val FULL_TINT_ALPHA = 0.5F

        private const val ARGUMENT_SESSION_UUID = "sessionUUID"
        private const val RESTORE_KEY_DOWNLOAD = "download"

        private const val INITIAL_PROGRESS = 5

        @JvmStatic
        fun createForSession(session: Session): BrowserFragment {
            val arguments = Bundle()
            arguments.putString(ARGUMENT_SESSION_UUID, session.id)

            val fragment = BrowserFragment()
            fragment.arguments = arguments

            return fragment
        }
    }
}
