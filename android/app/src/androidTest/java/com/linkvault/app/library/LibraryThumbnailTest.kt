package com.linkvault.app.library

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import com.linkvault.app.attachment.ImagePreparation
import com.linkvault.app.attachment.decodePreview
import com.linkvault.app.auth.AccountSessionState
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.CRC32
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test

class LibraryThumbnailTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun sharedDecoderAcceptsJpegPngAndWebpOnlyWhenMimeMatchesBytes() = runBlocking {
        val formats = mutableListOf(Bitmap.CompressFormat.JPEG, Bitmap.CompressFormat.PNG)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) formats += Bitmap.CompressFormat.WEBP_LOSSLESS
        formats.forEach { format ->
            val image = image(format = format)
            val decoded = decode(image.bytes, image.asset())
            assertNotNull(decoded)
            decoded?.recycle()
            val mismatched = if (image.mimeType == "image/png") "image/jpeg" else "image/png"
            assertNull(decode(image.bytes, image.asset().copy(mimeType = mismatched)))
        }
    }

    @Test
    fun sharedDecoderRejectsDimensionSizeMimeCorruptAndOversizedInputs() = runBlocking {
        val image = image()
        val asset = image.asset()
        val decoded = decode(image.bytes, asset)
        assertNotNull(decoded)
        decoded?.recycle()
        listOf(
            asset.copy(width = asset.width + 1),
            asset.copy(height = asset.height + 1),
            asset.copy(byteSize = asset.byteSize + 1),
            asset.copy(mimeType = "image/jpeg"),
            asset.copy(mimeType = "image/gif"),
        ).forEach { assertNull(decode(image.bytes, it)) }
        assertNull(decode(byteArrayOf(), asset.copy(byteSize = 0)))
        val corrupt = byteArrayOf(1, 2, 3, 4)
        assertNull(decode(corrupt, asset.copy(byteSize = corrupt.size.toLong())))
        val tooManyBytes = ByteArray(ImagePreparation.MAX_OUTPUT_BYTES.toInt() + 1)
        assertNull(decode(tooManyBytes, asset.copy(byteSize = tooManyBytes.size.toLong())))
        val tooManyPixels = image.bytes.copyOf()
        ByteBuffer.wrap(tooManyPixels).apply {
            putInt(16, 6_000)
            putInt(20, 5_000)
            putInt(29, CRC32().apply { update(tooManyPixels, 12, 17) }.value.toInt())
        }
        assertNull(decode(tooManyPixels, asset.copy(width = 6_000, height = 5_000)))
        assertNull(decode(image.bytes, asset, maxPixels = 0))
        assertNull(decode(image.bytes, asset, maxPixels = 2_000_001))
        assertNull(decode(image.bytes, asset, maxDimension = 0))
    }

    @Test
    fun sharedDecoderPreservesDetailDefaultsAndBoundsOddWideAndTallThumbnails() = runBlocking {
        listOf(image(2_049, 1_025), image(20_000, 1), image(1, 20_000)).forEach { image ->
            val asset = image.asset()
            val detail = decodePreview(image.bytes, asset.mimeType, asset.byteSize, asset.width, asset.height)
            assertNotNull(detail)
            try {
                assertTrue(checkNotNull(detail).width.toLong() * detail.height <= 2_000_000L)
                assertTrue(maxOf(detail.width, detail.height) > LIBRARY_THUMBNAIL_MAX_DIMENSION)
                if (minOf(image.width, image.height) == 1) {
                    assertEquals(20_000, maxOf(detail.width, detail.height))
                }
            } finally {
                detail?.recycle()
            }
            val thumbnail = decode(image.bytes, asset)
            assertNotNull(thumbnail)
            try {
                assertTrue(checkNotNull(thumbnail).width in 1..LIBRARY_THUMBNAIL_MAX_DIMENSION)
                assertTrue(thumbnail.height in 1..LIBRARY_THUMBNAIL_MAX_DIMENSION)
                assertTrue(thumbnail.width.toLong() * thumbnail.height <= LIBRARY_THUMBNAIL_MAX_PIXELS)
            } finally {
                thumbnail?.recycle()
            }
            val item = item(image)
            val loader = loader { _, _, _ -> image.bytes.copyOf() }
            try {
                loader.updateItems(listOf(item))
                val loaded = checkNotNull(loader.load(item))
                assertTrue(loaded.width in 1..LIBRARY_THUMBNAIL_MAX_DIMENSION)
                assertTrue(loaded.height in 1..LIBRARY_THUMBNAIL_MAX_DIMENSION)
                assertTrue(loaded.width.toLong() * loaded.height <= LIBRARY_THUMBNAIL_MAX_PIXELS)
            } finally {
                loader.close()
            }
        }
    }

    @Test
    fun missingDescriptorNeverDownloadsAndHasAttachmentDoesNotInventAnImage() = runBlocking {
        var calls = 0
        val missing = LibraryItemSummary("missing", url = "https://example.com", hasAttachment = true)
        val loader = loader { _, _, _ -> calls++; error("A descriptor is required.") }
        try {
            loader.updateItems(listOf(missing))
            assertNull(loader.load(missing))
            assertFalse(loader.isCurrent(missing))
            assertEquals(0, calls)
        } finally {
            loader.close()
        }
    }

    @Test
    fun cacheReusesOnlyCurrentItemsAndEvictionDoesNotRecycleUiReferences() = runBlocking {
        val image = image()
        val original = item(image)
        var calls = 0
        val downloaded = mutableListOf<ByteArray>()
        val loader = loader { owner, itemId, assetId ->
            assertEquals(OWNER, owner)
            assertEquals(original.id, itemId)
            assertTrue(assetId == "asset" || assetId == "replacement")
            calls++
            image.bytes.copyOf().also { downloaded += it }
        }
        try {
            loader.updateItems(listOf(original))
            val first = checkNotNull(loader.load(original))
            assertSame(first, loader.load(original.copy(noteExcerpt = "새 메모")))
            assertEquals(1, calls)
            val replacement = original.copy(thumbnailAsset = image.asset("replacement"))
            loader.updateItems(listOf(replacement))
            assertFalse(loader.isCurrent(original))
            assertCancellation { loader.load(original) }
            val second = checkNotNull(loader.load(replacement))
            assertNotSame(first, second)
            assertEquals(2, calls)
            loader.updateItems(listOf(replacement.copy(thumbnailAsset = null)))
            assertCancellation { loader.load(replacement) }
            loader.updateItems(listOf(replacement))
            assertNotSame(second, loader.load(replacement))
            assertEquals(3, calls)
            loader.updateItems(emptyList())
            assertCancellation { loader.load(replacement) }
            loader.close()
            assertFalse(first.isRecycled)
            assertFalse(second.isRecycled)
            assertTrue(downloaded.all { bytes -> bytes.all { it == 0.toByte() } })
        } finally {
            loader.close()
        }
    }

    @Test
    fun everyDescriptorFieldParticipatesInCacheIdentity() = runBlocking {
        val image = image()
        val original = item(image)
        val asset = checkNotNull(original.thumbnailAsset)
        var calls = 0
        val loader = loader { _, _, _ -> calls++; image.bytes.copyOf() }
        try {
            listOf(
                asset.copy(mimeType = "image/jpeg"),
                asset.copy(byteSize = asset.byteSize + 1),
                asset.copy(width = asset.width + 1),
                asset.copy(height = asset.height + 1),
            ).forEach { changed ->
                loader.updateItems(listOf(original))
                assertNotNull(loader.load(original))
                val before = calls
                val replacement = original.copy(thumbnailAsset = changed)
                loader.updateItems(listOf(replacement))
                assertIoFailure { loader.load(replacement) }
                assertEquals(before + 1, calls)
            }
        } finally {
            loader.close()
        }
    }

    @Test
    fun cacheIsLimitedToSixMiBAndLeastRecentBitmapRemainsUsableAfterEviction() = runBlocking {
        val image = image(256, 256)
        val items = (0..24).map { item(image, id = "item-$it") }
        val calls = AtomicInteger()
        val loader = loader { _, _, _ -> calls.incrementAndGet(); image.bytes.copyOf() }
        try {
            loader.updateItems(items)
            val bitmaps = items.map { checkNotNull(loader.load(it)) }
            assertTrue(bitmaps.sumOf { it.allocationByteCount.toLong() } > LIBRARY_THUMBNAIL_CACHE_BYTES)
            assertEquals(25, calls.get())
            assertSame(bitmaps.last(), loader.load(items.last()))
            assertEquals(25, calls.get())
            assertNotSame(bitmaps.first(), loader.load(items.first()))
            assertEquals(26, calls.get())
            assertTrue(bitmaps.none(Bitmap::isRecycled))
            loader.close()
            assertTrue(bitmaps.none(Bitmap::isRecycled))
        } finally {
            loader.close()
        }
    }

    @Test
    fun noMoreThanThreeDownloadsRunAtOnce() = runBlocking {
        withTimeout(10_000) {
            val image = image()
            val items = (0..8).map { item(image, id = "concurrent-$it") }
            val active = AtomicInteger()
            val maximum = AtomicInteger()
            val calls = AtomicInteger()
            val started = Channel<Unit>(Channel.UNLIMITED)
            val release = CompletableDeferred<Unit>()
            val loader = loader { _, _, _ ->
                val count = active.incrementAndGet()
                maximum.updateAndGet { maxOf(it, count) }
                calls.incrementAndGet()
                try {
                    started.send(Unit)
                    release.await()
                    image.bytes.copyOf()
                } finally {
                    active.decrementAndGet()
                }
            }
            try {
                loader.updateItems(items)
                val requests = items.map { async(Dispatchers.Default) { loader.load(it) } }
                repeat(3) { started.receive() }
                assertEquals(3, active.get())
                release.complete(Unit)
                assertTrue(requests.awaitAll().all { it != null })
                assertEquals(9, calls.get())
                assertEquals(3, maximum.get())
            } finally {
                release.complete(Unit)
                loader.close()
                started.close()
            }
        }
    }

    @Test
    fun removingQueuedItemPreventsDownloadAfterPermitBecomesAvailable() = runBlocking {
        withTimeout(10_000) {
            val image = image()
            val items = (0..3).map { item(image, id = "queued-$it") }
            val started = Channel<Unit>(Channel.UNLIMITED)
            val release = CompletableDeferred<Unit>()
            val calls = AtomicInteger()
            val loader = loader { _, _, _ ->
                calls.incrementAndGet()
                started.send(Unit)
                release.await()
                image.bytes.copyOf()
            }
            try {
                loader.updateItems(items)
                val active = items.take(3).map { async(Dispatchers.Default) { loader.load(it) } }
                repeat(3) { started.receive() }
                val queued = async(start = CoroutineStart.UNDISPATCHED) { loader.load(items.last()) }
                loader.updateItems(items.take(3))
                assertCancellation { queued.await() }
                release.complete(Unit)
                assertTrue(active.awaitAll().all { it != null })
                assertEquals(3, calls.get())
            } finally {
                release.complete(Unit)
                loader.close()
                started.close()
            }
        }
    }

    @Test
    fun ownerAndGenerationChangesRetireCachedLoaderEvenWhenOldSessionReturns() = runBlocking {
        val image = image()
        val item = item(image)
        listOf(session(owner = "another-owner"), session(generation = GENERATION + 1)).forEach { changed ->
            val sessions = MutableStateFlow(session())
            var calls = 0
            val loader = loader(sessions) { _, _, _ -> calls++; image.bytes.copyOf() }
            try {
                loader.updateItems(listOf(item))
                val bitmap = checkNotNull(loader.load(item))
                sessions.value = changed
                assertFalse(loader.isCurrent(item))
                assertCancellation { loader.load(item) }
                sessions.value = session()
                loader.updateItems(listOf(item))
                assertCancellation { loader.load(item) }
                assertEquals(1, calls)
                assertFalse(bitmap.isRecycled)
            } finally {
                loader.close()
            }
        }
    }

    @Test
    fun mismatchedOwnerGenerationAndSignedOutSessionNeverRequestBytes() = runBlocking {
        val image = image()
        val item = item(image)
        listOf(session(owner = "another-owner"), session(generation = GENERATION + 1), session(owner = null))
            .forEach { session ->
                var calls = 0
                val loader = loader(MutableStateFlow(session)) { _, _, _ -> calls++; image.bytes.copyOf() }
                try {
                    loader.updateItems(listOf(item))
                    assertCancellation { loader.load(item) }
                    assertEquals(0, calls)
                } finally {
                    loader.close()
                }
            }
    }

    @Test
    fun ownerAndGenerationChangesDiscardEvenNonCancellableInflightBytes() = runBlocking {
        listOf(session(owner = "another-owner"), session(generation = GENERATION + 1)).forEach { changed ->
            staleInflight { loader, sessions, original ->
                sessions.value = changed
                assertFalse(loader.isCurrent(original))
            }
        }
    }

    @Test
    fun replacementsDeletionsAndDisposalCancelInflightResultsAndZeroDownloadedBytes() = runBlocking {
        staleInflight { loader, _, original ->
            loader.updateItems(listOf(original.copy(thumbnailAsset = original.thumbnailAsset?.copy(id = "new"))))
        }
        staleInflight { loader, _, _ -> loader.updateItems(emptyList()) }
        staleInflight { loader, _, _ -> loader.close() }
    }

    @Test
    fun callerCancellationDoesNotBecomeFailureOrPopulateCacheAndBytesAreZeroed() = runBlocking {
        withTimeout(10_000) {
            val image = image()
            val item = item(image)
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val returned = AtomicReference<ByteArray>()
            val calls = AtomicInteger()
            val loader = loader { _, _, _ ->
                if (calls.incrementAndGet() == 1) {
                    withContext(NonCancellable) {
                        started.complete(Unit)
                        release.await()
                        image.bytes.copyOf().also(returned::set)
                    }
                } else {
                    image.bytes.copyOf()
                }
            }
            try {
                loader.updateItems(listOf(item))
                val request = async(Dispatchers.Default) { loader.load(item) }
                started.await()
                request.cancel()
                release.complete(Unit)
                assertCancellation { request.await() }
                assertTrue(checkNotNull(returned.get()).all { it == 0.toByte() })
                assertNotNull(loader.load(item))
                assertEquals(2, calls.get())
            } finally {
                release.complete(Unit)
                loader.close()
            }
        }
    }

    @Test
    fun failedDecodeZerosBytesAndTransportCancellationRemainsCancellation() = runBlocking {
        val image = image()
        val item = item(image)
        val corrupt = ByteArray(image.bytes.size) { 42 }
        val loader = loader { _, _, _ -> corrupt }
        try {
            loader.updateItems(listOf(item))
            assertIoFailure { loader.load(item) }
            assertTrue(corrupt.all { it == 0.toByte() })
        } finally {
            loader.close()
        }
        val cancelled = loader { _, _, _ -> throw CancellationException("Cancelled transport") }
        try {
            cancelled.updateItems(listOf(item))
            assertCancellation { cancelled.load(item) }
        } finally {
            cancelled.close()
        }
    }

    @Test
    fun renderedSuccessReplacementFailureAndMissingDescriptorKeepTextCardUsableWithoutOldImage() {
        val image = image()
        var item by mutableStateOf(item(image))
        var opened = 0
        val calls = AtomicInteger()
        val brokenStarted = CompletableDeferred<Unit>()
        val releaseBroken = CompletableDeferred<Unit>()
        val loader = loader { _, _, assetId ->
            calls.incrementAndGet()
            if (assetId == "broken") {
                brokenStarted.complete(Unit)
                releaseBroken.await()
                ByteArray(image.bytes.size) { 42 }
            } else {
                image.bytes.copyOf()
            }
        }
        loader.updateItems(listOf(item))
        try {
            compose.setContent {
                MaterialTheme {
                    SideEffect { loader.updateItems(listOf(item)) }
                    Column(Modifier.testTag("text-card").clickable { opened++ }) {
                        Text(checkNotNull(item.displayTitle))
                        Text("내 메모는 계속 읽을 수 있어요.")
                        LibraryThumbnail(item, loader)
                    }
                }
            }
            awaitImage(item.id)
            compose.onNodeWithTag("library-thumbnail-${item.id}", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithContentDescription("보관한 링크 첨부 이미지 미리보기", useUnmergedTree = true)
                .assertIsDisplayed()
            val bounds = compose.onNodeWithTag("library-thumbnail-${item.id}", useUnmergedTree = true)
                .getUnclippedBoundsInRoot()
            assertEquals(72f, (bounds.right - bounds.left).value, 0.001f)
            assertEquals(72f, (bounds.bottom - bounds.top).value, 0.001f)
            compose.runOnIdle { item = item.copy(thumbnailAsset = image.asset("broken")) }
            compose.waitUntil(5_000) { brokenStarted.isCompleted }
            compose.onNodeWithTag("library-thumbnail-${item.id}", useUnmergedTree = true).assertDoesNotExist()
            compose.onNodeWithTag("library-thumbnail-error-${item.id}", useUnmergedTree = true).assertDoesNotExist()
            releaseBroken.complete(Unit)
            compose.waitUntil(5_000) {
                compose.onAllNodesWithTag("library-thumbnail-error-${item.id}", useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("library-thumbnail-${item.id}", useUnmergedTree = true).assertDoesNotExist()
            compose.onNodeWithTag("library-thumbnail-error-${item.id}", useUnmergedTree = true)
                .assertTextEquals("이미지 미리보기 실패")
            compose.onNodeWithText("내 메모는 계속 읽을 수 있어요.", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithTag("text-card").assertHasClickAction().performClick()
            compose.runOnIdle { assertEquals(1, opened); item = item.copy(thumbnailAsset = null) }
            compose.onNodeWithTag("library-thumbnail-${item.id}", useUnmergedTree = true).assertDoesNotExist()
            compose.onNodeWithTag("library-thumbnail-error-${item.id}", useUnmergedTree = true).assertDoesNotExist()
            compose.onNodeWithText("보관한 링크", useUnmergedTree = true).assertIsDisplayed()
            assertEquals(2, calls.get())
        } finally {
            releaseBroken.complete(Unit)
            loader.close()
        }
    }

    @Test
    fun coalescedRemovalAndRestoreOfSameDescriptorRetriesCancelledThumbnail() {
        val image = image()
        val item = item(image)
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val loader = loader { _, _, _ ->
            if (calls.incrementAndGet() == 1) {
                started.complete(Unit)
                try {
                    release.await()
                } catch (error: CancellationException) {
                    cancelled.complete(Unit)
                    throw error
                }
            }
            image.bytes.copyOf()
        }
        loader.updateItems(listOf(item))
        try {
            compose.setContent { MaterialTheme { LibraryThumbnail(item, loader) } }
            compose.waitUntil(5_000) { started.isCompleted }
            compose.runOnIdle {
                loader.updateItems(emptyList())
                loader.updateItems(listOf(item))
            }
            compose.waitUntil(5_000) { cancelled.isCompleted }
            compose.waitUntil(5_000) { calls.get() == 2 }
            awaitImage(item.id)
            compose.onNodeWithTag("library-thumbnail-${item.id}", useUnmergedTree = true).assertIsDisplayed()
            assertEquals(2, calls.get())
        } finally {
            release.complete(Unit)
            loader.close()
        }
    }

    @Test
    fun unrelatedItemAdditionAndReplacementDoNotRestartInflightThumbnail() {
        val image = image()
        val original = item(image)
        val other = item(image, id = "other")
        val replacement = other.copy(thumbnailAsset = image.asset("replacement"))
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val loader = loader { _, itemId, _ ->
            assertEquals(original.id, itemId)
            calls.incrementAndGet()
            started.complete(Unit)
            try {
                release.await()
                image.bytes.copyOf()
            } catch (error: CancellationException) {
                cancelled.complete(Unit)
                throw error
            }
        }
        loader.updateItems(listOf(original))
        try {
            compose.setContent { MaterialTheme { LibraryThumbnail(original, loader) } }
            compose.waitUntil(5_000) { started.isCompleted }
            compose.runOnIdle { loader.updateItems(listOf(original, other)) }
            compose.waitForIdle()
            assertFalse(cancelled.isCompleted)
            assertEquals(1, calls.get())
            compose.runOnIdle { loader.updateItems(listOf(original, replacement)) }
            compose.waitForIdle()
            assertFalse(cancelled.isCompleted)
            assertEquals(1, calls.get())
            release.complete(Unit)
            awaitImage(original.id)
            compose.onNodeWithTag("library-thumbnail-${original.id}", useUnmergedTree = true).assertIsDisplayed()
            assertFalse(cancelled.isCompleted)
            assertEquals(1, calls.get())
        } finally {
            release.complete(Unit)
            loader.close()
        }
    }

    @Test
    fun disposingThumbnailCancelsOnlyItsRequestAndDoesNotCacheLateImage() {
        val image = image()
        val item = item(image)
        var shown by mutableStateOf(true)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val returned = AtomicReference<ByteArray>()
        val calls = AtomicInteger()
        val loader = loader { _, _, _ ->
            if (calls.incrementAndGet() == 1) {
                withContext(NonCancellable) {
                    started.complete(Unit)
                    release.await()
                    image.bytes.copyOf().also(returned::set)
                }
            } else {
                image.bytes.copyOf()
            }
        }
        loader.updateItems(listOf(item))
        try {
            compose.setContent { MaterialTheme { if (shown) LibraryThumbnail(item, loader) } }
            compose.waitUntil(5_000) { started.isCompleted }
            compose.runOnIdle { shown = false }
            // Commit removal and cancel its effect before the in-flight response returns.
            compose.waitForIdle()
            release.complete(Unit)
            compose.waitUntil(5_000) { returned.get()?.all { it == 0.toByte() } == true }
            compose.onNodeWithTag("library-thumbnail-${item.id}").assertDoesNotExist()
            assertTrue(loader.isCurrent(item))
            compose.runOnIdle { shown = true }
            awaitImage(item.id)
            assertEquals(2, calls.get())
        } finally {
            release.complete(Unit)
            loader.close()
        }
    }

    @Test
    fun renderedImageDisappearsOnOwnerChange() {
        renderedSessionInvalidation(session(owner = "another-owner"))
    }

    @Test
    fun renderedImageDisappearsOnGenerationChange() {
        renderedSessionInvalidation(session(generation = GENERATION + 1))
    }

    @Test
    fun renderedImageDisappearsWhenSnapshotDeletesItemOrLoaderIsDisposed() {
        val image = image()
        val item = item(image)
        val loader = loader { _, _, _ -> image.bytes.copyOf() }
        loader.updateItems(listOf(item))
        try {
            compose.setContent { MaterialTheme { LibraryThumbnail(item, loader) } }
            awaitImage(item.id)
            compose.runOnIdle { loader.updateItems(emptyList()) }
            compose.onNodeWithTag("library-thumbnail-${item.id}").assertDoesNotExist()
            compose.runOnIdle { loader.updateItems(listOf(item)) }
            awaitImage(item.id)
            compose.runOnIdle { loader.close() }
            compose.onNodeWithTag("library-thumbnail-${item.id}").assertDoesNotExist()
        } finally {
            loader.close()
        }
    }

    @Test
    fun closeWithPendingFrameThenDestroyActivity() = invalidateThenDestroy(false, false)

    @Test
    fun closeAfterAppliedFrameThenDestroyActivity() = invalidateThenDestroy(false, true)

    @Test
    fun generationWithPendingFrameThenDestroyActivity() = invalidateThenDestroy(true, false)

    @Test
    fun generationAfterAppliedFrameThenDestroyActivity() = invalidateThenDestroy(true, true)

    private fun invalidateThenDestroy(generationChange: Boolean, drain: Boolean) {
        val image = image()
        val original = item(image)
        val other = item(image, id = "other")
        val replacement = other.copy(thumbnailAsset = image.asset("replacement"))
        val sessions = MutableStateFlow(session())
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val loader = loader(sessions) { _, itemId, _ ->
            assertEquals(original.id, itemId)
            calls.incrementAndGet()
            started.complete(Unit)
            release.await()
            image.bytes.copyOf()
        }
        loader.updateItems(listOf(original))
        try {
            compose.setContent {
                MaterialTheme {
                    Column {
                        Text("보관한 링크")
                        LibraryThumbnail(original, loader)
                    }
                }
            }
            compose.waitUntil(5_000) { started.isCompleted }
            compose.runOnIdle { loader.updateItems(listOf(original, other)) }
            compose.runOnIdle { loader.updateItems(listOf(original, replacement)) }
            release.complete(Unit)
            awaitImage(original.id)
            compose.onNodeWithTag("library-thumbnail-${original.id}", useUnmergedTree = true)
                .assertIsDisplayed()
            assertEquals(1, calls.get())
            compose.mainClock.autoAdvance = false
            compose.runOnUiThread {
                if (generationChange) sessions.value = session(generation = GENERATION + 1)
                else loader.close()
                assertFalse(loader.isCurrent(original))
            }
            if (drain) {
                compose.mainClock.autoAdvance = true
                compose.waitForIdle()
                compose.onNodeWithTag("library-thumbnail-${original.id}").assertDoesNotExist()
                compose.onNodeWithText("보관한 링크").assertIsDisplayed()
                compose.mainClock.autoAdvance = false
            }
            compose.activityRule.scenario.moveToState(Lifecycle.State.DESTROYED)
        } finally {
            release.complete(Unit)
            loader.close()
            compose.mainClock.autoAdvance = true
        }
    }

    private fun renderedSessionInvalidation(changed: AccountSessionState) {
        val image = image()
        val item = item(image)
        val sessions = MutableStateFlow(session())
        val loader = loader(sessions) { _, _, _ -> image.bytes.copyOf() }
        loader.updateItems(listOf(item))
        try {
            compose.setContent { MaterialTheme { Column { Text("보관한 링크"); LibraryThumbnail(item, loader) } } }
            awaitImage(item.id)
            compose.runOnIdle { sessions.value = changed }
            compose.waitUntil(5_000) {
                compose.onAllNodesWithTag("library-thumbnail-${item.id}").fetchSemanticsNodes().isEmpty()
            }
            compose.onNodeWithText("보관한 링크").assertIsDisplayed()
        } finally {
            loader.close()
        }
    }

    private suspend fun staleInflight(
        invalidate: (LibraryThumbnailLoader, MutableStateFlow<AccountSessionState>, LibraryItemSummary) -> Unit,
    ) = coroutineScope {
        withTimeout(10_000) {
            val image = image()
            val item = item(image)
            val sessions = MutableStateFlow(session())
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val returned = AtomicReference<ByteArray>()
            val loader = loader(sessions) { _, _, _ ->
                withContext(NonCancellable) {
                    started.complete(Unit)
                    release.await()
                    image.bytes.copyOf().also(returned::set)
                }
            }
            try {
                loader.updateItems(listOf(item))
                val request = async(Dispatchers.Default) { loader.load(item) }
                started.await()
                invalidate(loader, sessions, item)
                release.complete(Unit)
                assertCancellation { request.await() }
                assertTrue(checkNotNull(returned.get()).all { it == 0.toByte() })
            } finally {
                release.complete(Unit)
                loader.close()
            }
        }
    }

    private fun awaitImage(itemId: String) {
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("library-thumbnail-$itemId", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private suspend fun assertCancellation(block: suspend () -> Any?) {
        try {
            block()
            fail("Expected cancellation, not an image or a failure fallback.")
        } catch (_: CancellationException) {
            // Cancellation must reach the caller unchanged.
        }
    }

    private suspend fun assertIoFailure(block: suspend () -> Any?) {
        try {
            block()
            fail("Expected an explicit invalid-image failure.")
        } catch (_: IOException) {
            // Invalid images are never cached or presented as success.
        }
    }

    private fun loader(
        sessions: MutableStateFlow<AccountSessionState> = MutableStateFlow(session()),
        download: suspend (String, String, String) -> ByteArray,
    ) = LibraryThumbnailLoader(OWNER, GENERATION, sessions, download)

    private fun session(owner: String? = OWNER, generation: Long = GENERATION) =
        AccountSessionState(ownerId = owner, generation = generation, initialized = true)

    private fun item(image: EncodedImage, id: String = "item") = LibraryItemSummary(
        id = id,
        url = "https://example.com/$id",
        displayTitle = "보관한 링크",
        hasAttachment = true,
        thumbnailAsset = image.asset(),
    )

    private suspend fun decode(
        bytes: ByteArray,
        asset: LibraryThumbnailAsset,
        maxPixels: Long = LIBRARY_THUMBNAIL_MAX_PIXELS,
        maxDimension: Int = LIBRARY_THUMBNAIL_MAX_DIMENSION,
    ) = decodePreview(bytes, asset.mimeType, asset.byteSize, asset.width, asset.height, maxPixels, maxDimension)

    private fun image(
        width: Int = 64,
        height: Int = 48,
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG,
    ): EncodedImage {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(Color.rgb(30, 110, 190))
            val output = ByteArrayOutputStream()
            assertTrue(bitmap.compress(format, 100, output))
            val mimeType = when (format) {
                Bitmap.CompressFormat.PNG -> "image/png"
                Bitmap.CompressFormat.JPEG -> "image/jpeg"
                else -> "image/webp"
            }
            EncodedImage(output.toByteArray(), width, height, mimeType)
        } finally {
            bitmap.recycle()
        }
    }

    private data class EncodedImage(val bytes: ByteArray, val width: Int, val height: Int, val mimeType: String) {
        fun asset(id: String = "asset") = LibraryThumbnailAsset(id, mimeType, bytes.size.toLong(), width, height)
    }

    private companion object {
        const val OWNER = "owner"
        const val GENERATION = 7L
    }
}
