package com.azazo1.auto_adb_wl_client.discovery

import android.util.Log
import com.azazo1.auto_adb_wl_client.BuildConfig
import com.azazo1.auto_adb_wl_client.data.DiscoveredService
import com.azazo1.auto_adb_wl_client.data.DiscoverySource
import io.github.azazo1.lnd.Client
import io.github.azazo1.lnd.DiscoveredNode
import io.github.azazo1.lnd.DiscoveryEvent
import io.github.azazo1.lnd.DiscoveryFilter
import io.github.azazo1.lnd.WatchHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

class LndLocator {
    fun discoverServices(): Flow<List<DiscoveredService>> {
        val configuredBaseUrl = compiledBaseUrl()
        if (configuredBaseUrl == null) {
            Log.i(TAG, "lnd discovery disabled: AUTO_ADB_WL_LND_BASE_URL is empty")
            return flowOf(emptyList())
        }

        return callbackFlow {
            val services = ConcurrentHashMap<String, DiscoveredService>()
            val client = Client(configuredBaseUrl, compiledBearerToken()).setTimeoutMillis(10_000)
            val filter = buildFilter(client)
            val currentWatchHandle = AtomicReference<WatchHandle?>(null)
            val localReachabilityScopes = runCatching { client.listReachabilityScopes() }
                .onFailure {
                    Log.w(TAG, "failed to list local reachability scopes: ${it.message}", it)
                }
                .getOrDefault(emptyList())
            Log.i(
                TAG,
                "lnd discovery flow opened: baseUrl=$configuredBaseUrl, service=${compiledServiceName()}, discoveryDomain=${compiledDiscoveryDomain() ?: "<none>"}, localScopes=${localReachabilityScopes.joinToString(",").ifEmpty { "<none>" }}"
            )

            fun emitSnapshot() {
                val merged = DiscoveredService.merge(services.values)
                Log.i(TAG, "lnd emit snapshot: raw=${services.size}, merged=${merged.size}")
                trySend(merged)
            }

            fun replaceSnapshot(nodes: List<DiscoveredNode>) {
                services.clear()
                var accepted = 0
                nodes.forEach { node ->
                    node.toService()?.let {
                        services[node.nodeId] = it
                        accepted++
                    }
                }
                Log.i(TAG, "lnd snapshot received: nodes=${nodes.size}, accepted=$accepted")
                emitSnapshot()
            }

            fun upsertNode(node: DiscoveredNode) {
                node.toService()?.let {
                    services[node.nodeId] = it
                    Log.i(TAG, "lnd upsert: ${nodeSummary(node)}")
                    emitSnapshot()
                }
            }

            fun removeNode(node: DiscoveredNode) {
                if (services.remove(node.nodeId) != null) {
                    Log.i(TAG, "lnd remove: ${nodeSummary(node)}")
                    emitSnapshot()
                } else {
                    Log.d(TAG, "lnd remove ignored: ${nodeSummary(node)}")
                }
            }

            val watchJob = launch(Dispatchers.IO) {
                discoverOnce(client, filter).onSuccess(::replaceSnapshot)

                var restartAttempt = 0
                while (isActive) {
                    Log.i(TAG, "lnd watch starting: attempt=${restartAttempt + 1}")
                    val watchHandleResult = startWatch(client, filter) { event ->
                        when (event.type) {
                            DiscoveryEvent.Type.SNAPSHOT -> replaceSnapshot(event.nodes)
                            DiscoveryEvent.Type.UPSERT -> event.node?.let(::upsertNode)
                            DiscoveryEvent.Type.REMOVE -> event.node?.let(::removeNode)
                            DiscoveryEvent.Type.RESET -> Log.w(TAG, "lnd watch reset received")
                            DiscoveryEvent.Type.KEEPALIVE -> Log.d(TAG, "lnd watch keepalive")
                        }
                    }
                    if (watchHandleResult.isFailure) {
                        restartAttempt++
                        val retryDelay = retryDelayMillis(restartAttempt)
                        val error = watchHandleResult.exceptionOrNull()
                        Log.w(
                            TAG,
                            "lnd watch start failed: attempt=$restartAttempt, retryInMs=$retryDelay, error=${error?.message}",
                            error
                        )
                        delay(retryDelay)
                        continue
                    }
                    val watchHandle = watchHandleResult.getOrThrow()

                    currentWatchHandle.set(watchHandle)
                    restartAttempt = 0
                    Log.i(TAG, "lnd watch started")
                    try {
                        watchHandle.awaitStopped()
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    } finally {
                        currentWatchHandle.compareAndSet(watchHandle, null)
                    }

                    if (!isActive) {
                        Log.i(TAG, "lnd watch loop stopping because coroutine is inactive")
                        break
                    }

                    discoverOnce(client, filter).onSuccess(::replaceSnapshot)

                    val watchError = watchHandle.getLastError()
                    if (watchError == null) {
                        Log.w(TAG, "lnd watch stopped without error, restarting in ${MIN_RETRY_DELAY_MILLIS}ms")
                        delay(MIN_RETRY_DELAY_MILLIS)
                        continue
                    }

                    restartAttempt++
                    val retryDelay = retryDelayMillis(restartAttempt)
                    Log.w(
                        TAG,
                        "lnd watch stopped with error: attempt=$restartAttempt, retryInMs=$retryDelay, error=${watchError.message}",
                        watchError
                    )
                    delay(retryDelay)
                }
            }

            awaitClose {
                Log.i(TAG, "lnd discovery flow closing")
                currentWatchHandle.getAndSet(null)?.close()
                watchJob.cancel()
            }
        }.flowOn(Dispatchers.IO)
    }

