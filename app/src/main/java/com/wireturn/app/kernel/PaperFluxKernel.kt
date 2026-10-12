package com.wireturn.app.kernel

import android.content.Context
import com.wireturn.app.CoreServiceState
import com.wireturn.app.CoreStatus
import com.wireturn.app.R
import com.wireturn.app.data.ClientConfig
import com.wireturn.app.data.KernelConfig
import com.wireturn.app.data.KernelVariant
import com.wireturn.app.data.kernel.PaperFluxConfig
import com.wireturn.app.ui.activities.kernel.PaperFluxConfigActivity

// PaperFlux (external/paperflux - our fork of Flofyyk/PaperFlux, "wireturn" branch: --android-proxy
// on any loopback port). Always an authenticated Session for one server profile. Two ways to run:
// the VPN without Xray hands it the device's TUN (--tun-fd-sock, like qWDTT/CSQTT), everything
// else gets its own SOCKS5 (--android-proxy --socks5), with no auth of its own. Launched the way
// the official Android client launches it (PaperFluxAndroid's OpenFluxVpnService) - minus its
// IPC bridge for solving Yandex's checks, so those surface as an error here instead.
object PaperFluxKernel : Kernel {
    override val variant: KernelVariant = KernelVariant.PAPERFLUX
    override val sensitiveCommandFlags: Set<String> =
        setOf("--profile-token", "--urls", "--url", "--volga-url", "--auth-service")
    override val displayNameRes: Int = R.string.kernel_paperflux
    override val configActivityClass = PaperFluxConfigActivity::class.java
    override val wgNotUsedMessageRes: Int = R.string.wg_not_used_with_paperflux

    override fun description(context: Context, cfg: KernelConfig): String {
        val config = (cfg as KernelConfig.PaperFlux).config
        return context.getString(displayNameRes) + " " + config.platformDisplayName
    }

    override fun profileSummaryExtra(context: Context, cfg: KernelConfig): List<String> {
        val config = (cfg as KernelConfig.PaperFlux).config
        val docCount = config.usableDocuments.size
        return listOfNotNull(
            context.getString(R.string.kernel_tag_call_count, docCount).takeIf { docCount > 1 },
            context.getString(R.string.kernel_tag_volga).takeIf { config.transport == "yandex" && config.volgaUrl.isNotBlank() }
        )
    }

    override fun iconRes(cfg: KernelConfig, outlined: Boolean): Int = when ((cfg as KernelConfig.PaperFlux).config.transport) {
        "yandex", "vyandex" -> R.drawable.ic_yandex_docs
        "mailru" -> R.drawable.ic_mailru
        "cupsonline" -> R.drawable.ic_cupsonline
        "boards" -> R.drawable.ic_yandex_boards
        "mtslink" -> R.drawable.ic_mts_link
        else -> R.drawable.route_24px
    }

    override val defaultProfileName: String = "PaperFlux Server"

    override fun decodeUri(uri: String): KernelConfig? =
        PaperFluxConfig.parse(uri)?.let { KernelConfig.PaperFlux(it) }

    override fun displayNameFromUri(uri: String): String? = PaperFluxConfig.displayName(uri)

    // Its own TUN handling is the only way it reaches IP packets in VPN mode without Xray.
    override fun supportsNativeTun(cfg: KernelConfig): Boolean = true

    override fun buildCommand(ctx: KernelCommandContext, cfg: ClientConfig): List<String> {
        val o = (cfg.kernelConfig as KernelConfig.PaperFlux).config
        val cmd = mutableListOf(
            "${ctx.nativeLibraryDir}/libpaperflux.so",
            "--client",
            "--transport", o.transport,
            "--session",
            "--profile-id", o.profileId,
            "--profile-token", o.token,
            "--client-ip", o.clientIp
        )
        // The server's verification listener, at 24000 + ID - as the official client derives it.
        val id = o.profileIdNumber
        if (id != null && id <= 41535 && SERVER_REGEX.matches(o.server)) {
            cmd.addAll(listOf("--auth-service", "${o.server}:${24000 + id}"))
        }
        if (o.transport == "yandex") {
            cmd.addAll(listOf("--urls", o.usableDocuments.joinToString(",")))
            if (o.volgaUrl.isNotBlank()) cmd.addAll(listOf("--volga-url", o.volgaUrl))
        } else {
            cmd.addAll(listOf("--url", o.usableDocuments.first()))
        }
        val tunSocket = ctx.nativeTunSocket
        if (tunSocket != null) {
            cmd.addAll(listOf("--tun-fd-sock", "@$tunSocket"))
        } else {
            // Loopback only (enforced by ClientConfig's validation and the core itself).
            cmd.addAll(listOf("--android-proxy", "--socks5", cfg.socksAddr.ifBlank { ClientConfig.DEFAULT_SOCKS_ADDR }))
        }
        return cmd
    }

