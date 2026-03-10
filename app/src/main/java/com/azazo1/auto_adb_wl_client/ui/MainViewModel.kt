package com.azazo1.auto_adb_wl_client.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.azazo1.auto_adb_wl_client.data.AdbConnectRequest
import com.azazo1.auto_adb_wl_client.data.AdbPairRequest
import com.azazo1.auto_adb_wl_client.data.DiscoveredService
import com.azazo1.auto_adb_wl_client.data.ScrcpyLaunchMode
import com.azazo1.auto_adb_wl_client.data.ScrcpyLaunchRequest
import com.azazo1.auto_adb_wl_client.discovery.MdnsDiscovery
import com.azazo1.auto_adb_wl_client.network.ApiService
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * UI 状态
 */
data class UiState(
    // 服务发现状态
    val isDiscovering: Boolean = false,
    val discoveredServices: List<DiscoveredService> = emptyList(),
    val discoveryError: String? = null,

    // 当前选中的服务
    val selectedService: Int? = null,
    val manualAddress: String = "",
    val manualPort: String = "21300",

    // 操作状态
    val isConnecting: Boolean = false,
    val isPairing: Boolean = false,
    val isLaunchingScrcpy: Boolean = false,

    // 操作结果
    val lastOperationResult: OperationResult? = null,

    // 配对对话框
    val showPairDialog: Boolean = false,
    val pairCode: String = "",

    // Scrcpy 模式选择
    val showScrcpyDialog: Boolean = false
)

/**
 * 操作结果
 */
data class OperationResult(
    val success: Boolean,
    val message: String,
    val type: OperationType
)

enum class OperationType {
    CONNECT, PAIR, SCRCPY
}

/**
 * 主 ViewModel
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val mdnsDiscovery = MdnsDiscovery(application)
    private var discoveryJob: Job? = null

    /**
     * 开始发现服务
     */
    fun startDiscovery() {
        if (_uiState.value.isDiscovering) return

        _uiState.update { it.copy(isDiscovering = true, discoveryError = null) }

        discoveryJob = viewModelScope.launch {
            try {
                mdnsDiscovery.discoverServices().collect { services ->
                    _uiState.update { it.copy(discoveredServices = services) }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isDiscovering = false,
                        discoveryError = "服务发现失败: ${e.message}"
                    )
                }
            }
        }
    }

    /**
     * 停止发现服务
     */
    fun stopDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = null
        _uiState.update { it.copy(isDiscovering = false) }
    }

    /**
     * 选择服务
     */
    fun selectService(service: Int?) {
        _uiState.update { it.copy(selectedService = service) }
    }

    /**
     * 更新手动地址
     */
    fun updateManualAddress(address: String) {
        _uiState.update { it.copy(manualAddress = address) }
    }

    /**
     * 更新手动端口
     */
    fun updateManualPort(port: String) {
        _uiState.update { it.copy(manualPort = port) }
    }

    /**
     * 获取当前有效的服务 URL
     */
    private fun getCurrentServiceUrl(): String? {
        val state = _uiState.value
        if (state.selectedService != null) {
            return state.discoveredServices[state.selectedService].baseUrl; } else {
            if (state.manualAddress.isNotBlank()) {
                return "http://${state.manualAddress}:${state.manualPort}"
            } else {
                return null
            }
        }
    }

    /**
     * ADB 连接
     */
    fun adbConnect(address: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isConnecting = true, lastOperationResult = null) }

            try {
                val serviceUrl = getCurrentServiceUrl()
                    ?: throw Exception("请选择服务或输入地址")

                val api = ApiService.create(serviceUrl)
                val result = api.adbConnect(
                    AdbConnectRequest(address)
                )

                _uiState.update {
                    it.copy(
                        isConnecting = false,
                        lastOperationResult = OperationResult(true, result, OperationType.CONNECT)
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isConnecting = false,
                        lastOperationResult = OperationResult(
                            false,
                            e.message ?: "连接失败",
                            OperationType.CONNECT
                        )
                    )
                }
            }
        }
    }

    /**
     * 显示配对对话框
     */
    fun showPairDialog() {
        _uiState.update { it.copy(showPairDialog = true, pairCode = "") }
    }

    /**
     * 隐藏配对对话框
     */
    fun hidePairDialog() {
        _uiState.update { it.copy(showPairDialog = false, pairCode = "") }
    }

    /**
     * 更新配对码
     */
    fun updatePairCode(code: String) {
        _uiState.update { it.copy(pairCode = code) }
    }

    /**
     * 执行配对
     */
    // todo 无障碍获取信息
    fun adbPair(address: String, pairCode: String) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isPairing = true,
                    showPairDialog = false,
                    lastOperationResult = null
                )
            }

            try {
                val serviceUrl = getCurrentServiceUrl()
                    ?: throw Exception("请选择服务或输入地址")

                val api = ApiService.create(serviceUrl)
                val result = api.adbPair(
                    AdbPairRequest(address, pairCode)
                )

                _uiState.update {
                    it.copy(
                        isPairing = false,
                        lastOperationResult = OperationResult(true, result, OperationType.PAIR)
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isPairing = false,
                        lastOperationResult = OperationResult(
                            false,
                            e.message ?: "配对失败",
                            OperationType.PAIR
                        )
                    )
                }
            }
        }
    }

    /**
     * 显示 Scrcpy 对话框
     */
    fun showScrcpyDialog() {
        _uiState.update { it.copy(showScrcpyDialog = true) }
    }

    /**
     * 隐藏 Scrcpy 对话框
     */
    fun hideScrcpyDialog() {
        _uiState.update { it.copy(showScrcpyDialog = false) }
    }

    /**
     * 启动 Scrcpy
     */
    fun launchScrcpy(mode: ScrcpyLaunchMode) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLaunchingScrcpy = true,
                    showScrcpyDialog = false,
                    lastOperationResult = null
                )
            }

            try {
                val serviceUrl = getCurrentServiceUrl()
                    ?: throw Exception("请选择服务或输入地址")

                val api = ApiService.create(serviceUrl)
                val result = api.scrcpyLaunch(
                    ScrcpyLaunchRequest(mode)
                )

                _uiState.update {
                    it.copy(
                        isLaunchingScrcpy = false,
                        lastOperationResult = OperationResult(true, result, OperationType.SCRCPY)
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLaunchingScrcpy = false,
                        lastOperationResult = OperationResult(
                            false,
                            e.message ?: "启动失败",
                            OperationType.SCRCPY
                        )
                    )
                }
            }
        }
    }

    /**
     * 清除操作结果
     */
    fun clearOperationResult() {
        _uiState.update { it.copy(lastOperationResult = null) }
    }

    override fun onCleared() {
        super.onCleared()
        discoveryJob?.cancel()
    }
}
