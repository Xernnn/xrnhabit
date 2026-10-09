package com.xern.xrnhabit.app.hardcore

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.core.content.edit
import com.xern.xrnhabit.domain.BlockState
import com.xern.xrnhabit.domain.EnforcementTier
import com.xern.xrnhabit.domain.World
import com.xern.xrnhabit.hardcore.HabitDeviceAdminReceiver
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Real [HardcoreGuard], compiled only into the `sideload` flavor. The password is stored as a
 * salted SHA-256 hash in a private `SharedPreferences` file — enough to stop a casual in-app
 * bypass attempt; the "Honest limits" copy in Settings already says ADB / safe mode / factory
 * reset defeat the whole app regardless (brainstorm §8), so this isn't trying to be a vault.
 */
class DeviceAdminHardcoreGuard(private val context: Context) : HardcoreGuard {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("hardcore_guard", Context.MODE_PRIVATE)
    private val dpm = context.getSystemService(DevicePolicyManager::class.java)

    override fun isSupported() = true

    override fun isEngaged(): Boolean = hasPassword() && isDeviceAdminActive()

    override fun shouldIntercept(pkg: String?, world: World): Boolean {
        if (!isEngaged() || pkg != SETTINGS_PACKAGE) return false
        return world.blocks.any {
            it.enforcementTier == EnforcementTier.HARDCORE && it.state != BlockState.UNLOCKED
        }
    }

    override fun hasPassword(): Boolean = prefs.contains(KEY_HASH)

    override fun setPassword(password: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs.edit {
            putString(KEY_SALT, salt.toHex())
            putString(KEY_HASH, hash(password, salt))
        }
    }

    override fun verifyPassword(input: String): Boolean {
        val saltHex = prefs.getString(KEY_SALT, null) ?: return false
        val expected = prefs.getString(KEY_HASH, null) ?: return false
        return hash(input, saltHex.hexToBytes()) == expected
    }

    override fun isDeviceAdminActive(): Boolean =
        dpm?.isAdminActive(HabitDeviceAdminReceiver.component(context)) == true

    override fun requestDeviceAdmin(activity: Activity) {
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, HabitDeviceAdminReceiver.component(activity))
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "xrnhabit uses this to stop itself being uninstalled while a hardcore block is active.",
            )
        }
        activity.startActivity(intent)
    }

    private fun hash(password: String, salt: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        return digest.digest(password.toByteArray(Charsets.UTF_8)).toHex()
    }

    companion object {
        private const val KEY_SALT = "salt"
        private const val KEY_HASH = "hash"
        private const val SETTINGS_PACKAGE = "com.android.settings"
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
private fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
