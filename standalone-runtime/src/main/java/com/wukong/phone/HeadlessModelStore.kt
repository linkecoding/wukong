package com.wukong.phone

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Shell-UID Android Keystore identity, independent of any installed application. */
internal class HeadlessModelStore(private val directory:File, private val alias:String="wukong.standalone.model") {
    private val file=File(directory,if(alias.endsWith(".selftest"))"model-selftest.enc" else "model.enc")
    private fun key():SecretKey {
        // app_process does not run ActivityThread.handleBindApplication, which
        // normally installs this Android provider for installed apps.
        if(java.security.Security.getProvider("AndroidKeyStore")==null) {
            val name=if(android.os.Build.VERSION.SDK_INT>=31)"android.security.keystore2.AndroidKeyStoreProvider" else "android.security.keystore.AndroidKeyStoreProvider"
            Class.forName(name).getMethod("install").invoke(null)
        }
        val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey(alias,null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());generateKey()
        }
    }
    @Synchronized fun load():ModelSettings? {
        if(!file.exists())return null
        val bytes=file.readBytes();check(bytes.size in 29..65536) { "Encrypted model configuration is invalid" }
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,bytes.copyOfRange(0,12)))
        return ModelSettings.fromJson(JSONObject(String(cipher.doFinal(bytes.copyOfRange(12,bytes.size)),Charsets.UTF_8)))
    }
    @Synchronized fun save(settings:ModelSettings) {
        settings.validate()
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE,key()) }
        val bytes=cipher.iv+cipher.doFinal(settings.toJson().toString().toByteArray(Charsets.UTF_8))
        val temporary=File(directory,file.name+".tmp");temporary.writeBytes(bytes);android.system.Os.chmod(temporary.path,384)
        check(temporary.renameTo(file))
    }
}
