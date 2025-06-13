package io.github.constasj.dhcp

import org.slf4j.LoggerFactory

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 37080
    
    val logger = LoggerFactory.getLogger("App")

    logger.info("Launch DHCP Renewal Service...")
    logger.info("Platform: ${System.getProperty("os.name")}")
    logger.info("Listening on port $port")
    logger.info("Please configure environment variable `DHCP_RENEWAL_SECRET` to set a secret")

    DHCPRenewalService(logger).start(port)
}
