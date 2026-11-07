package com.crunzex.linuxondex.engine.proot

import com.crunzex.linuxondex.engine.runtime.VmPaths
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootfsOrphanPruningTest {

    private val root: File = Files.createTempDirectory("rootfs-prune").toFile()
    private val paths = VmPaths(
        nativeLibraryDir = root.resolve("lib"),
        filesDir = root.resolve("files"),
        cacheDir = root.resolve("cache"),
        externalFilesDir = { kind -> root.resolve("ext/$kind") },
    )

    /** A one-file ustar archive, gzipped: enough for the extractor. */
    private fun tinyRootfsArchive(file: File) {
        val content = "devbox\n".toByteArray()
        val header = ByteArray(512)
        fun put(offset: Int, text: String) = text.toByteArray().copyInto(header, offset)
        put(0, "etc/hostname")
        put(100, "0000644\u0000")
        put(108, "0000000\u0000")
        put(116, "0000000\u0000")
        put(124, String.format("%011o", content.size) + "\u0000")
        put(136, "00000000000\u0000")
        put(156, "0")
        put(257, "ustar\u0000")
        put(263, "00")
        "        ".toByteArray().copyInto(header, 148)
        val checksum = header.sumOf { it.toInt() and 0xff }
        put(148, String.format("%06o", checksum) + "\u0000 ")
        val tar = ByteArrayOutputStream().apply {
            write(header)
            write(content)
            write(ByteArray(512 - content.size))
            write(ByteArray(1024))
        }.toByteArray()
        file.parentFile.mkdirs()
        GZIPOutputStream(file.outputStream()).use { it.write(tar) }
    }

    @Test
    fun `unpacking an image removes trees whose archive is gone, never homes`() {
        val images = paths.vmImagesDir!!.apply { mkdirs() }
        val kept = images.resolve("devbox-1.rootfs.tar.gz").also(::tinyRootfsArchive)
        val installer = RootfsImageInstaller(paths)
        installer.ensureExtracted(kept)

        // An older image whose archive was deleted outside the app.
        val orphan = paths.prootRootfsDirFor("devbox-0.rootfs.tar.gz").apply { mkdirs() }
        orphan.resolve(".linux-on-dex-rootfs-stamp").writeText("old")
        // Something this class did not create stays untouched.
        val foreign = paths.prootImagesDir.resolve("not-an-extraction").apply { mkdirs() }
        val home = paths.prootHomesDir.resolve("devbox").apply { mkdirs() }
        home.resolve("keep.txt").writeText("work")

        val next = images.resolve("devbox-2.rootfs.tar.gz").also(::tinyRootfsArchive)
        installer.ensureExtracted(next)

        assertFalse("orphaned tree must be removed", orphan.exists())
        assertTrue("tree of an archive still present stays", installer.isExtracted(kept))
        assertTrue("new image unpacked", installer.isExtracted(next))
        assertTrue("unknown directories are left alone", foreign.exists())
        assertTrue("homes are never touched", home.resolve("keep.txt").exists())
    }
}
