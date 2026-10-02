package app.slate.tablet.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TermuxSetupTest {
    @Test fun setupScriptIsValidShellAndInstallsTheBridge() {
        val script = TermuxClaude.setupScript("abc123")
        val f = File.createTempFile("slate-setup", ".sh").apply { writeText(script); deleteOnExit() }
        val check = ProcessBuilder("sh", "-n", f.absolutePath).redirectErrorStream(true).start()
        val output = check.inputStream.bufferedReader().readText()
        assertEquals("sh -n: $output", 0, check.waitFor())
        assertTrue(script.contains("printf '%s' 'abc123' > \"\$HOME/.slate/token\""))
        assertTrue(script.contains("curl -fsS http://127.0.0.1:${TermuxClaude.SETUP_PORT}/slate-bridge.js"))
        // The launcher's lines must start at column 0 so the heredoc and shebang work.
        assertTrue(script.lines().contains("#!/data/data/com.termux/files/usr/bin/sh"))
        assertTrue(script.lines().contains("EOS"))
        assertTrue(script.contains("exec node \"\$HOME/.slate/slate-bridge.js\" \"\$@\""))
    }
}
