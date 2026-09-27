/*
 * Copyright (C) 2026 YGHFv
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package io.github.YGHFv.ReaPressExtend.core

import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 备份文件的加密层（AES-GCM，AEAD 自带完整性校验，密文改一个字节解密即炸，不用另拼 HMAC）。
 * 备份里有淘宝登录态与住址、手机尾号，要拷去网盘/别的设备，所以认用户记得住的密码——
 * keystore 里的密钥换机、重装就打不开了，而备份存在的意义十有八九是换机。
 * 盐、迭代次数、IV 都写进文件（不是秘密，且参数换了还能用旧备份解出）。
 * 与 BackupBundle 同约定：不碰 Context、不读时钟，exportedAt 由调用方传入。
 */
internal object BackupCrypto {

    /** PBKDF2 迭代次数：让猜密码贵到不值得，一次解密仍在几百毫秒。写在文件里，调大不失效。 */
    const val DEFAULT_ITERATIONS = 120_000

    /** 加密信封的顶层标记。判断「这份文件是不是加密的」只认它。 */
    const val FILE_MARKER = "reapressEncrypted"

    private const val KEY_KDF = "kdf"
    private const val KEY_ITERATIONS = "iterations"
    private const val KEY_SALT = "salt"
    private const val KEY_IV = "iv"
    private const val KEY_DATA = "data"
    private const val KEY_EXPORTED_AT = "exportedAt"
    private const val KEY_APP_VERSION = "appVersion"

    /** 写进文件：以后换算法时旧文件能明确报「不支持」，而不是硬解出一堆乱码。 */
    private const val KDF_NAME = "PBKDF2WithHmacSHA256"
    private const val CIPHER_NAME = "AES/GCM/NoPadding"

    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val KEY_BITS = 256
    private const val GCM_TAG_BITS = 128

    /**
     * 密码不对（或文件被改坏）时抛出。两种情况刻意合成一个异常：GCM 对它们给同一个信号
     * （AEADBadTagException），用户能做的处置也是同一件事，拆开会让调用方误以为能区分。
     */
    class BadPasswordException(message: String) : IllegalArgumentException(message)

    /** 判断是不是加密信封。不是 JSON / 别的 JSON 都返回 false 走明文那条路；不抛异常。 */
    fun isEncrypted(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return false
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return false
        return root.optBoolean(FILE_MARKER, false)
    }

    /** 把明文备份加密成信封文本。iterations 给单测传小值跑快，格式与生产一致（解密迭代次数从文件读）。 */
    fun encrypt(
        plain: String,
        password: String,
        at: Long,
        appVersion: String,
        iterations: Int = DEFAULT_ITERATIONS,
    ): String {
        require(password.isNotEmpty()) { "加密备份必须有密码" }
        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
        // IV 每次全新生成：同密钥重复 IV 直接击穿 GCM，备份是周期动作，必然会发生。
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }

        val cipher = Cipher.getInstance(CIPHER_NAME)
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(deriveKey(password, salt, iterations), "AES"),
            GCMParameterSpec(GCM_TAG_BITS, iv),
        )
        val sealed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))

        return JSONObject()
            .put(FILE_MARKER, true)
            .put(KEY_KDF, KDF_NAME)
            .put(KEY_ITERATIONS, iterations)
            .put(KEY_SALT, encode(salt))
            .put(KEY_IV, encode(iv))
            .put(KEY_DATA, encode(sealed))
            .put(KEY_EXPORTED_AT, at)
            .put(KEY_APP_VERSION, appVersion)
            .toString()
    }

    /** 解开信封拿回明文。密码不对、文件被改坏、信封缺字段都抛 [BadPasswordException]。 */
    fun decrypt(raw: String, password: String): String {
        val root = runCatching { JSONObject(raw) }
            .getOrElse { throw BadPasswordException("这份加密备份读不出来（文件损坏）") }
        if (!root.optBoolean(FILE_MARKER, false)) {
            throw BadPasswordException("这份文件不是加密备份")
        }
        val kdf = root.optString(KEY_KDF, "")
        if (kdf != KDF_NAME) {
            throw BadPasswordException("这份备份用的是「$kdf」，当前版本还不支持解开它")
        }
        val iterations = root.optInt(KEY_ITERATIONS, 0)
        if (iterations <= 0) throw BadPasswordException("这份加密备份读不出来（缺少迭代参数）")

        val salt = decode(root.optString(KEY_SALT, ""))
            ?: throw BadPasswordException("这份加密备份读不出来（缺少盐值）")
        val iv = decode(root.optString(KEY_IV, ""))
            ?: throw BadPasswordException("这份加密备份读不出来（缺少随机向量）")
        val sealed = decode(root.optString(KEY_DATA, ""))
            ?: throw BadPasswordException("这份加密备份读不出来（没有密文）")

        return runCatching {
            val cipher = Cipher.getInstance(CIPHER_NAME)
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(deriveKey(password, salt, iterations), "AES"),
                GCMParameterSpec(GCM_TAG_BITS, iv),
            )
            String(cipher.doFinal(sealed), Charsets.UTF_8)
        }.getOrElse {
            throw BadPasswordException("密码不对，或者这份备份文件已经损坏")
        }
    }

    private fun deriveKey(password: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance(KDF_NAME).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun decode(text: String): ByteArray? =
        if (text.isEmpty()) null else runCatching { Base64.getDecoder().decode(text) }.getOrNull()
}
