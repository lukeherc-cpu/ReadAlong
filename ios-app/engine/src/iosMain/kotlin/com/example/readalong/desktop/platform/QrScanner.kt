package com.example.readalong.desktop.platform

import kotlinx.cinterop.ObjCAction
import platform.AVFoundation.*
import platform.QuartzCore.CATransaction
import platform.UIKit.*
import platform.darwin.NSObject
import platform.darwin.dispatch_get_main_queue

/**
 * Scans a QR code with the camera, for linking to ReadAlong Desktop (Settings › Link a phone shows the code).
 * [onCode] gets the first code read, on the main thread; [onError] says why the camera couldn't start.
 */
object QrScanner {
    private var sheet: Scanner? = null

    fun available(): Boolean = AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeVideo) != null

    fun scan(onCode: (String) -> Unit, onError: (String) -> Unit) {
        when (AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo)) {
            AVAuthorizationStatusAuthorized -> present(onCode, onError)
            AVAuthorizationStatusNotDetermined -> AVCaptureDevice.requestAccessForMediaType(AVMediaTypeVideo) { granted ->
                onMain { if (granted) present(onCode, onError) else onError("ReadAlong needs the camera to scan the code. You can paste the code instead.") }
            }
            else -> onError("Camera access is off for ReadAlong. Turn it on in the Settings app, or paste the code instead.")
        }
    }

    private fun present(onCode: (String) -> Unit, onError: (String) -> Unit) {
        val presenter = topViewController() ?: return onError("The camera couldn't open.")
        val camera = AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeVideo) ?: return onError("This device has no camera. Paste the code instead.")
        val input = AVCaptureDeviceInput.deviceInputWithDevice(camera, null) ?: return onError("The camera couldn't start. Paste the code instead.")
        val scanner = Scanner(input, onCode)
        sheet = scanner
        presenter.presentViewController(UINavigationController(rootViewController = scanner).apply { modalPresentationStyle = UIModalPresentationFullScreen }, animated = true, completion = null)
    }

    private class Scanner(input: AVCaptureDeviceInput, val onCode: (String) -> Unit) : UIViewController(nibName = null, bundle = null),
        AVCaptureMetadataOutputObjectsDelegateProtocol {
        private val session = AVCaptureSession()
        private val output = AVCaptureMetadataOutput()
        private var done = false

        init {
            if (session.canAddInput(input)) session.addInput(input)
            if (session.canAddOutput(output)) session.addOutput(output)
            output.setMetadataObjectsDelegate(this, dispatch_get_main_queue())
            output.metadataObjectTypes = listOf(AVMetadataObjectTypeQRCode)
        }

        override fun viewDidLoad() {
            super.viewDidLoad()
            title = "Scan the code on your computer"
            view.backgroundColor = UIColor.blackColor
            navigationItem.leftBarButtonItem = UIBarButtonItem(barButtonSystemItem = UIBarButtonSystemItem.UIBarButtonSystemItemCancel,
                target = this, action = platform.objc.sel_registerName("cancel"))
            val preview = AVCaptureVideoPreviewLayer(session = session)
            preview.videoGravity = AVLayerVideoGravityResizeAspectFill
            preview.frame = view.bounds
            view.layer.addSublayer(preview)
            platform.darwin.dispatch_async(platform.darwin.dispatch_get_global_queue(platform.darwin.DISPATCH_QUEUE_PRIORITY_HIGH.toLong(), 0u)) { session.startRunning() }
        }

        override fun viewDidLayoutSubviews() {
            super.viewDidLayoutSubviews()
            CATransaction.begin(); CATransaction.setDisableActions(true)
            view.layer.sublayers?.forEach { (it as? AVCaptureVideoPreviewLayer)?.frame = view.bounds }
            CATransaction.commit()
        }

        @ObjCAction fun cancel() { finish(null) }

        override fun captureOutput(output: AVCaptureOutput, didOutputMetadataObjects: List<*>, fromConnection: AVCaptureConnection) {
            val text = didOutputMetadataObjects.filterIsInstance<AVMetadataMachineReadableCodeObject>().firstNotNullOfOrNull { it.stringValue } ?: return
            finish(text)
        }

        private fun finish(code: String?) {
            if (done) return
            done = true
            session.stopRunning()
            dismissViewControllerAnimated(true) { sheet = null; code?.let(onCode) }
        }
    }
}
