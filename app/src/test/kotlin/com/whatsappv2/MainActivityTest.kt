package com.whatsappv2

import androidx.test.core.app.ActivityScenario
import com.whatsappv2.di.ROBOLECTRIC_SDK
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertNotNull

/**
 * Task 6 done-when: the Activity launches and its `@Inject` field resolves.
 *
 * Hilt injects an `@AndroidEntryPoint` Activity during `onCreate`, so a missing
 * binding surfaces as a crash on launch rather than a compile error. Launching the
 * real Activity is the only thing that actually proves the graph reaches it.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [ROBOLECTRIC_SDK])
class MainActivityTest {

    @get:Rule
    val hilt = HiltAndroidRule(this)

    @Before
    fun setUp() {
        hilt.inject()
    }

    @Test
    fun `launches and receives its injected Logger`() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                // lateinit throws if Hilt did not inject it during onCreate.
                assertNotNull(activity.logger)
            }
        }
    }
}
