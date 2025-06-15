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
import kotlinx.serialization.*
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

@Serializable
data class StatusResponse(
    val platform: String,
    @SerialName("tailscale_installed")
    val tailscaleInstalled: Boolean,
    val service: String
)

class DHCPRenewalService(
    private val logger: Logger
) {
    private val secret = System.getenv("DHCP_RENEWAL_SECRET")
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
     * Get console charset, especially for Windows
     */
    private fun getConsoleCharset(): Charset {
        return when (platform) {
            "windows" -> {
                try {
                    val process = ProcessBuilder("chcp").start()
                    val output = InputStreamReader(process.inputStream, Charset.forName("GBK")).readText()
                    process.waitFor(5, TimeUnit.SECONDS)

                    val codePageRegex = """(\d+)""".toRegex()
                    val matchResult = codePageRegex.find(output)
                    val codePage = matchResult?.value?.toIntOrNull()
                    
                    logger.debug("Detected codepage: $codePage")
                    
                    when (codePage) {
                        936 -> Charset.forName("GBK")
                        65001 -> Charset.forName("UTF-8")
                        950 -> Charset.forName("Big5")
                        else -> {
                            val systemCharset = System.getProperty("sun.jnu.encoding") 
                                ?: Charset.defaultCharset().displayName()
                                ?: "GBK"
                            logger.debug("Using system charset: $systemCharset")
                            Charset.forName(systemCharset)
                        }
                    }
                } catch (e: Exception) {
                    logger.debug("Cannot detect shell default charset, using GBK: ${e.message}")
                    Charset.forName("GBK")
                }
            }
            else -> Charset.forName("UTF-8")
        }
    }

    /**
     * Execute system commands with the correct charset
     */
    private suspend fun executeCommand(command: List<String>): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()

        val charset = getConsoleCharset()

        val output = StringBuilder()
        InputStreamReader(process.inputStream, charset).buffered().use { reader ->
            reader.forEachLine { line ->
                output.append(line).append("\n")
            }
        }
        
        val success = process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0
        
        logger.debug("Executed commands: ${command.joinToString(" ")}")
        logger.debug("Charset: ${charset.name()}")
        logger.debug("Output: ${output.toString().trim()}")

        Pair(success, output.toString().trim())
    } catch (e: Exception) {
        logger.error("Error on executing commands", e)
        Pair(false, e.message ?: "Unknown error on executing commands")
    }
}

    private fun isTailscaleInstalled(): Boolean {
        return try {
            when (platform) {
                "windows" -> {
                    // Check if the Tailscale service exists
                    val process = ProcessBuilder("sc", "query", "Tailscale")
                        .start()
                    process.waitFor(5, TimeUnit.SECONDS)
                    process.exitValue() == 0
                }
                "linux" -> {
                    // Check if the tailscale command exists
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

    private suspend fun renewDHCP(): String = withContext(Dispatchers.IO) {
        val commands = when (platform) {
            "windows" -> listOf(
                // Using ipconfig command to renew the lease
                listOf("ipconfig", "/renew"),
                listOf("ipconfig", "/renew6")
            )
            "linux" -> listOf(
                // Using networkctl(systemd-networkd) to renew the DHCP lease
                listOf("networkctl", "reconfigure", linuxInterfaceName),
                // Backup plan: if commands above failed, then rollback to dhclient
                listOf("dhclient", "-4o6", "-r"),
                listOf("dhclient", "-4o6")
            )
            else -> emptyList()
        }

        val results = mutableListOf<String>()

        for (command in commands) {
            val (success, output) = executeCommand(command)
            
            if (success) {
                results.add("✓ ${command.joinToString(" ")}")
                if (platform == "linux" && command.contains("networkctl")) {
                    // if networkctl executed successfully, then skip dhclient commands
                    break
                }
            } else {
                val errorMsg = output.ifEmpty { "Command execution failed" }
                results.add("✗ ${command.joinToString(" ")}: $errorMsg")
            }
        }

        results.joinToString("\n")
    }

    private suspend fun restartTailscale(): String = withContext(Dispatchers.IO) {
        if (!isTailscaleInstalled()) {
            return@withContext "Tailscale is not installed, skipping restart"
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
                val errorMsg = output.ifEmpty { "Command execution failed" }
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
                        "DHCP Renewal Service - Platform: $platform\n" +
                                "Using POST /renew to trigger DHCP lease renewal"
                    )
                }

                get("/status") {
                    call.respond(
                        StatusResponse(platform, isTailscaleInstalled(), "running")
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

                        logger.info("Received DHCP renewal request - Platform: $platform")

                        val dhcpResult = renewDHCP()
                        val tailscaleResult = if (isTailscaleInstalled()) {
                            restartTailscale()
                        } else {
                            "Tailscale not installed"
                        }

                        val message = "DHCP Renewal Result:\n$dhcpResult\n\nTailscale processing result:\n$tailscaleResult"

                        logger.info("Execution result:\n$message")

                        call.respond(
                            RenewResponse(true, message, platform)
                        )

                    } catch (e: Exception) {
                        logger.info("Error on processing request: ${e.message}")
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            RenewResponse(false, "Error on processing request: ${e.message}", platform)
                        )
                    }
                }
            }
        }.start(wait = true)
    }
}