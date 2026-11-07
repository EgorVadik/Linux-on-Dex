package com.crunzex.linuxondex.display.vnc

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.crunzex.linuxondex.core.AppLog

/**
 * Renders the VNC framebuffer scaled to fit and forwards touch/mouse/
 * keyboard input. Works both as a phone touchscreen (tap = left click,
 * long-press = right click, drag = move with button held) and as a DeX
 * desktop surface (hover moves the pointer, hardware keys type).
 */
@SuppressLint("ViewConstructor")
class VncView(context: Context) : View(context) {

    /** Input sink — set and cleared on the UI thread with the view lifecycle. */
    private var inputTarget: VncInputSink? = null

    private var framebuffer: Bitmap? = null
    private val drawMatrix = Matrix()
    private val inverseDrawMatrix = Matrix()
    private val mappedPoint = FloatArray(2)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var currentButtonMask = 0
    private var lastPointerX = 0
    private var lastPointerY = 0
    private val longPressRunnable = Runnable { performRightClick() }
    private var longPressPending = false
    private var longPressPerformed = false
    private var touchMoved = false
    private val pressedKeys = mutableMapOf<Pair<Int, Int>, Int>()
    private var textConnection: RemoteInputConnection? = null
    private var downX = 0f
    private var downY = 0f

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
    }

    /** Hardware keys should reach the guest without a preliminary tap. */
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post { requestFocus() }
    }

    fun bindInputTarget(target: VncInputSink) {
        inputTarget = target
        currentButtonMask = 0
        requestFocus()
        AppLog.info(SCOPE, "display input ready")
    }

    fun unbindInputTarget(target: VncInputSink) {
        if (inputTarget === target) {
            releaseInput()
            inputTarget = null
            currentButtonMask = 0
            cancelPendingRightClick()
        }
    }

    fun onFramebufferReady(bitmap: Bitmap) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            replaceFramebuffer(bitmap)
        } else {
            mainHandler.post { replaceFramebuffer(bitmap) }
        }
    }

    private fun replaceFramebuffer(bitmap: Bitmap) {
        framebuffer = bitmap
        updateDrawMatrix()
        invalidate()
    }

    fun onFrameUpdated() {
        postInvalidateOnAnimation()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        updateDrawMatrix()
    }

    private fun updateDrawMatrix() {
        val bitmap = framebuffer ?: return
        if (width == 0 || height == 0) return
        val scale = minOf(
            width.toFloat() / bitmap.width,
            height.toFloat() / bitmap.height,
        )
        val offsetX = (width - bitmap.width * scale) / 2f
        val offsetY = (height - bitmap.height * scale) / 2f
        drawMatrix.setScale(scale, scale)
        drawMatrix.postTranslate(offsetX, offsetY)
        drawMatrix.invert(inverseDrawMatrix)
    }

    override fun onDraw(canvas: Canvas) {
        val bitmap = framebuffer ?: return
        synchronized(bitmap) {
            canvas.drawBitmap(bitmap, drawMatrix, paint)
        }
    }

    // ---- Pointer input -----------------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        inputTarget ?: return false
        if (!mapGuestCoordinates(event.x, event.y)) return true
        if (event.actionMasked == MotionEvent.ACTION_DOWN) textConnection?.finishComposingText()

        if (event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE ||
            event.isFromSource(InputDevice.SOURCE_MOUSE) ||
            event.isFromSource(InputDevice.SOURCE_MOUSE_RELATIVE)
        ) {
            return handleMouseTouchEvent(event)
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                requestFocus()
                downX = event.x
                downY = event.y
                touchMoved = false
                longPressPerformed = false
                longPressPending = true
                postDelayed(longPressRunnable, LONG_PRESS_MS)
                movePointer(mappedGuestX, mappedGuestY)
            }
            MotionEvent.ACTION_MOVE -> {
                if (distanceFromDown(event) > TOUCH_SLOP_PX) {
                    cancelPendingRightClick()
                    touchMoved = true
                    // Drag = move with the left button held.
                    currentButtonMask = BUTTON_LEFT
                }
                movePointer(mappedGuestX, mappedGuestY)
            }
            MotionEvent.ACTION_UP -> {
                cancelPendingRightClick()
                if (!touchMoved && !longPressPerformed) performClick()
                currentButtonMask = 0
                movePointer(mappedGuestX, mappedGuestY)
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelPendingRightClick()
                currentButtonMask = 0
                movePointer(mappedGuestX, mappedGuestY)
            }
        }
        return true
    }

    /**
     * A DeX mouse remains an ordinary system pointer; the view never captures
     * or hides it. Android may deliver button-held movement as touch events,
     * so this path mirrors the complete reported button state to RFB.
     */
    private fun handleMouseTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                requestFocus()
                val reportedButtons = androidButtonsToMask(event.buttonState)
                // Samsung DeX may omit BUTTON_PRIMARY from ACTION_DOWN and
                // deliver no separate ACTION_BUTTON_PRESS through an
                // AndroidView. A mouse ACTION_DOWN is nevertheless a primary
                // press by definition, so never degrade it to movement only.
                currentButtonMask = reportedButtons.takeIf { it != 0 } ?: BUTTON_LEFT
                movePointer(mappedGuestX, mappedGuestY)
            }
            MotionEvent.ACTION_MOVE -> {
                val reportedButtons = androidButtonsToMask(event.buttonState)
                if (reportedButtons != 0 || currentButtonMask == 0) {
                    currentButtonMask = reportedButtons
                }
                movePointer(mappedGuestX, mappedGuestY)
            }
            MotionEvent.ACTION_UP -> {
                currentButtonMask = 0
                movePointer(mappedGuestX, mappedGuestY)
            }
            MotionEvent.ACTION_CANCEL -> {
                currentButtonMask = 0
                movePointer(mappedGuestX, mappedGuestY)
            }
            else -> return false
        }
        return true
    }

    // Android dispatches mouse hover here before onGenericMotionEvent. The
    // default clickable View consumes it, so handling hover only there loses it.
    override fun onHoverEvent(event: MotionEvent): Boolean {
        if (inputTarget == null || !mapGuestCoordinates(event.x, event.y)) {
            return super.onHoverEvent(event)
        }
        return when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                movePointer(mappedGuestX, mappedGuestY)
                true
            }
            MotionEvent.ACTION_HOVER_EXIT -> true
            else -> super.onHoverEvent(event)
        }
    }

    override fun onResolvePointerIcon(event: MotionEvent, pointerIndex: Int): PointerIcon =
        PointerIcon.getSystemIcon(context, PointerIcon.TYPE_ARROW)

    /** DeX / mouse: real buttons click, wheel scrolls. */
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val client = inputTarget ?: return false
        if (!mapGuestCoordinates(event.x, event.y)) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_HOVER_ENTER ->
                movePointer(mappedGuestX, mappedGuestY)
            MotionEvent.ACTION_BUTTON_PRESS -> {
                textConnection?.finishComposingText()
                currentButtonMask = androidButtonsToMask(event.buttonState) or
                    androidButtonToMask(event.actionButton)
                requestFocus()
                movePointer(mappedGuestX, mappedGuestY)
            }
            MotionEvent.ACTION_BUTTON_RELEASE -> {
                val releasedButton = androidButtonToMask(event.actionButton)
                currentButtonMask = androidButtonsToMask(event.buttonState) and
                    releasedButton.inv()
                movePointer(mappedGuestX, mappedGuestY)
            }
            MotionEvent.ACTION_SCROLL -> {
                lastPointerX = mappedGuestX
                lastPointerY = mappedGuestY
                val vertical = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                val horizontal = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
                if (vertical != 0f) {
                    pulseWheel(
                        client,
                        if (vertical > 0) BUTTON_WHEEL_UP else BUTTON_WHEEL_DOWN,
                    )
                }
                if (horizontal != 0f) {
                    pulseWheel(
                        client,
                        if (horizontal > 0) BUTTON_WHEEL_RIGHT else BUTTON_WHEEL_LEFT,
                    )
                }
            }
            else -> return super.onGenericMotionEvent(event)
        }
        return true
    }

    private fun performRightClick() {
        longPressPending = false
        longPressPerformed = true
        inputTarget?.let {
            clickAt(lastPointerX, lastPointerY, BUTTON_RIGHT)
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        clickAt(lastPointerX, lastPointerY, BUTTON_LEFT)
        return true
    }

    private fun clickAt(x: Int, y: Int, button: Int) {
        val client = inputTarget ?: return
        client.sendPointerEvent(x, y, currentButtonMask or button)
        client.sendPointerEvent(x, y, currentButtonMask)
    }

    private fun pulseWheel(client: VncInputSink, wheelButton: Int) {
        client.sendPointerEvent(lastPointerX, lastPointerY, currentButtonMask or wheelButton)
        client.sendPointerEvent(lastPointerX, lastPointerY, currentButtonMask)
    }

    private fun movePointer(x: Int, y: Int) {
        lastPointerX = x
        lastPointerY = y
        inputTarget?.sendPointerEvent(x, y, currentButtonMask)
    }

    private var mappedGuestX = 0
    private var mappedGuestY = 0

    private fun mapGuestCoordinates(viewX: Float, viewY: Float): Boolean {
        val bitmap = framebuffer ?: return false
        mappedPoint[0] = viewX
        mappedPoint[1] = viewY
        inverseDrawMatrix.mapPoints(mappedPoint)
        mappedGuestX = mappedPoint[0].toInt().coerceIn(0, bitmap.width - 1)
        mappedGuestY = mappedPoint[1].toInt().coerceIn(0, bitmap.height - 1)
        return true
    }

    private fun distanceFromDown(event: MotionEvent): Float {
        val dx = event.x - downX
        val dy = event.y - downY
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun cancelPendingRightClick() {
        if (longPressPending) {
            removeCallbacks(longPressRunnable)
            longPressPending = false
        }
    }

    private fun androidButtonsToMask(buttonState: Int): Int {
        var mask = 0
        if (buttonState and MotionEvent.BUTTON_PRIMARY != 0) mask = mask or BUTTON_LEFT
        if (buttonState and MotionEvent.BUTTON_TERTIARY != 0) mask = mask or BUTTON_MIDDLE
        if (buttonState and MotionEvent.BUTTON_SECONDARY != 0) mask = mask or BUTTON_RIGHT
        return mask
    }

    private fun androidButtonToMask(button: Int): Int = when (button) {
        MotionEvent.BUTTON_PRIMARY -> BUTTON_LEFT
        MotionEvent.BUTTON_TERTIARY -> BUTTON_MIDDLE
        MotionEvent.BUTTON_SECONDARY -> BUTTON_RIGHT
        else -> 0
    }

    // ---- Keyboard input ----------------------------------------------------

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        forwardKey(event, isDown = true) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        forwardKey(event, isDown = false) || super.onKeyUp(keyCode, event)

    // BaseInputConnection's fallback sends whole strings as ACTION_MULTIPLE,
    // not individual onKeyDown callbacks, including from some physical IMEs.
    override fun onKeyMultiple(keyCode: Int, repeatCount: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_UNKNOWN && event.characters != null) {
            return textConnection?.commitText(event.characters, 1) ?: sendCommittedText(event.characters)
        }
        return super.onKeyMultiple(keyCode, repeatCount, event)
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) post { requestFocus() } else releaseInput()
    }

    private fun releaseInput() {
        val target = inputTarget
        pressedKeys.values.forEach { target?.sendKeyEvent(it, false) }
        pressedKeys.clear()
        if (currentButtonMask != 0) target?.sendPointerEvent(lastPointerX, lastPointerY, 0)
        currentButtonMask = 0
        textConnection?.finishComposingText()
        cancelPendingRightClick()
    }

    private fun forwardKey(event: KeyEvent, isDown: Boolean): Boolean {
        val client = inputTarget ?: return false
        val key = event.deviceId to event.keyCode
        val keysym = if (isDown) {
            VncKeyMap.keysymFor(event)?.also { pressedKeys[key] = it }
        } else {
            pressedKeys.remove(key) ?: VncKeyMap.keysymFor(event)
        } ?: return false
        textConnection?.finishComposingText()
        client.sendKeyEvent(keysym, isDown)
        return true
    }

    /**
     * A real input connection is required for Gboard and Samsung Keyboard:
     * most IMEs commit text instead of synthesising hardware [KeyEvent]s.
     */
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        // This is a remote keyboard, not an Android text field. Ask IMEs for
        // raw keys, while still supporting those that send text/composition.
        outAttrs.inputType = InputType.TYPE_NULL
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_FULLSCREEN
        textConnection?.finishComposingText()
        return RemoteInputConnection().also { textConnection = it }
    }

    override fun onCheckIsTextEditor(): Boolean = true

    private inner class RemoteInputConnection : BaseInputConnection(this@VncView, false) {
        private var composingText = ""

        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean =
            text != null && replaceComposition(text.toString())

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            if (text == null || !replaceComposition(text.toString())) return false
            composingText = ""
            return true
        }

        override fun finishComposingText(): Boolean {
            // Provisional characters are already visible in the remote editor.
            composingText = ""
            return true
        }

        private fun replaceComposition(next: String): Boolean {
            if (inputTarget == null) return false
            var common = 0
            while (common < composingText.length && common < next.length) {
                val previousPoint = Character.codePointAt(composingText, common)
                if (previousPoint != Character.codePointAt(next, common)) break
                common += Character.charCount(previousPoint)
            }
            val removed = composingText.codePointCount(common, composingText.length)
            if (!sendRepeatedKey(XK_BACKSPACE, removed) || !sendCommittedText(next.substring(common))) {
                return false
            }
            composingText = next
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            if (beforeLength < 0 || afterLength < 0) return false
            var remaining = (composingText.length - beforeLength).coerceAtLeast(0)
            if (remaining > 0 && remaining < composingText.length &&
                Character.isLowSurrogate(composingText[remaining])
            ) remaining--
            val knownUnits = composingText.length - remaining
            val backspaces = composingText.codePointCount(remaining, composingText.length) +
                (beforeLength - knownUnits).coerceAtLeast(0)
            val backspacesSent = sendRepeatedKey(XK_BACKSPACE, backspaces)
            val deletesSent = sendRepeatedKey(XK_DELETE, afterLength)
            if (backspacesSent) composingText = composingText.substring(0, remaining)
            return backspacesSent && deletesSent
        }

        override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
            if (beforeLength < 0 || afterLength < 0) return false
            val count = composingText.codePointCount(0, composingText.length)
            val remaining = composingText.offsetByCodePoints(composingText.length, -minOf(count, beforeLength))
            val backspacesSent = sendRepeatedKey(XK_BACKSPACE, beforeLength)
            val deletesSent = sendRepeatedKey(XK_DELETE, afterLength)
            if (backspacesSent) composingText = composingText.substring(0, remaining)
            return backspacesSent && deletesSent
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean =
            this@VncView.dispatchKeyEvent(event)
    }

    private fun sendCommittedText(text: CharSequence): Boolean {
        val target = inputTarget ?: return false
        var offset = 0
        while (offset < text.length) {
            val codePoint = Character.codePointAt(text, offset)
            val keysym = VncKeyMap.keysymForCodePoint(codePoint) ?: return false
            if (!target.sendKeyEvent(keysym, true) || !target.sendKeyEvent(keysym, false)) {
                return false
            }
            offset += Character.charCount(codePoint)
        }
        return true
    }

    private fun sendRepeatedKey(keysym: Int, count: Int): Boolean {
        val target = inputTarget ?: return false
        repeat(count) {
            if (!target.sendKeyEvent(keysym, true) || !target.sendKeyEvent(keysym, false)) {
                return false
            }
        }
        return true
    }

    companion object {
        private const val SCOPE = "VncView"
        private const val BUTTON_LEFT = 1
        private const val BUTTON_MIDDLE = 2
        private const val BUTTON_RIGHT = 4
        private const val BUTTON_WHEEL_UP = 8
        private const val BUTTON_WHEEL_DOWN = 16
        private const val BUTTON_WHEEL_LEFT = 32
        private const val BUTTON_WHEEL_RIGHT = 64
        private const val XK_BACKSPACE = 0xFF08
        private const val XK_DELETE = 0xFFFF
        private const val LONG_PRESS_MS = 450L
        private const val TOUCH_SLOP_PX = 18f
    }
}
