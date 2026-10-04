package com.solara.music.customsource

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * v1.5.1 r26：音源脚本加密工具——AES/RSA（lx-music preload 的 native 实现）。
 *
 * 脚本解密各平台接口参数时常用（如 kw 的签名、tx 的加密参数）。
 * 参数全部走 base64 字符串（与 lx-music QuickJS.java 的调用约定一致）：
 * - aesEncrypt(data, key, iv, mode)：mode = "AES/CBC/PKCS7Padding" 或 "AES"
 * - rsaEncrypt(data, key, padding)：key 为去 PEM 头尾的 base64，padding = "RSA/ECB/NoPadding"
 */
object AesRsaHelper {

    fun aesEncrypt(args: Array<Any?>): String {
        val data = Base64.decode(args[0]?.toString() ?: "", Base64.NO_WRAP)
        val key = Base64.decode(args[1]?.toString() ?: "", Base64.NO_WRAP)
        val ivStr = args[2]?.toString() ?: ""
        val mode = args[3]?.toString() ?: "AES/CBC/PKCS7Padding"
        val cipher = Cipher.getInstance(mode)
        if (ivStr.isBlank()) {
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        } else {
            val iv = Base64.decode(ivStr, Base64.NO_WRAP)
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        }
        return Base64.encodeToString(cipher.doFinal(data), Base64.NO_WRAP)
    }

    fun rsaEncrypt(args: Array<Any?>): String {
        val data = Base64.decode(args[0]?.toString() ?: "", Base64.NO_WRAP)
        // key：去 PEM 头尾后的 base64 文本
        val keyText = args[1]?.toString()
            ?.replace("-----BEGIN PUBLIC KEY-----", "")
            ?.replace("-----END PUBLIC KEY-----", "")
            ?.replace("-----BEGIN PRIVATE KEY-----", "")
            ?.replace("-----END PRIVATE KEY-----", "")
            ?.trim() ?: ""
        val keyBytes = Base64.decode(keyText, Base64.NO_WRAP)
        val key = java.security.KeyFactory.getInstance("RSA")
            .generatePublic(java.security.spec.X509EncodedKeySpec(keyBytes))
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return Base64.encodeToString(cipher.doFinal(data), Base64.NO_WRAP)
    }
}