    // What the official client sets: the resolvers apps inside the tunnel get, and the classic TCP
    // recovery (plus Mail.ru's larger buffers) for the document carriers.
    override fun environment(cfg: ClientConfig): Map<String, String> {
        val o = (cfg.kernelConfig as KernelConfig.PaperFlux).config
        return buildMap {
            put("PAPERFLUX_DNS_PRIMARY", TUN_DNS[0])
            put("PAPERFLUX_DNS_SECONDARY", TUN_DNS[1])
            if (o.transport in setOf("yandex", "vyandex", "mailru")) put("PAPERFLUX_TCP_RECOVERY", "classic")
            if (o.transport == "mailru") put("PAPERFLUX_TCP_BUFFER_PRESET", "upstream")
        }
    }

    // Periodic counters and lane summaries - parsed, but not worth a log line at info level.
    override fun isNoise(line: String): Boolean = line.contains("[PAPERFLUX_STATS]") || line.contains("[PAPERFLUX_LANES]")

    override suspend fun parseLogLine(line: String, lower: String, state: BinaryOutputState, ctx: KernelLogContext, cfg: ClientConfig): Boolean {
        val o = (cfg.kernelConfig as? KernelConfig.PaperFlux)?.config ?: return false

        // 1. Hard errors: a check the official client solves over its IPC bridge (not here), a
        // profile pointing at the wrong kind of document, a Session that can't be set up at all.
        when {
            line.contains("YANDEX_LOGIN_REQUIRED") -> return failFast(ctx.getString(R.string.error_openflux_yandex_login), state, ctx)
            line.contains("CAPTCHA challenge") -> return failFast(ctx.getString(R.string.error_openflux_yandex_captcha), state, ctx)
            line.contains("YANDEX_VOLGA_REQUIRED") -> return failFast(ctx.getString(R.string.error_paperflux_volga_mismatch), state, ctx)
            line.contains("Session setup:") || lower.startsWith("panic:") -> return failFast(line, state, ctx)
        }

        // 2. The tunnel itself: a real DNS/TCP probe through the exit (tunnel/health.go) up or down.
        if (line.contains("[PAPERFLUX] TUNNEL_READY") || line.contains("[PAPERFLUX] TUNNEL_HEALTHY")) {
            if (CoreServiceState.status.value !is CoreStatus.Suppressed) {
                CoreServiceState.setStatus(CoreStatus.Connected)
                ctx.updateNotification(ctx.getString(R.string.core_active))
            }
            state.startupEmitted = true
            return false
        }
        if (line.contains("[PAPERFLUX] TUNNEL_LOST") || line.contains("[PAPERFLUX] PEER_LOST")) {
            if (CoreServiceState.status.value is CoreStatus.Connected) markConnecting()
            state.startupEmitted = true
            return false
        }

        // 3. Authenticated with the exit: in TUN mode the core opens its descriptor socket right
        // after this - the VPN can be built and handed over now. Earlier, its handover would give
        // up while the core is still authenticating. Only this one of the TRANSPORT_AUTH_OK lines:
        // the later "encrypted Session data carrier ready" comes once it took the descriptor and
        // stopped listening, so a second handover would only fail.
        if (line.contains("[PAPERFLUX] TRANSPORT_AUTH_OK") && line.contains("waiting for TUN descriptor")) {
            ctx.onNativeTunConfig(o.clientIp, TUN_DNS, TUN_MTU)
        }

        // 4. Progress - without it the binary never leaves Starting on its own.
        if (line.contains("=== Universal Bypass Tool ===") || line.contains("[PAPERFLUX] ")) {
            if (CoreServiceState.status.value !is CoreStatus.Connected && canUpdateConnectingStatus()) markConnecting()
            state.startupEmitted = true
        }

        // 5. Traffic counters for the TUN mode, in place of hev's.
        STATS_REGEX.find(line)?.let { m ->
            val rx = m.groupValues[1].toLongOrNull()
            val tx = m.groupValues[2].toLongOrNull()
            if (rx != null && tx != null) ctx.onNativeTunTraffic(rx, tx)
        }
        return false
    }

    private fun failFast(message: String, state: BinaryOutputState, ctx: KernelLogContext): Boolean {
        if (CoreServiceState.status.value !is CoreStatus.Suppressed) {
            CoreServiceState.setStatus(CoreStatus.Error(message))
            ctx.updateNotification(ctx.getString(R.string.error_connecting))
        }
        state.startupFailed = true
        return true
    }

    // The official client's defaults (NetworkSettings): Yandex DNS, MTU 1400 - the size its
    // built-in tun2socks reads the TUN with too.
    private val TUN_DNS = listOf("77.88.8.8", "77.88.8.1")
    private const val TUN_MTU = 1400

    private val SERVER_REGEX = Regex("[A-Za-z0-9.-]+")
    private val STATS_REGEX = Regex("""\[PAPERFLUX_STATS] rx=(\d+) tx=(\d+)""")
}
