package tv.own.owntv.rooms

import android.util.Log
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.realtime
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.RealtimeChannel
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import tv.own.owntv.player.PlaybackSyncState
import tv.own.owntv.player.PlayerType
import tv.own.owntv.player.SyncConfidence

interface TvPairingService {
    suspend fun createPairingSession(roomId: String? = null): RoomResult<TvPairingInfo>
    fun observePairingStatus(): Flow<TvPairingInfo?>
    suspend fun cancelPairing(): RoomResult<Boolean>
    suspend fun clear()
    fun observeRoomConnection(roomId: String): Flow<Boolean>
}

class SupabaseTvPairingService(
    private val socialAuthRepository: SocialAuthRepository = SupabaseSocialAuthRepository(),
    private val tvSessionManager: TvSessionManager = TvSessionManager(),
    private val client: io.github.jan.supabase.SupabaseClient = SupabaseClientProvider.client,
    private val playbackSyncStateFlow: StateFlow<PlaybackSyncState>? = null
) : TvPairingService {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _pairingStatus = MutableStateFlow<TvPairingInfo?>(null)
    private val pairingStatus: StateFlow<TvPairingInfo?> = _pairingStatus.asStateFlow()

    private var realtimeJob: Job? = null
    private var activeSessionId: String? = null
    private var activeRoomId: String? = null
    private var activeChannel: RealtimeChannel? = null

    private val jsonLenient = Json { ignoreUnknownKeys = true }

    override suspend fun createPairingSession(roomId: String?): RoomResult<TvPairingInfo> {
        return runCatching {
            val authResult = socialAuthRepository.ensureTvAuthenticated()
            if (authResult.isFailure) {
                return RoomResult.Error("Anonymous auth failure: ${authResult.exceptionOrNull()?.message}", authResult.exceptionOrNull())
            }

            val brandId = BrandResolver.resolveBrandId()
            val tvSessionId = tvSessionManager.getOrCreateTvSessionId()

            val response = client.postgrest.rpc(
                "create_pairing_session",
                buildJsonObject {
                    if (roomId != null) put("p_room_id", roomId) else put("p_room_id", null as String?)
                    put("p_brand_id", brandId)
                    put("p_tv_session_id", tvSessionId)
                }
            ).decodeSingle<CreatePairingResponseDto>()

            Log.d("TvPairing", "PAIRING_RT session-created sessionId=${response.id} status=${response.status}")

            val expiresAtMs = parseTimestampToMillis(response.expires_at)
            val info = TvPairingInfo(
                id = response.id,
                pairingCode = response.pairing_code,
                qrToken = response.qr_token,
                expiresAtMs = expiresAtMs,
                status = parsePairingStatus(response.status),
                roomId = roomId
            )

            activeSessionId = info.id
            activeRoomId = roomId
            _pairingStatus.value = info

            subscribeToSession(info.id)

            RoomResult.Success(info)
        }.getOrElse { e ->
            RoomResult.Error("Failed to create pairing session: ${e.message}", e)
        }
    }

    override fun observePairingStatus(): Flow<TvPairingInfo?> {
        return pairingStatus
    }

    override suspend fun cancelPairing(): RoomResult<Boolean> {
        val sessionId = activeSessionId ?: return RoomResult.Error("No active pairing session")
        return runCatching {
            val success = client.postgrest.rpc(
                "cancel_pairing_session",
                buildJsonObject {
                    put("p_session_id", sessionId)
                }
            ).decodeSingle<Boolean>()

            if (success) {
                _pairingStatus.value = _pairingStatus.value?.copy(status = PairingStatus.CANCELLED)
                clearRealtime()
            }
            RoomResult.Success(success)
        }.getOrElse { e ->
            RoomResult.Error("Failed to cancel pairing: ${e.message}", e)
        }
    }

    override suspend fun clear() {
        clearRealtime()
        _pairingStatus.value = null
        activeSessionId = null
        activeRoomId = null
    }

    private fun clearRealtime() {
        realtimeJob?.cancel()
        realtimeJob = null
        val ch = activeChannel
        activeChannel = null
        if (ch != null) {
            scope.launch {
                runCatching {
                    ch.unsubscribe()
                    client.realtime.removeChannel(ch)
                }
            }
        }
    }

    private fun subscribeToSession(sessionId: String) {
        clearRealtime()
        realtimeJob = scope.launch {
            try {
                Log.d("TvPairing", "PAIRING_RT collector-started")
                Log.d("TvPairing", "PAIRING_RT filter=id=eq.$sessionId")

                val channel = client.realtime.channel("pairing-$sessionId")
                activeChannel = channel

                val flow: Flow<PostgresAction> = channel.postgresChangeFlow(schema = "public") {
                    table = "pairing_sessions"
                    filter = "id=eq.$sessionId"
                }

                val collectJob = launch {
                    flow.collect { action ->
                        val record = when (action) {
                            is PostgresAction.Update -> action.record
                            is PostgresAction.Insert -> action.record
                            else -> null
                        }
                        if (record != null) {
                            val rowResult = runCatching {
                                jsonLenient.decodeFromJsonElement<PairingSessionRowDto>(record)
                            }
                            if (rowResult.isFailure) {
                                val e = rowResult.exceptionOrNull()!!
                                Log.e("TvPairing", "PAIRING_RT decode-error class=${e.javaClass.name} message=${e.message}")
                                return@collect
                            }
                            val row = rowResult.getOrThrow()

                            if (row.id == activeSessionId) {
                                Log.d("TvPairing", "PAIRING_RT update-received status=${row.status}")

                                val updatedInfo = row.toDomain().copy(roomId = activeRoomId)

                                val finalInfo = if (updatedInfo.isExpired()) {
                                    updatedInfo.copy(status = PairingStatus.EXPIRED)
                                } else {
                                    updatedInfo
                                }

                                _pairingStatus.value = finalInfo

                                if (finalInfo.status == PairingStatus.CLAIMED) {
                                    Log.d("TvPairing", "PAIRING_UI state=CLAIMED")
                                    preparePlaybackSyncEnvelope(finalInfo.id, finalInfo.roomId)
                                    clearRealtime()
                                }

                                if (finalInfo.status == PairingStatus.EXPIRED || finalInfo.status == PairingStatus.CANCELLED) {
                                    clearRealtime()
                                }
                            }
                        }
                    }
                }

                Log.d("TvPairing", "PAIRING_RT connect-call")
                client.realtime.connect()
                Log.d("TvPairing", "PAIRING_RT connect-returned")

                Log.d("TvPairing", "PAIRING_RT subscribe-call")
                try {
                    withTimeout(10_000) {
                        channel.subscribe(blockUntilSubscribed = true)
                    }
                    Log.d("TvPairing", "PAIRING_RT subscribed")
                } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                    Log.w("TvPairing", "PAIRING_RT subscribe-timeout")
                }

                // One-time post-subscribe/timeout reconciliation against race conditions & connection hiccups
                val existingDto = runCatching {
                    val rawRow = client.postgrest["pairing_sessions"]
                        .select {
                            filter { eq("id", sessionId) }
                        }
                        .decodeSingleOrNull<kotlinx.serialization.json.JsonObject>()

                    if (rawRow != null) {
                        jsonLenient.decodeFromJsonElement<PairingSessionRowDto>(rawRow)
                    } else {
                        null
                    }
                }.getOrNull()

                if (existingDto != null && existingDto.id == activeSessionId) {
                    val reconciledInfo = existingDto.toDomain().copy(roomId = activeRoomId)
                    Log.d("TvPairing", "PAIRING_RT reconcile status=${reconciledInfo.status}")
                    if (reconciledInfo.status == PairingStatus.CLAIMED || reconciledInfo.status == PairingStatus.EXPIRED || reconciledInfo.status == PairingStatus.CANCELLED) {
                        _pairingStatus.value = reconciledInfo
                        if (reconciledInfo.status == PairingStatus.CLAIMED) {
                            Log.d("TvPairing", "PAIRING_UI state=CLAIMED")
                            preparePlaybackSyncEnvelope(reconciledInfo.id, reconciledInfo.roomId)
                        }
                        clearRealtime()
                    }
                }

                collectJob.join()
            } catch (e: Exception) {
                Log.e("TvPairing", "PAIRING_RT subscribe-error class=${e.javaClass.name} message=${e.message}", e)
            }
        }
    }

    override fun observeRoomConnection(roomId: String): Flow<Boolean> = callbackFlow {
        trySend(false)

        val channel = client.realtime.channel("room-conn-$roomId")
        val flow: Flow<PostgresAction> = channel.postgresChangeFlow(schema = "public") {
            table = "pairing_sessions"
            filter = "room_id=eq.$roomId"
        }

        val collectJob = scope.launch {
            launch {
                try {
                    flow.collect { action ->
                        val record = when (action) {
                            is PostgresAction.Update -> action.record
                            is PostgresAction.Insert -> action.record
                            else -> null
                        }
                        if (record != null) {
                            val rowResult = runCatching {
                                jsonLenient.decodeFromJsonElement<PairingSessionRowDto>(record)
                            }
                            if (rowResult.isSuccess) {
                                val row = rowResult.getOrThrow()
                                if (row.room_id == roomId && row.status.lowercase() == "claimed") {
                                    trySend(true)
                                }
                            }
                        }
                    }
                } catch (_: Exception) {}
            }

            runCatching {
                client.realtime.connect()
                withTimeout(5_000) {
                    channel.subscribe(blockUntilSubscribed = true)
                }
            }
        }

        awaitClose {
            collectJob.cancel()
            scope.launch {
                runCatching {
                    channel.unsubscribe()
                    client.realtime.removeChannel(channel)
                }
            }
        }
    }

    private fun preparePlaybackSyncEnvelope(sessionId: String, roomId: String?) {
        val syncState = playbackSyncStateFlow?.value
        if (syncState != null) {
            val envelope = TvPlaybackSyncEnvelope(
                pairingSessionId = sessionId,
                roomId = roomId,
                channelKey = null,
                contentTimestampMs = syncState.contentTimestampMs,
                wallClockSampleMs = syncState.wallClockSampleMs,
                liveOffsetMs = syncState.liveOffsetMs,
                confidence = syncState.confidence,
                playerType = syncState.playerType,
                isPlaying = syncState.isPlaying,
                isBuffering = syncState.isBuffering
            )
        }
    }
}
