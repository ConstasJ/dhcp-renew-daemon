# DHCP Renew Daemon

A lightweight service that listens for HTTP requests to renew DHCP leases and restart Tailscale service if it exists. This daemon is designed for Windows and Linux systems.

## Features

- HTTP API for triggering IPv6 address renewal
- Platform-specific implementation for Windows and Linux
- Automatic detection and restart of Tailscale service
- Secure API with secret key authentication
- Proper handling of different console encodings
- Detailed logging

## Requirements

- Java 21 or higher
- Windows or Linux operating system
- Administrative privileges (for running network commands)
- Tailscale (optional)

## Installation

### Option 1: Download the pre-built JAR

1. Download the latest release from the [Releases](https://github.com/constasj/dhcp-renew-daemon/releases) page
2. Run the JAR file with Java:
   ```
   java -jar dhcp-renew-daemon.jar
   ```

### Option 2: Build from source

1. Clone the repository:
   ```
   git clone https://github.com/constasj/dhcp-renew-daemon.git
   cd dhcp-renew-daemon
   ```

2. Build with Gradle:
   ```
   ./gradlew shadowJar
   ```

3. Run the application:
   ```
   java -jar build/libs/dhcp-renew-daemon.jar
   ```

## Configuration

The daemon can be configured using environment variables:

| Variable | Description | Default |
|----------|-------------|---------|
| `PORT` | The HTTP port to listen on | 37080 |
| `IPV6_RENEWAL_SECRET` | Secret key for API authentication | "DEFAULT_SECRET_CHANGE_ME" |
| `LINUX_INTERFACE_NAME` | Network interface name (Linux only) | "eth0" |

## Usage

### API Endpoints

#### GET /

Returns basic information about the service.

#### GET /status

Returns the current status of the service, including platform and Tailscale installation status.

#### POST /renew

Triggers the IPv6 address renewal process and optionally restarts Tailscale.

Request body:
```json
{
  "secret": "your-configured-secret",
  "action": "renew"
}
```

Response:
```json
{
  "success": true,
  "message": "IPv6 renewal results...",
  "platform": "windows|linux"
}
```

### Example Usage with curl

```bash
curl -X POST http://localhost:37080/renew \
  -H "Content-Type: application/json" \
  -d '{"secret":"your-configured-secret","action":"renew"}'
```

## Security Considerations

- Always change the default secret key by setting the `IPV6_RENEWAL_SECRET` environment variable
- Consider running the service behind a reverse proxy with HTTPS for production use or just use in a local network
- The service requires administrative privileges to execute network commands

## License

This project is licensed under the Apache License 2.0—see the [LICENSE](LICENSE) file for details.
