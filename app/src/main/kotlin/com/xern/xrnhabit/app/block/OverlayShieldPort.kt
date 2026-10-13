package com.xern.xrnhabit.app.block

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.xern.xrnhabit.app.data.WorldStore
import com.xern.xrnhabit.app.loop.LoopHabits
import com.xern.xrnhabit.app.ui.BlockOverlayContent
import com.xern.xrnhabit.app.ui.freeAtWall
import com.xern.xrnhabit.app.web.SiteSightings
import com.xern.xrnhabit.domain.Block
import com.xern.xrnhabit.domain.BlockEvent
import com.xern.xrnhabit.domain.BlockMode
import com.xern.xrnhabit.domain.BlockState
import com.xern.xrnhabit.domain.ChecklistItem
import com.xern.xrnhabit.domain.RejectReason
import com.xern.xrnhabit.domain.ShieldPlan
import com.xern.xrnhabit.domain.ShieldPort
import com.xern.xrnhabit.domain.Sites
import com.xern.xrnhabit.domain.World

/**
 * The enforcement surface (brainstorm §4.3): a `TYPE_APPLICATION_OVERLAY` window rather than a
 * launched Activity, because a foreground service holding the overlay permission can show it
 * reliably where background Activity starts are restricted.
 *
 * [apply] only records the desired blocked set; [render] is called by the service whenever the
 * foreground app or the [World] changes and decides whether the overlay is visible. [render]
 * also re-adds the window if the OS silently tore it down (some OEM task killers do), so the
 * block cannot be dismissed by anything short of finishing the habit or leaving the app.
 *
 * A `ComposeView` added straight to a [WindowManager] has no Activity to supply a
 * ViewModelStoreOwner / SavedStateRegistryOwner, so [OverlayWindowOwner] provides a minimal one
 * scoped to the lifetime of the window itself.
 */
