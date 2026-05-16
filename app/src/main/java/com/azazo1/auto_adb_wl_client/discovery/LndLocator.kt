package com.azazo1.auto_adb_wl_client.discovery

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
        val configuredBaseUrl = compiledBaseUrl() ?: return flowOf(emptyList())

        return callbackFlow {
            val services = ConcurrentHashMap<String, DiscoveredService>()
            val client = Client(configuredBaseUrl, compiledBearerToken()).setTimeoutMillis(10_000)
            val filter = buildFilter(client)
            val currentWatchHandle = AtomicReference<WatchHandle?>(null)

            fun emitSnapshot() {
                trySend(DiscoveredService.merge(services.values))
            }

            fun replaceSnapshot(nodes: List<DiscoveredNode>) {
                services.clear()
                nodes.forEach { node ->
                    node.toService()?.let { services[node.nodeId] = it }
                }
                emitSnapshot()
            }

            fun upsertNode(node: DiscoveredNode) {
                node.toService()?.let {
                    services[node.nodeId] = it
                    emitSnapshot()
                }
            }

            fun removeNode(node: DiscoveredNode) {
                if (services.remove(node.nodeId) != null) {
                    emitSnapshot()
                }
            }

            val watchJob = launch(Dispatchers.IO) {
                discoverOnce(client, filter).onSuccess(::replaceSnapshot)

                var restartAttempt = 0
                while (isActive) {
                    val watchHandleResult = startWatch(client, filter) { event ->
                        when (event.type) {
                            DiscoveryEvent.Type.SNAPSHOT -> replaceSnapshot(event.nodes)
                            DiscoveryEvent.Type.UPSERT -> event.node?.let(::upsertNode)
                            DiscoveryEvent.Type.REMOVE -> event.node?.let(::removeNode)
                            DiscoveryEvent.Type.RESET,
                            DiscoveryEvent.Type.KEEPALIVE -> Unit
                        }
                    }
                    if (watchHandleResult.isFailure) {
                        restartAttempt++
                        delay(retryDelayMillis(restartAttempt))
                        continue
                    }
                    val watchHandle = watchHandleResult.getOrThrow()

                    currentWatchHandle.set(watchHandle)
                    restartAttempt = 0
                    try {
                        watchHandle.awaitStopped()
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    } finally {
                        currentWatchHandle.compareAndSet(watchHandle, null)
                    }

                    if (!isActive) {
                        break
                    }

                    discoverOnce(client, filter).onSuccess(::replaceSnapshot)

                    if (watchHandle.getLastError() == null) {
                        delay(MIN_RETRY_DELAY_MILLIS)
                        continue
                    }

                    restartAttempt++
                    delay(retryDelayMillis(restartAttempt))
                }
            }

            awaitClose {
                currentWatchHandle.getAndSet(null)?.close()
                watchJob.cancel()
            }
        }.flowOn(Dispatchers.IO)
    }

    private fun buildFilter(client: Client): DiscoveryFilter {
        val filter = DiscoveryFilter().withService(LND_SERVICE_NAME)
        runCatching { client.resolveNetworkId() }
            .getOrNull()
            ?.let(filter::withNetworkId)
        return filter
    }

    private fun discoverOnce(client: Client, filter: DiscoveryFilter): Result<List<DiscoveredNode>> {
        return runCatching { client.discoverWithAutoScopeOverlap(filter.copy()) }
            .recoverCatching { client.discover(filter.copy()) }
    }

    private fun startWatch(
        client: Client,
        filter: DiscoveryFilter,
        onEvent: (DiscoveryEvent) -> Unit
    ): Result<WatchHandle> {
        return runCatching {
            client.watchWithAutoScopeOverlap(filter.copy()) { envelope ->
                onEvent(envelope.event)
            }
        }.recoverCatching {
            client.watch(filter.copy()) { envelope ->
                onEvent(envelope.event)
            }
        }
    }

    private fun DiscoveredNode.toService(): DiscoveredService? {
        val hosts = lanAddrs.mapNotNull(DiscoveredService::extractHost).distinct()
        val preferredHost = hosts.firstOrNull() ?: return null
        return DiscoveredService(
            name = displayName,
            host = preferredHost,
            port = port,
            addresses = hosts,
            sources = setOf(DiscoverySource.LND),
            networkId = networkId
        )
    }

    private fun compiledBaseUrl(): String? {
        return BuildConfig.AUTO_ADB_WL_LND_BASE_URL
            .trim()
            .takeIf { it.isNotEmpty() }
    }

    private fun compiledBearerToken(): String = BuildConfig.AUTO_ADB_WL_LND_BEARER_TOKEN.trim()

    companion object {
        private const val LND_SERVICE_NAME = "_auto-adb-wl._tcp"
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
