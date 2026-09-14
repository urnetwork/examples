import io.ur.sdk.Sdk
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.client.plugins.HttpTimeout
import java.net.InetAddress
import java.net.Proxy
import java.util.UUID
import kotlinx.coroutines.runBlocking

fun main(args: Array<String>) = runBlocking {
    if (args.firstOrNull() == "--version") { println(Sdk.version()); return@runBlocking }
    if (args.firstOrNull() == "--new-id") { println(UUID.randomUUID()); return@runBlocking }
    UrSession().use { session ->
        if (args.firstOrNull() == "udp") {
            session.device.dial("udp", args.getOrElse(1) { error("Pass echo host:port") }).use { conn ->
                conn.setDeadline(System.currentTimeMillis() + 10_000)
                conn.write("hello".toByteArray())
                val reply = conn.read(65535)
                println(String(reply.data()) + "; EOF=" + reply.eof())
            }
        } else {
            HttpClient(OkHttp) {
                install(HttpTimeout) { requestTimeoutMillis = 30_000 }
                engine {
                    config {
                        socketFactory(UrSocketFactory(session.device))
                        dns { host -> listOf(InetAddress.getByAddress(host, ByteArray(4))) }
                        proxy(Proxy.NO_PROXY)
                    }
                }
            }.use { client -> println(client.get(args.firstOrNull() ?: "https://example.com/").bodyAsText()) }
        }
    }
}
