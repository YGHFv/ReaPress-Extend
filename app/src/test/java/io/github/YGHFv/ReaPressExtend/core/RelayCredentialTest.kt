package io.github.YGHFv.ReaPressExtend.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayCredentialTest {
    @Test
    fun `凭据具有足够长度且每次生成不同`() {
        val credentials = (1..64).map { RelayCredential.generate() }

        assertTrue(credentials.all { it.length == 64 && RelayCredential.isValid(it) })
        assertTrue(credentials.toSet().size == credentials.size)
    }

    @Test
    fun `只有完全一致的凭据通过验证`() {
        val expected = RelayCredential.generate()
        assertTrue(RelayCredential.matches(expected, expected))
        val replacement = if (expected.first() == '0') '1' else '0'
        assertFalse(RelayCredential.matches(expected, replacement + expected.drop(1)))
        assertFalse(RelayCredential.matches(expected, expected.dropLast(1) + 'z'))
        assertFalse(RelayCredential.matches(expected, RelayCredential.generate()))
    }

    @Test
    fun `缺失和非法凭据一律拒绝而不是空值相等即放行`() {
        val expected = RelayCredential.generate()
        listOf(null, "", " ", "0".repeat(63), "0".repeat(65), "g".repeat(64), "Ａ".repeat(64))
            .forEach { malformed ->
                assertFalse(RelayCredential.isValid(malformed))
                assertFalse(RelayCredential.matches(expected, malformed))
                assertFalse(RelayCredential.matches(malformed, expected))
                assertFalse(RelayCredential.matches(malformed, malformed))
            }
    }
}
