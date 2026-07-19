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

        val legacyExclusions = exclusions("backup_rules")
        val modernExclusions = exclusions("data_extraction_rules")

        assertTrue("Legacy backup exclusions are incomplete", legacyExclusions.containsAll(expectedDomains))
        assertTrue("Cloud/device-transfer exclusions are incomplete", modernExclusions.containsAll(expectedDomains))
        assertTrue("Every domain must be excluded from both transports", modernExclusions.size >= expectedDomains.size * 2)
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

    private fun exclusions(resourceName: String): List<String> {
        val resourceId = xmlResource(resourceName)
        assertTrue("$resourceName must be packaged", resourceId != 0)
        val parser = context.resources.getXml(resourceId)
        val domains = mutableListOf<String>()
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "exclude") {
                assertEquals(".", parser.getAttributeValue(null, "path"))
                domains += parser.getAttributeValue(null, "domain")
            }
            parser.next()
        }
        return domains
    }

    private fun xmlResource(name: String): Int = context.resources.getIdentifier(name, "xml", context.packageName)
}
