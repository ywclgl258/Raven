package com.raven.app.feature.settings.lansync

import com.raven.app.core.common.AppDispatchers
import com.raven.app.core.database.model.BackupSummary as DatabaseBackupSummary
import com.raven.app.core.database.repository.BackupDataRepository
import com.raven.app.core.model.RavenBackupPayload
import com.raven.app.core.security.BackupSummary as SecurityBackupSummary
import com.raven.app.core.security.BackupValidator
import com.raven.app.core.security.LanSyncCrypto
import com.raven.app.core.security.LanSyncSessionKeys
import com.raven.app.core.security.LanSyncTrustStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.GeneralSecurityException
import java.security.SecureRandom

data class LanSyncIncomingEvent(
    val peerDeviceId: String,
    val peerDeviceName: String,
    val mode: LanSyncMode,
    val completedAtMs: Long,
    val mergedSummary: DatabaseBackupSummary?,
)

sealed interface LanSyncOutcome {
    data class Completed(
        val peerDeviceId: String,
        val peerDeviceName: String,
        val mode: LanSyncMode,
        val mergedSummary: DatabaseBackupSummary,
        val pairedNewDevice: Boolean,
        val syncedAtMs: Long,
    ) : LanSyncOutcome

    data class PullPreviewReady(
        val peerDeviceId: String,
        val peerDeviceName: String,
        val peerHost: String,
        val peerPort: Int,
        val peerAppVersion: String,
        val payload: RavenBackupPayload,
        val summary: SecurityBackupSummary,
        val warnings: List<String>,
        val pairedNewDevice: Boolean,
        val fetchedAtMs: Long,
    ) : LanSyncOutcome
}

internal object LanSyncEnvelopeCipher {
    fun buildAad(
        frameType: Byte,
        sequence: Int,
        clientDeviceId: String,
        serverDeviceId: String,
    ): ByteArray = buildString {
        append(LanSyncConstants.PROTOCOL_VERSION)
        append('|')
        append(frameType.toInt())
        append('|')
        append(sequence)
        append('|')
        append(clientDeviceId)
        append('|')
        append(serverDeviceId)
    }.toByteArray(Charsets.UTF_8)

    fun encryptJsonPayload(
        sessionKey: ByteArray,
        frameType: Byte,
        sequence: Int,
        clientDeviceId: String,
        serverDeviceId: String,
        plaintext: ByteArray,
        random: SecureRandom,
    ): ByteArray {
        val iv = LanSyncCrypto.randomBytes(LanSyncCrypto.IV_BYTES, random)
        val aad = buildAad(frameType, sequence, clientDeviceId, serverDeviceId)
        val ciphertext = LanSyncCrypto.encryptFrame(
            sessionKey = sessionKey,
            iv = iv,
            aad = aad,
            plaintext = plaintext,
        )
        val envelope = EncryptedSnapshotEnvelope(
            ivBase64 = LanSyncCrypto.encodeBase64(iv),
            ciphertextBase64 = LanSyncCrypto.encodeBase64(ciphertext),
        )
        return LanSyncJson.codec.encodeToString(envelope).toByteArray(Charsets.UTF_8)
    }

    fun decryptEnvelope(
        sessionKey: ByteArray,
        frameType: Byte,
        sequence: Int,
        clientDeviceId: String,
        serverDeviceId: String,
        rawEnvelopeBytes: ByteArray,
    ): ByteArray {
        val envelope = try {
            LanSyncJson.codec.decodeFromString<EncryptedSnapshotEnvelope>(
                String(rawEnvelopeBytes, Charsets.UTF_8),
            )
        } catch (e: Exception) {
            throw LanSyncException(LanSyncErrorCode.INVALID_PROTOCOL, "无法解析加密数据信封", e)
        }
        val iv = try {
            LanSyncCrypto.decodeBase64(envelope.ivBase64)
        } catch (e: IllegalArgumentException) {
            throw LanSyncException(LanSyncErrorCode.INVALID_PROTOCOL, "加密 IV 格式无效", e)
        }
        val ciphertext = try {
            LanSyncCrypto.decodeBase64(envelope.ciphertextBase64)
        } catch (e: IllegalArgumentException) {
            throw LanSyncException(LanSyncErrorCode.INVALID_PROTOCOL, "加密密文格式无效", e)
        }
        val aad = buildAad(frameType, sequence, clientDeviceId, serverDeviceId)
        return try {
            LanSyncCrypto.decryptFrame(
                sessionKey = sessionKey,
                iv = iv,
                aad = aad,
                ciphertext = ciphertext,
            )
        } catch (e: GeneralSecurityException) {
            throw LanSyncException(LanSyncErrorCode.AUTH_FAILED, "同步数据密文认证失败", e)
        }
    }

    fun encodeCompressedBackup(payload: RavenBackupPayload): ByteArray {
        val jsonBytes = LanSyncJson.codec.encodeToString(payload).toByteArray(Charsets.UTF_8)
        return try {
            if (jsonBytes.size > RavenBackupPayload.MAX_PLAINTEXT_BYTES) {
                throw LanSyncException(
                    errorCode = LanSyncErrorCode.PAYLOAD_TOO_LARGE,
                    message = "本机同步数据超过 16 MiB 上限",
                )
            }
            LanSyncCrypto.gzipCompress(jsonBytes)
        } finally {
            jsonBytes.fill(0)
        }
    }

