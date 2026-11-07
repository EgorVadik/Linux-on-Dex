package com.crunzex.linuxondex

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
import kotlin.time.Duration.Companion.seconds

@RunWith(AndroidJUnit4::class)
class PrivateGuestToolsTest {
    @Test
    fun installAndCheckAgentCliProgramsWithoutCredentials() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val imagePath = InstrumentationRegistry.getArguments().getString("imagePath")
            ?: error("Pass the imagePath produced by the Debian integration test")
        val image = File(imagePath)
        assertTrue(image.isFile)
        val paths = VmPaths(context)
        val engine = QemuVmEngine(paths, PayloadInstaller(context, paths),
            DiskImageManager(paths), QemuAccelerator.TCG)
        val config = VmConfig(
            id = "private-test", name = "Private Debian tools",
            cpu = CpuConfig(coreCount = 2), memoryMb = 2048,
            storage = StorageConfig(diskSizeGb = 24),
            preparedImage = PreparedImageConfig("Private Debian", imagePath,
                firstBootCompleted = true), bootOrder = BootOrder.DISK_FIRST,
        )
        engine.start(config)
        try {
            val bootDeadline = System.currentTimeMillis() + 10 * 60_000L
            while (!engine.readSerialLog().contains("dex@dex") &&
                System.currentTimeMillis() < bootDeadline) Thread.sleep(1000)
            assertTrue("No guest shell: ${engine.readSerialLog().takeLast(3000)}",
                engine.readSerialLog().contains("dex@dex"))
            val script = instrumentation.context.assets.open("private-bootstrap.sh")
                .bufferedReader().use { it.readText() }
            val console = engine.openSerialConsole() ?: error("No serial console")
            console.use {
                Thread {
                    runCatching {
                        val bytes = ByteArray(8192)
                        while (console.read(bytes) >= 0) { }
                    }
                }.apply { isDaemon = true; start() }
                Thread.sleep(3000)
                val command = "cat > /tmp/private-bootstrap.sh <<'PRIVATE_BOOTSTRAP_EOF'\n" +
                    script + "\nPRIVATE_BOOTSTRAP_EOF\n" +
                    "bash /tmp/private-bootstrap.sh; status=\$?; printf PRIVATE_; " +
                    "printf TOOLS_EXIT_%s \"\$status\"\n"
                console.write(command.toByteArray())
                val deadline = System.currentTimeMillis() + 20 * 60_000L
                while (!engine.readSerialLog().contains("PRIVATE_TOOLS_EXIT_") &&
                    System.currentTimeMillis() < deadline) Thread.sleep(1000)
                assertTrue("Agent tool setup failed: ${engine.readSerialLog().takeLast(6000)}",
                    engine.readSerialLog().contains("PRIVATE_GUEST_TOOLS_OK") &&
                        engine.readSerialLog().contains("PRIVATE_TOOLS_EXIT_0"))
            }
        } finally {
            engine.stop(180.seconds)
        }
    }
}
