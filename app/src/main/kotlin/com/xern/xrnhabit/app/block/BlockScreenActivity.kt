package com.xern.xrnhabit.app.block

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xern.xrnhabit.app.XrnhabitApp
import com.xern.xrnhabit.app.ui.BlockOverlayContent
import com.xern.xrnhabit.app.ui.theme.XrnhabitTheme
import com.xern.xrnhabit.domain.BlockEvent
import com.xern.xrnhabit.domain.BlockState

/**
 * Full-screen fallback for the block screen, used only where [OverlayShieldPort] cannot draw
 * (e.g. overlay permission revoked). Same content, launched as an Activity.
 */
class BlockScreenActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = (application as XrnhabitApp).container.store
        val targetPkg = intent.getStringExtra(EXTRA_PACKAGE)

        setContent {
            XrnhabitTheme {
                val world by store.world.collectAsStateWithLifecycle()
                val block = world.blocks.firstOrNull {
                    targetPkg in it.targetPackages && it.state != BlockState.UNLOCKED
                }
                BlockOverlayContent(
                    ui = targetPkg?.let {
                        OverlayShieldPort.OverlayUi(
                            subject = it,
                            isSite = false,
                            block = block,
                            items = block?.let { b -> world.items(b.id) }.orEmpty(),
                        )
                    },
                    onToggleItem = { id, done -> store.dispatch(BlockEvent.ToggleChecklistItem(id, done)) },
                    onStartTimer = { id -> store.dispatch(BlockEvent.TimerControl(id, running = true)) },
                    onRedeem = { id ->
                        store.dispatch(BlockEvent.Redeem(id))
                        finish()
                    },
                    onLeave = { finish() },
                )
            }
        }
    }

    companion object {
        const val EXTRA_PACKAGE = "package"
    }
}
