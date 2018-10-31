/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */
@file:Suppress("TooManyFunctions")

package org.mozilla.focus.session.ui

import android.support.v7.widget.RecyclerView
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.RelativeLayout
import mozilla.components.browser.session.Session
import mozilla.components.browser.session.SessionManager
import org.mozilla.focus.ext.requireComponents
import org.mozilla.focus.fragment.BrowserFragment

/**
 * Adapter implementation to show a list of active browsing sessions and an "erase" button at the end.
 */
class SessionsAdapter internal constructor(
    private val fragment: BrowserFragment,
    private var sessions: List<Session> = emptyList()
) : RecyclerView.Adapter<RecyclerView.ViewHolder>(), SessionManager.Observer {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)

        return SessionViewHolder(
            fragment,
            inflater.inflate(SessionViewHolder.LAYOUT_ID, parent, false) as RelativeLayout
        )
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        (holder as SessionViewHolder).bind(sessions[position])
    }

    override fun getItemViewType(position: Int): Int {
        return SessionViewHolder.LAYOUT_ID
    }

    override fun getItemCount(): Int {
        return sessions.size
    }

    override fun onSessionAdded(session: Session) {
        synchronized(this) {
            this.sessions = fragment.requireComponents.sessionManager.sessions
            if (sessions.contains(session)) {
                notifyItemInserted(sessions.indexOf(session))
            }
        }
    }

    override fun onSessionRemoved(session: Session) {
        synchronized(this) {
            val sessionToRemove = sessions.indexOf(session)
            if (sessionToRemove >= 0) {
                this.sessions = sessions.drop(sessionToRemove)
                notifyItemRemoved(sessionToRemove)
            }
        }
    }

    override fun onSessionSelected(session: Session) {
        onSessionChanged(session)
    }

    override fun onAllSessionsRemoved() {
        onUpdate(fragment.requireComponents.sessionManager.sessions)
    }

    fun onURLChanged(session: Session) {
        onSessionChanged(session)
    }

    private fun onUpdate(sessions: List<Session>) {
        synchronized(this) {
            this.sessions = sessions
            notifyDataSetChanged()
        }
    }

    private fun onSessionChanged(session: Session) {
        synchronized(this) {
            this.sessions = fragment.requireComponents.sessionManager.sessions
            val index = sessions.indexOf(session)
            if (index >= 0) {
                notifyItemChanged(index)
            }
        }
    }
}