    fun decodeCompressedBackup(
        compressed: ByteArray,
        validator: BackupValidator,
    ): Pair<RavenBackupPayload, SecurityBackupSummary> {
        val plain = try {
            LanSyncCrypto.gzipDecompressBounded(compressed, RavenBackupPayload.MAX_PLAINTEXT_BYTES)
        } catch (e: IOException) {
            throw LanSyncException(
                errorCode = LanSyncErrorCode.PAYLOAD_TOO_LARGE,
                message = e.message ?: "解压同步快照失败",
                cause = e,
            )
        }
        return try {
            val payload = try {
                LanSyncJson.codec.decodeFromString<RavenBackupPayload>(String(plain, Charsets.UTF_8))
            } catch (e: SerializationException) {
                throw LanSyncException(
                    errorCode = LanSyncErrorCode.VALIDATION_FAILED,
                    message = "对端快照 JSON 格式无效",
                    cause = e,
                )
            }
            val summary = try {
                validator.validate(payload)
            } catch (e: Exception) {
                throw LanSyncException(
                    errorCode = LanSyncErrorCode.VALIDATION_FAILED,
                    message = e.message ?: "对端快照校验未通过",
                    cause = e,
                )
            }
            payload to summary
        } finally {
            plain.fill(0)
        }
    }
}

/**
 * Embedded LAN TCP sync server accepting RFC1918 peer connections (and loopback in unit tests).
 */
