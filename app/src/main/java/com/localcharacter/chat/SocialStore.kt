package com.localcharacter.chat

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

/** Separate encrypted, atomic store. A failed read never overwrites the original data. */
class SocialStore(context: Context) {
    private val app = context.applicationContext
    private val file = AtomicFile(File(app.filesDir, "social-world.aes"))
    companion object {
        private val lock = Any()
        private var cached: SocialWorld? = null
        private const val ALIAS = "private_character_social_aes_v1"
    }
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun load(): SocialWorld {
        cached?.let { return it }
        val loaded = if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) {
            val chat = ChatStore(app)
            SocialWorld.initial(chat.settings).also { w -> w.characters.first().mood = chat.conversation.brain.copy(); w.characters.first().baselineMood = chat.conversation.brain.copy() }
        } else {
            val bytes = file.openRead().use { it.readBytes() }
            require(bytes.size > 30 && bytes[0].toInt() == 1) { "Social data is unreadable; the original file has been preserved." }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
            SocialWorld.fromJson(JSONObject(cipher.doFinal(bytes.copyOfRange(13, bytes.size)).toString(Charsets.UTF_8)))
        }
        cached = loaded; return loaded
    }
    fun read(): SocialWorld = synchronized(lock) { SocialWorld.fromJson(load().toJson()) }
    fun <T> edit(change: (SocialWorld) -> T): T = synchronized(lock) {
        val next = SocialWorld.fromJson(load().toJson())
        val result = change(next); next.revision++
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key())
        val ciphertext = cipher.doFinal(next.toJson().toString().toByteArray(Charsets.UTF_8))
        var stream: java.io.FileOutputStream? = null
        try {
            stream = file.startWrite(); stream.write(byteArrayOf(1)); stream.write(cipher.iv); stream.write(ciphertext)
            file.finishWrite(stream); cached = next
        } catch (e: Exception) { if (stream != null) file.failWrite(stream); throw e }
        result
    }
}
