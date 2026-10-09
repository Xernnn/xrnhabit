package com.xern.xrnhabit.app.hardcore

import android.content.Context

/** `sideload` flavor: `:hardcore` is linked in, so the real Device Admin guard is available. */
fun createHardcoreGuard(context: Context): HardcoreGuard = DeviceAdminHardcoreGuard(context)
