package eu.kanade.tachiyomi.ui.libraryUpdateError

import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.core.util.addOrRemove
import eu.kanade.presentation.libraryUpdateError.components.LibraryUpdateErrorUiModel
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.domain.libraryUpdateError.interactor.DeleteLibraryUpdateErrors
import tachiyomi.domain.libraryUpdateError.interactor.GetLibraryUpdateErrorWithRelations
import tachiyomi.domain.libraryUpdateError.model.LibraryUpdateErrorWithRelations
import tachiyomi.domain.libraryUpdateErrorMessage.interactor.GetLibraryUpdateErrorMessages
import tachiyomi.domain.libraryUpdateErrorMessage.model.LibraryUpdateErrorMessage
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class LibraryUpdateErrorScreenModel(
    private val getLibraryUpdateErrorWithRelations: GetLibraryUpdateErrorWithRelations = Injekt.get(),
    private val getLibraryUpdateErrorMessages: GetLibraryUpdateErrorMessages = Injekt.get(),
    private val deleteLibraryUpdateErrors: DeleteLibraryUpdateErrors = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
) : StateScreenModel<LibraryUpdateErrorScreenState>(LibraryUpdateErrorScreenState()) {

    // First and last selected index in list
    private val selectedPositions: Array<Int> = arrayOf(-1, -1)
    private val selectedErrorIds: HashSet<Long> = HashSet()

    init {
        screenModelScope.launchIO {
            getLibraryUpdateErrorWithRelations.subscribeAll()
                .collectLatest { errors ->
                    val errorMessages = getLibraryUpdateErrorMessages.await()
                    mutableState.update {
                        it.copy(
                            isLoading = false,
                            items = errors.toLibraryUpdateErrorItems(),
                            messages = errorMessages,
                        )
                    }
                }
        }
    }

    private fun List<LibraryUpdateErrorWithRelations>.toLibraryUpdateErrorItems(): List<LibraryUpdateErrorItem> {
        return map { error ->
            LibraryUpdateErrorItem(
                error = error,
                sourceName = sourceManager.getOrStub(error.mangaSource).name,
                selected = error.errorId in selectedErrorIds,
            )
        }
            // Sort by messageId to group errors by type, preserving last_update order within groups (from DB)
            .sortedBy { it.error.messageId }
    }

    fun toggleSelection(
        item: LibraryUpdateErrorItem,
        selected: Boolean,
        userSelected: Boolean = false,
        fromLongPress: Boolean = false,
    ) {
        mutableState.update { state ->
            val newItems = state.items.toMutableList().apply {
                val selectedIndex = indexOfFirst { it.error.errorId == item.error.errorId }
                if (selectedIndex < 0) return@apply

                val selectedItem = get(selectedIndex)
                if (selectedItem.selected == selected) return@apply

                val firstSelection = none { it.selected }
                set(selectedIndex, selectedItem.copy(selected = selected))
                selectedErrorIds.addOrRemove(item.error.errorId, selected)

                if (selected && userSelected && fromLongPress) {
                    if (firstSelection) {
                        selectedPositions[0] = selectedIndex
                        selectedPositions[1] = selectedIndex
                    } else {
                        // Try to select the items in-between when possible
                        val range: IntRange
                        if (selectedIndex < selectedPositions[0]) {
                            range = selectedIndex + 1 until selectedPositions[0]
                            selectedPositions[0] = selectedIndex
                        } else if (selectedIndex > selectedPositions[1]) {
                            range = (selectedPositions[1] + 1) until selectedIndex
                            selectedPositions[1] = selectedIndex
                        } else {
                            // Just select itself
                            range = IntRange.EMPTY
                        }

                        range.forEach {
                            val inbetweenItem = get(it)
                            if (!inbetweenItem.selected) {
                                selectedErrorIds.add(inbetweenItem.error.errorId)
                                set(it, inbetweenItem.copy(selected = true))
                            }
                        }
                    }
                } else if (userSelected && !fromLongPress) {
                    if (!selected) {
                        if (selectedIndex == selectedPositions[0]) {
                            selectedPositions[0] = indexOfFirst { it.selected }
                        } else if (selectedIndex == selectedPositions[1]) {
                            selectedPositions[1] = indexOfLast { it.selected }
                        }
                    } else {
                        if (selectedIndex < selectedPositions[0]) {
                            selectedPositions[0] = selectedIndex
                        } else if (selectedIndex > selectedPositions[1]) {
                            selectedPositions[1] = selectedIndex
                        }
                    }
                }
            }
            state.copy(items = newItems)
        }
    }

    fun toggleAllSelection(selected: Boolean) {
        mutableState.update { state ->
            val newItems = state.items.map {
                selectedErrorIds.addOrRemove(it.error.errorId, selected)
                it.copy(selected = selected)
            }
            state.copy(items = newItems)
        }

        selectedPositions[0] = -1
        selectedPositions[1] = -1
    }

    fun invertSelection() {
        mutableState.update { state ->
            val newItems = state.items.map {
                selectedErrorIds.addOrRemove(it.error.errorId, !it.selected)
                it.copy(selected = !it.selected)
            }
            state.copy(items = newItems)
        }
        selectedPositions[0] = -1
        selectedPositions[1] = -1
    }

    @OptIn(DelicateCoroutinesApi::class)
    fun deleteSelected() {
        launchIO {
            deleteLibraryUpdateErrors.delete(selectedErrorIds.toList())
            withUIContext {
                selectedErrorIds.clear()
            }
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    fun delete(errorId: Long) {
        launchIO {
            deleteLibraryUpdateErrors.delete(listOf(errorId))
            withUIContext {
                selectedErrorIds.remove(errorId)
            }
        }
    }
}

@Immutable
data class LibraryUpdateErrorScreenState(
    val isLoading: Boolean = true,
    val items: List<LibraryUpdateErrorItem> = emptyList(),
    val messages: List<LibraryUpdateErrorMessage> = emptyList(),
) {

    val selected = items.filter { it.selected }
    val selectionMode = selected.isNotEmpty()

    fun getUiModel(): List<LibraryUpdateErrorUiModel> {
        val uiModels = mutableListOf<LibraryUpdateErrorUiModel>()
        val errorMap = items.groupBy { it.error.messageId }
        errorMap.forEach { (messageId, errors) ->
            val message = messages.find { it.id == messageId }
            uiModels.add(LibraryUpdateErrorUiModel.Header(message!!.message, errors.size))
            uiModels.addAll(errors.map { LibraryUpdateErrorUiModel.Item(it) })
        }
        return uiModels
    }

    fun getHeaderIndexes(): List<Int> = getUiModel()
        .withIndex()
        .filter { it.value is LibraryUpdateErrorUiModel.Header }
        .map { it.index }
}

@Immutable
data class LibraryUpdateErrorItem(
    val error: LibraryUpdateErrorWithRelations,
    val sourceName: String,
    val selected: Boolean,
) {
    val mangaCover = MangaCover(
        mangaId = error.mangaId,
        sourceId = error.mangaSource,
        isMangaFavorite = error.favorite,
        ogUrl = error.mangaThumbnail,
        lastModified = error.coverLastModified,
    )
}
