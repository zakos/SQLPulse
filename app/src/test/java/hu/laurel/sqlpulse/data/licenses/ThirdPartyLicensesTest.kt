package hu.laurel.sqlpulse.data.licenses

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keeps the Licenses screen honest: a dependency added to the app without a license entry, a version
 * bumped without updating the entry, or an entry pointing at a missing text fails here.
 * Gradle runs unit tests with the module directory (`app/`) as the working directory.
 */
class ThirdPartyLicensesTest {
    private val assets = File("src/main/assets/licenses")
    private val buildFile = File("build.gradle.kts")
    private val catalog = File("../gradle/libs.versions.toml")

    @Test
    fun everyReferencedAssetExistsAndHasText() {
        for (name in ThirdPartyLicenses.allAssets) {
            val file = File(assets, name)
            assertTrue("missing license asset $name", file.isFile)
            assertTrue("empty license asset $name", file.readText().isNotBlank())
        }
    }

    @Test
    fun noticeAssetIsTheReposNoticeFile() {
        assertEquals(File("../NOTICE").readText(), File(assets, ThirdPartyLicenses.NOTICE_ASSET).readText())
    }

    @Test
    fun appLicenseAssetIsTheReposLicenseFile() {
        assertEquals(File("../LICENSE").readText(), File(assets, ThirdPartyLicenses.APP_LICENSE_ASSET).readText())
    }

    @Test
    fun everyRuntimeDependencyHasAnEntry() {
        val declared = runtimeAliases()
        assertTrue("found no implementation(libs.…) lines; did the build file move?", declared.size > 10)
        val covered = ThirdPartyLicenses.components.flatMap { it.gradleAliases }.toSet()
        val missing = declared - covered
        assertTrue("dependencies without a license entry in ThirdPartyLicenses: $missing", missing.isEmpty())
    }

    @Test
    fun noEntryClaimsADependencyTheAppDoesNotHave() {
        val declared = runtimeAliases()
        val stale = ThirdPartyLicenses.components.flatMap { it.gradleAliases }.toSet() - declared
        assertTrue("license entries for dependencies no longer in app/build.gradle.kts: $stale", stale.isEmpty())
    }

    @Test
    fun versionsMatchTheVersionCatalog() {
        val versions = Regex("""^([A-Za-z0-9_-]+)\s*=\s*"([^"]+)"""", RegexOption.MULTILINE)
            .findAll(catalog.readText().substringAfter("[versions]").substringBefore("[libraries]"))
            .associate { it.groupValues[1] to it.groupValues[2] }
        for (component in ThirdPartyLicenses.components) {
            val key = component.versionKey ?: continue
            val actual = versions[key] ?: error("no '$key' in libs.versions.toml for ${component.name}")
            assertTrue(
                "${component.name}: shows '${component.version}' but the catalog has $actual",
                component.version!!.endsWith(actual),
            )
        }
    }

    @Test
    fun entriesAreWellFormed() {
        val names = ThirdPartyLicenses.components.map { it.name }
        assertEquals("duplicate component names", names.size, names.toSet().size)
        for (c in ThirdPartyLicenses.components) {
            assertTrue("${c.name}: no license", c.licenses.isNotEmpty())
            assertTrue("${c.name}: source must be https", c.sourceUrl.startsWith("https://"))
        }
        assertTrue(ThirdPartyLicenses.byGroup(LicenseGroup.FONTS).map { it.name }.containsAll(listOf("Inter", "JetBrains Mono")))
    }

    /** Aliases of the plain `implementation(...)` lines (also inside `platform(...)` / `variantOf(...)`). */
    private fun runtimeAliases(): Set<String> =
        Regex("""^\s*implementation\((?:platform\(|variantOf\()?libs\.([A-Za-z0-9.]+)""", RegexOption.MULTILINE)
            .findAll(buildFile.readText())
            .map { it.groupValues[1] }
            .toSet()
}
