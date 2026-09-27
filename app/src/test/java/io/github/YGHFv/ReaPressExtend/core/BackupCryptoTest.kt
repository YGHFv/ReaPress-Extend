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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

/** [BackupCrypto] 的加解密与坏文件判定。 */
class BackupCryptoTest {

    /**
     * 单测里的迭代次数。
     *
     * 用小值只是为了跑得快 —— 迭代次数写在信封里，解密时从文件读，所以与生产取值
     * （[BackupCrypto.DEFAULT_ITERATIONS]）**格式完全一致、互相可解**。「生产取值也能往返」
     * 单独有一条用例。
     */
    private val fast = 1_000

    private val at = 1_790_520_701_747L

    private fun encrypt(plain: String, password: String, iterations: Int = fast) =
        BackupCrypto.encrypt(
            plain = plain,
            password = password,
            at = at,
            appVersion = "0.1.0",
            iterations = iterations,
        )

    @Test
    fun `加密之后能原样解回`() {
        val plain = """{"format":1,"prefs":{"reapress_records":{"records":"[]"}}}"""
        val envelope = encrypt(plain, "hunter2")
        assertEquals(plain, BackupCrypto.decrypt(envelope, "hunter2"))
    }

    @Test
    fun `生产迭代次数也能往返，且次数写进了文件`() {
        val envelope = encrypt("payload", "pw", iterations = BackupCrypto.DEFAULT_ITERATIONS)
        assertEquals(BackupCrypto.DEFAULT_ITERATIONS, JSONObject(envelope).getInt("iterations"))
        assertEquals("payload", BackupCrypto.decrypt(envelope, "pw"))
    }

    @Test
    fun `密码不对时明确说是密码问题`() {
        val envelope = encrypt("secret", "right")
        try {
            BackupCrypto.decrypt(envelope, "wrong")
            fail("密码不对时应当拒绝")
        } catch (e: BackupCrypto.BadPasswordException) {
            assertTrue(e.message!!, e.message!!.contains("密码"))
        }
    }

    /**
     * 改一个字节就解不开 —— GCM 的完整性校验，也是「文件被改坏」与「密码不对」被合成
     * 一句话的依据（见 [BackupCrypto.BadPasswordException] 的注释）。
     */
    @Test
    fun `密文被改动一个字节就解不开`() {
        val envelope = JSONObject(encrypt("secret", "pw"))
        val sealed = Base64.getDecoder().decode(envelope.getString("data"))
        sealed[sealed.size - 1] = (sealed[sealed.size - 1].toInt() xor 0x01).toByte()
        envelope.put("data", Base64.getEncoder().encodeToString(sealed))

        try {
            BackupCrypto.decrypt(envelope.toString(), "pw")
            fail("密文被动过时应当拒绝")
        } catch (e: BackupCrypto.BadPasswordException) {
            assertTrue(e.message!!, e.message!!.contains("损坏") || e.message!!.contains("密码"))
        }
    }

    @Test
    fun `盐和随机向量每次都不同 —— 同样的内容密码加两次，结果不一样`() {
        val a = encrypt("same", "pw")
        val b = encrypt("same", "pw")
        // 一样就说明盐或 IV 被复用了；GCM 下 IV 复用会直接击穿安全性，不是风格问题。
        assertFalse("两次加密的结果不应相同", a == b)
        assertEquals("same", BackupCrypto.decrypt(a, "pw"))
        assertEquals("same", BackupCrypto.decrypt(b, "pw"))
    }

    @Test
    fun `中文与符号密码也能往返`() {
        val plain = "包裹记录：菜鸟驿站 · 取件码 8-2-3021"
        val password = "密码 with spaces & 符号!@#"
        assertEquals(plain, BackupCrypto.decrypt(encrypt(plain, password), password))
    }

    @Test
    fun `isEncrypted 能区分明文备份 加密备份 与垃圾`() {
        assertTrue(BackupCrypto.isEncrypted(encrypt("x", "pw")))
        // 明文备份长这样（BackupBundle 的输出），不能被当成加密的 ——
        // 否则恢复时会平白要一次密码。
        assertFalse(
            BackupCrypto.isEncrypted("""{"format":1,"exportedAt":1,"appVersion":"x","prefs":{}}"""),
        )
        assertFalse(BackupCrypto.isEncrypted("这不是 JSON"))
        assertFalse(BackupCrypto.isEncrypted(""))
        assertFalse(BackupCrypto.isEncrypted(null))
    }

    @Test
    fun `空密码在加密阶段就被拒绝`() {
        try {
            encrypt("x", "")
            fail("空密码应当被拒绝")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!, e.message!!.contains("密码"))
        }
    }

    @Test
    fun `信封缺字段时给出可读的原因而不是崩掉`() {
        // 注意 `JSONObject.remove` 返回的是**被删掉的那个值**，不是对象本身 ——
        // 直接链式 `.toString()` 会拿到一个字符串 "salt" 的值，测出来的就不是想测的东西。
        val damaged = JSONObject(encrypt("x", "pw"))
        damaged.remove("salt")
        try {
            BackupCrypto.decrypt(damaged.toString(), "pw")
            fail("缺盐值时应当拒绝")
        } catch (e: BackupCrypto.BadPasswordException) {
            assertTrue(e.message!!, e.message!!.contains("盐"))
        }
    }

    /**
     * 换 KDF 之后拿旧文件来解密要能说清楚，而不是硬解出乱码 ——
     * `kdf` 写进文件正是为了这一条。
     */
    @Test
    fun `不认识的 KDF 明确报出来`() {
        val other = JSONObject(encrypt("x", "pw")).put("kdf", "argon2id").toString()
        try {
            BackupCrypto.decrypt(other, "pw")
            fail("不认识的 KDF 应当拒绝")
        } catch (e: BackupCrypto.BadPasswordException) {
            assertTrue(e.message!!, e.message!!.contains("argon2id"))
        }
    }

    @Test
    fun `用别的迭代次数加的密也能解开 —— 参数随文件走`() {
        val envelope = encrypt("payload", "pw", iterations = 2_000)
        assertEquals(2_000, JSONObject(envelope).getInt("iterations"))
        assertEquals("payload", BackupCrypto.decrypt(envelope, "pw"))
    }
}
