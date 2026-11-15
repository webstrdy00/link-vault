package com.linkvault.app.attachment

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.ResultReceiver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises OS URI grants and preparation failures, not photo-picker UI or production IAM. */
@RunWith(AndroidJUnit4::class)
class UriPermissionLifecycleTest {
    private var nextRequestId = 0

    @Test
    fun separateUidReadGrantRevocationAndRegrantControlImagePreparation() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        val testApk = instrumentation.context
        assertEquals(UriPermissionFixture.TARGET_PACKAGE, target.packageName)
        assertEquals(UriPermissionFixture.TEST_PACKAGE, testApk.packageName)
        assertEquals(target.applicationInfo.uid, Process.myUid())
        assertNotEquals(target.applicationInfo.uid, testApk.applicationInfo.uid)

        val directory = File(target.cacheDir, "uri-permission-lifecycle-${UUID.randomUUID()}")
        var primaryFailure: Throwable? = null
        try {
            assertTrue(directory.mkdirs())
            helper(target, testApk.applicationInfo.uid, UriPermissionFixture.PREPARE)
            assertReadDenied(target)

            helper(target, testApk.applicationInfo.uid, UriPermissionFixture.GRANT)
            assertGrantedImage(target, File(directory, "granted.png"))

            helper(target, testApk.applicationInfo.uid, UriPermissionFixture.REVOKE)
            assertReadDenied(target)
            val revokedDirectory = File(directory, "revoked")
            val revokedDestination = File(revokedDirectory, "must-not-exist.png")
            val failure = try {
                ImagePreparation.prepareImage(target, UriPermissionFixture.URI, revokedDestination)
                throw AssertionError("Revoked URI unexpectedly prepared an image")
            } catch (error: ImagePreparationException) {
                error
            }
            assertEquals(ImagePreparationFailure.SOURCE_UNAVAILABLE, failure.reason)
            assertFalse(revokedDestination.exists())
            assertTrue(revokedDirectory.isDirectory)
            assertTrue(requireNotNull(revokedDirectory.list()).isEmpty())

            helper(target, testApk.applicationInfo.uid, UriPermissionFixture.GRANT)
            assertGrantedImage(target, File(directory, "regranted.png"))
        } catch (error: Throwable) {
            primaryFailure = error
            throw error
        } finally {
            var cleanupFailure: Throwable? = null
            try {
                helper(target, testApk.applicationInfo.uid, UriPermissionFixture.CLEANUP)
                assertReadDenied(target)
            } catch (error: Throwable) {
                cleanupFailure = error
            }
            try {
                assertTrue("Could not remove isolated test output", directory.deleteRecursively())
            } catch (error: Throwable) {
                val previous = cleanupFailure
                if (previous == null) cleanupFailure = error else previous.addSuppressed(error)
            }
            cleanupFailure?.let { cleanup ->
                val primary = primaryFailure
                if (primary == null) throw cleanup else primary.addSuppressed(cleanup)
            }
        }
    }

    private fun assertReadDenied(context: Context) {
        assertEquals(
            PackageManager.PERMISSION_DENIED,
            context.checkUriPermission(
                UriPermissionFixture.URI,
                Process.myPid(),
                Process.myUid(),
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            ),
        )
        try {
            context.contentResolver.openInputStream(UriPermissionFixture.URI)?.use { it.read() }
            throw AssertionError("Read without an OS URI grant unexpectedly succeeded")
        } catch (_: SecurityException) {
            // The framework must reject access; the provider does not synthesize this exception.
        }
    }

    private suspend fun assertGrantedImage(context: Context, destination: File) {
        assertEquals(
            PackageManager.PERMISSION_GRANTED,
            context.checkUriPermission(
                UriPermissionFixture.URI,
                Process.myPid(),
                Process.myUid(),
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            ),
        )
        assertEquals(
            PackageManager.PERMISSION_DENIED,
            context.checkUriPermission(
                UriPermissionFixture.URI,
                Process.myPid(),
                Process.myUid(),
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            ),
        )
        assertEquals("image/png", context.contentResolver.getType(UriPermissionFixture.URI))
        val bytes = requireNotNull(context.contentResolver.openInputStream(UriPermissionFixture.URI))
            .use { input ->
                val buffer = ByteArray(UriPermissionFixture.MAX_BYTES + 1)
                var total = 0
                while (total < buffer.size) {
                    val count = input.read(buffer, total, buffer.size - total)
                    if (count == -1) break
                    check(count > 0) { "Fixture read did not advance" }
                    total += count
                }
                assertTrue(total in 1..UriPermissionFixture.MAX_BYTES)
                buffer.copyOf(total)
            }
        val source = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        try {
            assertEquals(UriPermissionFixture.WIDTH, source.width)
            assertEquals(UriPermissionFixture.HEIGHT, source.height)
        } finally {
            source.recycle()
        }

        val prepared = ImagePreparation.prepareImage(context, UriPermissionFixture.URI, destination)
        assertEquals(destination.canonicalFile, prepared.file.canonicalFile)
        assertEquals("image/png", prepared.mimeType)
        assertEquals(UriPermissionFixture.WIDTH, prepared.width)
        assertEquals(UriPermissionFixture.HEIGHT, prepared.height)
        assertTrue(prepared.bytes in 1..ImagePreparation.MAX_OUTPUT_BYTES)
        assertEquals(prepared.bytes, destination.length())
        val decoded = requireNotNull(BitmapFactory.decodeFile(destination.absolutePath))
        try {
            assertEquals(UriPermissionFixture.WIDTH, decoded.width)
            assertEquals(UriPermissionFixture.HEIGHT, decoded.height)
        } finally {
            decoded.recycle()
        }
    }

    private fun helper(context: Context, expectedUid: Int, action: String) {
        val requestId = ++nextRequestId
        val completed = CountDownLatch(1)
        val response = AtomicReference<Pair<Int, Bundle>?>(null)
        val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                if (response.compareAndSet(null, resultCode to (resultData ?: Bundle()))) {
                    completed.countDown()
                }
            }
        }
        context.startActivity(
            Intent(action).apply {
                component = ComponentName(
                    UriPermissionFixture.TEST_PACKAGE,
                    UriPermissionFixtureActivity::class.java.name,
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(UriPermissionFixture.EXTRA_TARGET, UriPermissionFixture.TARGET_PACKAGE)
                putExtra(UriPermissionFixture.EXTRA_REQUEST, requestId)
                putExtra(UriPermissionFixture.EXTRA_RECEIVER, receiver)
            },
        )
        assertTrue("Fixture helper did not acknowledge $action", completed.await(8, TimeUnit.SECONDS))
        val (code, data) = requireNotNull(response.get())
        assertEquals("Fixture helper rejected $action", Activity.RESULT_OK, code)
        assertEquals(requestId, data.getInt(UriPermissionFixture.EXTRA_REQUEST, -1))
        assertEquals(action, data.getString(UriPermissionFixture.EXTRA_ACTION))
        assertEquals(expectedUid, data.getInt(UriPermissionFixture.EXTRA_UID, -1))
        assertNotEquals(Process.myUid(), data.getInt(UriPermissionFixture.EXTRA_UID, -1))
        val fixtureBytes = data.getLong(UriPermissionFixture.EXTRA_BYTES, -1L)
        if (action == UriPermissionFixture.CLEANUP) {
            assertEquals(0L, fixtureBytes)
        } else {
            assertTrue(fixtureBytes in 1L..UriPermissionFixture.MAX_BYTES.toLong())
        }
    }
}
