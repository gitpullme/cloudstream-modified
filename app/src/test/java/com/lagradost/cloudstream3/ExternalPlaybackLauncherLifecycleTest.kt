package com.lagradost.cloudstream3

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import com.lagradost.cloudstream3.actions.ExternalPlaybackSupport
import com.lagradost.cloudstream3.actions.NetworkStreamPlayerLauncher
import com.lagradost.cloudstream3.actions.OpenInAppAction
import com.lagradost.cloudstream3.actions.VideoClickActionHolder
import com.lagradost.cloudstream3.actions.temp.NetworkStreamPlayerPackage
import com.lagradost.cloudstream3.ui.result.LinkLoadingResult
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.txt
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class ExternalPlaybackLauncherLifecycleTest {

    private class TestActivityResultLauncher : ActivityResultLauncher<Intent>() {
        var lastLaunchedIntent: Intent? = null
        var lastLaunchedOptions: ActivityOptionsCompat? = null
        var launchCount: Int = 0
        var unregisterCalled: Boolean = false

        override fun launch(input: Intent, options: ActivityOptionsCompat?) {
            lastLaunchedIntent = input
            lastLaunchedOptions = options
            launchCount++
        }

        override fun unregister() {
            unregisterCalled = true
        }

        override val contract: ActivityResultContract<Intent, *>
            get() = throw UnsupportedOperationException("Not implemented for test")
    }

    private class TestMainActivity : MainActivity() {
        var testIsFinishing: Boolean = false
        var testIsDestroyed: Boolean = false
        var testLifecycleState: Lifecycle.State? = null

        override fun isFinishing(): Boolean = testIsFinishing
        override fun isDestroyed(): Boolean = testIsDestroyed

        override val lifecycle: Lifecycle
            get() = object : Lifecycle() {
                override val currentState: State
                    get() = testLifecycleState ?: State.INITIALIZED

                override fun addObserver(observer: LifecycleObserver) {}
                override fun removeObserver(observer: LifecycleObserver) {}
            }

        fun setLifecycleState(state: Lifecycle.State) {
            testLifecycleState = state
        }

        fun invokeOnDestroy() {
            onDestroy()
        }

        override fun onDestroy() {
            externalActivityResultLauncher = null
            testIsDestroyed = true
            testLifecycleState = Lifecycle.State.DESTROYED
        }
    }

    class FakeOpenInAppAction : OpenInAppAction(txt("FakeApp"), "com.fake.app") {
        var onReturnedCalled = false
        var passedResultCode: Int? = null
        var onResultCalled = false

        override val sourceTypes: Set<ExtractorLinkType> = emptySet()

        override suspend fun putExtra(
            context: Context,
            intent: Intent,
            video: ResultEpisode,
            result: LinkLoadingResult,
            index: Int?
        ) {}

        override fun onResult(activity: Activity, intent: Intent?) {
            onResultCalled = true
        }

        override fun onExternalPlaybackReturned(resultCode: Int, data: Intent?): Boolean {
            onReturnedCalled = true
            passedResultCode = resultCode
            return true
        }
    }

    class FakeNetworkStreamPlayerPackage(
        private val finalizeSuccess: Boolean
    ) : NetworkStreamPlayerPackage() {
        var onReturnedCalled = false
        var passedResultCode: Int? = null

        override fun onExternalPlaybackReturned(resultCode: Int, data: Intent?): Boolean {
            onReturnedCalled = true
            passedResultCode = resultCode
            return finalizeSuccess
        }
    }

    @Before
    fun setUp() {
        CommonActivity.setActivityInstance(null)
    }

    @After
    fun tearDown() {
        CommonActivity.setActivityInstance(null)
    }

    @Test
    fun `MainActivity companion object has no static activityResultLauncher`() {
        val companionFields = MainActivity.Companion::class.java.declaredFields.map { it.name }
        assertFalse(
            "MainActivity.Companion must not contain static activityResultLauncher",
            companionFields.contains("activityResultLauncher")
        )

        val mainActivityFields = MainActivity::class.java.declaredFields.map { it.name }
        assertTrue(
            "MainActivity must contain instance property externalActivityResultLauncher",
            mainActivityFields.contains("externalActivityResultLauncher")
        )
    }

    @Test
    fun `CommonActivity class does not define or store any external ActivityResultLauncher`() {
        val commonFields = CommonActivity::class.java.declaredFields.map { it.name }
        assertFalse(
            "CommonActivity must not store activityResultLauncher",
            commonFields.contains("activityResultLauncher")
        )
        assertFalse(
            "CommonActivity must not store externalActivityResultLauncher",
            commonFields.contains("externalActivityResultLauncher")
        )
    }

    @Test
    fun `launchExternalActivityForResult throws when launcher is not registered`() {
        val activity = TestMainActivity().apply {
            setLifecycleState(Lifecycle.State.STARTED)
            externalActivityResultLauncher = null
        }

        try {
            activity.launchExternalActivityForResult(Intent("TEST_ACTION"))
            fail("Expected IllegalStateException when launcher is not registered")
        } catch (e: IllegalStateException) {
            assertTrue(
                "Exception message should mention launcher not registered, but was: ${e.message}",
                e.message?.contains("ActivityResultLauncher is not registered") == true
            )
        }
    }

    @Test
    fun `launchExternalActivityForResult throws when activity is finishing or destroyed`() {
        val launcher = TestActivityResultLauncher()

        // Finishing
        val finishingActivity = TestMainActivity().apply {
            setLifecycleState(Lifecycle.State.STARTED)
            externalActivityResultLauncher = launcher
            testIsFinishing = true
        }
        try {
            finishingActivity.launchExternalActivityForResult(Intent("TEST_ACTION"))
            fail("Expected IllegalStateException when activity is finishing")
        } catch (e: IllegalStateException) {
            assertTrue(
                "Exception message should mention finishing, but was: ${e.message}",
                e.message?.contains("finishing") == true
            )
        }

        // Destroyed
        val destroyedActivity = TestMainActivity().apply {
            setLifecycleState(Lifecycle.State.STARTED)
            externalActivityResultLauncher = launcher
            testIsDestroyed = true
        }
        try {
            destroyedActivity.launchExternalActivityForResult(Intent("TEST_ACTION"))
            fail("Expected IllegalStateException when activity is destroyed")
        } catch (e: IllegalStateException) {
            assertTrue(
                "Exception message should mention destroyed, but was: ${e.message}",
                e.message?.contains("destroyed") == true
            )
        }
    }

    @Test
    fun `launchExternalActivityForResult throws when lifecycle state is not at least STARTED`() {
        val launcher = TestActivityResultLauncher()
        val activity = TestMainActivity().apply {
            externalActivityResultLauncher = launcher
            setLifecycleState(Lifecycle.State.CREATED)
        }

        try {
            activity.launchExternalActivityForResult(Intent("TEST_ACTION"))
            fail("Expected IllegalStateException when lifecycle is CREATED")
        } catch (e: IllegalStateException) {
            assertTrue(
                "Exception message should mention STARTED, but was: ${e.message}",
                e.message?.contains("STARTED") == true
            )
        }
    }

    @Test
    fun `launchExternalActivityForResult succeeds when activity is STARTED and launcher is registered`() {
        val launcher = TestActivityResultLauncher()
        val activity = TestMainActivity().apply {
            setLifecycleState(Lifecycle.State.STARTED)
            externalActivityResultLauncher = launcher
        }

        val testIntent = Intent("TEST_ACTION")
        activity.launchExternalActivityForResult(testIntent)

        assertEquals("Launcher should be invoked once", 1, launcher.launchCount)
        assertEquals("Launched intent must match", testIntent, launcher.lastLaunchedIntent)
    }

    @Test
    fun `recreated MainActivity instances use their own independent launcher`() {
        val launcher1 = TestActivityResultLauncher()
        val instance1 = TestMainActivity().apply {
            setLifecycleState(Lifecycle.State.STARTED)
            externalActivityResultLauncher = launcher1
        }

        val launcher2 = TestActivityResultLauncher()
        val instance2 = TestMainActivity().apply {
            setLifecycleState(Lifecycle.State.STARTED)
            externalActivityResultLauncher = launcher2
        }

        // Launch on instance 1
        val intent1 = Intent("INTENT_1")
        instance1.launchExternalActivityForResult(intent1)
        assertEquals(1, launcher1.launchCount)
        assertEquals(0, launcher2.launchCount)

        // Simulate destruction of instance 1: launcher is cleared
        instance1.invokeOnDestroy()
        assertNull(
            "Destroyed instance must clear its launcher",
            instance1.externalActivityResultLauncher
        )

        // Launch on instance 2 uses its own launcher
        val intent2 = Intent("INTENT_2")
        instance2.launchExternalActivityForResult(intent2)
        assertEquals(1, launcher1.launchCount)
        assertEquals(1, launcher2.launchCount)
        assertEquals(intent2, launcher2.lastLaunchedIntent)
    }

    @Test
    fun `handleExternalActivityResult cleans up proxy and notifies action for RESULT_CANCELED`() {
        val activity = TestMainActivity()
        val fakeAction = FakeOpenInAppAction()
        VideoClickActionHolder.allVideoClickActions.add(fakeAction)

        val keysRemoved = AtomicBoolean(false)

        try {
            activity.handleExternalActivityResult(
                resultCode = Activity.RESULT_CANCELED,
                data = null,
                getActionUid = { fakeAction.uniqueId() },
                onRemoveKeys = { keysRemoved.set(true) }
            )

            assertTrue(
                "onExternalPlaybackReturned must be called for RESULT_CANCELED",
                fakeAction.onReturnedCalled
            )
            assertEquals(Activity.RESULT_CANCELED, fakeAction.passedResultCode)
        } finally {
            VideoClickActionHolder.allVideoClickActions.remove(fakeAction)
        }
    }

    @Test
    fun `handleExternalActivityResult preserves pending launch marker if tracker has not finalized NetworkStreamPlayer`() {
        val activity = TestMainActivity()
        val nspAction = FakeNetworkStreamPlayerPackage(finalizeSuccess = false)
        VideoClickActionHolder.allVideoClickActions.add(nspAction)

        val keysRemoved = AtomicBoolean(false)

        try {
            activity.handleExternalActivityResult(
                resultCode = Activity.RESULT_CANCELED,
                data = null,
                getActionUid = { nspAction.uniqueId() },
                onRemoveKeys = { keysRemoved.set(true) }
            )

            assertTrue(
                "onExternalPlaybackReturned must be called",
                nspAction.onReturnedCalled
            )
            assertFalse(
                "Keys must NOT be removed when NetworkStreamPlayer tracker has not finalized",
                keysRemoved.get()
            )
        } finally {
            VideoClickActionHolder.allVideoClickActions.remove(nspAction)
        }
    }

    @Test
    fun `handleExternalActivityResult clears markers when tracker has finalized NetworkStreamPlayer`() {
        val activity = TestMainActivity()
        val nspAction = FakeNetworkStreamPlayerPackage(finalizeSuccess = true)
        VideoClickActionHolder.allVideoClickActions.add(nspAction)

        val keysRemoved = AtomicBoolean(false)

        try {
            activity.handleExternalActivityResult(
                resultCode = Activity.RESULT_CANCELED,
                data = null,
                getActionUid = { nspAction.uniqueId() },
                onRemoveKeys = { keysRemoved.set(true) }
            )

            assertTrue(
                "onExternalPlaybackReturned must be called",
                nspAction.onReturnedCalled
            )
            assertTrue(
                "Keys MUST be removed when NetworkStreamPlayer tracker has finalized",
                keysRemoved.get()
            )
        } finally {
            VideoClickActionHolder.allVideoClickActions.remove(nspAction)
        }
    }

    @Test
    fun `handleExternalActivityResult invokes onResultSafe on RESULT_OK`() {
        val activity = TestMainActivity()
        val fakeAction = FakeOpenInAppAction()
        VideoClickActionHolder.allVideoClickActions.add(fakeAction)

        val keysRemoved = AtomicBoolean(false)

        try {
            activity.handleExternalActivityResult(
                resultCode = Activity.RESULT_OK,
                data = Intent("RETURN_DATA"),
                getActionUid = { fakeAction.uniqueId() },
                onRemoveKeys = { keysRemoved.set(true) }
            )

            assertTrue(
                "onExternalPlaybackReturned must be called",
                fakeAction.onReturnedCalled
            )
            assertTrue(
                "onResult must be called on RESULT_OK",
                fakeAction.onResultCalled
            )
            assertTrue(
                "Keys should be removed on RESULT_OK",
                keysRemoved.get()
            )
        } finally {
            VideoClickActionHolder.allVideoClickActions.remove(fakeAction)
        }
    }
}
