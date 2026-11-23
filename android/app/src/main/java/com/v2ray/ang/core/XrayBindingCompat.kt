package com.v2ray.ang.core

import libv2ray.CoreController
import libv2ray.Libv2ray

/** Optional Android binding APIs differ from the stable upstream core's API. */
object XrayBindingCompat {
    fun reconcileBrowserDialer(address: String) {
        runCatching { Libv2ray::class.java.getMethod("reconcileBrowserDialer", String::class.java).invoke(null, address) }
    }

    fun queryAllStats(controller: CoreController): String = queryStatsPayload(controller)

    internal fun queryStatsPayload(controller: Any): String {
        val method = controller.javaClass.methods.firstOrNull { it.name == "queryAllOutboundTrafficStats" && it.parameterCount == 0 }
        if (method != null) return method.invoke(controller) as String
        val legacy = controller.javaClass.getMethod("queryStats", String::class.java, String::class.java)
        return listOf("proxy", "direct").flatMap { tag ->
            listOf("uplink", "downlink").map { direction ->
                "$tag,$direction,${legacy.invoke(controller, tag, direction)};"
            }
        }.joinToString("")
    }

    fun registerProcessFinder(controller: CoreController, find: (String, String, Long, String, Long) -> Long): Any? {
        val method = controller.javaClass.methods.firstOrNull { it.name == "registerProcessFinder" && it.parameterCount == 1 } ?: return null
        val type = method.parameterTypes[0]
        val proxy = java.lang.reflect.Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, called, args ->
            when (called.name) {
                "findProcessByConnection" -> find(args!![0] as String, args[1] as String, args[2] as Long, args[3] as String, args[4] as Long)
                "toString" -> "SpicyVPN process finder"
                "hashCode" -> System.identityHashCode(find)
                "equals" -> false
                else -> -1L
            }
        }
        method.invoke(controller, proxy)
        return proxy
    }

    fun certificateFingerprint(method: String, request: String): String =
        Libv2ray::class.java.getMethod(method, String::class.java).invoke(null, request) as String
}
