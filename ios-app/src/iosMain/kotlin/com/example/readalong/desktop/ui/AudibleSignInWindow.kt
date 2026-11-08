package com.example.readalong.desktop.ui

import com.example.readalong.audible.AudibleAuth
import com.example.readalong.desktop.platform.topViewController
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.UIKit.UIAdaptivePresentationControllerDelegateProtocol
import platform.UIKit.UIBarButtonItem
import platform.UIKit.UIBarButtonSystemItem
import platform.UIKit.UINavigationController
import platform.UIKit.UIPasteboard
import platform.UIKit.navigationItem
import platform.UIKit.presentationController
import platform.UIKit.UIPresentationController
import platform.UIKit.UIViewController
import platform.UIKit.UIModalPresentationPageSheet
import platform.WebKit.WKNavigationAction
import platform.WebKit.WKNavigationActionPolicy
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.WebKit.WKWebsiteDataStore
import platform.darwin.NSObject
import kotlinx.cinterop.ObjCAction

/**
 * Amazon's sign-in page in a sheet over ReadAlong (WKWebView), so nobody has to copy the final address: the
 * sheet watches where the page goes and hands back Amazon's "maplanding" redirect, the same way the Android
 * app's WebView and the desktop's sign-in window do.
 */
object AudibleSignInWindow {
    private const val IPHONE_SAFARI = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148"
    private var sheet: UIViewController? = null
    // UIKit holds delegates weakly; these keep them alive while the sheet is up.
    private var navigation: Navigation? = null
    private var dismissal: Dismissal? = null
    private var cancel: Cancel? = null

    private class Navigation(val onRedirect: (String) -> Unit) : NSObject(), WKNavigationDelegateProtocol {
        var finished = false
        override fun webView(webView: WKWebView, decidePolicyForNavigationAction: WKNavigationAction, decisionHandler: (WKNavigationActionPolicy) -> Unit) {
            val location = decidePolicyForNavigationAction.request.URL?.absoluteString
            if (!finished && location != null && AudibleAuth.isFinalRedirect(location)) {
                finished = true
                // The maplanding page itself is a "not found" page; there's no need to show it.
                decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyCancel)
                close()
                onRedirect(location)
                return
            }
            decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyAllow)
        }
    }

    private class Dismissal(val onClosed: () -> Unit) : NSObject(), UIAdaptivePresentationControllerDelegateProtocol {
        override fun presentationControllerDidDismiss(presentationController: UIPresentationController) { forget(); onClosed() }
    }

    private class Cancel(val onClosed: () -> Unit) : NSObject() {
        @ObjCAction fun tapped() { close(); onClosed() }
    }

    /** Opens [url] in a sign-in sheet. [onRedirect] gets Amazon's final address (once); [onClosed] runs if the person closes the sheet first. */
    fun open(url: String, title: String, onRedirect: (String) -> Unit, onClosed: () -> Unit): Boolean {
        close()
        val presenter = topViewController() ?: return false
        val configuration = WKWebViewConfiguration().apply { websiteDataStore = WKWebsiteDataStore.nonPersistentDataStore() }
        val web = WKWebView(frame = platform.CoreGraphics.CGRectMake(0.0, 0.0, 0.0, 0.0), configuration = configuration)
        web.customUserAgent = IPHONE_SAFARI
        val nav = Navigation(onRedirect)
        web.navigationDelegate = nav
        val page = UIViewController(nibName = null, bundle = null)
        page.view = web
        page.title = title.substringBefore(" · ")
        val canceller = Cancel(onClosed)
        page.navigationItem.leftBarButtonItem = UIBarButtonItem(barButtonSystemItem = UIBarButtonSystemItem.UIBarButtonSystemItemCancel,
            target = canceller, action = platform.objc.sel_registerName("tapped"))
        val container = UINavigationController(rootViewController = page)
        container.modalPresentationStyle = UIModalPresentationPageSheet
        val dismissed = Dismissal(onClosed)
        container.presentationController?.delegate = dismissed
        navigation = nav; dismissal = dismissed; cancel = canceller; sheet = container
        NSURL.URLWithString(url)?.let { web.loadRequest(NSURLRequest.requestWithURL(it)) } ?: return false
        presenter.presentViewController(container, animated = true, completion = null)
        return true
    }

    private fun forget() { sheet = null; navigation = null; dismissal = null; cancel = null }

    fun close() {
        val open = sheet ?: return
        forget()
        open.dismissViewControllerAnimated(true, completion = null)
    }

    /** Amazon's final address if it's on the clipboard now. */
    fun redirectOnClipboard(): String? = UIPasteboard.generalPasteboard.string?.trim()?.takeIf { AudibleAuth.isFinalRedirect(it) }
}
