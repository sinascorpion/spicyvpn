package com.v2ray.ang.viewmodel

import androidx.lifecycle.ViewModel
import com.v2ray.ang.AppConfig
import com.v2ray.ang.AppConfig.ANG_PACKAGE
import com.v2ray.ang.util.LogUtil
import java.io.IOException

class LogcatViewModel : ViewModel() {
    private val logsetsAll: MutableList<String> = mutableListOf()
    private var filteredLogs: List<String> = emptyList()
    private var currentFilter: String = ""

    fun getAll(): List<String> = filteredLogs

    fun loadLogcat() {
        try {
            val lst = LinkedHashSet<String>()
            lst.add("logcat")
            lst.add("-d")
            lst.add("-v")
            lst.add("time")
            lst.add("-s")
            lst.add("GoLog,${ANG_PACKAGE},AndroidRuntime,System.err")
            val process = Runtime.getRuntime().exec(lst.toTypedArray())
            val allText = process.inputStream.bufferedReader().use { it.readLines() }.reversed()

            logsetsAll.clear()
            logsetsAll.addAll(allText)
        } catch (e: IOException) {
            LogUtil.e(AppConfig.TAG, "Failed to get logcat", e)
        }

        // Fallback: if logcat returned no logs, read our custom spicyvpn_log.txt file!
        if (logsetsAll.isEmpty()) {
            try {
                val app = com.v2ray.ang.AngApplication.application
                val logFile = java.io.File(app.getExternalFilesDir(null), "spicyvpn_log.txt")
                if (logFile.exists()) {
                    val fileLogs = logFile.readLines().reversed()
                    logsetsAll.addAll(fileLogs)
                }
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to read custom spicyvpn_log.txt fallback", e)
            }
        }

        applyFilter()
    }

    fun clearLogcat() {
        try {
            val lst = LinkedHashSet<String>()
            lst.add("logcat")
            lst.add("-c")
            val process = Runtime.getRuntime().exec(lst.toTypedArray())
            process.waitFor()
        } catch (e: IOException) {
            LogUtil.e(AppConfig.TAG, "Failed to clear logcat", e)
        }

        try {
            val app = com.v2ray.ang.AngApplication.application
            val logFile = java.io.File(app.getExternalFilesDir(null), "spicyvpn_log.txt")
            if (logFile.exists()) {
                logFile.delete()
            }
        } catch (e: Exception) {
            // ignore
        }

        logsetsAll.clear()
        filteredLogs = emptyList()
    }

    fun filter(content: String?) {
        currentFilter = content?.trim() ?: ""
        applyFilter()
    }

    private fun applyFilter() {
        filteredLogs = if (currentFilter.isEmpty()) {
            logsetsAll.toList()
        } else {
            logsetsAll.filter { it.contains(currentFilter) }
        }
    }
}
