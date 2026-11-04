package com.linkvault.app.library

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.linkvault.app.attachment.decodePreview
import com.linkvault.app.auth.AccountClient
import com.linkvault.app.auth.AccountSessionState
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal const val LIBRARY_THUMBNAIL_MAX_DIMENSION = 256
internal const val LIBRARY_THUMBNAIL_MAX_PIXELS = 256L * 256L
internal const val LIBRARY_THUMBNAIL_CACHE_BYTES = 6 * 1024 * 1024

/** Reference identity survives unchanged snapshots, but never removal or replacement. */
internal class LibraryThumbnailIdentity(val itemId: String, val asset: LibraryThumbnailAsset)

/** One screen and one session identity; updateItems is the authoritative visible list snapshot. */
internal class LibraryThumbnailLoader(
    private val ownerId: String?,
    private val sessionGeneration: Long,
    private val sessions: StateFlow<AccountSessionState>,
    private val download: suspend (ownerId: String, itemId: String, assetId: String) -> ByteArray,
) : AutoCloseable {
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val permits = Semaphore(3)
    private val keys = mutableMapOf<String, LibraryThumbnailIdentity>()
    private val requests = mutableMapOf<Job, LibraryThumbnailIdentity>()
    private var closed = false
    private val mutableRevision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = mutableRevision.asStateFlow()
    private val cache = object : LruCache<LibraryThumbnailIdentity, Bitmap>(LIBRARY_THUMBNAIL_CACHE_BYTES) {
        override fun sizeOf(key: LibraryThumbnailIdentity, value: Bitmap): Int = value.allocationByteCount
        // Eviction only drops our reference; a composed Image may still own this bitmap.
    }

    init {
        scope.launch {
            sessions.collect { session ->
                if (!matchesSession(session)) close()
            }
        }
    }

    fun updateItems(items: List<LibraryItemSummary>) = synchronized(lock) {
        if (!checkSessionLocked()) return@synchronized
        val updated = items.mapNotNull { item ->
            item.thumbnailAsset?.let { asset ->
                item.id to (keys[item.id]?.takeIf { it.asset == asset }
                    ?: LibraryThumbnailIdentity(item.id, asset))
            }
        }.toMap()
        if (keys == updated) return@synchronized
        keys.clear()
        keys.putAll(updated)
        cache.snapshot().keys.filter { keys[it.itemId] !== it }.forEach { cache.remove(it) }
        requests.filterValues { keys[it.itemId] !== it }.keys.toList().forEach {
            it.cancel(CancellationException("The thumbnail was replaced or removed."))
        }
        mutableRevision.value += 1L
    }

    fun currentIdentity(item: LibraryItemSummary): LibraryThumbnailIdentity? = synchronized(lock) {
        if (!checkSessionLocked()) return@synchronized null
        keys[item.id]?.takeIf { it.asset == item.thumbnailAsset }
    }

    fun isCurrent(item: LibraryItemSummary): Boolean = currentIdentity(item) != null

    suspend fun load(
        item: LibraryItemSummary,
        identity: LibraryThumbnailIdentity? = currentIdentity(item),
    ): Bitmap? = coroutineScope {
        val asset = item.thumbnailAsset ?: return@coroutineScope null
        val key = identity?.takeIf { it.itemId == item.id && it.asset == asset }
            ?: throw CancellationException("The thumbnail no longer belongs to this screen and session.")
        val context = currentCoroutineContext()
        val request = context.job
        val cached = synchronized(lock) {
            requireCurrentLocked(key)
            context.ensureActive()
            cache.get(key)?.also { requireCurrentLocked(key) }
                ?: run {
                    requests[request] = key
                    null
                }
        }
        if (cached != null) return@coroutineScope cached
        var bytes: ByteArray? = null
        try {
            permits.withPermit {
                currentCoroutineContext().ensureActive()
                val reused = synchronized(lock) {
                    requireCurrentLocked(key)
                    cache.get(key)?.also { requireCurrentLocked(key) }
                }
                if (reused != null) return@withPermit reused
                // No URL, object path, disk cache, or unauthenticated transport is involved.
                val downloaded = download(checkNotNull(ownerId), item.id, asset.id)
                bytes = downloaded
                currentCoroutineContext().ensureActive()
                synchronized(lock) { requireCurrentLocked(key) }
                val decoded = decodePreview(
                    bytes = downloaded,
                    expectedMimeType = asset.mimeType,
                    expectedByteSize = asset.byteSize,
                    expectedWidth = asset.width,
                    expectedHeight = asset.height,
                    maxPixels = LIBRARY_THUMBNAIL_MAX_PIXELS,
                    maxDimension = LIBRARY_THUMBNAIL_MAX_DIMENSION,
                )
                currentCoroutineContext().ensureActive()
                synchronized(lock) {
                    requireCurrentLocked(key)
                    context.ensureActive()
                    val bitmap = decoded ?: throw IOException("The thumbnail cannot be decoded safely.")
                    cache.put(key, bitmap)
                    bitmap
                }
            }
        } finally {
            bytes?.fill(0)
            synchronized(lock) { requests.remove(request) }
        }
    }

    override fun close() = synchronized(lock) {
        if (closed) return@synchronized
        closed = true
        cache.evictAll()
        keys.clear()
        mutableRevision.value += 1L
        requests.keys.toList().forEach {
            it.cancel(CancellationException("The thumbnail screen or session was closed."))
        }
        scope.cancel()
    }

    private fun matchesSession(session: AccountSessionState): Boolean =
        !ownerId.isNullOrBlank() && session.ownerId == ownerId && session.generation == sessionGeneration

    private fun checkSessionLocked(): Boolean {
        if (!closed && !matchesSession(sessions.value)) close()
        return !closed
    }

    private fun requireCurrentLocked(key: LibraryThumbnailIdentity) {
        if (!checkSessionLocked() || keys[key.itemId] !== key) {
            throw CancellationException("The thumbnail no longer belongs to this screen and session.")
        }
    }

    companion object {
        fun create(
            client: AccountClient,
            ownerId: String?,
            sessionGeneration: Long,
        ): LibraryThumbnailLoader = LibraryThumbnailLoader(
            ownerId = ownerId,
            sessionGeneration = sessionGeneration,
            sessions = client.sessionState,
            download = client::downloadActiveAsset,
        )
    }
}

