package app.slate.tablet.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class TermuxSetupTest {
    private val assets = File("src/main/assets/termux")
    private val bridge = File(assets, "slate-bridge.js").readText()
    private val launcher = File(assets, "slate-claude.sh").readText()
    private val starter = File(assets, "slate-bridge-start.sh").readText()
    private val script = TermuxClaude.setupScript("abc123", bridge, launcher, starter)

    @Test fun setupScriptIsValidShellAndCarriesTheFilesIntact() {
        val f = File.createTempFile("slate-setup", ".sh").apply { writeText(script); deleteOnExit() }
        val check = ProcessBuilder("sh", "-n", f.absolutePath).redirectErrorStream(true).start()
        assertEquals("sh -n: " + check.inputStream.bufferedReader().readText(), 0, check.waitFor())
        assertTrue(script.contains("printf '%s' 'abc123' > \"\$HOME/.slate/token\""))
        for (shell in listOf(launcher, starter)) {
            val c = ProcessBuilder("sh", "-n", "/dev/stdin").start()
            c.outputStream.use { it.write(shell.toByteArray()) }
            assertEquals(0, c.waitFor())
        }
    }

    /**
     * End to end in a scratch "Termux": run the setup, then slate-claude. Claude Code exists only
     * inside a fake proot-distro Ubuntu (whose login joins words like the real one), so the bridge
     * must start in there and answer on 127.0.0.1.
     */
    @Test fun slateClaudeStartsTheBridgeInsideTheDistro() {
        if (ProcessBuilder("sh", "-c", "command -v node").start().waitFor() != 0) return
        val node = ProcessBuilder("sh", "-c", "command -v node").start().inputStream.bufferedReader().readText().trim()
        val tmp = kotlin.io.path.createTempDirectory("slate-termux").toFile()
        try {
            val home = File(tmp, "home").apply { mkdirs() }
            val prefix = File(tmp, "usr").apply { File(this, "bin").mkdirs() }
            val roots = File(tmp, "rootfs")
            val ubuntu = File(roots, "ubuntu").apply { File(this, "root/.local/bin").mkdirs() }
            val fakeBin = File(tmp, "fakebin").apply { mkdirs() }
            File("../termux-test/fake-proot-distro").copyTo(File(fakeBin, "proot-distro")).setExecutable(true)
            File("../termux-test/fake-claude").copyTo(File(ubuntu, "root/.local/bin/claude")).setExecutable(true)
            java.nio.file.Files.createSymbolicLink(File(fakeBin, "node").toPath(), File(node).toPath())
            val env = mapOf(
                // Like the tablet: slate-claude can't see the distros' files from outside (wrong/unknown
                // path), so it must find Claude Code by asking proot-distro.
                "HOME" to home.absolutePath, "PREFIX" to prefix.absolutePath, "SLATE_ROOTFS_DIR" to File(tmp, "nowhere").absolutePath,
                "FAKE_ROOTS" to roots.absolutePath,
                "PATH" to "${fakeBin.absolutePath}:/usr/bin:/bin", "SLATE_PORT" to "47829",
            )
            fun sh(vararg cmd: String) = ProcessBuilder(*cmd).redirectErrorStream(true).apply { environment().putAll(env) }
            // Like the real `… | base64 -d | sh`: the script isn't on any command line (it pkills old bridges).
            val scriptFile = File(tmp, "setup.sh").apply { writeText(script) }
            val setup = sh("sh", scriptFile.absolutePath).start()
            val setupLog = setup.inputStream.bufferedReader().readText()
            assertEquals(setupLog, 0, setup.waitFor())
            assertEquals(bridge.trimEnd() + "\n", File(home, ".slate/slate-bridge.js").readText())

            val run = sh("sh", File(prefix, "bin/slate-claude").absolutePath).redirectOutput(File(tmp, "run.log")).start()
            var authorised = false
            val deadline = System.currentTimeMillis() + 20_000
            while (System.currentTimeMillis() < deadline && !authorised) {
                Thread.sleep(300)
                authorised = runCatching {
                    val c = URL("http://127.0.0.1:47829/health").openConnection() as HttpURLConnection
                    c.setRequestProperty("x-slate-token", "abc123")
                    c.inputStream.bufferedReader().readText().contains("\"authorised\":true")
                }.getOrDefault(false)
            }
            run.destroy()
            ProcessBuilder("pkill", "-f", tmp.absolutePath).start().waitFor() // the bridge, started under the scratch dir
            val log = File(tmp, "run.log").readText()
            assertTrue("bridge didn't come up:\n$log", authorised)
            assertTrue(log, log.contains("inside ubuntu"))
            assertTrue(log, log.contains("Slate bridge ready")) // inside the distro Claude Code is found plainly
            assertEquals("abc123", File(ubuntu, "root/.slate/token").readText())
        } finally {
            tmp.deleteRecursively()
        }
    }
}
