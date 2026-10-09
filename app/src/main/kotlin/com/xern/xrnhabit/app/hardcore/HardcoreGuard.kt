package com.xern.xrnhabit.app.hardcore

import android.app.Activity
import com.xern.xrnhabit.domain.World

/**
 * Anti-bypass surface (brainstorm §4.5). The real implementation lives in the `:hardcore`
 * module and is only linked into the `sideload` build flavor; `:app`'s main source set never
 * references `:hardcore` directly so the `play` flavor can exclude it entirely. The
 * flavor-specific factory (`HardcoreGuardFactory.kt` under `src/sideload` / `src/play`) wires
 * the real implementation — or [NoopHardcoreGuard] — in at compile time.
 */
interface HardcoreGuard {
    /** False in the `play` flavor, where `:hardcore` isn't linked in at all. */
    fun isSupported(): Boolean

    /** True once a password is set *and* device admin is active — i.e. the guard does anything. */
    fun isEngaged(): Boolean

    /** True if [pkg] is the system Settings app and some HARDCORE block in [world] is still armed. */
    fun shouldIntercept(pkg: String?, world: World): Boolean

    fun hasPassword(): Boolean
    fun setPassword(password: String)
    fun verifyPassword(input: String): Boolean

    fun isDeviceAdminActive(): Boolean
    fun requestDeviceAdmin(activity: Activity)
}

object NoopHardcoreGuard : HardcoreGuard {
    override fun isSupported() = false
    override fun isEngaged() = false
    override fun shouldIntercept(pkg: String?, world: World) = false
    override fun hasPassword() = false
    override fun setPassword(password: String) = Unit
    override fun verifyPassword(input: String) = false
    override fun isDeviceAdminActive() = false
    override fun requestDeviceAdmin(activity: Activity) = Unit
}
