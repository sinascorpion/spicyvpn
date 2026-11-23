package com.v2ray.ang.contracts

import android.app.Service
import java.net.Socket

interface ServiceControl {
    /**
     * Gets the service instance.
     * @return The service instance.
     */
    fun getService(): Service

    /**
     * Starts the service.
     */
    fun startService()

    /**
     * Stops the service.
     */
    fun stopService()

    /**
     * Protects the VPN socket (fd).
     * @param socket The socket fd to protect.
     * @return True if the socket is protected, false otherwise.
     */
    fun vpnProtect(socket: Int): Boolean

    /**
     * Protects a high-level java.net.Socket instance.
     */
    fun vpnProtect(socket: Socket): Boolean = false
}
