package com.crunzex.linuxondex.service

import android.net.TrafficStats
import android.system.Os
import java.io.File

/**
 * Tells whether the guest did anything since the last [sample]: used CPU or
 * moved network traffic.
 *
 * Every guest process (PRoot, its tracees, QEMU) runs as a child of this app
 * under the app's uid, so walking /proc for that uid finds all of them. CPU is
 * counted per process — a process that exits between samples must not make
 * the total go *down* and hide work that happened — and includes the time of
 * children each one has already reaped (compilers, git, short shell tools).
 *
 * Network is the uid's traffic counter, which catches the case CPU misses:
 * an agent waiting on a streamed API response uses almost no CPU but must
 * not have the phone fall asleep under it.
 */
internal class GuestActivityMonitor(
    private val uid: Int = android.os.Process.myUid(),
    private val ownPid: Int = android.os.Process.myPid(),
    private val procDir: File = File("/proc"),
    private val networkBytes: (Int) -> Long = { u ->
        val rx = TrafficStats.getUidRxBytes(u)
        val tx = TrafficStats.getUidTxBytes(u)
        if (rx < 0 || tx < 0) -1 else rx + tx
    },
) {
    private var lastTicksByPid: Map<Int, Long> = emptyMap()
    private var lastNetworkBytes: Long = -1
    private var primed = false

    data class Sample(val cpuTicks: Long, val networkBytes: Long) {
        fun isActive(cpuThresholdTicks: Long, networkThresholdBytes: Long): Boolean =
            cpuTicks >= cpuThresholdTicks || networkBytes >= networkThresholdBytes
    }

    /** Activity since the previous call; the first call only sets a baseline. */
    fun sample(): Sample {
        val ticks = readGuestTicks()
        val net = runCatching { networkBytes(uid) }.getOrDefault(-1)
        var cpuDelta = 0L
        for ((pid, now) in ticks) {
            val before = lastTicksByPid[pid]
            cpuDelta += if (before == null) {
                if (primed) now else 0 // a new process: all of its time is new
            } else {
                (now - before).coerceAtLeast(0)
            }
        }
        val netDelta = if (net >= 0 && lastNetworkBytes >= 0) {
            (net - lastNetworkBytes).coerceAtLeast(0)
        } else {
            0
        }
        lastTicksByPid = ticks
        lastNetworkBytes = net
        primed = true
        return Sample(cpuDelta, netDelta)
    }

    private fun readGuestTicks(): Map<Int, Long> {
        val result = HashMap<Int, Long>()
        val entries = procDir.list() ?: return result
        for (name in entries) {
            val pid = name.toIntOrNull() ?: continue
            if (pid == ownPid) continue
            val ticks = runCatching {
                if (Os.stat("${procDir.path}/$name").st_uid != uid) return@runCatching null
                parseCpuTicks(File(procDir, "$name/stat").readText())
            }.getOrNull() ?: continue
            result[pid] = ticks
        }
        return result
    }

    companion object {
        /**
         * utime + stime + cutime + cstime from a /proc/<pid>/stat line. The
         * command name may contain spaces and parentheses, so fields are
         * counted from the last ')'.
         */
        fun parseCpuTicks(stat: String): Long? {
            val tail = stat.substringAfterLast(')', "").trim()
            if (tail.isEmpty()) return null
            val fields = tail.split(' ')
            // tail[0] is field 3 (state); utime is field 14 → tail index 11.
            if (fields.size < 15) return null
            return (11..14).sumOf { fields[it].toLongOrNull() ?: 0L }
        }
    }
}
