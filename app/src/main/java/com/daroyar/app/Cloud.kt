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
 * The parent phone publishes its encrypted state as a *retained* message to
 * EVERY broker it can reach; the caregiver phone reads from every broker it
 * can reach and keeps the newest. So the two phones meet even if Iran blocks
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

    private val rnd = SecureRandom()

    private fun clientId(): String {
        val sb = StringBuilder("dy")
        repeat(10) { sb.append("0123456789abcdef"[rnd.nextInt(16)]) }
        return sb.toString()
    }

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

    /** Publishes [payload] as retained to every reachable broker. Returns how many accepted it. */
    fun publishRetained(topic: String, payload: ByteArray, log: MutableList<String>): Int {
        var ok = 0
        for (h in HOSTS) {
            val c = connect(h, log) ?: continue
            try {
                c.publish(topic, payload, 1, true)
                ok++
            } catch (t: Throwable) {
                log.add("${h.name}: ارسال ✗ ${t.javaClass.simpleName}")
            } finally {
                close(c)
            }
        }
        return ok
    }

    class FetchResult(val messages: Map<String, List<ByteArray>>, val hostsReached: Int)

    /** Reads the retained messages of [topics] from every reachable broker. */
    fun fetchRetained(topics: List<String>, log: MutableList<String>): FetchResult {
        val out = HashMap<String, MutableList<ByteArray>>()
        var reached = 0
        if (topics.isEmpty()) return FetchResult(out, 0)
        for (h in HOSTS) {
            val c = connect(h, log) ?: continue
            reached++
            try {
                val latch = CountDownLatch(topics.size)
                c.setCallback(object : MqttCallback {
                    override fun connectionLost(cause: Throwable?) {}

                    override fun messageArrived(topic: String?, message: MqttMessage?) {
                        if (topic == null || message == null) return
                        synchronized(out) {
                            out.getOrPut(topic) { mutableListOf() }.add(message.payload)
                        }
                        latch.countDown()
                    }

                    override fun deliveryComplete(token: IMqttDeliveryToken?) {}
                })
                c.subscribe(topics.toTypedArray(), IntArray(topics.size) { 1 })
                // Retained messages arrive right after subscribing.
                latch.await(5, TimeUnit.SECONDS)
            } catch (t: Throwable) {
                log.add("${h.name}: دریافت ✗ ${t.javaClass.simpleName}")
            } finally {
                close(c)
            }
        }
        return FetchResult(out, reached)
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
