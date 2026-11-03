package ru.tapps.trackerblocker.stable

import android.util.Log
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Транспорт для разрешённых DNS-запросов: обычный UDP:53 и DNS over TLS.
 *
 * UDP-сокеты и DoT-соединение обязательно пропускаются через
 * [VpnService.protect], иначе их пакеты снова попали бы в собственный туннель.
 *
 * Для DoT держим одно TLS-соединение и переиспользуем его: рукопожатие
 * заметно дороже самого запроса. Запросы сериализуются, потому что DNS over
 * TLS — потоковый протокол с длиной сообщения в первых двух байтах. Если
 * сервер закрыл соединение или случился таймаут, следующая попытка создаёт
 * новое; после серии неудач DoT временно отключается (circuit breaker), чтобы
 * не добавлять задержку каждому запросу.
 */
internal class DnsUpstream(
    private val protectDatagram: (DatagramSocket) -> Boolean,
    private val protectSocket: (Socket) -> Boolean
) {

    private class DotConnection(
        val host: String,
        val socket: SSLSocket
    ) {
        private val input = DataInputStream(socket.inputStream)
        private val output: OutputStream = socket.outputStream

        fun exchange(payload: ByteArray, timeoutMs: Int): ByteArray {
            socket.soTimeout = timeoutMs
            val length = ByteArray(2)
            length[0] = (payload.size ushr 8).toByte()
            length[1] = payload.size.toByte()
            output.write(length)
            output.write(payload)
            output.flush()

            val respLength = ByteArray(2)
            input.readFully(respLength)
            val size = ((respLength[0].toInt() and 0xFF) shl 8) or
                (respLength[1].toInt() and 0xFF)
            if (size <= 0 || size > MAX_DNS_MESSAGE) {
                throw IOException("Некорректная длина DoT-ответа: $size")
            }
            val response = ByteArray(size)
            input.readFully(response)
            return response
        }

        fun close() {
            try {
                socket.close()
            } catch (_: IOException) {
            }
        }
    }

    private val dotLock = Any()
    private var dot: DotConnection? = null

    /** До какого момента DoT считается нерабочим. */
    @Volatile
    private var dotDisabledUntil = 0L

    /** Обычный DNS-запрос по UDP:53 через защищённый сокет. */
    fun queryUdp(server: String, payload: ByteArray, timeoutMs: Int): ByteArray? {
        val socket = DatagramSocket()
        return try {
            if (!protectDatagram(socket)) {
                Log.w(TAG, "Не удалось защитить UDP-сокет DNS")
                return null
            }
            socket.soTimeout = timeoutMs
            // connect() отбрасывает пакеты с чужих адресов — базовая защита от
            // подмены ответа на уровне сокета.
            socket.connect(InetAddress.getByName(server), DNS_PORT)
            socket.send(DatagramPacket(payload, payload.size))
            val buffer = ByteArray(MAX_DNS_MESSAGE)
            val response = DatagramPacket(buffer, buffer.size)
            socket.receive(response)
            response.data.copyOf(response.length)
        } catch (e: IOException) {
            Log.w(TAG, "DNS $server недоступен", e)
            null
        } finally {
            socket.close()
        }
    }

    /**
     * DNS over TLS: сначала переиспользуем открытое соединение, затем пробуем
     * подключиться по каждому IP. Возвращает null, если DoT недоступен —
     * вызывающий код откатится на UDP.
     */
    fun queryDot(host: String, ips: List<String>, payload: ByteArray, timeoutMs: Int): ByteArray? {
        if (ips.isEmpty()) return null
        synchronized(dotLock) {
            if (System.currentTimeMillis() < dotDisabledUntil) return null

            dot?.let { existing ->
                if (existing.host == host) {
                    try {
                        return existing.exchange(payload, timeoutMs)
                    } catch (e: IOException) {
                        Log.w(TAG, "DoT $host: соединение потеряно, переподключаюсь", e)
                        existing.close()
                    }
                } else {
                    existing.close()
                }
                dot = null
            }

            for (ip in ips) {
                var connection: DotConnection? = null
                try {
                    val raw = Socket()
                    if (!protectSocket(raw)) {
                        raw.close()
                        Log.w(TAG, "Не удалось защитить DoT-сокет")
                        continue
                    }
                    raw.connect(InetSocketAddress(ip, DOT_PORT), timeoutMs)
                    raw.soTimeout = timeoutMs
                    // Передаём host в createSocket, чтобы JSSE проверил
                    // сертификат именно по имени, а не по IP, и отправил SNI.
                    val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                        .createSocket(raw, host, DOT_PORT, true) as SSLSocket
                    val params = ssl.sslParameters
                    params.endpointIdentificationAlgorithm = "HTTPS"
                    ssl.sslParameters = params
                    ssl.startHandshake()
                    connection = DotConnection(host, ssl)
                    dot = connection
                    val answer = connection.exchange(payload, timeoutMs)
                    Log.d(TAG, "DoT $host ($ip): ответ ${answer.size} байт")
                    return answer
                } catch (e: IOException) {
                    connection?.close()
                    dot = null
                    Log.w(TAG, "DoT $host через $ip недоступен", e)
                } catch (e: Exception) {
                    connection?.close()
                    dot = null
                    Log.w(TAG, "DoT $host через $ip: ошибка", e)
                }
            }

            dotDisabledUntil = System.currentTimeMillis() + DOT_BACKOFF_MS
            Log.w(TAG, "DoT недоступен, на ${DOT_BACKOFF_MS / 60_000} мин переключаюсь на UDP")
            return null
        }
    }

    /** Сбрасывает соединение при смене провайдера или настроек. */
    fun resetDot() {
        synchronized(dotLock) {
            dot?.close()
            dot = null
            dotDisabledUntil = 0L
        }
    }

    fun close() {
        resetDot()
    }

    private companion object {
        const val TAG = "DnsUpstream"
        const val DNS_PORT = 53
        const val DOT_PORT = 853
        const val MAX_DNS_MESSAGE = 65_535
        const val DOT_BACKOFF_MS = 5 * 60 * 1000L
    }
}
