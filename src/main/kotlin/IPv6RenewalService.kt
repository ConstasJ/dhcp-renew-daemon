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
import java.io.InputStreamReader
import java.nio.charset.Charset
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

    /**
     * 获取Windows控制台输出的正确编码
     */
    private fun getConsoleCharset(): Charset {
        return when (platform) {
            "windows" -> {
                try {
                    // 尝试获取Windows控制台代码页
                    val process = ProcessBuilder("chcp").start()
                    val output = InputStreamReader(process.inputStream, Charset.forName("GBK")).readText()
                    process.waitFor(5, TimeUnit.SECONDS)
                    
                    // 从输出中提取代码页
                    val codePageRegex = """(\d+)""".toRegex()
                    val matchResult = codePageRegex.find(output)
                    val codePage = matchResult?.value?.toIntOrNull()
                    
                    logger.debug("检测到代码页: $codePage")
                    
                    when (codePage) {
                        936 -> Charset.forName("GBK")
                        65001 -> Charset.forName("UTF-8")
                        950 -> Charset.forName("Big5")
                        else -> {
                            // 尝试检测系统默认编码
                            val systemCharset = System.getProperty("sun.jnu.encoding") 
                                ?: System.getProperty("file.encoding")
                                ?: "GBK"
                            logger.debug("使用系统编码: $systemCharset")
                            Charset.forName(systemCharset)
                        }
                    }
                } catch (e: Exception) {
                    logger.debug("无法检测控制台编码，使用默认GBK: ${e.message}")
                    Charset.forName("GBK")
                }
            }
            else -> Charset.forName("UTF-8")
        }
    }

    /**
     * 执行系统命令并正确处理编码
     */
    private suspend fun executeCommand(command: List<String>): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()

        val charset = getConsoleCharset()
        
        // 使用BufferedReader逐行读取，避免编码问题
        val output = StringBuilder()
        InputStreamReader(process.inputStream, charset).buffered().use { reader ->
            reader.forEachLine { line ->
                output.append(line).append("\n")
            }
        }
        
        val success = process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0
        
        logger.debug("命令执行: ${command.joinToString(" ")}")
        logger.debug("使用编码: ${charset.name()}")
        logger.debug("输出: ${output.toString().trim()}")

        Pair(success, output.toString().trim())
    } catch (e: Exception) {
        logger.error("执行命令时发生错误", e)
        Pair(false, e.message ?: "执行命令时发生未知错误")
    }
}

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
                listOf("networkctl", "reconfigure", linuxInterfaceName),
                // 备用方案：如果上面失败，尝试传统方法
                listOf("dhclient", "-6", "-r"),
                listOf("dhclient", "-6")
            )
            else -> emptyList()
        }

        val results = mutableListOf<String>()

        for (command in commands) {
            val (success, output) = executeCommand(command)
            
            if (success) {
                results.add("✓ ${command.joinToString(" ")}")
                if (platform == "linux" && command.contains("networkctl")) {
                    // 如果 networkctl 成功，跳过后续的 dhclient 命令
                    break
                }
            } else {
                val errorMsg = output.ifEmpty { "命令执行失败" }
                results.add("✗ ${command.joinToString(" ")}: $errorMsg")
            }
        }

        results.joinToString("\n")
    }

    private suspend fun restartTailscale(): String = withContext(Dispatchers.IO) {
        if (!isTailscaleInstalled()) {
            return@withContext "Tailscale 未安装，跳过重启"
        }

        val commands = listOf(
            listOf("tailscale", "down"),
            listOf("tailscale", "up")
        )

        val results = mutableListOf<String>()

        for (command in commands) {
            val (success, output) = executeCommand(command)
            
            if (success) {
                results.add("✓ Tailscale: ${command.joinToString(" ")}")
            } else {
                val errorMsg = output.ifEmpty { "命令执行失败" }
                results.add("✗ Tailscale: ${command.joinToString(" ")}: $errorMsg")
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