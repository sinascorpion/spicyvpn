package com.v2ray.ang.tunnel

import android.content.Context
import android.content.Intent
import android.net.Ikev2VpnProfile
import android.net.VpnManager
import android.os.Build
import com.v2ray.ang.dto.entities.ProfileItem

object Ikev2PlatformController {
    @Volatile var connected = false
    @Volatile var active = false

    fun available(context: Context) = Build.VERSION.SDK_INT >= 30 &&
        context.packageManager.hasSystemFeature("android.software.ipsec_tunnels")

    @androidx.annotation.RequiresApi(30)
    fun buildProfile(profile: ProfileItem): Ikev2VpnProfile {
        require(!profile.server.isNullOrBlank() && !profile.username.isNullOrBlank() && !profile.password.isNullOrBlank())
        return Ikev2VpnProfile.Builder(profile.server!!, profile.sni ?: profile.username!!)
            .setAuthUsernamePassword(profile.username!!, profile.password!!, null)
            .setBypassable(false).setMetered(false).build()
    }

    @androidx.annotation.RequiresApi(30)
    fun prepare(context: Context, profile: ProfileItem): Intent? {
        check(available(context)) { "IKEv2 requires Android 11 or later with IPsec tunnel support" }
        return context.getSystemService(VpnManager::class.java).provisionVpnProfile(buildProfile(profile))
    }

    fun start(context: Context) {
        androidx.core.content.ContextCompat.startForegroundService(context, Intent(context, Ikev2PlatformService::class.java))
    }

    fun stop(context: Context) {
        if (Build.VERSION.SDK_INT >= 30 && active) runCatching {
            context.getSystemService(VpnManager::class.java).stopProvisionedVpnProfile()
        }
        active = false; connected = false
        context.stopService(Intent(context, Ikev2PlatformService::class.java))
    }
}
