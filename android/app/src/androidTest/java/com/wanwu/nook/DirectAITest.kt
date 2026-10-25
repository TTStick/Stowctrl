package com.wanwu.nook

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wanwu.nook.image.SvgDocument
import com.wanwu.nook.net.DirectClaude
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.URL

/** The Anthropic Java SDK on the Android runtime, against tools/fake_claude.py on the host. */
@RunWith(AndroidJUnit4::class)
class DirectAITest {
    @Test fun drawsIconWithoutServer() = runBlocking<Unit> {
        val base = "http://10.0.2.2:18200"
        val up = (1..8).any { runCatching { URL("$base/calls").readText() }.isSuccess.also { ok -> if (!ok) Thread.sleep(2000) } }
        assumeTrue("fake Claude not running", up)
        assertTrue(runCatching { DirectClaude("wrong-key", base, "claude-opus-5-5").checkKey() }.isFailure)
        DirectClaude("test-key", base, "claude-opus-5-5").checkKey()
        val svg = DirectClaude("test-key", base, "claude-opus-5-5").drawIcon("汤勺")
        assertNotNull(SvgDocument.parse(svg))
    }
}