/** Call once in each screen, not in each card, with its complete current result list. */
@Composable
internal fun rememberLibraryThumbnailLoader(
    client: AccountClient,
    items: List<LibraryItemSummary>,
): LibraryThumbnailLoader {
    val session by client.sessionState.collectAsState()
    val loader = remember(client, session.ownerId, session.generation) {
        LibraryThumbnailLoader.create(client, session.ownerId, session.generation).apply {
            updateItems(items)
        }
    }
    SideEffect { loader.updateItems(items) }
    DisposableEffect(loader) { onDispose { loader.close() } }
    return loader
}

private sealed interface LibraryThumbnailState {
    data object Loading : LibraryThumbnailState
    data class Ready(val bitmap: Bitmap) : LibraryThumbnailState
    data object Failed : LibraryThumbnailState
}

@Composable
internal fun LibraryThumbnail(
    item: LibraryItemSummary,
    loader: LibraryThumbnailLoader,
    modifier: Modifier = Modifier,
) {
    val asset = item.thumbnailAsset ?: return
    val revision by loader.revision.collectAsState()
    val identity = remember(loader, item.id, asset, revision) { loader.currentIdentity(item) }
    var state by remember(loader, item.id, asset, identity) {
        mutableStateOf<LibraryThumbnailState>(LibraryThumbnailState.Loading)
    }
    LaunchedEffect(loader, item.id, asset, identity) {
        if (identity == null) return@LaunchedEffect
        try {
            loader.load(item, identity)?.let {
                if (loader.currentIdentity(item) === identity) state = LibraryThumbnailState.Ready(it)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            if (loader.currentIdentity(item) === identity) state = LibraryThumbnailState.Failed
        }
    }
    if (identity == null || loader.currentIdentity(item) !== identity) return
    when (val displayed = state) {
        LibraryThumbnailState.Loading -> Unit
        is LibraryThumbnailState.Ready -> Image(
            bitmap = displayed.bitmap.asImageBitmap(),
            contentDescription = "${item.displayTitle?.takeUnless(String::isBlank) ?: item.url} 첨부 이미지 미리보기",
            modifier = modifier.size(72.dp).clip(MaterialTheme.shapes.small)
                .testTag("library-thumbnail-${item.id}"),
            contentScale = ContentScale.Crop,
        )
        LibraryThumbnailState.Failed -> Text(
            text = "이미지 미리보기 실패",
            modifier = modifier.widthIn(max = 72.dp).testTag("library-thumbnail-error-${item.id}"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}
