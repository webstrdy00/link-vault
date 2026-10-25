package com.linkvault.app

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.test.platform.app.InstrumentationRegistry
import com.linkvault.app.attachment.AttachmentImageContent
import com.linkvault.app.attachment.AttachmentCommandKind
import com.linkvault.app.attachment.AttachmentCommandReview
import com.linkvault.app.attachment.AttachmentLocalStage
import com.linkvault.app.attachment.AttachmentUiState
import com.linkvault.app.discovery.DiscoverySource
import com.linkvault.app.library.DetailContent
import com.linkvault.app.library.LibraryActiveAsset
import com.linkvault.app.library.LibraryCategoryRef
import com.linkvault.app.library.LibraryDetailState
import com.linkvault.app.library.LibraryEditForm
import com.linkvault.app.library.LibraryEditUiState
import com.linkvault.app.library.LibraryItemCard
import com.linkvault.app.library.LibraryItemDetail
import com.linkvault.app.library.LibraryItemSummary
import com.linkvault.app.library.LibraryRootHeader
import com.linkvault.app.storage.OutboxEntry
import com.linkvault.app.storage.OutboxState
import com.linkvault.app.ui.VaultTheme
import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LibraryPresentationTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun everySourceShortcutScrollsIntoNarrowHeaderAndInvokesOnlyItsBrowseCallbackOnce() {
        val browsedSources = mutableListOf<DiscoverySource>()
        var discoverCalls = 0
        compose.setContent {
            VaultTheme {
                Surface(modifier = Modifier.width(220.dp)) {
                    LibraryRootHeader(
                        canRefresh = true,
                        onAdd = {},
                        onRefresh = {},
                        onDiscover = { discoverCalls++ },
                        onBrowseSource = { browsedSources += it },
                    )
                }
            }
        }

        val shortcuts = compose.onNodeWithTag("library-source-shortcuts").getUnclippedBoundsInRoot()
        val search = compose.onNodeWithTag("library-search").getUnclippedBoundsInRoot()
        assertTrue(shortcuts.top >= search.bottom)
        compose.onNodeWithTag("library-source-other").assertIsNotDisplayed()
        DiscoverySource.entries.forEachIndexed { index, source ->
            val chip = compose.onNodeWithTag("library-source-${source.apiValue ?: "all"}")
            chip.performScrollTo()
                .assertIsDisplayed()
                .assertHasClickAction()
                .assertTextEquals(source.displayName)
            assertFalse(chip.fetchSemanticsNode().config.contains(SemanticsProperties.Selected))
            val bounds = chip.getUnclippedBoundsInRoot()
            assertTrue(bounds.left >= shortcuts.left - 1.dp && bounds.right <= shortcuts.right + 1.dp)
            assertTrue(bounds.bottom - bounds.top >= 48.dp)
            chip.performClick()
            compose.runOnIdle {
                assertEquals(DiscoverySource.entries.take(index + 1), browsedSources)
                assertEquals(0, discoverCalls)
            }
        }

        compose.onNodeWithTag("library-search").performClick()
        compose.runOnIdle {
            assertEquals(1, discoverCalls)
            assertEquals(DiscoverySource.entries, browsedSources)
        }
    }

    @Test
    fun sourceBadgesKeepNamesMonogramsAndTravelCategorySeparateInLightTheme() {
        assertSourceBadgePresentation(darkTheme = false)
    }

    @Test
    fun sourceBadgesKeepNamesMonogramsAndTravelCategorySeparateInDarkTheme() {
        assertSourceBadgePresentation(darkTheme = true)
    }

    @Test
    fun sourceBadgePreservesIndependentListDeleteAndWholeCardClick() {
        val item = LibraryItemSummary(
            id = "source-delete-item",
            version = 1,
            url = "https://example.com/source-delete",
            displayTitle = "직접 정한 제목",
            source = "instagram",
            noteExcerpt = "직접 쓴 내용도 그대로 유지합니다.",
            categoryRefs = listOf(LibraryCategoryRef(name = "여행")),
        )
        val deleted = mutableListOf<String>()
        val opened = mutableListOf<String>()
        var deleteEnabled by mutableStateOf(true)
        compose.setContent {
            VaultTheme {
                Surface(modifier = Modifier.width(260.dp)) {
                    LibraryItemCard(
                        item = item,
                        onOpenDetail = { opened += it },
                        onDelete = { deleted += it },
                        deleteEnabled = deleteEnabled,
                    )
                }
            }
        }

        compose.onNodeWithText(item.displayTitle!!).assertIsDisplayed()
        compose.onNodeWithText(item.noteExcerpt!!).assertIsDisplayed()
        compose.onNodeWithTag("list-delete-${item.id}").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(listOf(item.id), deleted)
            assertTrue(opened.isEmpty())
            deleteEnabled = false
        }
        compose.onNodeWithTag("list-delete-${item.id}").assertIsNotEnabled()
        compose.onNodeWithTag("detail-${item.id}").assertHasClickAction().performClick()
        compose.runOnIdle {
            assertEquals(listOf(item.id), opened)
            assertEquals(listOf(item.id), deleted)
        }
    }

    @Test
    fun editorialRowCompactsLongTitleToTwoLinesAndWholeRowOpensDetail() {
        val title =
            "아주 긴 한글 제목도 잘리지 않고 개인 보관함에서 충분히 읽을 수 있어야 합니다 "
                .repeat(4)
                .trim()
        val compactTitle = title.take(59).trimEnd() + "…"
        var openedItemId: String? = null

        compose.setContent {
            VaultTheme {
                Column(modifier = Modifier.width(220.dp)) {
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
        }

        compose.onNodeWithText(title).assertDoesNotExist()
        compose.onNodeWithText(compactTitle, useUnmergedTree = true)
            .assertTextEquals(compactTitle)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
                val layouts = mutableListOf<TextLayoutResult>()
                assertTrue(action(layouts))
                assertEquals(2, layouts.single().lineCount)
                assertTrue(layouts.single().isLineEllipsized(1))
            }
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
    fun observedThreadsPostShowsShortHeadingAndCompleteDescriptionWithoutExpanding() {
        val heading = "You've got mail: a postcard from Mars!"
        val acquiredText = "$heading To mark the Curiosity rover’s 5,000th day on the Red Planet, " +
            "this new postcard combines two panoramas captured by the rover's navigation cameras."
        showDetail(
            LibraryItemDetail(
                id = "public-threads-presentation-fixture",
                version = 1,
                url = "https://www.threads.com/@nasa/post/DdXJKzbkqPg",
                displayTitle = acquiredText,
                fetchedTitle = acquiredText,
                description = acquiredText,
            ),
        )

        compose.onNodeWithText(heading).assertIsDisplayed()
        assertNoDetailClutter()
        compose.onAllNodesWithText(acquiredText).assertCountEquals(1)
        compose.onNodeWithText(acquiredText).performScrollTo()
            .assertIsDisplayed()
            .assertTextEquals(acquiredText)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
                val layouts = mutableListOf<TextLayoutResult>()
                assertTrue(action(layouts))
                assertFalse(layouts.single().hasVisualOverflow)
            }
        compose.onNodeWithText("가져온 제목").assertDoesNotExist()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(context.cacheDir, "threads-short-title-description.png").outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    @Test
    fun detailShowsFullBodyOnceWithoutDuplicatingDescriptionOrRawProvenance() {
        val title = "첫 문장으로 알아보는 여행 기록. " + "가져온 긴 제목의 나머지 내용 ".repeat(8)
        val body = "본문 첫 줄\n" + "생략하면 안 되는 본문 문장입니다.\n".repeat(8) + "본문 마지막 줄 😀"
        val description = "설명 첫 줄\n" + "미리보기 설명도 끝까지 보입니다.\n".repeat(8) + "설명 마지막 줄 😀"
        val sharedText = "공유할 때 받은 원래 글\n줄바꿈을 유지합니다."
        val url = "https://example.com/" + "original-url-".repeat(10)
        var openedUrl: String? = null
        showDetail(
            item = LibraryItemDetail(
                id = "full-acquired-text",
                version = 1,
                url = url,
                displayTitle = title,
                fetchedTitle = title,
                sharedText = sharedText,
                bodyText = body,
                description = description,
                categoryRefs = listOf(LibraryCategoryRef(name = "여행")),
            ),
            onOpenOriginal = { openedUrl = it },
        )

        compose.onNodeWithText("첫 문장으로 알아보는 여행 기록.").assertIsDisplayed()
        compose.onNodeWithText(title).assertDoesNotExist()
        compose.onNodeWithText(sharedText).assertDoesNotExist()
        compose.onNodeWithText("가져온 제목").assertDoesNotExist()
        compose.onNodeWithText("여행").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("원문 열기").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(url, openedUrl) }
        listOf(body).forEach { fullText ->
            compose.onNodeWithText(fullText)
                .performScrollTo()
                .assertTextEquals(fullText)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
                    val layouts = mutableListOf<TextLayoutResult>()
                    assertTrue(action(layouts))
                    val layout = layouts.single()
                    assertTrue(layout.lineCount > 3)
                    assertFalse(layout.hasVisualOverflow)
                    assertEquals(fullText.length, layout.getLineEnd(layout.lineCount - 1))
                }
        }

        compose.onNodeWithText(title).assertDoesNotExist()
        compose.onNodeWithText(sharedText).assertDoesNotExist()
        compose.onAllNodesWithText(body).assertCountEquals(1)
        compose.onNodeWithText(description).assertDoesNotExist()
        compose.onNodeWithText("표시 제목 원문").assertDoesNotExist()
        assertNoDetailClutter()
    }

    @Test
    fun detailPrefersTheManualTitleWithoutInferringATopicFromDescription() {
        val userTitle = "  내가\t정한 제목입니다.  " + "내 제목의 원래 뒷부분 ".repeat(8)
        val displayTitle = "서버가 고른 제목입니다. 원래 표시 제목의 뒷부분입니다."
        val fetchedTitle = "@example_account"
        val description = "맛집에 대한 긴 설명입니다. 계정 이름을 주제로 바꾸지 않습니다."
        showDetail(
            item = LibraryItemDetail(
                id = "manual-title",
                version = 2,
                url = "https://example.com/manual-title",
                displayTitle = displayTitle,
                userTitle = userTitle,
                fetchedTitle = fetchedTitle,
                description = description,
            ),
        )

        compose.onNodeWithTag("detail-title").assertTextEquals(userTitle)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
                val layouts = mutableListOf<TextLayoutResult>()
                assertTrue(action(layouts))
                assertEquals(2, layouts.single().lineCount)
                assertTrue(layouts.single().isLineEllipsized(1))
            }
        compose.onNodeWithText("서버가 고른 제목입니다.").assertDoesNotExist()
        compose.onNodeWithText(fetchedTitle).assertDoesNotExist()
        compose.onNodeWithText(description).performScrollTo().assertTextEquals(description)
        compose.onNodeWithText(displayTitle).assertDoesNotExist()
        compose.onNodeWithText(fetchedTitle).assertDoesNotExist()
        compose.onNodeWithText("본문").assertDoesNotExist()
        assertNoDetailClutter()
    }

    @Test
    fun detailKeepsAccountDisplayTitleRatherThanInventingATopicFromDescription() {
        val description = "카페에 대한 유용한 설명입니다. 가져온 설명을 그대로 보여 줍니다."
        showDetail(
            item = LibraryItemDetail(
                id = "account-title",
                version = 1,
                url = "https://example.com/account-post",
                source = "threads",
                displayTitle = "@example_account",
                userTitle = " \t ",
                fetchedTitle = "@example_account",
                description = description,
            ),
        )

        compose.onNodeWithText("@example_account").assertIsDisplayed()
        compose.onNodeWithText("카페에 대한 유용한 설명입니다.").assertDoesNotExist()
        compose.onNodeWithText(description).performScrollTo().assertTextEquals(description)
    }

    @Test
    fun detailCompactsFallbackUrlButKeepsTheOriginalActionAndOmitsBlankContent() {
        val url = "https://example.com/" + "😀".repeat(65)
        val compactUrl = "https://example.com/" + "😀".repeat(39) + "…"
        var openedUrl: String? = null
        showDetail(
            item = LibraryItemDetail(
                id = "fallback-detail",
                version = 1,
                url = url,
                displayTitle = " \n ",
                bodyText = " \t ",
                description = "\n ",
            ),
            onOpenOriginal = { openedUrl = it },
        )

        compose.onNodeWithText(compactUrl).assertTextEquals(compactUrl)
        compose.onNodeWithText(url).assertTextEquals(url)
        compose.onNodeWithText("원문 열기").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(url, openedUrl) }
        compose.onNodeWithText("본문").assertDoesNotExist()
        compose.onNodeWithText("미리보기 설명").assertDoesNotExist()
        compose.onNodeWithText("저장된 내용 보기").assertDoesNotExist()
    }

    @Test
    fun detailCentersReadableFrameAndTitleWithBackAtTheRightEdge() {
        var backCalls = 0
        showDetail(publicTextDetail(), onBack = { backCalls++ })

        assertDetailGeometry()
        assertNoDetailClutter()
        compose.onNodeWithTag("detail-content").performScrollTo().assertTextEquals(publicText)
        captureDetail("clean-detail-centered-public-text.png")
        compose.onNodeWithTag("detail-back").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, backCalls) }
    }

    @Test
    fun narrowDetailKeepsRightBackAndFullTextInsideItsFrame() {
        showDetail(publicTextDetail(), viewportWidth = 260.dp)

        assertDetailGeometry()
        compose.onNodeWithTag("detail-content")
            .performScrollTo()
            .assertTextEquals(publicText)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
                val layouts = mutableListOf<TextLayoutResult>()
                assertTrue(action(layouts))
                assertFalse(layouts.single().hasVisualOverflow)
                assertEquals(publicText.length, layouts.single().getLineEnd(layouts.single().lineCount - 1))
            }
        val frame = compose.onNodeWithTag("detail-frame").getUnclippedBoundsInRoot()
        val content = compose.onNodeWithTag("detail-content").getUnclippedBoundsInRoot()
        assertTrue(content.left >= frame.left && content.right <= frame.right)
        captureDetail("clean-detail-narrow-public-text.png")
    }

    @Test
    fun categoryActionCallsTheEditorDirectlyWithoutImageOrOcrManagement() {
        var categoryActions = 0
        var editActions = 0
        showDetail(
            publicTextDetail(),
            onManageCategories = { categoryActions++ },
            onBeginEdit = { editActions++ },
        )

        compose.onNodeWithTag("detail-manage-images").assertDoesNotExist()
        compose.onNodeWithTag("detail-manage-categories").assertDoesNotExist()
        assertNoDetailClutter()
        compose.onNodeWithTag("detail-more").performClick()
        compose.onNodeWithText("이미지 관리").assertDoesNotExist()
        compose.onNodeWithText("카테고리 관리").assertDoesNotExist()
        compose.onNodeWithText("텍스트 인식 (OCR)").assertDoesNotExist()
        compose.onNodeWithTag("detail-manage-categories").assertTextEquals("분류 수정").performClick()
        compose.runOnIdle {
            assertEquals(1, categoryActions)
            assertEquals(0, editActions)
        }
        compose.onNodeWithTag("detail-manage-categories").assertDoesNotExist()
        compose.onNodeWithTag("detail-more").performClick()
        compose.onNodeWithText("제목/내용 수정").performClick()
        compose.runOnIdle { assertEquals(1, editActions) }
    }

    @Test
    fun manualContentOverridesCollectedTextWithoutShowingBothVersions() {
        val manual = "직접 수정한 첫 줄\n\n원래 줄바꿈과 마지막 줄을 그대로 보여요."
        val item = publicTextDetail().copy(
            note = manual,
            sharedText = "공유받은 다른 원문",
        )
        showDetail(item)

        compose.onNodeWithTag("detail-content").performScrollTo().assertTextEquals(manual)
        compose.onAllNodesWithText(manual).assertCountEquals(1)
        compose.onNodeWithText(publicText).assertDoesNotExist()
        compose.onNodeWithText(item.sharedText!!).assertDoesNotExist()
        assertNoDetailClutter()
    }

    @Test
    fun editorShowsTheFullPrefilledTitleAndContentWithClearEmptyContentHint() {
        val title = "내가 정한 전체 제목 " + "뒷부분도 수정할 수 있어요. ".repeat(8)
        val content = "내가 수정한 내용\n두 번째 줄"
        val form = LibraryEditForm(title = title, note = content)
        val item = publicTextDetail().copy(userTitle = title, note = content)
        showDetail(
            item,
            edit = LibraryEditUiState(
                itemId = item.id,
                expectedVersion = item.version!!,
                originalTitle = title,
                originalNote = content,
                form = form,
                baselineForm = form,
            ),
        )

        compose.onNodeWithTag("detail-edit-title").assertTextContains(title)
        compose.onNodeWithTag("detail-edit-content").performScrollTo().assertTextContains(content)
        compose.onNodeWithText("제목").assertExists()
        compose.onNodeWithText("내용").assertExists()
        compose.onNodeWithText("내용을 비우면 수집한 내용을 다시 보여요.").assertExists()
        compose.onNodeWithText("메모 (선택 사항)").assertDoesNotExist()
    }

    @Test
    fun attachedImageIsInlineBelowTextAndKeepsConciseExplicitActions() {
        val bitmap = Bitmap.createBitmap(24, 16, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.BLUE)
        val item = publicTextDetail().copy(
            bodyText = "이미지는 이 내용 아래에 바로 보여요.",
            activeAsset = LibraryActiveAsset(
                id = "presentation-image",
                objectPath = "private-presentation-image",
                mimeType = "image/png",
                byteSize = 128,
                width = 24,
                height = 16,
            ),
        )
        var imageState by mutableStateOf(
            AttachmentUiState(
                item = item,
                attachmentQueueLoaded = true,
                outboxLoaded = true,
                previewLoading = true,
            ),
        )
        var replacements = 0
        var deletions = 0
        showDetail(item, attachmentContent = {
            AttachmentImageContent(
                state = imageState,
                incomingImageUri = null,
                onImportShared = {},
                onPick = { replacements++ },
                onDelete = { deletions++ },
                onRetryCommand = {},
                onReviewCommand = {},
                onConfirmCommandAgain = {},
                onDiscardCommand = {},
            )
        })

        compose.onNodeWithTag("attachment-preview").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("미리보기를 안전하게 여는 중이에요.").assertExists()
        compose.onNodeWithTag("attachment-image").assertDoesNotExist()
        compose.runOnIdle { imageState = imageState.copy(preview = bitmap, previewLoading = false) }
        compose.onNodeWithTag("attachment-image").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("첨부 이미지 미리보기").assertExists()
        val content = compose.onNodeWithTag("detail-content").getUnclippedBoundsInRoot()
        val image = compose.onNodeWithTag("attachment-image").getUnclippedBoundsInRoot()
        assertTrue(image.top >= content.bottom)
        compose.onNodeWithTag("attachment-replace").performScrollTo().assertTextEquals("이미지 변경").performClick()
        compose.onNodeWithTag("attachment-delete").performScrollTo().assertTextEquals("이미지 삭제").performClick()
        compose.runOnIdle {
            assertEquals(1, replacements)
            assertEquals(1, deletions)
            imageState = imageState.copy(actionError = "선택한 이미지를 읽을 수 없어요.")
        }
        compose.onNodeWithText("선택한 이미지를 읽을 수 없어요.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("attachment-image").assertExists()
        assertNoDetailClutter()
        compose.onNodeWithTag("attachment-ocr-retry").assertDoesNotExist()
        compose.onNodeWithTag("metadata-retry").assertDoesNotExist()
        compose.runOnIdle { imageState = imageState.copy(sessionValid = false) }
        compose.onNodeWithTag("attachment-image").assertDoesNotExist()
    }

    @Test
    fun emptyAttachmentShowsOnlyAddAndPreservesIncomingImageActionDuringPreparation() {
        val incoming = Uri.parse("content://com.linkvault.test/incoming.png")
        var imageState by mutableStateOf(
            AttachmentUiState(
                item = publicTextDetail(),
                attachmentQueueLoaded = true,
                outboxLoaded = true,
            ),
        )
        var sharedImage by mutableStateOf<Uri?>(null)
        var selected: Uri? = null
        var picks = 0
        showDetail(publicTextDetail(), attachmentContent = {
            AttachmentImageContent(
                state = imageState,
                incomingImageUri = sharedImage,
                onImportShared = { selected = it },
                onPick = { picks++ },
                onDelete = {},
                onRetryCommand = {},
                onReviewCommand = {},
                onConfirmCommandAgain = {},
                onDiscardCommand = {},
            )
        })

        compose.onNodeWithTag("attachment-pick").performScrollTo().assertTextEquals("이미지 추가").performClick()
        compose.onNodeWithTag("attachment-preview").assertDoesNotExist()
        compose.onNodeWithTag("attachment-delete").assertDoesNotExist()
        compose.onNodeWithTag("attachment-replace").assertDoesNotExist()
        compose.onNodeWithTag("attachment-import-shared").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, picks)
            sharedImage = incoming
        }
        compose.onNodeWithTag("attachment-import-shared").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(incoming, selected)
            imageState = imageState.copy(localStage = AttachmentLocalStage.OCR_RUNNING)
        }
        compose.onNodeWithText("이미지를 준비하고 있어요.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("attachment-import-shared").assertIsNotEnabled()
        compose.onNodeWithTag("attachment-pick").assertIsNotEnabled()
        assertNoDetailClutter()
    }

    @Test
    fun previousBackgroundImageRequestsCanBeReviewedOrDiscardedWithoutOcrControls() {
        val item = publicTextDetail()
        val entry = OutboxEntry(
            requestId = "old-image-request",
            ownerId = "test-owner",
            method = "PATCH",
            path = "/items/${item.id}/assets/previous-image/ocr",
            payloadJson = "{}",
            state = OutboxState.CONFLICT,
            attemptCount = 1,
            createdAt = 1,
            expiresAt = Long.MAX_VALUE,
            nextAttemptAt = 1,
            errorCode = "VERSION_CONFLICT",
        )
        var imageState by mutableStateOf(
            AttachmentUiState(
                item = item,
                attachmentQueueLoaded = true,
                outboxLoaded = true,
                commandEntries = listOf(entry),
            ),
        )
        var reviewedRequest: String? = null
        var confirmations = 0
        var discardedRequest: String? = null
        showDetail(item, attachmentContent = {
            AttachmentImageContent(
                state = imageState,
                incomingImageUri = null,
                onImportShared = {},
                onPick = {},
                onDelete = {},
                onRetryCommand = { throw AssertionError("Hidden background requests have no retry action.") },
                onReviewCommand = { requestId ->
                    reviewedRequest = requestId
                    imageState = imageState.copy(
                        commandReview = AttachmentCommandReview(
                            requestId = requestId,
                            kind = AttachmentCommandKind.OCR,
                            targetAssetId = "previous-image",
                            reviewedVersion = 2,
                            targetStillCurrent = true,
                        ),
                    )
                },
                onConfirmCommandAgain = {
                    confirmations++
                    imageState = imageState.copy(commandEntries = emptyList(), commandReview = null)
                },
                onDiscardCommand = { requestId ->
                    discardedRequest = requestId
                    imageState = imageState.copy(commandEntries = emptyList(), commandReview = null)
                },
            )
        })

        compose.onNodeWithText("이전 이미지 작업을 확인해 주세요").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("attachment-pick").assertIsNotEnabled()
        compose.onNodeWithText("최신 내용 확인").performScrollTo().performClick()
        compose.onNodeWithText("확인한 버전으로 새 요청").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(entry.requestId, reviewedRequest)
            assertEquals(1, confirmations)
        }
        compose.onNodeWithTag("attachment-pick").assertIsEnabled()
        compose.runOnIdle {
            imageState = imageState.copy(
                commandEntries = listOf(entry.copy(state = OutboxState.FAILED, errorCode = "NETWORK_ERROR")),
            )
        }
        compose.onNodeWithTag("attachment-pick").assertIsNotEnabled()
        compose.onNodeWithText("요청 다시 시도").assertDoesNotExist()
        compose.onNodeWithText("요청 버리기").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(entry.requestId, discardedRequest) }
        compose.onNodeWithTag("attachment-pick").assertIsEnabled()
        compose.onNodeWithText("이전 이미지 작업을 확인해 주세요").assertDoesNotExist()
        assertNoDetailClutter()
    }

    @Test
    fun urlOnlyBodyAndDescriptionFallBackToMeaningfulSharedText() {
        val url = "https://example.com/shared"
        val shared = "공유받은 글의 첫 줄입니다.\n줄바꿈과 마지막 문장도 유지합니다."
        showDetail(
            LibraryItemDetail(
                id = "shared-content",
                version = 1,
                url = url,
                displayTitle = "공유받은 글",
                bodyText = "  $url\nhttps://example.com/another  ",
                description = url,
                sharedText = shared,
            ),
        )

        compose.onNodeWithTag("detail-content").performScrollTo().assertTextEquals(shared)
        compose.onAllNodesWithText(shared).assertCountEquals(1)
    }

    @Test
    fun urlOnlySharedTextDoesNotInventAcquiredContent() {
        val url = "https://example.com/url-only"
        showDetail(
            LibraryItemDetail(id = "url-only", version = 1, url = url, sharedText = "  $url  "),
        )

        compose.onNodeWithTag("detail-content").assertDoesNotExist()
        assertNoDetailClutter()
    }

    private fun assertSourceBadgePresentation(darkTheme: Boolean) {
        val sources = listOf(
            Triple("instagram", "Instagram", "Ig"),
            Triple("threads", "Threads", "Th"),
            Triple("naver_blog", "네이버 블로그", "N"),
            Triple("other", "기타", "기"),
            Triple("example.com", "example.com", "E"),
        )
        val summaries = sources.map { (source, _, _) ->
            LibraryItemSummary(
                id = source,
                url = "https://example.com/$source",
                displayTitle = "여행 자료 $source",
                source = source,
                categoryRefs = listOf(LibraryCategoryRef(name = "여행")),
            )
        } + listOf(
            LibraryItemSummary(id = "null-source", url = "https://example.com/null-source"),
            LibraryItemSummary(id = "blank-source", url = "https://example.com/blank-source", source = "  "),
        )
        compose.setContent {
            VaultTheme {
                MaterialTheme(colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme()) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        Column(
                            modifier = Modifier
                                .width(260.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            summaries.forEach { LibraryItemCard(item = it, onOpenDetail = {}) }
                        }
                    }
                }
            }
        }

        sources.forEach { (source, label, monogram) ->
            compose.onNodeWithTag("detail-$source").performScrollTo()
                .assertTextContains(label)
                .assertTextContains(monogram)
                .assertTextContains("여행")
            compose.onNodeWithTag("library-source-label-$source", useUnmergedTree = true)
                .assertIsDisplayed().assertTextEquals(label)
            compose.onNodeWithTag("library-source-monogram-$source", useUnmergedTree = true)
                .assertIsDisplayed().assertTextEquals(monogram)
        }
        listOf("null-source", "blank-source").forEach { id ->
            compose.onNodeWithTag("detail-$id").performScrollTo()
            compose.onNodeWithTag("library-source-label-$id", useUnmergedTree = true).assertDoesNotExist()
            compose.onNodeWithTag("library-source-monogram-$id", useUnmergedTree = true).assertDoesNotExist()
            compose.onNodeWithText("https://example.com/$id").assertIsDisplayed()
        }
    }

    private fun assertDetailGeometry() {
        val viewport = compose.onNodeWithTag("detail-viewport").getUnclippedBoundsInRoot()
        val frame = compose.onNodeWithTag("detail-frame").getUnclippedBoundsInRoot()
        val title = compose.onNodeWithTag("detail-title").getUnclippedBoundsInRoot()
        val back = compose.onNodeWithTag("detail-back").getUnclippedBoundsInRoot()
        assertTrue(frame.right - frame.left <= 841.dp)
        val frameCenter = (frame.left.value + frame.right.value) / 2f
        assertEquals((viewport.left.value + viewport.right.value) / 2f, frameCenter, 1f)
        assertEquals(frameCenter, (title.left.value + title.right.value) / 2f, 1f)
        assertEquals(frame.right.value, back.right.value, 1f)
        assertTrue((back.left.value + back.right.value) / 2f > frameCenter)
        assertTrue(back.left >= frame.left)
        compose.onNodeWithTag("detail-title")
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
                val layouts = mutableListOf<TextLayoutResult>()
                assertTrue(action(layouts))
                assertEquals(TextAlign.Center, layouts.single().layoutInput.style.textAlign)
            }
        compose.onNodeWithTag("detail-back").assertTextEquals("보관함 목록")
        compose.onAllNodesWithText("보관함 목록").assertCountEquals(1)
        compose.onNodeWithText("뒤로").assertDoesNotExist()
        assertTrue(abs((frame.left - viewport.left).value - (viewport.right - frame.right).value) <= 1f)
    }

    private fun assertNoDetailClutter() {
        listOf(
            "처리 정보 보기", "저장된 내용 보기", "세부 정보", "가져온 제목", "본문", "미리보기 설명",
            "텍스트 인식 (OCR)", "이미지 첨부", "이미지 상태", "검색 준비", "자동 분류",
            "링크 정보를 가져오지 못했어요.", "이미지에서 글자를 읽지 못했어요.",
        ).forEach { compose.onNodeWithText(it).assertDoesNotExist() }
    }

    private fun captureDetail(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.cacheDir, name).outputStream().use { output ->
            assertTrue(compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, output))
        }
    }

    private val publicText =
        "Four score and seven years ago our fathers brought forth on this continent, " +
            "a new nation, conceived in Liberty, and dedicated to the proposition that all men are created equal.\n\n" +
            "Now we are engaged in a great civil war, testing whether that nation, or any nation so conceived " +
            "and so dedicated, can long endure. We are met on a great battle-field of that war. " +
            "We have come to dedicate a portion of that field, as a final resting place for those who here " +
            "gave their lives that that nation might live. It is altogether fitting and proper that we should do this."

    private fun publicTextDetail() = LibraryItemDetail(
        id = "public-domain-reading",
        version = 1,
        url = "https://www.loc.gov/exhibits/gettysburg-address/",
        displayTitle = "Gettysburg Address. A public-domain reading fixture.",
        bodyText = publicText,
        description = publicText,
        source = "Library of Congress",
        createdAt = "2026-10-24T03:00:00Z",
        categoryRefs = listOf(LibraryCategoryRef(name = "업무·학습")),
        metadataState = "failed",
        ocrState = "failed",
        classificationState = "pending",
    )

    private fun showDetail(
        item: LibraryItemDetail,
        onOpenOriginal: (String) -> Unit = {},
        viewportWidth: Dp? = null,
        onBack: () -> Unit = {},
        onManageCategories: () -> Unit = {},
        onBeginEdit: () -> Unit = {},
        edit: LibraryEditUiState? = null,
        attachmentContent: @Composable () -> Unit = {},
    ) {
        compose.setContent {
            VaultTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    Column(
                        modifier = Modifier
                            .then(if (viewportWidth != null) Modifier.width(viewportWidth) else Modifier.fillMaxSize())
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState())
                            .testTag("detail-viewport")
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        DetailContent(
                            detail = LibraryDetailState.Loaded(item),
                            edit = edit,
                            delete = null,
                            onBackToList = onBack,
                            onOpenOriginal = onOpenOriginal,
                            onBeginEdit = onBeginEdit,
                            onEditTitleChange = {},
                            onEditNoteChange = {},
                            onSaveEdit = {},
                            onCancelEdit = {},
                            onLoadLatest = {},
                            onConfirmAgain = {},
                            onBeginDelete = {},
                            onManageCategories = onManageCategories,
                        )
                        attachmentContent()
                    }
                    }
                }
            }
        }
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

        compose.onNodeWithText("LinkFileBox").assertExists()
        compose.onNodeWithText("내 보관함").assertExists()
        compose.onNodeWithText("최근 보관한 링크").assertExists()
        summaries.forEach { item ->
            compose.onNodeWithText(item.displayTitle!!).assertExists()
        }
        compose.onAllNodesWithText("네이버 블로그").assertCountEquals(2)
        compose.onAllNodesWithText("Threads").assertCountEquals(2)
        compose.onAllNodesWithText("Instagram").assertCountEquals(2)

        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val evidence = File(targetContext.cacheDir, "design-v2-populated.png")
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        evidence.outputStream().use { output ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
        assertTrue(evidence.isFile && evidence.length() > 0L)
        val sourceEvidence = File(targetContext.cacheDir, "source-shortcuts.png")
        sourceEvidence.outputStream().use { output ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
        assertTrue(sourceEvidence.isFile && sourceEvidence.length() > 0L)
    }
}
