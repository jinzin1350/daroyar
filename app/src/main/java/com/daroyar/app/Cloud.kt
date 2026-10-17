package com.daroyar.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocketFactory

/**
 * Link between the two phones through free public MQTT brokers.
 *
 * Both phones connect to EVERY broker they can reach. The parent phone
 * publishes its encrypted state there and reads the medicine list (config)
 * the caregivers published; each side keeps the newest copy it finds. So the two phones meet even if Iran blocks
 * one broker and not another. For each broker we try TLS first, then
 * WebSocket-TLS (port 8084/8884 looks like web traffic), then plain ones;
 * plain is fine because the payload itself is encrypted.
 */
object Cloud {
    class Host(val name: String, val uris: List<String>)

    val HOSTS = listOf(
        Host(
            "EMQX", listOf(
                "ssl://broker.emqx.io:8883",
                "wss://broker.emqx.io:8084/mqtt",
                "tcp://broker.emqx.io:1883",
                "ws://broker.emqx.io:8083/mqtt"
            )
        ),
        Host(
            "HiveMQ", listOf(
                "ssl://broker.hivemq.com:8883",
                "wss://broker.hivemq.com:8884/mqtt",
                "tcp://broker.hivemq.com:1883",
                "ws://broker.hivemq.com:8000/mqtt"
            )
        ),
        Host(
            "Mosquitto", listOf(
                "ssl://test.mosquitto.org:8886",
                "wss://test.mosquitto.org:8081",
                "tcp://test.mosquitto.org:1883",
                "ws://test.mosquitto.org:8080"
            )
        )
    )

    fun stateTopic(topicId: String) = "daroyar/v1/$topicId/state"
    fun configTopic(topicId: String) = "daroyar/v1/$topicId/config"

    private val rnd = SecureRandom()

    private fun clientId(): String {
        val sb = StringBuilder("dy")
        repeat(10) { sb.append("0123456789abcdef"[rnd.nextInt(16)]) }
        return sb.toString()
    }

    class Conn(val host: Host, val client: MqttClient)

    private fun connect(host: Host, log: MutableList<String>): MqttClient? {
        for (uri in host.uris) {
            var client: MqttClient? = null
            try {
                client = MqttClient(uri, clientId(), MemoryPersistence())
                client.setTimeToWait(15_000)
                val opts = MqttConnectOptions().apply {
                    isCleanSession = true
                    connectionTimeout = 10
                    keepAliveInterval = 30
                    isAutomaticReconnect = false
                    if (uri.startsWith("ssl") || uri.startsWith("wss")) {
                        socketFactory = SSLSocketFactory.getDefault()
                    }
                }
                client.connect(opts)
                log.add("${host.name}: وصل شد (${uri.substringBefore(":")})")
                return client
            } catch (t: Throwable) {
                log.add("${host.name}: ${uri.substringBefore(":")} ✗ ${t.javaClass.simpleName}")
                close(client)
            }
        }
        return null
    }

    private fun close(c: MqttClient?) {
        if (c == null) return
        try {
            if (c.isConnected) c.disconnect(2_000)
        } catch (_: Throwable) {
        }
        try {
            c.close()
        } catch (_: Throwable) {
        }
    }

    /** Opens a connection to every broker that is reachable right now. */
    fun connectAll(log: MutableList<String>): List<Conn> =
        HOSTS.mapNotNull { h -> connect(h, log)?.let { Conn(h, it) } }

    fun closeAll(conns: List<Conn>) = conns.forEach { close(it.client) }

    /**
     * Retained message of each topic on each connection (result list is in
     * the same order as [conns]). Waits at most [waitMs] in total.
     */
    fun readRetainedAll(conns: List<Conn>, topics: List<String>, waitMs: Long): List<Map<String, ByteArray>> {
        val maps = conns.map { HashMap<String, ByteArray>() }
        if (conns.isEmpty() || topics.isEmpty()) return maps
        val latch = CountDownLatch(conns.size * topics.size)
        conns.forEachIndexed { i, conn ->
            val map = maps[i]
            try {
                conn.client.setCallback(object : MqttCallback {
                    override fun connectionLost(cause: Throwable?) {}

                    override fun messageArrived(topic: String?, message: MqttMessage?) {
                        if (topic == null || message == null) return
                        var first = false
                        synchronized(map) {
                            if (!map.containsKey(topic)) {
                                map[topic] = message.payload
                                first = true
                            }
                        }
                        if (first) latch.countDown()
                    }

                    override fun deliveryComplete(token: IMqttDeliveryToken?) {}
                })
                conn.client.subscribe(topics.toTypedArray(), IntArray(topics.size) { 1 })
            } catch (_: Throwable) {
            }
        }
        try {
            latch.await(waitMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
        }
        for (conn in conns) {
            try {
                conn.client.unsubscribe(topics.toTypedArray())
            } catch (_: Throwable) {
            }
        }
        return maps.map { m -> synchronized(m) { HashMap(m) } }
    }

    /** Publishes as a retained message (newcomers get it right away). */
    fun publish(conn: Conn, topic: String, payload: ByteArray, log: MutableList<String>): Boolean {
        return try {
            conn.client.publish(topic, payload, 1, true)
            true
        } catch (t: Throwable) {
            log.add("${conn.host.name}: ارسال ✗ ${t.javaClass.simpleName}")
            false
        }
    }
}

object Net {
    fun online(c: Context): Boolean {
        return try {
            val cm = c.getSystemService(ConnectivityManager::class.java) ?: return false
            val n = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(n) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (t: Throwable) {
            true
        }
    }
}
