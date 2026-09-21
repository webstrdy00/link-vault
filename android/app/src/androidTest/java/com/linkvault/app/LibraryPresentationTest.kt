package com.linkvault.app

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.linkvault.app.library.LibraryCategoryRef
import com.linkvault.app.library.LibraryItemCard
import com.linkvault.app.library.LibraryItemSummary
import com.linkvault.app.library.LibraryRootHeader
import com.linkvault.app.ui.VaultTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LibraryPresentationTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun editorialRowKeepsRealMetadataVisibleAndWholeRowOpensDetail() {
        val title =
            "아주 긴 한글 제목도 잘리지 않고 개인 보관함에서 충분히 읽을 수 있어야 합니다 "
                .repeat(4)
                .trim()
        var openedItemId: String? = null

        compose.setContent {
            VaultTheme {
                LibraryItemCard(
                    item = LibraryItemSummary(
                        id = "long-title-item",
                        url = "https://example.com/long-title",
                        displayTitle = title,
                        source = "example.com",
                        noteExcerpt = "나중에 다시 찾기 위한 짧은 메모",
                        categoryRefs = listOf(
                            LibraryCategoryRef(name = "여행"),
                            LibraryCategoryRef(name = "업무·학습"),
                        ),
                        hasAttachment = true,
                        createdAt = "2026-09-20T12:00:00Z",
                    ),
                    onOpenDetail = { openedItemId = it },
                )
            }
        }

        compose.onNodeWithText(title).assertExists()
        compose.onNodeWithText("example.com").assertIsDisplayed()
        compose.onNodeWithText("여행 · 업무·학습").assertIsDisplayed()
        compose.onNodeWithText("첨부 있음").assertIsDisplayed()
        compose.onNodeWithTag("detail-long-title-item")
            .assertHasClickAction()
            .performClick()
        compose.runOnIdle { assertEquals("long-title-item", openedItemId) }
        compose.onAllNodesWithText("원문 열기").assertCountEquals(0)
        compose.onAllNodesWithText("상세").assertCountEquals(0)
    }

    @Test
    fun blankDisplayTitleFallsBackToUrlWithoutAttachmentLabel() {
        val url = "https://example.com/fallback-title"

        compose.setContent {
            VaultTheme {
                LibraryItemCard(
                    item = LibraryItemSummary(
                        id = "fallback-title-item",
                        url = url,
                        displayTitle = "   ",
                        hasAttachment = false,
                    ),
                    onOpenDetail = {},
                )
            }
        }

        compose.onNodeWithText(url).assertTextEquals(url)
        compose.onNodeWithText("첨부 있음").assertDoesNotExist()
    }

    @Test
    fun populatedEditorialLibraryProducesVisualEvidence() {
        val summaries = listOf(
            LibraryItemSummary(
                id = "visual-travel",
                url = "https://blog.example.com/jeju",
                displayTitle = "비 오는 제주에서 가고 싶은 곳",
                source = "naver_blog",
                noteExcerpt = "작은 책방과 조용한 카페. 다음 여행에 다시 보기.",
                categoryRefs = listOf(LibraryCategoryRef(name = "여행")),
                hasAttachment = true,
                createdAt = "2026-09-21T01:15:00Z",
            ),
            LibraryItemSummary(
                id = "visual-design",
                url = "https://threads.example.com/design",
                displayTitle = "좋은 앱은 무엇을 덜어내는가",
                source = "threads",
                noteExcerpt = "검색 화면과 정보 밀도에 관한 디자인 메모.",
                categoryRefs = listOf(LibraryCategoryRef(name = "업무·학습")),
                createdAt = "2026-09-20T08:30:00Z",
            ),
            LibraryItemSummary(
                id = "visual-recipe",
                url = "https://instagram.example.com/pasta",
                displayTitle = "집에서 만드는 토마토 파스타",
                source = "instagram",
                noteExcerpt = "주말 저녁에 만들어 볼 간단한 레시피.",
                categoryRefs = listOf(LibraryCategoryRef(name = "음식·맛집")),
                createdAt = "2026-09-19T11:45:00Z",
            ),
        )

        compose.setContent {
            VaultTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        LibraryRootHeader(
                            canRefresh = true,
                            onAdd = {},
                            onRefresh = {},
                            onDiscover = {},
                        )
                        Text(
                            text = "최근 보관한 링크",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        summaries.forEach { item ->
                            LibraryItemCard(item = item, onOpenDetail = {})
                        }
                    }
                }
            }
        }

        compose.onNodeWithText("link vault").assertExists()
        compose.onNodeWithText("내 보관함").assertExists()
        compose.onNodeWithText("최근 보관한 링크").assertExists()
        summaries.forEach { item ->
            compose.onNodeWithText(item.displayTitle!!).assertExists()
        }
        compose.onNodeWithText("네이버 블로그").assertExists()
        compose.onNodeWithText("Threads").assertExists()
        compose.onNodeWithText("Instagram").assertExists()

        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val evidence = File(targetContext.cacheDir, "design-v2-populated.png")
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        evidence.outputStream().use { output ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
        assertTrue(evidence.isFile && evidence.length() > 0L)
    }
}
