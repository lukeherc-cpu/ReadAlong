package com.example.readalong.desktop.ui

import com.example.readalong.audible.AudibleAuth
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.JFrame
import javax.swing.SwingUtilities

/**
 * Amazon's sign-in page in a ReadAlong window (JavaFX's WebKit), so nobody has to copy the final
 * address: the window watches where the page goes and hands back Amazon's "maplanding" redirect,
 * the same way the phone's WebView does. The phone's iPhone Safari user agent keeps Amazon on the
 * mobile sign-in that the Audible device registration expects.
 */
object AudibleSignInWindow {
    private const val IPHONE_SAFARI = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148"
    private var open: JFrame? = null

    /**
     * Opens [url] in a sign-in window. [onRedirect] gets Amazon's final address (once), and [onClosed] runs if the
     * person closes the window first. Returns false when the built-in browser can't start here, so the caller can
     * fall back to the system browser.
     */
    fun open(url: String, title: String, onRedirect: (String) -> Unit, onClosed: () -> Unit): Boolean = try {
        close()
        // Creating the panel starts the JavaFX toolkit; keep it alive between sign-ins.
        val panel = javafx.embed.swing.JFXPanel()
        javafx.application.Platform.setImplicitExit(false)
        var finished = false
        val frame = JFrame(title).apply {
            defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
            setSize(460, 760)
            setLocationRelativeTo(null)
            contentPane.add(panel)
            addWindowListener(object : WindowAdapter() {
                override fun windowClosed(e: WindowEvent?) { if (open === this@apply) open = null; if (!finished) onClosed() }
            })
        }
        javafx.application.Platform.runLater {
            val view = javafx.scene.web.WebView()
            val engine = view.engine
            engine.userAgent = IPHONE_SAFARI
            fun check(location: String?) {
                if (finished || location == null || !AudibleAuth.isFinalRedirect(location)) return
                finished = true
                // The maplanding page itself is a "not found" page; there's no need to show it.
                engine.loadWorker.cancel()
                SwingUtilities.invokeLater { frame.dispose() }
                onRedirect(location)
            }
            engine.locationProperty().addListener { _, _, location -> check(location) }
            panel.scene = javafx.scene.Scene(view)
            engine.load(url)
        }
        frame.isVisible = true
        open = frame
        true
    } catch (failure: Throwable) {
        // Missing natives (an unusual platform) or no display for JavaFX.
        System.err.println("ReadAlong: the sign-in window couldn't open: $failure")
        false
    }

    fun close() { open?.let { frame -> open = null; SwingUtilities.invokeLater { frame.dispose() } } }

    /** The fallback's helper: Amazon's final address if it's on the clipboard now. */
    fun redirectOnClipboard(): String? = runCatching {
        (Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as? String)?.trim()?.takeIf { AudibleAuth.isFinalRedirect(it) }
    }.getOrNull()
}
