package no.nordicsemi.dfu.profile.main.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import no.nordicsemi.android.analytics.AppAnalytics
import no.nordicsemi.android.analytics.DFUErrorEvent
import no.nordicsemi.android.analytics.DFUSuccessEvent
import no.nordicsemi.android.navigation.*
import no.nordicsemi.dfu.profile.DfuSettingsScreen
import no.nordicsemi.dfu.profile.DfuWelcomeScreen
import no.nordicsemi.dfu.profile.main.data.*
import no.nordicsemi.dfu.profile.main.repository.DFURepository
import no.nordicsemi.dfu.profile.main.view.*
import no.nordicsemi.dfu.profile.main.view.DFUProgressViewEntity.Companion.createErrorStage
import no.nordicsemi.dfu.profile.settings.repository.SettingsRepository
import no.nordicsemi.dfu.storage.*
import no.nordicsemi.ui.scanner.ScannerDestinationId
import no.nordicsemi.ui.scanner.ui.exhaustive
import no.nordicsemi.ui.scanner.ui.getDevice
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StateHolder @Inject constructor() {
    internal val state = MutableStateFlow(DFUViewState())

    fun clear() {
        state.value = DFUViewState()
    }
}

@HiltViewModel
internal class DFUViewModel @Inject constructor(
    private val stateHolder: StateHolder,
    private val repository: DFURepository,
    private val navigationManager: NavigationManager,
    private val settingsRepository: SettingsRepository,
    private val externalFileDataSource: ExternalFileDataSource,
    private val deepLinkHandler: DeepLinkHandler,
    private val analytics: AppAnalytics
) : ViewModel() {

    private val _state: MutableStateFlow<DFUViewState> = stateHolder.state
    val state = _state.asStateFlow()

    init {
        repository.data.onEach {
            ((it as? WorkingStatus)?.status as? EnablingDfu)?.let {
                _state.value = _state.value.copy(progressViewEntity = DFUProgressViewEntity.createDfuStage())
            }

            ((it as? WorkingStatus)?.status as? Completed)?.let {
                _state.value = _state.value.copy(progressViewEntity = DFUProgressViewEntity.createSuccessStage())
                analytics.logEvent(DFUSuccessEvent)
            }

            ((it as? WorkingStatus)?.status as? ProgressUpdate)?.let {
                _state.value = _state.value.copy(progressViewEntity = DFUProgressViewEntity.createInstallingStage(it))
            }

            ((it as? WorkingStatus)?.status as? Aborted)?.let {
                val newStatus = (_state.value.progressViewEntity as? WorkingProgressViewEntity)?.status?.createErrorStage("Aborted")
                newStatus?.let {
                    _state.value = _state.value.copy(progressViewEntity = newStatus)
                }
            }

            ((it as? WorkingStatus)?.status as? Error)?.let {
                val newStatus = (_state.value.progressViewEntity as? WorkingProgressViewEntity)?.status?.createErrorStage(it.message)
                newStatus?.let {
                    _state.value = _state.value.copy(progressViewEntity = newStatus)
                }
                analytics.logEvent(DFUErrorEvent(it.message))
            }
        }.launchIn(viewModelScope)

        settingsRepository.settings.onEach {
            _state.value = _state.value.copy(settings = it)

            if (it.showWelcomeScreen) {
                navigationManager.navigateTo(DfuWelcomeScreen)
            }
        }.launchIn(viewModelScope)

        externalFileDataSource.fileResource.onEach {
            when (it) {
                is FileDownloaded -> onZipFileSelected(it.uri)
                FileError -> _state.value = _state.value.copy(
                    fileViewEntity = NotSelectedFileViewEntity(isRunning = false, isError = true),
                )
                LoadingFile -> _state.value = _state.value.copy(
                    fileViewEntity = NotSelectedFileViewEntity(isRunning = true, isError = false),
                )
                null -> doNothing()
            }.exhaustive
        }.launchIn(viewModelScope)

        deepLinkHandler.zipFile.onEach {
            it?.let { onZipFileSelected(it) }
        }.launchIn(viewModelScope)
    }

    private fun requestBluetoothDevice() {
        navigationManager.navigateTo(ScannerDestinationId)

        navigationManager.recentResult.onEach {
            if (it.destinationId == ScannerDestinationId) {
                handleArgs(it)
            }
        }.launchIn(viewModelScope)
    }

    private fun handleArgs(args: DestinationResult?) {
        when (args) {
            is CancelDestinationResult -> doNothing()
            is SuccessDestinationResult -> {
                repository.device = args.getDevice()
                _state.value = _state.value.copy(
                    deviceViewEntity = SelectedDeviceViewEntity(args.getDevice()),
                    progressViewEntity = WorkingProgressViewEntity()
                )
            }
            null -> navigationManager.navigateTo(ScannerDestinationId)
        }.exhaustive
    }

    fun onEvent(event: DFUViewEvent) {
        when (event) {
            OnDisconnectButtonClick -> navigationManager.navigateUp()
            OnInstallButtonClick -> onInstallButtonClick()
            OnAbortButtonClick -> repository.abort()
            is OnZipFileSelected -> onZipFileSelected(event.file)
            NavigateUp -> navigationManager.navigateUp()
            OnCloseButtonClick -> navigationManager.navigateUp()
            OnSelectDeviceButtonClick -> requestBluetoothDevice()
            OnSettingsButtonClick -> navigationManager.navigateTo(DfuSettingsScreen)
        }.exhaustive
    }

    private fun onInstallButtonClick() = _state.value.settings?.let {
        repository.launch(it)
        _state.value = _state.value.copy(progressViewEntity = DFUProgressViewEntity.createBootloaderStage())
    }

    private fun onZipFileSelected(uri: Uri) {
        val zipFile = repository.setZipFile(uri)
        if (zipFile == null) {
            _state.value = _state.value.copy(fileViewEntity = NotSelectedFileViewEntity(true))
        } else {
            _state.value = _state.value.copy(
                fileViewEntity = SelectedFileViewEntity(zipFile),
                deviceViewEntity = NotSelectedDeviceViewEntity
            )
        }
        if (repository.device != null) {
            _state.value = _state.value.copy(
                progressViewEntity = WorkingProgressViewEntity(),
                deviceViewEntity = SelectedDeviceViewEntity(repository.device!!)
            )
        }
    }

    override fun onCleared() {
        super.onCleared()

        if (!repository.isRunning()) {
            stateHolder.clear()
            repository.release()
        }
    }
}