class OverlayShieldPort(
    private val context: Context,
    private val store: WorldStore,
    private val loop: LoopHabits,
    private val onEvent: (BlockEvent) -> Unit,
) : ShieldPort {

    private val wm = context.getSystemService(WindowManager::class.java)

    @Volatile
    private var plan = ShieldPlan(emptySet())

    private var view: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var windowOwner: OverlayWindowOwner? = null
    private val uiState = mutableStateOf<OverlayUi?>(null)

    override fun apply(plan: ShieldPlan) {
        this.plan = plan
    }

    fun render(world: World, foregroundPkg: String?, foregroundSite: String?) {
        val current = plan
        val appBlocked = foregroundPkg != null && foregroundPkg in current.blockedPackages
        val siteBlocked = !appBlocked && current.covers(null, foregroundSite)
        if (!appBlocked && !siteBlocked) {
            hide()
            return
        }
        val engine = store.engine
        val clock = store.clock
        val covering = world.blocks.filter { b ->
            if (appBlocked) foregroundPkg in b.targetPackages
            else b.targetSites.any { Sites.matches(foregroundSite!!, it) }
        }
        // A daily limit / schedule / lockdown can't be lifted by a habit (I22), so it is the one to explain.
        val block = covering.firstOrNull { it.mode != BlockMode.HABIT && engine.isBlocking(world, it, clock) }
            ?: covering.firstOrNull { it.mode == BlockMode.HABIT && it.state != BlockState.UNLOCKED }
        uiState.value = OverlayUi(
            subject = if (appBlocked) appLabel(foregroundPkg!!) else foregroundSite!!,
            isSite = siteBlocked,
            block = block,
            items = block?.let { world.items(it.id) }.orEmpty(),
            habitAppLabel = block?.trigger?.habitPackages
                ?.takeIf { it.isNotEmpty() }
                ?.joinToString(" or ") { appLabel(it) },
            freeAtWall = block?.let { freeAtWall(it, world, engine, clock) },
            freeInMs = block?.let { freeAtWall(it, world, engine, clock) }?.let { it - clock.nowWallMs() },
            allowanceMs = block?.takeIf { it.mode == BlockMode.ALLOWANCE }?.dailyAllowanceMs,
            timerMs = block?.let { world.progress[it.id]?.accumulatedMs } ?: 0,
            timerRunning = block?.let { world.progress[it.id]?.timerRunning } == true,
            redeemRejection = block?.takeIf { it.state == BlockState.EARNED }?.let { engine.redeemRejection(world, it.id, clock) },
            unlockUsedTodayMs = engine.unlockUsedTodayMs(world, clock),
            dailyCapMs = world.settings.dailyCapMs,
        )
        // If the OS dropped our window out from under us, rebuild it.
        if (view != null && view?.isAttachedToWindow != true) hide()
        // Hitting the shield re-reads Loop, so a missed push or a credit that lapsed unused (I24)
        // is caught now rather than at the next screen unlock.
        if (view == null && uiState.value?.items?.any { it.externalHabitId != null } == true) loop.sync()
        show(focusable = !siteBlocked)
    }

    /**
     * Leaving a blocked *site* means stepping the browser back; leaving a blocked *app* means
     * going home. Back falls through to the browser because a site shield isn't focusable.
     */
    private fun leave(isSite: Boolean) {
        val wentBack = isSite && SiteSightings.backAction?.invoke() == true
        if (!wentBack) {
            context.startActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    private fun show(focusable: Boolean) {
        view?.let { existing ->
            val p = params ?: return
            val wanted = withFocusable(p.flags, focusable)
            if (p.flags != wanted) {
                p.flags = wanted
                runCatching { wm.updateViewLayout(existing, p) }
            }
            return
        }

        val owner = OverlayWindowOwner().also { it.onCreate() }
        // Opening Loop moves the foreground off the blocked app, which drops the overlay.
        val openLoop: ((String) -> Unit)? = loop.launchIntent()?.let {
            { uuid -> loop.openHabitIntent(uuid)?.let { runCatching { context.startActivity(it) } }; Unit }
        }
        val composeView = ComposeView(context).apply {
            setContent {
                BlockOverlayContent(
                    ui = uiState.value,
                    onToggleItem = { id, done -> onEvent(BlockEvent.ToggleChecklistItem(id, done)) },
                    onSetTimer = { blockId, running -> onEvent(BlockEvent.TimerControl(blockId, running)) },
                    onRedeem = { blockId -> onEvent(BlockEvent.Redeem(blockId)) },
                    onLeave = { leave(uiState.value?.isSite == true) },
                    onOpenLoop = openLoop,
                )
            }
        }
        // Swallow BACK so the overlay can't be dismissed with the nav gesture / button.
        val host = object : FrameLayout(context) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) return true
                return super.dispatchKeyEvent(event)
            }
        }
        // Compose resolves its recomposer from the *root* view attached to the window, so the
        // ViewTree owners must live on `host`, not on the child ComposeView.
        host.setViewTreeLifecycleOwner(owner)
        host.setViewTreeViewModelStoreOwner(owner)
        host.setViewTreeSavedStateRegistryOwner(owner)
        host.addView(composeView)

        val layout = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            withFocusable(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                focusable,
            ),
            PixelFormat.OPAQUE,
        ).apply {
            gravity = Gravity.CENTER
            // Overlay windows don't inherit an Activity's manifest orientation lock; without
            // this, rotating the phone while a landscape-capable app (e.g. a game) is blocked
            // rotates the shield with it, scrambling the portrait-only layout.
            screenOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }

        runCatching { wm.addView(host, layout) }
        view = host
        params = layout
        windowOwner = owner
        owner.onStart()
    }

    private fun hide() {
        view?.let { v -> runCatching { wm.removeView(v) } }
        view = null
        params = null
        windowOwner?.onDestroy()
        windowOwner = null
    }

    /** A site shield must not take focus, so Back reaches the browser and steps off the site. */
    private fun withFocusable(flags: Int, focusable: Boolean): Int =
        if (focusable) flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        else flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE

    private fun appLabel(pkg: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private fun overlayType(): Int = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    data class OverlayUi(
        /** The blocked app's name, or the blocked site's host. */
        val subject: String,
        val isSite: Boolean,
        val block: Block?,
        val items: List<ChecklistItem>,
        /** Set when [Block.trigger] has `habitPackages` — the app(s) the timer actually counts in. */
        val habitAppLabel: String? = null,
        /** Non-HABIT blocks: when the block lets go (wall ms), if known. */
        val freeAtWall: Long? = null,
        /** The same, as time from now. */
        val freeInMs: Long? = null,
        /** ALLOWANCE blocks: the daily allowance that has been used up. */
        val allowanceMs: Long? = null,
        /** TIMER / COMBINED: time counted so far, and whether the timer is running. */
        val timerMs: Long = 0,
        val timerRunning: Boolean = false,
        /** EARNED blocks: why "Open" wouldn't go through right now, if it wouldn't (I9). */
        val redeemRejection: RejectReason? = null,
        val unlockUsedTodayMs: Long = 0,
        val dailyCapMs: Long = 0,
    )
}

/**
 * Minimal Lifecycle / ViewModelStore / SavedStateRegistry owner for a `ComposeView` attached
 * directly to a [WindowManager] window, which has no Activity to supply these. Scoped to one
 * overlay window: [onCreate] before the view is attached, [onStart] right after, [onDestroy]
 * when the window is removed.
 */
internal class OverlayWindowOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore = ViewModelStore()
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    fun onCreate() {
        savedStateRegistryController.performAttach()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    fun onStart() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        viewModelStore.clear()
    }
}
