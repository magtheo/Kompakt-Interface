package dev.magnor.kompakt

import org.junit.Assert.assertTrue
import org.junit.Test

class AppInfoTest {

    @Test
    fun appProtocolIsPositive() {
        assertTrue(AppInfo.APP_PROTOCOL > 0)
    }

    @Test
    fun clientDoesNotRequireNewerServerThanItself() {
        assertTrue(AppInfo.MINIMUM_SERVER_PROTOCOL <= AppInfo.APP_PROTOCOL)
    }
}
