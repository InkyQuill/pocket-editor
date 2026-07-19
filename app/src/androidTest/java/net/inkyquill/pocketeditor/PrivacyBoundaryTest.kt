package net.inkyquill.pocketeditor

import android.content.Context
import android.content.pm.ApplicationInfo
import android.security.NetworkSecurityPolicy
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser

@RunWith(AndroidJUnit4::class)
class PrivacyBoundaryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun installedApplicationDisablesBackupAndCleartextTraffic() {
        val applicationInfo = context.applicationInfo

        assertEquals(0, applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
        assertFalse(NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted)
        assertTrue("Network security config must be packaged", xmlResource("network_security_config") != 0)
    }

    @Test
    fun packagedBackupPoliciesExcludeEveryPrivateStorageDomain() {
        val expectedDomains = setOf(
            "root",
            "file",
            "database",
            "sharedpref",
            "external",
            "device_root",
            "device_file",
            "device_database",
            "device_sharedpref",
        )

        val legacyExclusions = exclusionsByTransport("backup_rules")
        val modernExclusions = exclusionsByTransport("data_extraction_rules")

        assertEquals(expectedDomains, legacyExclusions.getValue("full-backup-content").toSet())
        assertEquals(expectedDomains, modernExclusions.getValue("cloud-backup").toSet())
        assertEquals(expectedDomains, modernExclusions.getValue("device-transfer").toSet())
    }

    @Test
    fun installedApplicationContainsNoTelemetrySdk() {
        val forbiddenClasses = listOf(
            "com.google.firebase.analytics.FirebaseAnalytics",
            "com.google.firebase.crashlytics.FirebaseCrashlytics",
            "com.facebook.appevents.AppEventsLogger",
            "io.sentry.Sentry",
            "com.mixpanel.android.mpmetrics.MixpanelAPI",
        )

        forbiddenClasses.forEach { className ->
            assertTrue(
                "$className must not be packaged",
                runCatching { Class.forName(className) }.isFailure,
            )
        }
    }

    private fun exclusionsByTransport(resourceName: String): Map<String, List<String>> {
        val resourceId = xmlResource(resourceName)
        assertTrue("$resourceName must be packaged", resourceId != 0)
        val parser = context.resources.getXml(resourceId)
        val domains = mutableMapOf<String, MutableList<String>>()
        var transport: String? = null
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when {
                parser.eventType == XmlPullParser.START_TAG && parser.name in TRANSPORT_TAGS -> {
                    transport = parser.name
                }
                parser.eventType == XmlPullParser.START_TAG && parser.name == "exclude" -> {
                    assertEquals(".", parser.getAttributeValue(null, "path"))
                    domains.getOrPut(requireNotNull(transport)) { mutableListOf() } +=
                        parser.getAttributeValue(null, "domain")
                }
                parser.eventType == XmlPullParser.END_TAG && parser.name == transport -> {
                    transport = null
                }
            }
            parser.next()
        }
        return domains
    }

    private fun xmlResource(name: String): Int = context.resources.getIdentifier(name, "xml", context.packageName)

    private companion object {
        val TRANSPORT_TAGS = setOf("full-backup-content", "cloud-backup", "device-transfer")
    }
}
