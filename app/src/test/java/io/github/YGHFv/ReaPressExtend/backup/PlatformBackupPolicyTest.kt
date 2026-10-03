package io.github.YGHFv.ReaPressExtend.backup

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element

class PlatformBackupPolicyTest {
    private val domains = setOf("root", "file", "database", "sharedpref", "external", "device_root", "device_file", "device_database", "device_sharedpref")

    @Test
    fun manifestDisablesBackupAndReferencesBothPolicies() {
        val manifest = parse("AndroidManifest.xml")
        val app = manifest.getElementsByTagName("application").item(0) as Element
        assertEquals("false", app.getAttribute("android:allowBackup"))
        assertEquals("@xml/backup_rules", app.getAttribute("android:fullBackupContent"))
        assertEquals("@xml/data_extraction_rules", app.getAttribute("android:dataExtractionRules"))
        assertFalse(app.hasAttribute("android:backupAgent"))
    }

    @Test
    fun legacyBackupExcludesAllCredentialAndFileDomains() {
        assertExcludes(parse("res/xml/backup_rules.xml"))
    }

    @Test
    fun cloudAndDeviceTransferEachExcludeAllDomains() {
        val root = parse("res/xml/data_extraction_rules.xml")
        for (transport in listOf("cloud-backup", "device-transfer")) {
            val nodes = root.getElementsByTagName(transport)
            assertEquals(1, nodes.length)
            assertExcludes(nodes.item(0) as Element)
        }
    }

    private fun assertExcludes(root: Element) {
        assertEquals(0, root.getElementsByTagName("include").length)
        val excludes = root.getElementsByTagName("exclude")
        assertEquals(domains.size, excludes.length)
        assertEquals(domains, (0 until excludes.length).map { index ->
            val node = excludes.item(index) as Element
            assertEquals(".", node.getAttribute("path"))
            node.getAttribute("domain")
        }.toSet())
    }

    private fun parse(relative: String): Element {
        val file = listOf(File("app/src/main/$relative"), File("src/main/$relative")).first(File::exists)
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        return factory.newDocumentBuilder().parse(file).documentElement
    }
}
