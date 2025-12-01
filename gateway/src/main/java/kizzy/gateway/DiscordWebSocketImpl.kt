package kizzy.gateway

import com.my.kizzy.domain.interfaces.Logger
import com.my.kizzy.domain.interfaces.NoOpLogger
import io.ktor.client.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kizzy.gateway.entities.Heartbeat
import kizzy.gateway.entities.Identify.Companion.toIdentifyPayload
import kizzy.gateway.entities.Payload
import kizzy.gateway.entities.Ready
import kizzy.gateway.entities.Resume
import kizzy.gateway.entities.op.OpCode
import kizzy.gateway.entities.op.OpCode.*
import kizzy.gateway.entities.presence.Presence
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.milliseconds

open class DiscordWebSocketImpl(
    private val token: String,
    private val logger: Logger = NoOpLogger
) : DiscordWebSocket {
    private val gatewayUrl = "wss://gateway.discord.gg/?v=10&encoding=json"
    private var websocket: DefaultClientWebSocketSession? = null
    private var sequence = 0
    private var sessionId: String? = null
    private var heartbeatInterval = 0L
    private var resumeGatewayUrl: String? = null
    private var heartbeatJob: Job? = null
    private val _connected = MutableStateFlow(false)
    private var client: HttpClient = HttpClient {
        install(WebSockets)
    }
    private val json = Json{
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    
    // Exponential backoff para reconexão
    private var reconnectAttempts = 0
    private val baseReconnectDelay = 1000L // 1 segundo
    private val maxReconnectDelay = 60000L // 60 segundos
    private val maxReconnectAttempts = 10

    override val coroutineContext: CoroutineContext
        get() = SupervisorJob() + Dispatchers.Default

    override suspend fun connect() {
        launch {
            try {
                logger.i("Gateway","Connect called")
                val url = resumeGatewayUrl ?: gatewayUrl
                websocket = client.webSocketSession(url)

                // start receiving messages
                websocket!!.incoming.receiveAsFlow()
                    .collect {
                        when (it) {
                            is Frame.Text -> {
                                val jsonString = it.readText()
                                onMessage(json.decodeFromString(jsonString))
                            }
                            else -> {}
                        }
                    }
                handleClose()
            } catch (e: Exception) {
                logger.e("Gateway",e.message?:"")
                close()
            }
        }
    }

    private suspend fun handleClose(){
        heartbeatJob?.cancel()
        _connected.value = false
        val close = websocket?.closeReason?.await()
        val closeCode = close?.code?.toInt() ?: 0
        
        logger.w("Gateway","Closed with code: $closeCode, " +
                "reason: ${close?.message}, " +
                "reconnect_attempt: $reconnectAttempts")
        
        // Códigos que permitem reconexão
        val canReconnect = closeCode in listOf(4000, 4001, 4002, 4003, 4005, 4007, 4008, 4009)
        
        if (canReconnect && reconnectAttempts < maxReconnectAttempts) {
            val delayMs = calculateBackoffDelay()
            logger.i("Gateway", "Reconnecting in ${delayMs}ms (attempt ${reconnectAttempts + 1}/$maxReconnectAttempts)")
            delay(delayMs.milliseconds)
            reconnectAttempts++
            connect()
        } else {
            if (reconnectAttempts >= maxReconnectAttempts) {
                logger.e("Gateway", "Max reconnect attempts reached, giving up")
            }
            close()
        }
    }
    
    /**
     * Calcula o delay com exponential backoff + jitter
     */
    private fun calculateBackoffDelay(): Long {
        val exponentialDelay = baseReconnectDelay * (1 shl minOf(reconnectAttempts, 6))
        val cappedDelay = minOf(exponentialDelay, maxReconnectDelay)
        // Adiciona jitter de 0-25% para evitar thundering herd
        val jitter = (cappedDelay * 0.25 * Math.random()).toLong()
        return cappedDelay + jitter
    }
    
    /**
     * Reseta o contador de reconexão após conexão bem-sucedida
     */
    private fun resetReconnectAttempts() {
        reconnectAttempts = 0
    }

    private suspend fun onMessage(payload: Payload) {
        logger.d("Gateway","Received op:${payload.op}, seq:${payload.s}, event :${payload.t}")

        payload.s?.let {
            sequence = it
        }
        when (payload.op) {
            DISPATCH -> payload.handleDispatch()
            HEARTBEAT -> sendHeartBeat()
            RECONNECT -> reconnectWebSocket()
            INVALID_SESSION -> handleInvalidSession()
            HELLO -> payload.handleHello()
            else -> {}
        }
    }

    open suspend fun Payload.handleDispatch() {
        when (this.t.toString()) {
            "READY" -> {
                val ready = json.decodeFromJsonElement<Ready>(this.d!!)
                sessionId = ready.sessionId
                resumeGatewayUrl = ready.resumeGatewayUrl + "/?v=10&encoding=json"
                logger.i("Gateway","resume_gateway_url updated to $resumeGatewayUrl")
                logger.i("Gateway","session_id updated to $sessionId")
                _connected.value = true
                resetReconnectAttempts() // Conexão bem-sucedida, reseta backoff
                return
            }
            "RESUMED" -> {
                logger.i("Gateway","Session Resumed")
                resetReconnectAttempts() // Sessão resumida com sucesso
            }
            else -> {}
        }
    }

    private suspend inline fun handleInvalidSession() {
        logger.i("Gateway","Handling Invalid Session")
        logger.d("Gateway","Sending Identify after 150ms")
        delay(150)
        sendIdentify()
    }

    private suspend inline fun Payload.handleHello() {
        if (sequence > 0 && !sessionId.isNullOrBlank()) {
            sendResume()
        } else {
            sendIdentify()
        }
        heartbeatInterval =  json.decodeFromJsonElement<Heartbeat>(this.d!!).heartbeatInterval
        logger.i("Gateway","Setting heartbeatInterval= $heartbeatInterval")
        startHeartbeatJob(heartbeatInterval)
    }

    private suspend fun sendHeartBeat() {
        logger.i("Gateway","Sending $HEARTBEAT with seq: $sequence")
        send(
            op = HEARTBEAT,
            d = if (sequence == 0) "null" else sequence.toString(),
        )
    }

    private suspend inline fun reconnectWebSocket() {
        websocket?.close(
            CloseReason(
                code = 4000,
                message = "Attempting to reconnect"
            )
        )
    }

    private suspend fun sendIdentify() {
        logger.i("Gateway","Sending $IDENTIFY")
        send(
            op = IDENTIFY,
            d = token.toIdentifyPayload()
        )
    }

    private suspend fun sendResume() {
        logger.i("Gateway","Sending $RESUME")
        send(
            op = RESUME,
            d = Resume(
                seq = sequence,
                sessionId = sessionId,
                token = token
            )
        )
    }

    private fun startHeartbeatJob(interval: Long) {
        heartbeatJob?.cancel()
        heartbeatJob = launch {
            while (isActive) {
                sendHeartBeat()
                delay(interval)
            }
        }
    }

    private fun isSocketConnectedToAccount(): Boolean {
        return _connected.value && websocket?.isActive == true
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun isWebSocketConnected(): Boolean {
        return websocket?.incoming != null && websocket?.outgoing?.isClosedForSend == false
    }

    private suspend inline fun <reified T> send(op: OpCode, d: T?) {
        if (websocket?.isActive == true) {
            val payload = json.encodeToString(
                Payload(
                    op = op,
                    d= json.encodeToJsonElement(d),
                )
            )
            websocket?.send(Frame.Text(payload))
        }
    }

    override suspend fun close() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        this.cancel()
        resumeGatewayUrl = null
        sessionId = null
        _connected.value = false
        reconnectAttempts = 0 // Reset para próxima conexão
        websocket?.close()
        logger.i("Gateway","Connection to gateway closed")
    }

    override suspend fun sendActivity(presence: Presence) {
        // Wait for socket to be connected
        _connected.first { it }
        logger.i("Gateway","Sending $PRESENCE_UPDATE")
        send(
            op = PRESENCE_UPDATE,
            d = presence
        )
    }

}