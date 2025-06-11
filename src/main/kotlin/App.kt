package io.github.constasj.dhcp

import org.slf4j.LoggerFactory

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 37080
    
    val logger = LoggerFactory.getLogger("App")

    logger.info("启动 IPv6 地址刷新服务...")
    logger.info("平台: ${System.getProperty("os.name")}")
    logger.info("监听端口: $port")
    logger.info("请设置环境变量 IPV6_RENEWAL_SECRET 来配置密钥")

    IPv6RenewalService(logger).start(port)
}
