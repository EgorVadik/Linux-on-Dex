package com.crunzex.linuxondex

import android.graphics.Bitmap
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.crunzex.linuxondex.display.vnc.RfbClient
import com.crunzex.linuxondex.engine.qemu.QemuAccelerator
import com.crunzex.linuxondex.engine.qemu.QemuVmEngine
import com.crunzex.linuxondex.engine.runtime.PayloadInstaller
import com.crunzex.linuxondex.engine.runtime.VmPaths
import com.crunzex.linuxondex.vm.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import kotlin.time.Duration.Companion.seconds

/** Boots an authenticated official Debian disk through the same import and
 * QEMU code used by the app, then exercises Docker in the running guest. */
@RunWith(AndroidJUnit4::class)
class PrivateVmIntegrationTest {
    @Test
    fun officialDebianBootsWithPrivateDisplayAndRunsDocker() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val paths = VmPaths(context)
        val source = File(context.getExternalFilesDir(null), "debian-official.qcow2")
        assertTrue("Push the verified official Debian disk before running this test", source.isFile)
        val digest = MessageDigest.getInstance("SHA-256")
        source.inputStream().use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        assertEquals("Official input image differs from the reviewed disk",
            "39fefc05e50b2d02f416f78b79c400e3af6b81215fa8d15c671f48398f7f335c",
            digest.digest().joinToString("") { "%02x".format(it) })

        val images = PreparedImageRepository(context, paths)
        val image = images.importFromDocument(Uri.fromFile(source))
        val config = VmConfig(
            id = "private-test", name = "Private Debian test",
            cpu = CpuConfig(coreCount = 2), memoryMb = 2048,
            storage = StorageConfig(diskSizeGb = 24),
            preparedImage = images.toConfig(image), bootOrder = BootOrder.DISK_FIRST,
        )
        val engine = QemuVmEngine(paths, PayloadInstaller(context, paths),
            DiskImageManager(paths), QemuAccelerator.TCG)
        engine.start(config)
        try {
            val deadline = System.currentTimeMillis() + 15 * 60_000L
            while (!engine.readSerialLog().contains("Private local console for dex") &&
                System.currentTimeMillis() < deadline) {
                Thread.sleep(1000)
            }
            assertTrue("First boot did not finish: ${engine.readSerialLog().takeLast(4000)}",
                engine.readSerialLog().contains("Private local console for dex"))
            val running = engine.state.value as VmState.Running
            assertNotNull(running.vncSocketPath)
            assertTrue(File(running.vncSocketPath!!).exists())
            RfbClient("127.0.0.1", config.vncPort, object : RfbClient.Listener {
                override fun onFramebufferReady(bitmap: Bitmap) = Unit
                override fun onFrameUpdated() = Unit
                override fun onDisconnected(reason: String) = Unit
            }, unixSocketPath = running.vncSocketPath).use { it.connect() }
            val tcpIsOpen = runCatching {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", config.vncPort), 1000) }
            }.isSuccess
            assertFalse("VNC must not expose an unauthenticated TCP listener", tcpIsOpen)

            val console = engine.openSerialConsole() ?: error("No private serial console")
            console.use {
                // A connected QEMU serial socket must be drained, otherwise
                // boot/package output eventually blocks the guest console.
                Thread {
                    runCatching {
                        val buffer = ByteArray(8192)
                        while (console.read(buffer) >= 0) { /* logged by QEMU */ }
                    }
                }.apply { isDaemon = true; start() }
                // The guest's terminal-size query times out when this test
                // attaches a raw console rather than a terminal emulator.
                Thread.sleep(3000)
                val command = "sudo -n sh -c 'set -e; " +
                    "test \"\$(passwd -S dex | cut -d\" \" -f2)\" = L; " +
                    "sshd -T | grep -q \"passwordauthentication no\"; " +
                    "apt-get update; " +
                    "DEBIAN_FRONTEND=noninteractive apt-get install -y docker.io docker-compose nodejs npm python3 python3-venv git build-essential curl; " +
                    "systemctl enable --now docker; " +
                    "docker info; docker run --rm hello-world; " +
                    "docker-compose version; " +
                    "docker volume create private-proof; " +
                    "docker run --rm -v private-proof:/proof alpine:3.22 touch /proof/persisted; " +
                    "docker run --rm -v private-proof:/proof alpine:3.22 test -f /proof/persisted; " +
                    "docker run --rm alpine:3.22 nslookup deb.debian.org; " +
                    "docker volume rm private-proof; df -h /; " +
                    "node --version; npm --version; python3 --version; " +
                    "printf PRIVATE_; printf VM_DOCKER_OK'; " +
                    "status=\$?; printf PRIVATE_; printf VM_EXIT_%s \"\$status\"\n"
                console.write(command.toByteArray())
                val finishBy = System.currentTimeMillis() + 30 * 60_000L
                while (!engine.readSerialLog().contains("PRIVATE_VM_EXIT_") &&
                    System.currentTimeMillis() < finishBy) {
                    Thread.sleep(1000)
                }
                assertTrue("Guest development stack failed: ${engine.readSerialLog().takeLast(6000)}",
                    engine.readSerialLog().contains("PRIVATE_VM_DOCKER_OK") &&
                        engine.readSerialLog().contains("PRIVATE_VM_EXIT_0"))
            }
            println("PRIVATE_VM_IMAGE=${image.diskFile.absolutePath}")
        } finally {
            engine.stop(180.seconds)
        }
    }
}