class LanSyncServer(
    private val backupDataRepository: BackupDataRepository,
    private val backupValidator: BackupValidator,
    private val trustStore: LanSyncTrustStore,
    private val appVersion: String,
    private val dispatchers: AppDispatchers,
    private val defaultDeviceName: String = LanSyncTrustStore.DEFAULT_DEVICE_NAME,
    private val pbkdf2Iterations: Int = LanSyncCrypto.PIN_PBKDF2_ITERATIONS,
    private val allowLoopbackForTest: Boolean = false,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
) {
    private val stateLock = Any()
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private var activePin: String = LanSyncCrypto.generatePairingPin(random)
    private val failedPinAttemptTimestamps = ArrayDeque<Long>()

    fun isRunning(): Boolean = synchronized(stateLock) {
        serverSocket?.isClosed == false && acceptJob?.isActive == true
    }

    fun listeningPort(): Int = synchronized(stateLock) {
        serverSocket?.localPort ?: 0
    }

    fun currentPin(): String = synchronized(stateLock) { activePin }

    fun rotatePin(): String = synchronized(stateLock) {
        val next = LanSyncCrypto.generatePairingPin(random)
        activePin = next
        failedPinAttemptTimestamps.clear()
        next
    }

    suspend fun start(
        scope: CoroutineScope,
        onIncomingEvent: (LanSyncIncomingEvent) -> Unit = {},
        onPinRotated: (String) -> Unit = {},
    ): Int {
        stop()
        val socket = withContext(dispatchers.io) {
            try {
                ServerSocket(LanSyncConstants.DEFAULT_TCP_PORT).apply {
                    reuseAddress = true
                }
            } catch (_: IOException) {
                ServerSocket(0).apply {
                    reuseAddress = true
                }
            }
        }
        val port = socket.localPort
        val job = scope.launch(dispatchers.io) {
            while (isActive && !socket.isClosed) {
                val clientSocket = try {
                    socket.accept()
                } catch (_: IOException) {
                    break
                }
                launch(dispatchers.io) {
                    handleConnection(
                        socket = clientSocket,
                        onIncomingEvent = onIncomingEvent,
                        onPinRotated = onPinRotated,
                    )
                }
            }
        }
        synchronized(stateLock) {
            serverSocket = socket
            acceptJob = job
        }
        return port
    }

    fun stop() {
        val socketToClose: ServerSocket?
        val jobToCancel: Job?
        synchronized(stateLock) {
            socketToClose = serverSocket
            jobToCancel = acceptJob
            serverSocket = null
            acceptJob = null
        }
        runCatching { socketToClose?.close() }
        jobToCancel?.cancel()
    }

    private suspend fun handleConnection(
        socket: Socket,
        onIncomingEvent: (LanSyncIncomingEvent) -> Unit,
        onPinRotated: (String) -> Unit,
    ) {
        socket.use { client ->
            client.soTimeout = LanSyncConstants.SOCKET_READ_TIMEOUT_MS
            val remoteIp = client.inetAddress?.hostAddress.orEmpty()
            if (!LanSyncAddressValidator.isAllowedLanIpv4(remoteIp, allowLoopbackForTest)) {
                return
            }

            val input = BufferedInputStream(client.getInputStream())
            val output = BufferedOutputStream(client.getOutputStream())
            var sessionKeys: LanSyncSessionKeys? = null
            try {
                val helloFrame = LanSyncFrameCodec.readFrame(input, LanSyncConstants.MAX_HANDSHAKE_FRAME_BYTES)
                if (helloFrame.type != LanSyncFrameCodec.FRAME_HELLO) {
                    sendError(output, LanSyncErrorCode.INVALID_PROTOCOL, "握手阶段收到非 HELLO 数据帧")
                    return
                }
                val hello = try {
                    LanSyncJson.codec.decodeFromString<HelloPayload>(String(helloFrame.payload, Charsets.UTF_8))
                } catch (_: Exception) {
                    sendError(output, LanSyncErrorCode.INVALID_PROTOCOL, "HELLO 握手载荷格式无效")
                    return
                }
                if (hello.protocolVersion != PROTOCOL_VERSION || hello.clientDeviceId.isBlank()) {
                    sendError(output, LanSyncErrorCode.INVALID_PROTOCOL, "不支持的同步协议或客户端标识")
                    return
                }

                val clientNonce = runCatching { LanSyncCrypto.decodeBase64(hello.clientNonceBase64) }.getOrNull()
                val clientPubBytes = runCatching { LanSyncCrypto.decodeBase64(hello.clientPublicKeyBase64) }.getOrNull()
                if (clientNonce == null ||
                    clientNonce.size != LanSyncCrypto.NONCE_BYTES ||
                    clientPubBytes == null ||
                    clientPubBytes.isEmpty() ||
                    clientPubBytes.size > LanSyncCrypto.MAX_EC_PUBLIC_KEY_BYTES
                ) {
                    sendError(output, LanSyncErrorCode.INVALID_PROTOCOL, "客户端握手随机数或公钥无效")
                    return
                }

                if (hello.authMode == LanAuthMode.PIN && isPinRateLimited()) {
                    sendError(output, LanSyncErrorCode.RATE_LIMITED, "配对码连续失败次数过多，已自动更换配对码")
                    return
                }

                val trustState = trustStore.loadOrCreate(defaultDeviceName)
                val serverDeviceId = trustState.localDeviceId
                val serverDeviceName = trustState.localDeviceName

                val authSecret = when (hello.authMode) {
                    LanAuthMode.TRUSTED -> {
                        val trusted = trustStore.findTrustedPeer(hello.clientDeviceId)
                        if (trusted == null) {
                            sendError(output, LanSyncErrorCode.NOT_TRUSTED, "本机尚未信任对方设备，请先使用 6 位配对码配对")
                            return
                        }
                        runCatching { trusted.decodeSharedSecret() }.getOrElse {
                            sendError(output, LanSyncErrorCode.AUTH_FAILED, "本机保存的设备信任密钥已失效")
                            return
                        }
                    }
                    LanAuthMode.PIN -> null
                }

                val serverKeyPair = LanSyncCrypto.generateEphemeralKeyPair(random)
                val serverPubBytes = LanSyncCrypto.encodePublicKey(serverKeyPair.public)
                val serverNonce = LanSyncCrypto.randomBytes(LanSyncCrypto.NONCE_BYTES, random)

                val ecdhSecret = try {
                    LanSyncCrypto.computeEcdhSharedSecret(serverKeyPair.private, clientPubBytes)
                } catch (_: Exception) {
                    sendError(output, LanSyncErrorCode.INVALID_PROTOCOL, "ECDH 公钥校验失败")
                    return
                }

                val resolvedAuthSecret = try {
                    authSecret ?: run {
                        val pinChars = currentPin().toCharArray()
                        val salt = LanSyncCrypto.computePinSalt(clientNonce, serverNonce, serverDeviceId)
                        try {
                            LanSyncCrypto.derivePinAuthSecret(pinChars, salt, pbkdf2Iterations)
                        } finally {
                            pinChars.fill('\u0000')
                        }
                    }
                } catch (e: Exception) {
                    ecdhSecret.fill(0)
                    sendError(output, LanSyncErrorCode.INTERNAL_ERROR, e.message ?: "密钥派生失败")
                    return
                }

                val transcriptHash = LanSyncCrypto.computeTranscriptHash(
                    clientDeviceId = hello.clientDeviceId,
                    serverDeviceId = serverDeviceId,
                    syncMode = hello.syncMode.name,
                    authMode = hello.authMode.name,
                    clientNonce = clientNonce,
                    serverNonce = serverNonce,
                    clientPublicKey = clientPubBytes,
                    serverPublicKey = serverPubBytes,
                )

                sessionKeys = try {
                    LanSyncCrypto.deriveHandshakeKeys(
                        ecdhSecret = ecdhSecret,
                        authSecretBytes = resolvedAuthSecret,
                        clientNonce = clientNonce,
                        serverNonce = serverNonce,
                        clientPublicKey = clientPubBytes,
                        serverPublicKey = serverPubBytes,
                        transcriptHash = transcriptHash,
                    )
                } finally {
                    ecdhSecret.fill(0)
                    resolvedAuthSecret.fill(0)
                }

                val helloAck = HelloAckPayload(
                    serverDeviceId = serverDeviceId,
                    serverDeviceName = serverDeviceName,
                    serverAppVersion = appVersion,
                    serverNonceBase64 = LanSyncCrypto.encodeBase64(serverNonce),
                    serverPublicKeyBase64 = LanSyncCrypto.encodeBase64(serverPubBytes),
                )
                LanSyncFrameCodec.writeFrame(
                    output,
                    LanSyncFrameCodec.FRAME_HELLO_ACK,
                    LanSyncJson.codec.encodeToString(helloAck).toByteArray(Charsets.UTF_8),
                )

                val confirmFrame = LanSyncFrameCodec.readFrame(input, LanSyncConstants.MAX_HANDSHAKE_FRAME_BYTES)
                if (confirmFrame.type != LanSyncFrameCodec.FRAME_AUTH_CONFIRM) {
                    sendError(output, LanSyncErrorCode.INVALID_PROTOCOL, "期望收到 AUTH_CONFIRM 帧")
                    return
                }
                val confirm = try {
                    LanSyncJson.codec.decodeFromString<AuthConfirmPayload>(String(confirmFrame.payload, Charsets.UTF_8))
                } catch (_: Exception) {
                    sendError(output, LanSyncErrorCode.INVALID_PROTOCOL, "认证确认帧格式无效")
                    return
                }
                val clientTag = runCatching { LanSyncCrypto.decodeBase64(confirm.clientAuthTagBase64) }.getOrNull()
                if (clientTag == null || !LanSyncCrypto.constantTimeEquals(clientTag, sessionKeys.clientAuthTag)) {
                    if (hello.authMode == LanAuthMode.PIN) {
                        val rotated = recordPinFailureAndMaybeRotate()
                        if (rotated != null) {
                            onPinRotated(rotated)
                            sendError(output, LanSyncErrorCode.RATE_LIMITED, "配对码连续失败次数过多，已自动更换配对码")
                            return
                        }
                    }
                    sendError(
                        output,
                        LanSyncErrorCode.AUTH_FAILED,
                        if (hello.authMode == LanAuthMode.PIN) "配对码不正确" else "设备信任凭据不匹配，请重新配对",
                    )
                    return
                }

                clearPinFailures()

                val authOk = AuthOkPayload(
                    serverAuthTagBase64 = LanSyncCrypto.encodeBase64(sessionKeys.serverAuthTag),
                )
                LanSyncFrameCodec.writeFrame(
                    output,
                    LanSyncFrameCodec.FRAME_AUTH_OK,
                    LanSyncJson.codec.encodeToString(authOk).toByteArray(Charsets.UTF_8),
                )

                val peerName = LanSyncTrustStore.normalizeDeviceName(hello.clientDeviceName)
                if (hello.authMode == LanAuthMode.PIN && hello.rememberDevice) {
                    trustStore.saveTrustedPeer(
                        deviceId = hello.clientDeviceId,
                        deviceName = peerName,
                        sharedSecretBytes = sessionKeys.trustedSharedSecret,
                        host = remoteIp,
                        port = 0,
                        nowMs = clock(),
                    )
                }

                executeServerTransfer(
                    syncMode = hello.syncMode,
                    clientDeviceId = hello.clientDeviceId,
                    serverDeviceId = serverDeviceId,
                    peerName = peerName,
                    remoteIp = remoteIp,
                    sessionKey = sessionKeys.sessionKey,
                    input = input,
                    output = output,
                    onIncomingEvent = onIncomingEvent,
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: LanSyncException) {
                runCatching {
                    sendError(output, e.errorCode ?: LanSyncErrorCode.INTERNAL_ERROR, e.message ?: "同步失败")
                }
            } catch (e: Exception) {
                runCatching {
                    sendError(output, LanSyncErrorCode.INTERNAL_ERROR, e.message ?: "局域网同步服务异常")
                }
            } finally {
                sessionKeys?.wipe()
            }
        }
    }

    private suspend fun executeServerTransfer(
        syncMode: LanSyncMode,
        clientDeviceId: String,
        serverDeviceId: String,
        peerName: String,
        remoteIp: String,
        sessionKey: ByteArray,
        input: BufferedInputStream,
        output: BufferedOutputStream,
        onIncomingEvent: (LanSyncIncomingEvent) -> Unit,
    ) {
        when (syncMode) {
            LanSyncMode.TWO_WAY -> {
                val frame = LanSyncFrameCodec.readFrame(input, LanSyncConstants.MAX_ENCRYPTED_FRAME_BYTES)
                if (frame.type != LanSyncFrameCodec.FRAME_DATA_SNAPSHOT) {
                    sendError(output, LanSyncErrorCode.INVALID_PROTOCOL, "双向同步阶段期望收到数据快照")
                    return
                }
                val compressed = LanSyncEnvelopeCipher.decryptEnvelope(
                    sessionKey = sessionKey,
                    frameType = LanSyncFrameCodec.FRAME_DATA_SNAPSHOT,
                    sequence = 1,
                    clientDeviceId = clientDeviceId,
                    serverDeviceId = serverDeviceId,
                    rawEnvelopeBytes = frame.payload,
                )
                val (incomingPayload, _) = LanSyncEnvelopeCipher.decodeCompressedBackup(compressed, backupValidator)
                val mergedSummary = backupDataRepository.applyBackup(incomingPayload, appVersion = appVersion)
                val mergedPayload = backupDataRepository.exportPayload(appVersion = appVersion)
                val mergedCompressed = LanSyncEnvelopeCipher.encodeCompressedBackup(mergedPayload)
                val responseEnvelope = LanSyncEnvelopeCipher.encryptJsonPayload(
                    sessionKey = sessionKey,
                    frameType = LanSyncFrameCodec.FRAME_DATA_SNAPSHOT,
                    sequence = 2,
                    clientDeviceId = clientDeviceId,
                    serverDeviceId = serverDeviceId,
                    plaintext = mergedCompressed,
                    random = random,
                )
                LanSyncFrameCodec.writeFrame(output, LanSyncFrameCodec.FRAME_DATA_SNAPSHOT, responseEnvelope)
                val now = clock()
                trustStore.recordSyncSuccess(clientDeviceId, peerName, host = remoteIp, port = 0, nowMs = now)
                onIncomingEvent(
                    LanSyncIncomingEvent(
                        peerDeviceId = clientDeviceId,
                        peerDeviceName = peerName,
                        mode = syncMode,
                        completedAtMs = now,
                        mergedSummary = mergedSummary,
                    ),
                )
            }

            LanSyncMode.PUSH -> {
                val frame = LanSyncFrameCodec.readFrame(input, LanSyncConstants.MAX_ENCRYPTED_FRAME_BYTES)
                if (frame.type != LanSyncFrameCodec.FRAME_DATA_SNAPSHOT) {
                    sendError(output, LanSyncErrorCode.INVALID_PROTOCOL, "接收推送阶段期望收到数据快照")
                    return
                }
                val compressed = LanSyncEnvelopeCipher.decryptEnvelope(
                    sessionKey = sessionKey,
                    frameType = LanSyncFrameCodec.FRAME_DATA_SNAPSHOT,
                    sequence = 1,
                    clientDeviceId = clientDeviceId,
                    serverDeviceId = serverDeviceId,
                    rawEnvelopeBytes = frame.payload,
                )
                val (incomingPayload, _) = LanSyncEnvelopeCipher.decodeCompressedBackup(compressed, backupValidator)
                val mergedSummary = backupDataRepository.applyBackup(incomingPayload, appVersion = appVersion)
                val ackJson = LanSyncJson.codec.encodeToString(
                    SyncAckPayload(mergedSummary = BackupSummarySnapshot.from(mergedSummary)),
                ).toByteArray(Charsets.UTF_8)
                val responseEnvelope = LanSyncEnvelopeCipher.encryptJsonPayload(
                    sessionKey = sessionKey,
                    frameType = LanSyncFrameCodec.FRAME_SYNC_ACK,
                    sequence = 2,
                    clientDeviceId = clientDeviceId,
                    serverDeviceId = serverDeviceId,
                    plaintext = ackJson,
                    random = random,
                )
                LanSyncFrameCodec.writeFrame(output, LanSyncFrameCodec.FRAME_SYNC_ACK, responseEnvelope)
                val now = clock()
                trustStore.recordSyncSuccess(clientDeviceId, peerName, host = remoteIp, port = 0, nowMs = now)
                onIncomingEvent(
                    LanSyncIncomingEvent(
                        peerDeviceId = clientDeviceId,
                        peerDeviceName = peerName,
                        mode = syncMode,
                        completedAtMs = now,
                        mergedSummary = mergedSummary,
                    ),
                )
            }

            LanSyncMode.PULL -> {
                val localPayload = backupDataRepository.exportPayload(appVersion = appVersion)
                val compressed = LanSyncEnvelopeCipher.encodeCompressedBackup(localPayload)
                val responseEnvelope = LanSyncEnvelopeCipher.encryptJsonPayload(
                    sessionKey = sessionKey,
                    frameType = LanSyncFrameCodec.FRAME_DATA_SNAPSHOT,
                    sequence = 1,
                    clientDeviceId = clientDeviceId,
                    serverDeviceId = serverDeviceId,
                    plaintext = compressed,
                    random = random,
                )
                LanSyncFrameCodec.writeFrame(output, LanSyncFrameCodec.FRAME_DATA_SNAPSHOT, responseEnvelope)
                val now = clock()
                trustStore.recordSyncSuccess(clientDeviceId, peerName, host = remoteIp, port = 0, nowMs = now)
                onIncomingEvent(
                    LanSyncIncomingEvent(
                        peerDeviceId = clientDeviceId,
                        peerDeviceName = peerName,
                        mode = syncMode,
                        completedAtMs = now,
                        mergedSummary = null,
                    ),
                )
            }
        }
    }

    private fun isPinRateLimited(): Boolean = synchronized(stateLock) {
        val cutoff = clock() - PIN_FAILURE_WINDOW_MS
        while (failedPinAttemptTimestamps.isNotEmpty() && failedPinAttemptTimestamps.first() < cutoff) {
            failedPinAttemptTimestamps.removeFirst()
        }
        failedPinAttemptTimestamps.size >= MAX_PIN_FAILURES_IN_WINDOW
    }

    private fun recordPinFailureAndMaybeRotate(): String? = synchronized(stateLock) {
        val now = clock()
        val cutoff = now - PIN_FAILURE_WINDOW_MS
        while (failedPinAttemptTimestamps.isNotEmpty() && failedPinAttemptTimestamps.first() < cutoff) {
            failedPinAttemptTimestamps.removeFirst()
        }
        failedPinAttemptTimestamps.addLast(now)
        if (failedPinAttemptTimestamps.size >= MAX_PIN_FAILURES_IN_WINDOW) {
            val next = LanSyncCrypto.generatePairingPin(random)
            activePin = next
            failedPinAttemptTimestamps.clear()
            next
        } else {
            null
        }
    }

    private fun clearPinFailures() = synchronized(stateLock) {
        failedPinAttemptTimestamps.clear()
    }

    private fun sendError(output: BufferedOutputStream, code: LanSyncErrorCode, message: String) {
        val bytes = LanSyncJson.codec.encodeToString(ErrorPayload(code = code, message = message))
            .toByteArray(Charsets.UTF_8)
        LanSyncFrameCodec.writeFrame(output, LanSyncFrameCodec.FRAME_ERROR, bytes)
    }

    companion object {
        private const val PIN_FAILURE_WINDOW_MS: Long = 60_000L
        private const val MAX_PIN_FAILURES_IN_WINDOW: Int = 5
    }
}

/**
 * Initiates authenticated, end-to-end encrypted LAN sync sessions with another Raven device.
 */
class LanSyncClient(
    private val backupDataRepository: BackupDataRepository,
    private val backupValidator: BackupValidator,
    private val trustStore: LanSyncTrustStore,
    private val appVersion: String,
    private val dispatchers: AppDispatchers,
    private val defaultDeviceName: String = LanSyncTrustStore.DEFAULT_DEVICE_NAME,
    private val pbkdf2Iterations: Int = LanSyncCrypto.PIN_PBKDF2_ITERATIONS,
    private val allowLoopbackForTest: Boolean = false,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
) {

    suspend fun syncTwoWay(
        host: String,
        port: Int,
        expectedDeviceId: String? = null,
        pin: String? = null,
        rememberDevice: Boolean = true,
    ): LanSyncOutcome.Completed = executeSync(
        mode = LanSyncMode.TWO_WAY,
        host = host,
        port = port,
        expectedDeviceId = expectedDeviceId,
        pin = pin,
        rememberDevice = rememberDevice,
    ) as LanSyncOutcome.Completed

    suspend fun pushSnapshot(
        host: String,
        port: Int,
        expectedDeviceId: String? = null,
        pin: String? = null,
        rememberDevice: Boolean = true,
    ): LanSyncOutcome.Completed = executeSync(
        mode = LanSyncMode.PUSH,
        host = host,
        port = port,
        expectedDeviceId = expectedDeviceId,
        pin = pin,
        rememberDevice = rememberDevice,
    ) as LanSyncOutcome.Completed

    /**
     * Pulls and decrypts the remote snapshot without mutating the local database until
     * [applyConfirmedPull] is called.
     */
    suspend fun pullPreview(
        host: String,
        port: Int,
        expectedDeviceId: String? = null,
        pin: String? = null,
        rememberDevice: Boolean = true,
    ): LanSyncOutcome.PullPreviewReady = executeSync(
        mode = LanSyncMode.PULL,
        host = host,
        port = port,
        expectedDeviceId = expectedDeviceId,
        pin = pin,
        rememberDevice = rememberDevice,
    ) as LanSyncOutcome.PullPreviewReady

    suspend fun applyConfirmedPull(preview: LanSyncOutcome.PullPreviewReady): LanSyncOutcome.Completed =
        withContext(dispatchers.io) {
            val summary = backupDataRepository.applyBackup(preview.payload, appVersion = appVersion)
            val now = clock()
            trustStore.recordSyncSuccess(
                deviceId = preview.peerDeviceId,
                deviceName = preview.peerDeviceName,
                host = preview.peerHost,
                port = preview.peerPort,
                nowMs = now,
            )
            LanSyncOutcome.Completed(
                peerDeviceId = preview.peerDeviceId,
                peerDeviceName = preview.peerDeviceName,
                mode = LanSyncMode.PULL,
                mergedSummary = summary,
                pairedNewDevice = preview.pairedNewDevice,
                syncedAtMs = now,
            )
        }

    private suspend fun executeSync(
        mode: LanSyncMode,
        host: String,
        port: Int,
        expectedDeviceId: String?,
        pin: String?,
        rememberDevice: Boolean,
    ): LanSyncOutcome = withContext(dispatchers.io) {
        val cleanHost = host.trim()
        if (!LanSyncAddressValidator.isAllowedLanIpv4(cleanHost, allowLoopbackForTest)) {
            throw LanSyncException(
                errorCode = LanSyncErrorCode.INVALID_PROTOCOL,
                message = "仅允许连接局域网私有 IPv4 地址 (10.x / 172.16-31.x / 192.168.x)",
            )
        }
        if (port !in 1024..65535) {
            throw LanSyncException(
                errorCode = LanSyncErrorCode.INVALID_PROTOCOL,
                message = "无效的同步端口：$port",
            )
        }

        val normalizedPin = pin?.trim().orEmpty()
        val trustedPeer = if (normalizedPin.isEmpty() && !expectedDeviceId.isNullOrBlank()) {
            trustStore.findTrustedPeer(expectedDeviceId)
        } else {
            null
        }
        val authMode = if (trustedPeer != null && normalizedPin.isEmpty()) {
            LanAuthMode.TRUSTED
        } else {
            LanAuthMode.PIN
        }
        if (authMode == LanAuthMode.PIN &&
            (normalizedPin.length != LanSyncCrypto.PIN_LENGTH || normalizedPin.any { it !in '0'..'9' })
        ) {
            throw LanSyncException(
                errorCode = LanSyncErrorCode.AUTH_FAILED,
                message = "请输入对端设备显示的 6 位数字配对码",
            )
        }

        val localIdentity = trustStore.loadOrCreate(defaultDeviceName)
        if (!expectedDeviceId.isNullOrBlank() && expectedDeviceId == localIdentity.localDeviceId) {
            throw LanSyncException(
                errorCode = LanSyncErrorCode.INVALID_PROTOCOL,
                message = "不能与当前本机设备发起同步",
            )
        }

        val exportedSnapshot = if (mode == LanSyncMode.TWO_WAY || mode == LanSyncMode.PUSH) {
            val payload = backupDataRepository.exportPayload(appVersion = appVersion)
            backupValidator.validate(payload)
            payload
        } else {
            null
        }

        Socket().use { socket ->
            socket.connect(InetSocketAddress(cleanHost, port), LanSyncConstants.SOCKET_CONNECT_TIMEOUT_MS)
            socket.soTimeout = LanSyncConstants.SOCKET_READ_TIMEOUT_MS
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())
            var sessionKeys: LanSyncSessionKeys? = null
            val pinChars = if (authMode == LanAuthMode.PIN) normalizedPin.toCharArray() else CharArray(0)

            try {
                val clientKeyPair = LanSyncCrypto.generateEphemeralKeyPair(random)
                val clientPubBytes = LanSyncCrypto.encodePublicKey(clientKeyPair.public)
                val clientNonce = LanSyncCrypto.randomBytes(LanSyncCrypto.NONCE_BYTES, random)

                val hello = HelloPayload(
                    clientDeviceId = localIdentity.localDeviceId,
                    clientDeviceName = localIdentity.localDeviceName,
                    clientAppVersion = appVersion,
                    syncMode = mode,
                    authMode = authMode,
                    rememberDevice = rememberDevice,
                    clientNonceBase64 = LanSyncCrypto.encodeBase64(clientNonce),
                    clientPublicKeyBase64 = LanSyncCrypto.encodeBase64(clientPubBytes),
                )
                LanSyncFrameCodec.writeFrame(
                    output,
                    LanSyncFrameCodec.FRAME_HELLO,
                    LanSyncJson.codec.encodeToString(hello).toByteArray(Charsets.UTF_8),
                )

                val ackFrame = readNonErrorFrame(input, LanSyncConstants.MAX_HANDSHAKE_FRAME_BYTES)
                if (ackFrame.type != LanSyncFrameCodec.FRAME_HELLO_ACK) {
                    throw LanSyncException(LanSyncErrorCode.INVALID_PROTOCOL, "未收到对端有效握手响应")
                }
                val helloAck = try {
                    LanSyncJson.codec.decodeFromString<HelloAckPayload>(String(ackFrame.payload, Charsets.UTF_8))
                } catch (e: Exception) {
                    throw LanSyncException(LanSyncErrorCode.INVALID_PROTOCOL, "对端握手响应格式无效", e)
                }
                if (helloAck.protocolVersion != PROTOCOL_VERSION || helloAck.serverDeviceId.isBlank()) {
                    throw LanSyncException(LanSyncErrorCode.INVALID_PROTOCOL, "对端同步协议版本不兼容")
                }
                if (helloAck.serverDeviceId == localIdentity.localDeviceId) {
                    throw LanSyncException(LanSyncErrorCode.INVALID_PROTOCOL, "检测到连接到本机自身，已中止同步")
                }
                if (!expectedDeviceId.isNullOrBlank() && helloAck.serverDeviceId != expectedDeviceId) {
                    throw LanSyncException(LanSyncErrorCode.AUTH_FAILED, "对端设备身份与预期不一致，已拒绝连接")
                }

                val serverNonce = LanSyncCrypto.decodeBase64(helloAck.serverNonceBase64)
                val serverPubBytes = LanSyncCrypto.decodeBase64(helloAck.serverPublicKeyBase64)
                if (serverNonce.size != LanSyncCrypto.NONCE_BYTES || serverPubBytes.isEmpty()) {
                    throw LanSyncException(LanSyncErrorCode.INVALID_PROTOCOL, "对端握手参数无效")
                }

                val ecdhSecret = LanSyncCrypto.computeEcdhSharedSecret(clientKeyPair.private, serverPubBytes)
                val authSecret = when (authMode) {
                    LanAuthMode.PIN -> {
                        val salt = LanSyncCrypto.computePinSalt(clientNonce, serverNonce, helloAck.serverDeviceId)
                        LanSyncCrypto.derivePinAuthSecret(pinChars, salt, pbkdf2Iterations)
                    }
                    LanAuthMode.TRUSTED -> trustedPeer!!.decodeSharedSecret()
                }
                val transcriptHash = LanSyncCrypto.computeTranscriptHash(
                    clientDeviceId = localIdentity.localDeviceId,
                    serverDeviceId = helloAck.serverDeviceId,
                    syncMode = mode.name,
                    authMode = authMode.name,
                    clientNonce = clientNonce,
                    serverNonce = serverNonce,
                    clientPublicKey = clientPubBytes,
                    serverPublicKey = serverPubBytes,
                )

                sessionKeys = try {
                    LanSyncCrypto.deriveHandshakeKeys(
                        ecdhSecret = ecdhSecret,
                        authSecretBytes = authSecret,
                        clientNonce = clientNonce,
                        serverNonce = serverNonce,
                        clientPublicKey = clientPubBytes,
                        serverPublicKey = serverPubBytes,
                        transcriptHash = transcriptHash,
                    )
                } finally {
                    ecdhSecret.fill(0)
                    authSecret.fill(0)
                }

                LanSyncFrameCodec.writeFrame(
                    output,
                    LanSyncFrameCodec.FRAME_AUTH_CONFIRM,
                    LanSyncJson.codec.encodeToString(
                        AuthConfirmPayload(clientAuthTagBase64 = LanSyncCrypto.encodeBase64(sessionKeys.clientAuthTag)),
                    ).toByteArray(Charsets.UTF_8),
                )

                val authOkFrame = readNonErrorFrame(input, LanSyncConstants.MAX_HANDSHAKE_FRAME_BYTES)
                if (authOkFrame.type != LanSyncFrameCodec.FRAME_AUTH_OK) {
                    throw LanSyncException(LanSyncErrorCode.INVALID_PROTOCOL, "未收到对端认证确认")
                }
                val authOk = LanSyncJson.codec.decodeFromString<AuthOkPayload>(String(authOkFrame.payload, Charsets.UTF_8))
                val serverTag = LanSyncCrypto.decodeBase64(authOk.serverAuthTagBase64)
                if (!LanSyncCrypto.constantTimeEquals(serverTag, sessionKeys.serverAuthTag)) {
                    throw LanSyncException(LanSyncErrorCode.AUTH_FAILED, "对端身份签名校验未通过")
                }

                val peerName = LanSyncTrustStore.normalizeDeviceName(helloAck.serverDeviceName)
                val pairedNewDevice = authMode == LanAuthMode.PIN && rememberDevice
                if (pairedNewDevice) {
                    trustStore.saveTrustedPeer(
                        deviceId = helloAck.serverDeviceId,
                        deviceName = peerName,
                        sharedSecretBytes = sessionKeys.trustedSharedSecret,
                        host = cleanHost,
                        port = port,
                        nowMs = clock(),
                    )
                }

                when (mode) {
                    LanSyncMode.TWO_WAY -> {
                        val compressed = LanSyncEnvelopeCipher.encodeCompressedBackup(exportedSnapshot!!)
                        val outEnvelope = LanSyncEnvelopeCipher.encryptJsonPayload(
                            sessionKey = sessionKeys.sessionKey,
                            frameType = LanSyncFrameCodec.FRAME_DATA_SNAPSHOT,
                            sequence = 1,
                            clientDeviceId = localIdentity.localDeviceId,
                            serverDeviceId = helloAck.serverDeviceId,
                            plaintext = compressed,
                            random = random,
                        )
                        LanSyncFrameCodec.writeFrame(output, LanSyncFrameCodec.FRAME_DATA_SNAPSHOT, outEnvelope)

                        val inFrame = readNonErrorFrame(input, LanSyncConstants.MAX_ENCRYPTED_FRAME_BYTES)
                        if (inFrame.type != LanSyncFrameCodec.FRAME_DATA_SNAPSHOT) {
                            throw LanSyncException(LanSyncErrorCode.INVALID_PROTOCOL, "期望收到合并后的加密快照")
                        }
                        val inCompressed = LanSyncEnvelopeCipher.decryptEnvelope(
                            sessionKey = sessionKeys.sessionKey,
                            frameType = LanSyncFrameCodec.FRAME_DATA_SNAPSHOT,
                            sequence = 2,
                            clientDeviceId = localIdentity.localDeviceId,
                            serverDeviceId = helloAck.serverDeviceId,
                            rawEnvelopeBytes = inFrame.payload,
                        )
                        val (mergedRemotePayload, _) = LanSyncEnvelopeCipher.decodeCompressedBackup(
                            inCompressed,
                            backupValidator,
                        )
                        val mergedSummary = backupDataRepository.applyBackup(
                            mergedRemotePayload,
                            appVersion = appVersion,
                        )
                        val now = clock()
                        trustStore.recordSyncSuccess(
                            deviceId = helloAck.serverDeviceId,
                            deviceName = peerName,
                            host = cleanHost,
                            port = port,
                            nowMs = now,
                        )
                        LanSyncOutcome.Completed(
                            peerDeviceId = helloAck.serverDeviceId,
                            peerDeviceName = peerName,
                            mode = LanSyncMode.TWO_WAY,
                            mergedSummary = mergedSummary,
                            pairedNewDevice = pairedNewDevice,
                            syncedAtMs = now,
                        )
                    }

                    LanSyncMode.PUSH -> {
                        val compressed = LanSyncEnvelopeCipher.encodeCompressedBackup(exportedSnapshot!!)
                        val outEnvelope = LanSyncEnvelopeCipher.encryptJsonPayload(
                            sessionKey = sessionKeys.sessionKey,
                            frameType = LanSyncFrameCodec.FRAME_DATA_SNAPSHOT,
                            sequence = 1,
                            clientDeviceId = localIdentity.localDeviceId,
                            serverDeviceId = helloAck.serverDeviceId,
                            plaintext = compressed,
                            random = random,
                        )
                        LanSyncFrameCodec.writeFrame(output, LanSyncFrameCodec.FRAME_DATA_SNAPSHOT, outEnvelope)

                        val ackResponse = readNonErrorFrame(input, LanSyncConstants.MAX_HANDSHAKE_FRAME_BYTES)
                        if (ackResponse.type != LanSyncFrameCodec.FRAME_SYNC_ACK) {
                            throw LanSyncException(LanSyncErrorCode.INVALID_PROTOCOL, "对端未返回推送确认")
                        }
                        val ackPlain = LanSyncEnvelopeCipher.decryptEnvelope(
                            sessionKey = sessionKeys.sessionKey,
                            frameType = LanSyncFrameCodec.FRAME_SYNC_ACK,
                            sequence = 2,
                            clientDeviceId = localIdentity.localDeviceId,
                            serverDeviceId = helloAck.serverDeviceId,
                            rawEnvelopeBytes = ackResponse.payload,
                        )
                        val ack = LanSyncJson.codec.decodeFromString<SyncAckPayload>(String(ackPlain, Charsets.UTF_8))
                        val now = clock()
                        trustStore.recordSyncSuccess(
                            deviceId = helloAck.serverDeviceId,
                            deviceName = peerName,
                            host = cleanHost,
                            port = port,
                            nowMs = now,
                        )
                        LanSyncOutcome.Completed(
                            peerDeviceId = helloAck.serverDeviceId,
                            peerDeviceName = peerName,
                            mode = LanSyncMode.PUSH,
                            mergedSummary = ack.mergedSummary.toDatabaseSummary(now, appVersion),
                            pairedNewDevice = pairedNewDevice,
                            syncedAtMs = now,
                        )
                    }

                    LanSyncMode.PULL -> {
                        val inFrame = readNonErrorFrame(input, LanSyncConstants.MAX_ENCRYPTED_FRAME_BYTES)
                        if (inFrame.type != LanSyncFrameCodec.FRAME_DATA_SNAPSHOT) {
                            throw LanSyncException(LanSyncErrorCode.INVALID_PROTOCOL, "拉取模式期望收到加密快照")
                        }
                        val inCompressed = LanSyncEnvelopeCipher.decryptEnvelope(
                            sessionKey = sessionKeys.sessionKey,
                            frameType = LanSyncFrameCodec.FRAME_DATA_SNAPSHOT,
                            sequence = 1,
                            clientDeviceId = localIdentity.localDeviceId,
                            serverDeviceId = helloAck.serverDeviceId,
                            rawEnvelopeBytes = inFrame.payload,
                        )
                        val (remotePayload, summary) = LanSyncEnvelopeCipher.decodeCompressedBackup(
                            inCompressed,
                            backupValidator,
                        )
                        val warnings = buildPullWarnings(summary)
                        LanSyncOutcome.PullPreviewReady(
                            peerDeviceId = helloAck.serverDeviceId,
                            peerDeviceName = peerName,
                            peerHost = cleanHost,
                            peerPort = port,
                            peerAppVersion = helloAck.serverAppVersion,
                            payload = remotePayload,
                            summary = summary,
                            warnings = warnings,
                            pairedNewDevice = pairedNewDevice,
                            fetchedAtMs = clock(),
                        )
                    }
                }
            } finally {
                pinChars.fill('\u0000')
                sessionKeys?.wipe()
            }
        }
    }

    private fun readNonErrorFrame(input: BufferedInputStream, maxBytes: Int): LanSyncFrameCodec.Frame {
        val frame = LanSyncFrameCodec.readFrame(input, maxBytes)
        if (frame.type == LanSyncFrameCodec.FRAME_ERROR) {
            val error = runCatching {
                LanSyncJson.codec.decodeFromString<ErrorPayload>(String(frame.payload, Charsets.UTF_8))
            }.getOrNull()
            throw LanSyncException(
                errorCode = error?.code ?: LanSyncErrorCode.INTERNAL_ERROR,
                message = error?.message ?: "对端拒绝了同步请求",
            )
        }
        return frame
    }

    private fun buildPullWarnings(summary: SecurityBackupSummary): List<String> = buildList {
        if (summary.retiredSiteIdsPresent.isNotEmpty()) {
            add("包含已退役站点历史（${summary.retiredSiteIdsPresent.joinToString(", ") { it.value }}），合并后会继续保留用于查阅。")
        }
    }
}
