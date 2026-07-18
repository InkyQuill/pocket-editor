package net.inkyquill.pocketeditor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BuildSmokeTest {
    @Test
    fun applicationId_isStable() {
        assertEquals("net.inkyquill.pocketeditor", BuildConfig.APPLICATION_ID)
    }
}
