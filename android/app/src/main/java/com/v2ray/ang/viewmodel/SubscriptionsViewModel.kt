package com.v2ray.ang.viewmodel

import androidx.lifecycle.ViewModel
import com.v2ray.ang.dto.entities.SubscriptionCache
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.handler.SettingsManager

class SubscriptionsViewModel : ViewModel() {
    private val subscriptions: MutableList<SubscriptionCache> =
        visibleSubscriptions().toMutableList()

    private fun visibleSubscriptions() = MmkvManager.decodeSubscriptions().filterNot {
        it.guid in setOf(com.v2ray.ang.AppConfig.DEFAULT_SUBSCRIPTION_ID,
            "spicyvpn-default", "spicyvpn-auto-candidates", "spicyvpn-custom-user",
            "spicyvpn-manual-background-test", "spicyvpn-manual-test", "spicyvpn-custom-test", "psiphon-dummy") ||
            (it.subscription.remarks == "SpicyVPN" &&
                (it.subscription.url.isBlank() ||
                    it.subscription.url == "${com.v2ray.ang.AppConfig.API_BASE_URL}/api/sub"))
    }

    fun getAll(): List<SubscriptionCache> = subscriptions.toList()

    fun reload() {
        subscriptions.clear()
        subscriptions.addAll(visibleSubscriptions())
    }

    fun remove(subId: String): Boolean {
        val changed = subscriptions.removeAll { it.guid == subId }
        if (changed) {
            SettingsManager.removeSubscriptionWithDefault(subId)
            SettingsChangeManager.makeSetupGroupTab()
        }
        return changed
    }

    fun update(subId: String, item: SubscriptionItem) {
        val idx = subscriptions.indexOfFirst { it.guid == subId }
        if (idx >= 0) {
            subscriptions[idx] = SubscriptionCache(subId, item)
            MmkvManager.encodeSubscription(subId, item)
        }
    }

    fun swap(fromPosition: Int, toPosition: Int) {
        if (fromPosition in subscriptions.indices && toPosition in subscriptions.indices) {
            val allIds = MmkvManager.decodeSubsList()
            val from = allIds.indexOf(subscriptions[fromPosition].guid)
            val to = allIds.indexOf(subscriptions[toPosition].guid)
            val item = subscriptions.removeAt(fromPosition)
            subscriptions.add(toPosition, item)
            if (from >= 0 && to >= 0) {
                val id = allIds.removeAt(from)
                allIds.add(to, id)
                MmkvManager.encodeSubsList(allIds)
            }
            SettingsChangeManager.makeSetupGroupTab()
        }
    }
}

