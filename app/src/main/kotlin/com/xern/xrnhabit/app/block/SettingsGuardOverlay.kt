package com.xern.xrnhabit.app.block

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.PixelFormat
import android.os.SystemClock
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.xern.xrnhabit.app.hardcore.HardcoreGuard
import com.xern.xrnhabit.app.ui.PasswordGateContent
import com.xern.xrnhabit.domain.World

/**
 * Phase C anti-bypass (brainstorm §4.5): while a HARDCORE block is ARMED/EARNED, intercept the
 * system Settings app itself with a password prompt, so the user can't navigate to
 * Settings > Apps > xrnhabit > Device admin apps and deactivate protection mid-urge.
 *
 * Coarser than the brainstorm's per-screen targeting — this only sees "Settings is in the
 * foreground", not which screen inside it, because the baseline detector is UsageStats-only
 * (brainstorm §4.1 Option C, no Accessibility). Same honesty tradeoff as the rest of the app:
 * gate the whole app rather than claim precision we don't have. [NoopHardcoreGuard] makes
 * [HardcoreGuard.shouldIntercept] always false, so this is a no-op in the `play` flavor and
 * when no password/device-admin is configured.
 */
class SettingsGuardOverlay(
    private val context: Context,
    private val guard: HardcoreGuard,
    private val unlockGraceMs: Long = 5 * 60_000L,
) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private var view: ComposeView? = null
    private var windowOwner: OverlayWindowOwner? = null
    private val showError = mutableStateOf(false)

    @Volatile
    private var unlockedUntilMono = 0L

    fun render(foregroundPkg: String?, world: World) {
        val shouldGuard = guard.shouldIntercept(foregroundPkg, world) &&
            SystemClock.elapsedRealtime() >= unlockedUntilMono
        if (!shouldGuard) {
            hide()
            return
        }
        show()
    }

    private fun show() {
        if (view != null) return
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.OPAQUE,
        ).apply {
            gravity = Gravity.CENTER
            screenOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }

        val owner = OverlayWindowOwner().also { it.onCreate() }
        val composeView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent {
                PasswordGateContent(
                    showError = showError.value,
                    onSubmit = { input ->
                        if (guard.verifyPassword(input)) {
                            unlockedUntilMono = SystemClock.elapsedRealtime() + unlockGraceMs
                            showError.value = false
                            hide()
                        } else {
                            showError.value = true
                        }
                    },
                    onCancel = { goHome() },
                )
            }
        }
        wm.addView(composeView, params)
        view = composeView
        windowOwner = owner
        owner.onStart()
    }

    private fun hide() {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
        windowOwner?.onDestroy()
        windowOwner = null
    }

    private fun goHome() {
        val homeIntent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(homeIntent)
        hide()
    }

    private fun overlayType(): Int = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
}
