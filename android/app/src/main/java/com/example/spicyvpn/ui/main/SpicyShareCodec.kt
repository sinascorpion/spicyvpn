package com.example.spicyvpn.ui.main

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.util.JsonUtil
import java.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

data class SharedProfile(val profile: ProfileItem, val raw: String = "", val sourceId: String = "")
data class SharedFolder(val version: Int = 1, val name: String, val message: String = "", val profiles: List<SharedProfile>, val editLock: String? = null)

object SpicyShareCodec {
    const val PREFIX = "spicy://bundle/"
    private const val MAX_BYTES = 4 * 1024 * 1024
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private fun key(password: String, salt: ByteArray) = SecretKeySpec(
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(password.toCharArray(), salt, 210000, 256)).encoded, "AES")

    fun createEditLock(password: String): String {
        require(password.isNotEmpty())
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        return encoder.encodeToString(salt + key(password, salt).encoded)
    }

    fun unlockEdit(lock: String, password: String): Boolean = runCatching {
        val bytes = Base64.getUrlDecoder().decode(lock)
        require(bytes.size == 48)
        java.security.MessageDigest.isEqual(bytes.copyOfRange(16, 48), key(password, bytes.copyOfRange(0, 16)).encoded)
    }.getOrDefault(false)

    fun encodeConnectOnly(folder: SharedFolder, password: String): String = encode(
        folder.copy(editLock = password.takeIf { it.isNotEmpty() }?.let(::createEditLock)))

    fun encode(folder: SharedFolder, password: String = ""): String {
        require(folder.message.codePointCount(0, folder.message.length) <= 500)
        require(folder.profiles.isNotEmpty() && folder.profiles.size <= 500)
        val bytes = JsonUtil.toJson(folder).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES)
        if (password.isEmpty()) return PREFIX + "1." + encoder.encodeToString(bytes)
        val random = SecureRandom()
        val salt = ByteArray(16).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv))
        cipher.updateAAD("spicy-bundle-v1".toByteArray())
        return PREFIX + "e1." + encoder.encodeToString(salt + iv + cipher.doFinal(bytes))
    }
    fun encrypted(link: String) = link.startsWith(PREFIX + "e1.")
    fun decode(link: String, password: String = ""): SharedFolder {
        require(link.startsWith(PREFIX) && link.length <= MAX_BYTES * 2)
        val parts = link.removePrefix(PREFIX).split('.', limit = 2)
        require(parts.size == 2)
        val data = Base64.getUrlDecoder().decode(parts[1])
        val plain = when (parts[0]) {
            "1" -> data
            "e1" -> {
                require(data.size >= 44 && password.isNotEmpty())
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key(password, data.copyOfRange(0, 16)), GCMParameterSpec(128, data.copyOfRange(16, 28)))
                cipher.updateAAD("spicy-bundle-v1".toByteArray())
                cipher.doFinal(data.copyOfRange(28, data.size))
            }
            else -> error("Unsupported shared bundle")
        }
        require(plain.size <= MAX_BYTES)
        val folder = JsonUtil.fromJsonSafe(String(plain, Charsets.UTF_8), SharedFolder::class.java) ?: error("Invalid bundle")
        require(folder.version == 1 && folder.profiles.size in 1..500 && folder.message.codePointCount(0, folder.message.length) <= 500)
        folder.editLock?.let { require(Base64.getUrlDecoder().decode(it).size == 48) }
        require(folder.profiles.all { it.sourceId.isNotBlank() } && folder.profiles.map { it.sourceId }.distinct().size == folder.profiles.size)
        val ids = folder.profiles.map { it.sourceId }.toSet()
        require(folder.profiles.all { shared ->
            shared.profile.configType in com.v2ray.ang.enums.EConfigType.entries &&
                shared.profile.proxyChainProfiles.orEmpty().split(',').filter { it.isNotBlank() }.all { it.trim() in ids }
        })
        return folder
    }
}
