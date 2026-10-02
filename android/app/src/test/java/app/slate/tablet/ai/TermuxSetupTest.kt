package app.slate.tablet.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TermuxSetupTest {
    @Test fun setupScriptIsValidShellAndInstallsTheBridge() {
        val bridge = File("src/main/assets/termux/slate-bridge.js").readText()
        val script = TermuxClaude.setupScript("abc123", bridge)
        val f = File.createTempFile("slate-setup", ".sh").apply { writeText(script); deleteOnExit() }
        val check = ProcessBuilder("sh", "-n", f.absolutePath).redirectErrorStream(true).start()
        val output = check.inputStream.bufferedReader().readText()
        assertEquals("sh -n: $output", 0, check.waitFor())
        assertTrue(script.contains("printf '%s' 'abc123' > \"\$HOME/.slate/token\""))
        // The bridge travels inside the script, byte for byte.
        val body = script.substringAfter("<<'SLATE_EOF'\n").substringBefore("\nSLATE_EOF\n")
        assertEquals(bridge.trimEnd(), body)
        // The launcher's lines must start at column 0 so the heredoc and shebang work.
        assertTrue(script.lines().contains("#!/data/data/com.termux/files/usr/bin/sh"))
        assertTrue(script.lines().contains("EOS"))
        assertTrue(script.contains("exec node \"\$HOME/.slate/slate-bridge.js\" \"\$@\""))

        // Run it for real in a scratch home (where sh and node exist): it must install a working bridge.
        if (ProcessBuilder("sh", "-c", "command -v node").start().waitFor() != 0) return
        val home = kotlin.io.path.createTempDirectory("slate-home").toFile()
        File(home, "usr/bin").mkdirs()
        val run = ProcessBuilder("sh", f.absolutePath).redirectErrorStream(true).apply {
            environment()["HOME"] = home.absolutePath
            environment()["PREFIX"] = File(home, "usr").absolutePath
        }.start()
        val log = run.inputStream.bufferedReader().readText()
        assertEquals(log, 0, run.waitFor())
        assertEquals("abc123", File(home, ".slate/token").readText())
        assertEquals(bridge.trimEnd() + "\n", File(home, ".slate/slate-bridge.js").readText())
        assertTrue(File(home, "usr/bin/slate-claude").canExecute())
        assertEquals(0, ProcessBuilder("node", "--check", File(home, ".slate/slate-bridge.js").absolutePath).start().waitFor())
        home.deleteRecursively()
    }
}
