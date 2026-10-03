package io.github.YGHFv.ReaPressExtend.relay

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class PackageSyncContractTest {
    @Test fun snapshotBatchDoesNotFanOutTraceRequests() {
        val source = source("relay/ExpressRelayReceiver.kt")
        val start = source.indexOf("val packageSnapshot =")
        val guard = source.indexOf("if (!packageSnapshot)", start)
        val auto = source.indexOf("ModuleTraceFetcher.maybeAutoFetch", start)
        val backstop = source.indexOf("ModuleTraceFetcher.maybeBackstopFetch", start)
        assertTrue(start >= 0 && guard > start && auto > guard && backstop > guard)
        assertTrue(source.substring(start, guard).contains("EXTRA_PACKAGE_SNAPSHOT"))
    }
    @Test fun moduleAndHostCooldownFilesAreNotImportedByBackup() {
        assertFalse(source("backup/ExpressBackup.kt").contains("reapress_package_sync"))
    }
    @Test fun hostAdapterNeverChangesForegroundFlagOrCallsReset() {
        val source = source("hook/CainiaoPackageSyncClient.kt")
        assertFalse(source.contains("bNe"))
        assertFalse(source.contains("\"reset\""))
        assertFalse(source.contains("\"enterForeground\""))
        assertTrue(source.contains("\"showLoginUI\", false"))
        assertTrue(source.contains("\"retryTime\", 0"))
        assertTrue(source.contains("\"cancelRequest\""))
    }
    private fun source(path: String): String = listOf("app/src/main/java/io/github/YGHFv/ReaPressExtend/$path",
        "src/main/java/io/github/YGHFv/ReaPressExtend/$path").map(::File).first(File::exists).readText()
}
