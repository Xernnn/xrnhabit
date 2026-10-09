package com.xern.xrnhabit.app.web

import android.content.Context

/** `play` flavor: no Accessibility reader, so no website tracking or blocking. */
fun createWebsiteMonitor(context: Context): WebsiteMonitor = NoopWebsiteMonitor
