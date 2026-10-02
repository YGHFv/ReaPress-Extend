package io.github.YGHFv.ReaPressExtend.relay

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayContractTest {
    @Test
    fun `所有入站广播发送入口必须经过统一鉴权发送器`() {
        val root = projectFile("src/main/java/io/github/YGHFv/ReaPressExtend")
        val callers = listOf("hook/ExpressRelaySender.kt", "hook/CainiaoIdentityBridge.kt", "relay/WatchdogReporter.kt")
        callers.forEach { relative ->
            val source = File(root, relative).readText()
            assertTrue(relative, source.contains("AuthenticatedRelaySender.send("))
            assertFalse(relative, Regex("""\.sendBroadcast(?:AsUser)?\s*\(""").containsMatchIn(source))
        }
    }

    @Test
    fun `入站接收器必须在业务调度前验证凭据`() {
        val source = projectFile("src/main/java/io/github/YGHFv/ReaPressExtend/relay/ExpressRelayReceiver.kt").readText()
        val ingress = source.indexOf("if (!RelayIngress.accept(context, intent)) return")
        val dispatch = source.indexOf("handleAuthenticated(context, intent)")
        assertTrue(ingress >= 0)
        assertTrue(dispatch > ingress)
    }

    @Test
    fun `仅单测依赖libxposedAPI正式APK保持compileOnly`() {
        val build = projectFile("build.gradle.kts").readText()
        assertTrue(build.contains("compileOnly(\"io.github.libxposed:api:"))
        assertFalse(Regex("""(?m)^\s*(?:implementation|api|runtimeOnly)\("io\.github\.libxposed:api:""").containsMatchIn(build))
    }

    private fun projectFile(relative: String): File = listOf("app/$relative", relative)
        .map(::File)
        .firstOrNull(File::exists)
        ?: throw AssertionError("Cannot locate $relative in ${File(".").absolutePath}")
}
