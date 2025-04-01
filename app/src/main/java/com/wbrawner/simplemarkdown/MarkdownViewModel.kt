package com.wbrawner.simplemarkdown

import androidx.annotation.StringRes
import androidx.compose.foundation.text.input.TextFieldState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.wbrawner.simplemarkdown.core.LocalOnlyException
import com.wbrawner.simplemarkdown.ui.markdownParser
import com.wbrawner.simplemarkdown.ui.markdownRenderer
import com.wbrawner.simplemarkdown.ui.toHtml
import com.wbrawner.simplemarkdown.utility.FileHelper
import com.wbrawner.simplemarkdown.utility.Preference
import com.wbrawner.simplemarkdown.utility.PreferenceHelper
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.net.URI

data class EditorState(
    val fileName: String = "Untitled.md",
    val textFieldState: TextFieldState = TextFieldState(),
    val path: URI? = null,
    val toast: ParameterizedText? = null,
    val alert: AlertDialogModel? = null,
    val saveCallback: (() -> Unit)? = null,
    val lockSwiping: Boolean = false,
    val enableReadability: Boolean = false,
    val enableAutosave: Boolean = false,
    // I'd rather this be a derived property but unfortunately it needs to be handled manually in
    // order to properly trigger state updates in the UI
    val dirty: Boolean = false,
    val initialMarkdown: String = "",
    val exitApp: Boolean = false,
    val shareText: ShareText? = null,
) {
    val markdown: String
        get() = textFieldState.text.toString()
}

