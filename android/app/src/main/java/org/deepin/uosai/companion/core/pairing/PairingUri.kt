package org.deepin.uosai.companion.core.pairing

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** A single-use invitation emitted by the UOS AI desktop companion settings page. */
data class PairingUri(
    val host: String,
    val port: Int,
    val pairingSecret: String,
    val expiresAtMs: Long,
    val hostDisplayName: String,
) {
    /**
     * Production companion connections are deliberately certificate-verified WSS.
     * A Tailscale HTTPS certificate is issued for the supplied MagicDNS name.
     */
    fun webSocketUrl(): String = "wss://$host:$port/"

    companion object {
        private val expectedParameters = setOf(
            "v",
            "host",
            "port",
            "pairingSecret",
            "expiresAtMs",
            "hostDisplayName",
        )

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
            if (values.keys.any { it !in expectedParameters }) {
                throw PairingUriError.UnexpectedParameter
            }
            if (values["v"] != "1") {
                throw PairingUriError.MissingOrInvalidParameter
            }

            val host = values.required("host").lowercase()
            val port = values.required("port").toIntOrNull()?.takeIf { it in 1..65535 }
                ?: throw PairingUriError.MissingOrInvalidParameter
            val pairingSecret = values.required("pairingSecret")
            val expiresAtMs = values.required("expiresAtMs").toLongOrNull()
                ?: throw PairingUriError.MissingOrInvalidParameter
            val displayName = values.required("hostDisplayName")

            if (!isSafeTailnetHostname(host)) {
                throw PairingUriError.UnsafeHost
            }
            if (expiresAtMs <= nowMs) {
                throw PairingUriError.Expired
            }

            return PairingUri(host, port, pairingSecret, expiresAtMs, displayName)
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
    }
}

sealed class PairingUriError(message: String) : IllegalArgumentException(message) {
    data object InvalidScheme : PairingUriError("The QR code is not a UOS AI invitation")
    data object UnexpectedParameter : PairingUriError("The invitation contains an unexpected parameter")
    data object MissingOrInvalidParameter : PairingUriError("The invitation is incomplete or invalid")
    data object UnsafeHost : PairingUriError("The invitation must use an ASCII .ts.net hostname")
    data object Expired : PairingUriError("The invitation has expired")
}
