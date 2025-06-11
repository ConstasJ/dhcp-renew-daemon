package io.github.constasj.dhcp

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.slf4j.Logger
import java.util.concurrent.TimeUnit

@Serializable
data class RenewRequest(
    val secret: String,
    val action: String = "renew"
)

@Serializable
data class RenewResponse(
    val success: Boolean,
    val message: String,
    val platform: String
)

class IPv6RenewalService(
    private val logger: Logger
) {
    private val secret = System.getenv("IPV6_RENEWAL_SECRET")
        ?: "DEFAULT_SECRET_CHANGE_ME"

    private val platform = detectPlatform()

    private fun detectPlatform(): String {
        val osName = System.getProperty("os.name").lowercase()
        return when {
            osName.contains("windows") -> "windows"
            osName.contains("linux") -> "linux"
            else -> "unknown"
        }
    }

    private val linuxInterfaceName = System.getenv("LINUX_INTERFACE_NAME")
        ?: "eth0"

    private fun isTailscaleInstalled(): Boolean {
        return try {
            when (platform) {
                "windows" -> {
                    // 检查 Tailscale 服务是否存在
                    val process = ProcessBuilder("sc", "query", "Tailscale")
                        .start()
                    process.waitFor(5, TimeUnit.SECONDS)
                    process.exitValue() == 0
                }
                "linux" -> {
                    // 检查 tailscale 命令是否存在
                    val process = ProcessBuilder("which", "tailscale")
                        .start()
                    process.waitFor(5, TimeUnit.SECONDS)
                    process.exitValue() == 0
                }
                else -> false
            }
        } catch (e: Exception) {
            logger.info("检查 Tailscale 时出错: ${e.message}")
            false
        }
    }

    private suspend fun renewIPv6(): String = withContext(Dispatchers.IO) {
        val commands = when (platform) {
            "windows" -> listOf(
                listOf("ipconfig", "/renew6")
            )
            "linux" -> listOf(
                // 使用 networkctl 重新配置接口 (systemd-networkd)
                listOf("sudo", "networkctl", "reload"),
                listOf("sudo", "networkctl", "reconfigure", linuxInterfaceName),
                // 备用方案：如果上面失败，尝试传统方法
                listOf("sudo", "dhclient", "-6", "-r"),
                listOf("sudo", "dhclient", "-6")
            )
            else -> emptyList()
        }

        val results = mutableListOf<String>()

        for (command in commands) {
            try {
                val process = ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start()

                val output = process.inputStream.bufferedReader().readText()
                val success = process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0

                if (success) {
                    results.add("✓ ${command.joinToString(" ")}")
                    if (platform == "linux" && command.contains("networkctl")) {
                        // 如果 networkctl 成功，跳过后续的 dhclient 命令
                        break
                    }
                } else {
                    results.add("✗ ${command.joinToString(" ")}: $output")
                }
            } catch (e: Exception) {
                results.add("✗ ${command.joinToString(" ")}: ${e.message}")
            }
        }

        results.joinToString("\n")
    }

    private suspend fun restartTailscale(): String = withContext(Dispatchers.IO) {
        if (!isTailscaleInstalled()) {
            return@withContext "Tailscale 未安装，跳过重启"
        }

        val commands = when (platform) {
            "windows" -> listOf(
                listOf("net", "stop", "Tailscale"),
                listOf("net", "start", "Tailscale")
            )
            "linux" -> listOf(
                listOf("sudo", "systemctl", "restart", "tailscaled")
            )
            else -> emptyList()
        }

        val results = mutableListOf<String>()

        for (command in commands) {
            try {
                val process = ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start()

                val output = process.inputStream.bufferedReader().readText()
                val success = process.waitFor(15, TimeUnit.SECONDS) && process.exitValue() == 0

                if (success) {
                    results.add("✓ Tailscale: ${command.joinToString(" ")}")
                } else {
                    results.add("✗ Tailscale: ${command.joinToString(" ")}: $output")
                }
            } catch (e: Exception) {
                results.add("✗ Tailscale: ${command.joinToString(" ")}: ${e.message}")
            }
        }

        results.joinToString("\n")
    }

    fun start(port: Int = 8080) {
        embeddedServer(CIO, port = port) {
            install(ContentNegotiation) {
                json(Json {
                    prettyPrint = true
                    isLenient = true
                })
            }

            routing {
                get("/") {
                    call.respondText(
                        "IPv6 Renewal Service - Platform: $platform\n" +
                                "使用 POST /renew 来触发IPv6地址刷新"
                    )
                }

                get("/status") {
                    call.respond(
                        mapOf(
                            "platform" to platform,
                            "tailscale_installed" to isTailscaleInstalled(),
                            "service" to "running"
                        )
                    )
                }

                post("/renew") {
                    try {
                        val request = call.receive<RenewRequest>()

                        if (request.secret != secret) {
                            call.respond(
                                HttpStatusCode.Unauthorized,
                                RenewResponse(false, "无效的密钥", platform)
                            )
                            return@post
                        }

                        logger.info("收到IPv6地址刷新请求 - Platform: $platform")

                        val ipv6Result = renewIPv6()
                        val tailscaleResult = if (isTailscaleInstalled()) {
                            restartTailscale()
                        } else {
                            "Tailscale 未安装"
                        }

                        val message = "IPv6刷新结果:\n$ipv6Result\n\nTailscale处理:\n$tailscaleResult"

                        logger.info("执行结果:\n$message")

                        call.respond(
                            RenewResponse(true, message, platform)
                        )

                    } catch (e: Exception) {
                        logger.info("处理请求时出错: ${e.message}")
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            RenewResponse(false, "处理请求时出错: ${e.message}", platform)
                        )
                    }
                }
            }
        }.start(wait = true)
    }
}