@OptIn(ExperimentalCoroutinesApi::class)
class MarkdownViewModel(
    private val fileHelper: FileHelper,
    private val preferenceHelper: PreferenceHelper,
    private val ioDispatcher: CoroutineDispatcher
) : ViewModel() {
    private val _state = MutableStateFlow(EditorState())
    val state = _state.asStateFlow()
    private val saveMutex = Mutex()

    init {
        preferenceHelper.observe<Boolean>(Preference.LOCK_SWIPING)
            .onEach {
                updateState { copy(lockSwiping = it) }
            }
            .launchIn(viewModelScope)
        preferenceHelper.observe<Boolean>(Preference.AUTOSAVE_ENABLED)
            .onEach {
                updateState { copy(enableAutosave = it) }
            }
            .launchIn(viewModelScope)
        preferenceHelper.observe<Boolean>(Preference.READABILITY_ENABLED)
            .onEach {
                updateState {
                    copy(enableReadability = it)
                }
            }
            .launchIn(viewModelScope)
    }

    private var autosaveJob: Job? = null

    fun markdownUpdated() {
        updateState { copy(dirty = markdown != initialMarkdown) }
        autosaveJob?.cancel()
        autosaveJob = viewModelScope.launch {
            delay(500)
            autosave()
        }
    }

    fun dismissToast() {
        updateState { copy(toast = null) }
    }

    fun dismissAlert() {
        updateState { copy(alert = null) }
    }

    fun onBackPressed() {
        if (_state.value.toast == ParameterizedText.ConfirmExitOnBack) {
            updateState {
                copy(exitApp = true)
            }
        } else {
            updateState {
                copy(toast = ParameterizedText.ConfirmExitOnBack)
            }
        }
    }

    private fun unsetSaveCallback() {
        updateState { copy(saveCallback = null) }
    }

    suspend fun load(loadPath: String?) {
        saveMutex.withLock {
            val actualLoadPath = loadPath
                ?.ifBlank { null }
                ?: preferenceHelper[Preference.AUTOSAVE_URI]
                    ?.let {
                        val autosaveUri = it as? String
                        if (autosaveUri.isNullOrBlank()) {
                            preferenceHelper[Preference.AUTOSAVE_URI] = null
                            null
                        } else {
                            Timber.d("Using uri from shared preferences: $it")
                            autosaveUri
                        }
                    } ?: return
            Timber.d("Loading file at $actualLoadPath")
            try {
                val uri = URI.create(actualLoadPath)
                fileHelper.open(uri)
                    ?.let { fileData ->
                        updateState {
                            if (fileData.type?.startsWith("text/") != true) {
                                copy(
                                    alert = AlertDialogModel(
                                        text = ParameterizedText(
                                            R.string.prompt_confirm_open_non_text,
                                            arrayOf(fileData.name)
                                        ),
                                        primaryButton = AlertDialogModel.ButtonModel(
                                            text = ParameterizedText(R.string.yes),
                                            onClick = {
                                                updateState {
                                                    copy(
                                                        path = uri,
                                                        fileName = fileData.name,
                                                        initialMarkdown = fileData.content,
                                                        dirty = false,
                                                        textFieldState = TextFieldState(initialText = fileData.content),
                                                        toast = ParameterizedText(
                                                            R.string.file_loaded,
                                                            arrayOf(fileData.name)
                                                        ),
                                                        alert = null,
                                                    )
                                                }
                                            }
                                        ),
                                        secondaryButton = AlertDialogModel.ButtonModel(
                                            text = ParameterizedText(R.string.no),
                                            onClick = {
                                                updateState {
                                                    copy(
                                                        alert = null,
                                                    )
                                                }
                                            }
                                        )
                                    )
                                )
                            } else {
                                copy(
                                    path = uri,
                                    fileName = fileData.name,
                                    initialMarkdown = fileData.content,
                                    dirty = false,
                                    textFieldState = TextFieldState(initialText = fileData.content),
                                    toast = ParameterizedText(
                                        R.string.file_loaded,
                                        arrayOf(fileData.name)
                                    )
                                )
                            }
                        }
                        preferenceHelper[Preference.AUTOSAVE_URI] = actualLoadPath
                    } ?: throw IllegalStateException("Opened file was null")
            } catch (e: Exception) {
                Timber.e(LocalOnlyException(e), "Failed to open file at path: $actualLoadPath")
                updateState {
                    copy(
                        alert = AlertDialogModel(
                            text = ParameterizedText(R.string.file_load_error),
                            primaryButton = AlertDialogModel.ButtonModel(
                                ParameterizedText(R.string.ok),
                                onClick = ::dismissAlert
                            )
                        )
                    )
                }
            }
        }
    }

    suspend fun save(savePath: URI? = null, interactive: Boolean = true): Boolean =
        saveMutex.withLock {
            val actualSavePath = savePath
                ?: _state.value.path
                ?: run {
                    Timber.w("Attempted to save file with empty path")
                    if (interactive) {
                        updateState {
                            copy(saveCallback = ::unsetSaveCallback)
                        }
                    }
                    return@withLock false
                }
            try {
                Timber.i("Saving file to $actualSavePath...")
                val currentState = _state.value
                val name = fileHelper.save(actualSavePath, currentState.markdown)
                updateState {
                    currentState.copy(
                        fileName = name,
                        path = actualSavePath,
                        initialMarkdown = currentState.markdown,
                        dirty = false,
                        toast = if (interactive) ParameterizedText(
                            R.string.file_saved,
                            arrayOf(name)
                        ) else null
                    )
                }
                Timber.i("Saved file $name to uri $actualSavePath")
                Timber.i("Persisting autosave uri in shared prefs: $actualSavePath")
                preferenceHelper[Preference.AUTOSAVE_URI] = actualSavePath
                true
            } catch (e: Exception) {
                Timber.e(e, "Failed to save file to $actualSavePath")
                if (interactive) {
                    updateState {
                        copy(
                            alert = AlertDialogModel(
                                text = ParameterizedText(R.string.file_save_error),
                                primaryButton = AlertDialogModel.ButtonModel(
                                    text = ParameterizedText(R.string.ok),
                                    onClick = ::dismissAlert
                                )
                            )
                        )
                    }
                }
                false
            }
        }

    suspend fun autosave() {
        val isAutoSaveEnabled = preferenceHelper[Preference.AUTOSAVE_ENABLED] as Boolean
        if (!isAutoSaveEnabled) {
            Timber.i("Ignoring autosave as autosave not enabled")
            return
        }
        if (!_state.value.dirty) {
            Timber.d("Ignoring autosave as contents haven't changed")
            return
        }
        if (saveMutex.isLocked) {
            Timber.i("Ignoring autosave since manual save is already in progress")
            return
        }
        Timber.d("Performing autosave")
        if (!save(interactive = false)) {
            withContext(ioDispatcher) {
                // The user has left the app, with autosave enabled, and we don't already have a
                // Uri for them or for some reason we were unable to save to the original Uri. In
                // this case, we need to just save to internal file storage so that we can recover
                val file = File(fileHelper.defaultDirectory, _state.value.fileName).toURI()
                Timber.i("No cached uri for autosave, saving to $file instead")
                // Here we call the fileHelper directly so that the file is still registered as dirty.
                // This prevents the user from ending up in a scenario where they've autosaved the file
                // to an internal storage location, thus marking it as not dirty, but no longer able to
                // access the file if the accidentally go to create a new file without properly saving
                // the current one
                fileHelper.save(file, _state.value.markdown)
                preferenceHelper[Preference.AUTOSAVE_URI] = file
            }
        }
    }

    fun reset(untitledFileName: String, force: Boolean = false) {
        Timber.i("Resetting view model to default state")
        if (!force && _state.value.dirty) {
            updateState {
                copy(alert = AlertDialogModel(
                    text = ParameterizedText(R.string.prompt_save_changes),
                    primaryButton = AlertDialogModel.ButtonModel(
                        text = ParameterizedText(R.string.yes),
                        onClick = {
                            _state.value = _state.value.copy(
                                saveCallback = {
                                    reset(untitledFileName, false)
                                }
                            )
                        }
                    ),
                    secondaryButton = AlertDialogModel.ButtonModel(
                        text = ParameterizedText(R.string.no),
                        onClick = {
                            reset(untitledFileName, true)
                        }
                    )
                ))
            }
            return
        }
        updateState {
            EditorState(
                fileName = untitledFileName,
                lockSwiping = preferenceHelper[Preference.LOCK_SWIPING] as Boolean
            )
        }
        Timber.i("Removing autosave uri from shared prefs")
        preferenceHelper[Preference.AUTOSAVE_URI] = null
    }

    fun share() {
        if (markdownParser == null || markdownRenderer == null) {
            updateState {
                copy(alert = null, shareText = ShareText(markdown))
            }
            return
        }

        updateState {
            copy(
                alert = AlertDialogModel(
                    text = ParameterizedText(R.string.title_share_as),
                primaryButton = AlertDialogModel.ButtonModel(
                    text = ParameterizedText(R.string.share_markdown),
                    onClick = {
                        updateState {
                            copy(alert = null, shareText = ShareText(markdown))
                        }
                    }
                ),
                secondaryButton = AlertDialogModel.ButtonModel(
                    text = ParameterizedText(R.string.share_html),
                    onClick = {
                        updateState {
                            copy(
                                alert = null,
                                shareText = ShareText(markdown.toHtml() ?: markdown)
                            )
                        }
                    }
                )
            ))
        }
    }

    fun dismissShare() {
        updateState {
            copy(alert = null, shareText = null)
        }
    }

    fun setLockSwiping(enabled: Boolean) {
        preferenceHelper[Preference.LOCK_SWIPING] = enabled
    }

    private fun updateState(block: EditorState.() -> EditorState) {
        _state.value = _state.value.block()
    }

    companion object {
        fun factory(
            fileHelper: FileHelper,
            preferenceHelper: PreferenceHelper,
            ioDispatcher: CoroutineDispatcher
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(
                modelClass: Class<T>,
                extras: CreationExtras
            ): T {
                return MarkdownViewModel(fileHelper, preferenceHelper, ioDispatcher) as T
            }
        }
    }
}

data class AlertDialogModel(
    val text: ParameterizedText,
    val primaryButton: ButtonModel,
    val secondaryButton: ButtonModel? = null
) {
    data class ButtonModel(val text: ParameterizedText, val onClick: () -> Unit)
}

data class ParameterizedText(@StringRes val text: Int, val params: Array<Any> = arrayOf()) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ParameterizedText

        if (text != other.text) return false
        if (!params.contentEquals(other.params)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = text
        result = 31 * result + params.contentHashCode()
        return result
    }

    companion object {
        val ConfirmExitOnBack = ParameterizedText(R.string.confirm_exit)
    }
}

data class ShareText(val text: String, val contentType: String = "text/plain")