    private fun buildFilter(client: Client): DiscoveryFilter {
        val filter = DiscoveryFilter().withService(compiledServiceName())
        compiledDiscoveryDomain()
            ?.let(filter::withDiscoveryDomain)
        return filter
    }

    private fun discoverOnce(client: Client, filter: DiscoveryFilter): Result<List<DiscoveredNode>> {
        val autoScopeResult = runCatching { client.discoverWithAutoScopeOverlap(filter.copy()) }
        if (autoScopeResult.isSuccess) {
            Log.i(
                TAG,
                "lnd discover succeeded with auto scope overlap: nodes=${autoScopeResult.getOrNull()?.size ?: 0}"
            )
            return autoScopeResult
        }

        val autoScopeError = autoScopeResult.exceptionOrNull()
        Log.w(
            TAG,
            "lnd discoverWithAutoScopeOverlap failed, falling back to plain discover: error=${autoScopeError?.message}",
            autoScopeError
        )

        val plainDiscoverResult = runCatching { client.discover(filter.copy()) }
        plainDiscoverResult.onSuccess {
            Log.i(TAG, "lnd discover succeeded without auto scope overlap: nodes=${it.size}")
        }.onFailure {
            Log.e(TAG, "lnd discover failed: error=${it.message}", it)
        }
        return plainDiscoverResult
    }

    private fun startWatch(
        client: Client,
        filter: DiscoveryFilter,
        onEvent: (DiscoveryEvent) -> Unit
    ): Result<WatchHandle> {
        val autoScopeResult = runCatching {
            client.watchWithAutoScopeOverlap(filter.copy()) { envelope ->
                onEvent(envelope.event)
            }
        }
        if (autoScopeResult.isSuccess) {
            Log.i(TAG, "lnd watch started with auto scope overlap")
            return autoScopeResult
        }

        val autoScopeError = autoScopeResult.exceptionOrNull()
        Log.w(
            TAG,
            "lnd watchWithAutoScopeOverlap failed, falling back to plain watch: error=${autoScopeError?.message}",
            autoScopeError
        )

        val plainWatchResult = runCatching {
            client.watch(filter.copy()) { envelope ->
                onEvent(envelope.event)
            }
        }
        plainWatchResult.onSuccess {
            Log.i(TAG, "lnd watch started without auto scope overlap")
        }.onFailure {
            Log.e(TAG, "lnd watch failed: error=${it.message}", it)
        }
        return plainWatchResult
    }

    private fun DiscoveredNode.toService(): DiscoveredService? {
        val hosts = lanAddrs.mapNotNull(DiscoveredService::extractHost).distinct()
        val preferredHost = hosts.firstOrNull()
        if (preferredHost == null) {
            Log.w(TAG, "skip lnd node without usable host: ${nodeSummary(this)}")
            return null
        }
        return DiscoveredService(
            name = displayName,
            host = preferredHost,
            port = port,
            addresses = hosts,
            sources = setOf(DiscoverySource.LND),
            discoveryDomain = discoveryDomain
        )
    }

    private fun compiledBaseUrl(): String? {
        return BuildConfig.AUTO_ADB_WL_LND_BASE_URL
            .trim()
            .takeIf { it.isNotEmpty() }
    }

    private fun compiledBearerToken(): String = BuildConfig.AUTO_ADB_WL_LND_BEARER_TOKEN.trim()

    private fun compiledDiscoveryDomain(): String? {
        return BuildConfig.AUTO_ADB_WL_LND_DISCOVERY_DOMAIN
            .trim()
            .ifEmpty { null }
    }

    private fun compiledServiceName(): String {
        return BuildConfig.AUTO_ADB_WL_LND_SERVICE_NAME
            .trim()
            .ifEmpty { DEFAULT_LND_SERVICE_NAME }
    }

    private fun nodeSummary(node: DiscoveredNode): String {
        return "nodeId=${node.nodeId}, name=${node.displayName}, service=${node.service}, port=${node.port}, discoveryDomain=${node.discoveryDomain ?: "<none>"}, addrs=${node.lanAddrs.joinToString(",")}, scopes=${node.reachabilityScopes.joinToString(",").ifEmpty { "<none>" }}"
    }

    companion object {
        private const val TAG = "LndLocator"
        private const val DEFAULT_LND_SERVICE_NAME = "_http._tcp"
        private const val MIN_RETRY_DELAY_MILLIS = 1_000L
        private const val MAX_RETRY_DELAY_MILLIS = 15_000L

        private fun retryDelayMillis(attempt: Int): Long {
            var delay = MIN_RETRY_DELAY_MILLIS
            repeat((attempt - 1).coerceAtLeast(0).coerceAtMost(4)) {
                delay = (delay * 2).coerceAtMost(MAX_RETRY_DELAY_MILLIS)
            }
            return delay
        }
    }
}
