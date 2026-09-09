package org.deepin.uosai.companion.core.pairing

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64

enum class PairingTransport { TAILNET, LOCAL }

/** A single-use invitation emitted by the UOS AI desktop companion settings page. */
data class PairingUri(
    val host: String,
    val port: Int,
    val pairingSecret: String,
    val expiresAtMs: Long,
    val hostDisplayName: String,
    val transport: PairingTransport,
    val tlsSpkiSha256: String? = null,
) {
    /**
     * Production companion connections are deliberately certificate-verified WSS.
     * A Tailscale HTTPS certificate is issued for the supplied MagicDNS name.
     */
    fun webSocketUrl(): String = "wss://$host:$port/"

    companion object {
        private val v1Parameters = setOf(
            "v",
            "host",
            "port",
            "pairingSecret",
            "expiresAtMs",
            "hostDisplayName",
        )
        private val v2Parameters = v1Parameters + setOf("transport", "tlsSpkiSha256")

        fun parse(raw: String, nowMs: Long = System.currentTimeMillis()): PairingUri {
            val uri = try {
                URI(raw)
            } catch (_: Exception) {
                throw PairingUriError.InvalidScheme
            }
            if (uri.scheme != "uos-ai" || uri.host != "pair") {
                throw PairingUriError.InvalidScheme
            }

            val values = parseQuery(uri.rawQuery ?: "")
            val version = values["v"]
            val expectedParameters = when (version) {
                "1" -> v1Parameters
                "2" -> v2Parameters
                else -> throw PairingUriError.MissingOrInvalidParameter
            }
            if (values.keys.any { it !in expectedParameters }) {
                throw PairingUriError.UnexpectedParameter
            }

            val host = values.required("host").lowercase()
            val port = values.required("port").toIntOrNull()?.takeIf { it in 1..65535 }
                ?: throw PairingUriError.MissingOrInvalidParameter
            val pairingSecret = values.required("pairingSecret")
            val expiresAtMs = values.required("expiresAtMs").toLongOrNull()
                ?: throw PairingUriError.MissingOrInvalidParameter
            val displayName = values.required("hostDisplayName")

            val (transport, tlsSpkiSha256) = when (version) {
                "1" -> {
                    if (!isSafeTailnetHostname(host)) throw PairingUriError.UnsafeHost
                    PairingTransport.TAILNET to null
                }
                "2" -> {
                    if (values.required("transport") != "local") {
                        throw PairingUriError.MissingOrInvalidParameter
                    }
                    if (!isEligibleLocalIpv4(host)) throw PairingUriError.UnsafeHost
                    PairingTransport.LOCAL to values.required("tlsSpkiSha256").also(::validateTlsSpkiSha256)
                }
                else -> throw PairingUriError.MissingOrInvalidParameter
            }
            if (expiresAtMs <= nowMs) {
                throw PairingUriError.Expired
            }

            return PairingUri(host, port, pairingSecret, expiresAtMs, displayName, transport, tlsSpkiSha256)
        }

        private fun Map<String, String>.required(name: String): String =
            get(name)?.takeIf { it.isNotBlank() } ?: throw PairingUriError.MissingOrInvalidParameter

        private fun parseQuery(rawQuery: String): Map<String, String> {
            if (rawQuery.isBlank()) throw PairingUriError.MissingOrInvalidParameter
            return buildMap {
                rawQuery.split('&').forEach { entry ->
                    val separator = entry.indexOf('=')
                    if (separator <= 0) throw PairingUriError.MissingOrInvalidParameter
                    val name = decode(entry.substring(0, separator))
                    val value = decode(entry.substring(separator + 1))
                    if (name.isBlank() || put(name, value) != null) {
                        throw PairingUriError.MissingOrInvalidParameter
                    }
                }
            }
        }

        private fun decode(value: String): String = try {
            URLDecoder.decode(value, StandardCharsets.UTF_8.name())
        } catch (_: IllegalArgumentException) {
            throw PairingUriError.MissingOrInvalidParameter
        }

        private fun isSafeTailnetHostname(host: String): Boolean {
            if (host.any { it.code > 0x7f } || !host.endsWith(".ts.net")) return false
            val labels = host.split('.')
            return labels.size >= 3 && labels.all { label ->
                label.isNotEmpty() && label.length <= 63 &&
                    label.first().isLetterOrDigit() && label.last().isLetterOrDigit() &&
                    label.all { it.isLetterOrDigit() || it == '-' }
            }
        }

        private fun isEligibleLocalIpv4(host: String): Boolean {
            val octets = host.split('.')
            if (octets.size != 4) return false
            val values = octets.map { octet ->
                if (octet.isEmpty() || (octet.length > 1 && octet.startsWith('0')) ||
                    octet.any { !it.isDigit() }
                ) {
                    return false
                }
                octet.toIntOrNull()?.takeIf { it in 0..255 } ?: return false
            }
            val (first, second) = values
            return first == 10 ||
                (first == 172 && second in 16..31) ||
                (first == 192 && second == 168) ||
                (first == 100 && second in 64..127)
        }

        private fun validateTlsSpkiSha256(value: String) {
            if (!value.matches(Regex("[A-Za-z0-9_-]+"))) {
                throw PairingUriError.MissingOrInvalidParameter
            }
            val bytes = try {
                Base64.getUrlDecoder().decode(value)
            } catch (_: IllegalArgumentException) {
                throw PairingUriError.MissingOrInvalidParameter
            }
            if (bytes.size != 32 || Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) != value) {
                throw PairingUriError.MissingOrInvalidParameter
            }
        }
    }
}

sealed class PairingUriError(message: String) : IllegalArgumentException(message) {
    data object InvalidScheme : PairingUriError("The QR code is not a UOS AI invitation")
    data object UnexpectedParameter : PairingUriError("The invitation contains an unexpected parameter")
    data object MissingOrInvalidParameter : PairingUriError("The invitation is incomplete or invalid")
    data object UnsafeHost : PairingUriError("The invitation must use an ASCII .ts.net hostname")
    data object Expired : PairingUriError("The invitation has expired")
}
