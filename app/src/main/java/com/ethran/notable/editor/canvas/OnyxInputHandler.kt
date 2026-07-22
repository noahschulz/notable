package com.ethran.notable.editor.canvas

import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toRect
import com.ethran.notable.editor.EditorViewModel
import com.ethran.notable.editor.state.Mode
import com.ethran.notable.editor.PageView
import com.ethran.notable.editor.state.History
import com.ethran.notable.editor.utils.DeviceCompat
import com.ethran.notable.editor.utils.Eraser
import com.ethran.notable.editor.utils.Pen
import com.ethran.notable.editor.utils.calculateBoundingBox
import com.ethran.notable.editor.utils.cancelPendingScreenFreezeReset
import com.ethran.notable.editor.utils.copyInput
import com.ethran.notable.editor.utils.configureCalligraphyLiveAngle
import com.ethran.notable.editor.utils.copyInputToSimplePointF
import com.ethran.notable.editor.utils.ERASER_INDICATOR_COLOR
import com.ethran.notable.editor.utils.enableNativeEraser
import com.ethran.notable.editor.utils.getModifiedStrokeEndpoints
import com.ethran.notable.editor.utils.handleDraw
import com.ethran.notable.editor.utils.handleErase
import com.ethran.notable.editor.utils.handleScribbleToErase
import com.ethran.notable.editor.utils.handleSelect
import com.ethran.notable.editor.utils.onSurfaceInit
import com.ethran.notable.editor.utils.penToStroke
import com.ethran.notable.editor.utils.setupSurface
import com.ethran.notable.editor.utils.transformToLine
import com.ethran.notable.ui.convertDpToPixel
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.device.Device
import com.onyx.android.sdk.extension.isNullOrEmpty
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.TouchHelper
import com.onyx.android.sdk.pen.data.TouchPointList
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min

class OnyxInputHandler(
    private val drawCanvas: DrawCanvas,
    private val page: PageView,
    private val viewModel: EditorViewModel,
    private val history: History,
    private val coroutineScope: CoroutineScope,
    private val strokeHistoryBatch: MutableList<String>,
) {
    var isErasing: Boolean = false
    var lastStrokeEndTime: Long = 0
    private val log = ShipBook.getLogger("DrawCanvas")
    private val toolbarState get() = viewModel.toolbarState.value

    // TODO: As OnyxInput is not done by lazy, which forces evaluation of the touchHelper
    //       lazy during DrawCanvas construction.
    val touchHelper by lazy {
        val helper = if (DeviceCompat.isOnyxDevice) {
            try {
                referencedSurfaceView = this.hashCode().toString()
                TouchHelper.create(drawCanvas, inputCallback)
            } catch (t: Throwable) {
                Log.w("OnyxInputHandler", "TouchHelper.create failed: ${t.message}")
                null
            }
        } else null
        helper
    }

    @Suppress("RedundantOverride")
    private val inputCallback: RawInputCallback = object : RawInputCallback() {
        // Documentation: https://github.com/onyx-intl/OnyxAndroidDemo/blob/d3a1ffd3af231fe4de60a2a0da692c17cb35ce31/doc/Onyx-Pen-SDK.md#L40-L62
        // - pen : `onBeginRawDrawing()` -> `onRawDrawingTouchPointMoveReceived()` -> `onRawDrawingTouchPointListReceived()` -> `onEndRawDrawing()`
        // - erase :  `onBeginRawErasing()` -> `onRawErasingTouchPointMoveReceived()` -> `onRawErasingTouchPointListReceived()` -> `onEndRawErasing()`

        override fun onBeginRawDrawing(p0: Boolean, p1: TouchPoint?) {
        }

        override fun onEndRawDrawing(p0: Boolean, p1: TouchPoint?) {
        }

        override fun onRawDrawingTouchPointMoveReceived(p0: TouchPoint?) {
        }

        override fun onRawDrawingTouchPointListReceived(plist: TouchPointList) =
            onRawDrawingList(plist)


        // Handle button/eraser tip of the pen:
        override fun onBeginRawErasing(p0: Boolean, p1: TouchPoint?) {
            if (touchHelper == null) return
            // Re-assert the native eraser indicator because setRawDrawingEnabled(true) (called
            // on every resume) resets it to disabled internally. The track style follows the active
            // eraser type: the wide marker (style 8) for the pen/drag eraser, a dotted outline
            // (DASH style 5) for the lasso/select eraser. See docs/onyx-sdk/onyx-native-eraser-indicator.md.
            enableNativeEraser(touchHelper, toolbarState.eraser)
            // The eraser channel carries no colour of its own — the firmware paints the track with
            // the global setStrokeColor. Set it here (width comes from the style's params, so this
            // touches colour only, not thickness). This is the one thing we still set on begin.
            touchHelper?.setStrokeColor(ERASER_INDICATOR_COLOR)
            isErasing = true
        }

        override fun onEndRawErasing(p0: Boolean, p1: TouchPoint?) {
            updatePenAndStroke()
        }

        override fun onRawErasingTouchPointListReceived(plist: TouchPointList?) =
            onRawErasingList(plist)

        override fun onRawErasingTouchPointMoveReceived(p0: TouchPoint?) {
        }

        override fun onPenUpRefresh(refreshRect: RectF?) {
            super.onPenUpRefresh(refreshRect)
        }

        override fun onPenActive(point: TouchPoint?) {
            super.onPenActive(point)
        }
    }

    fun updatePenAndStroke() {
        if(touchHelper == null) return
        // it takes around 11 ms to run on Note 4c.
        log.i("Update pen and stroke")
        when (toolbarState.mode) {
            // we need to change size according to zoom level before drawing on screen
            Mode.Draw, Mode.Line -> {
                val scaledWidth = toolbarState.activePenSetting.strokeSize * page.zoomLevel.value
                touchHelper!!.setStrokeStyle(penToStroke(toolbarState.pen))
                    ?.setStrokeWidth(scaledWidth)
                    ?.setStrokeColor(toolbarState.activePenSetting.color)
                // Match the live square-pen nib angle to the dry render (+45°) so the calligraphy
                // stroke doesn't rotate on pen-up. See docs/onyx-sdk/onyx-pen-styles-catalog.md.
                if (toolbarState.pen == Pen.CALLIGRAPHY) {
                    configureCalligraphyLiveAngle(angleDegrees = 45f, strokeWidth = scaledWidth)
                }
            }

            Mode.Erase -> applyEraserIndicatorStyle(penEraserColor = Color.GRAY)

            Mode.Select -> touchHelper?.setStrokeStyle(penToStroke(Pen.BALLPEN))?.setStrokeWidth(3f)
                ?.setStrokeColor(Color.GRAY)
        }
    }

    /**
     * Configures the helper's stroke so the eraser feedback matches the active eraser type:
     * a marker for the pen eraser, and a dashed line for the lasso / select eraser. Shared
     * by the hand eraser (Mode.Erase in [updatePenAndStroke]) and the pen side-button
     * eraser ([onBeginRawErasing], native indicator).
     *
     * @param penEraserColor colour for the [Eraser.PEN] marker. Hand-erase uses grey; the
     * native button-erase indicator uses black (matches the user's preference and is more
     * visible against ink).
     */
    private fun applyEraserIndicatorStyle(penEraserColor: Int = Color.BLACK) {
        if (touchHelper == null) return
        when (toolbarState.eraser) {
            Eraser.PEN -> touchHelper!!.setStrokeStyle(penToStroke(Pen.MARKER))
                ?.setStrokeWidth(30f)
                ?.setStrokeColor(penEraserColor)

            Eraser.SELECT -> {
                val dashStyleID = penToStroke(Pen.DASHED)
                touchHelper!!.setStrokeStyle(dashStyleID)
                    ?.setStrokeWidth(3f)
                    ?.setStrokeColor(Color.BLACK)
                val params = FloatArray(4)
                params[0] = 5f // thickness
                params[1] = 9f // no idea
                params[2] = 9f // no idea
                params[3] = 0f // no idea
                Device.currentDevice().setStrokeParameters(dashStyleID, params)
            }
        }
    }

    suspend fun updateIsDrawing() {
        if(touchHelper == null) return
        log.i("Update is drawing: $toolbarState.isDrawing")
        if (toolbarState.isDrawing) {
            touchHelper!!.setRawDrawingEnabled(true)
            // setRawDrawingEnabled(true) resets the framework stroke config to firmware defaults
            // (brush channel on, eraser channel off). Re-assert the eraser channel (styled for the
            // active eraser type) and re-send the active pen style so the next stroke uses the tool.
            enableNativeEraser(touchHelper, toolbarState.eraser)
            updatePenAndStroke()
        } else {
            // A pending resetScreenFreeze resume would re-freeze the screen after we disable
            // raw drawing (e.g. lasso select: the select-stroke refreshUi armed it) — kill it.
            cancelPendingScreenFreezeReset()
            // Check if drawing is completed
            CanvasEventBus.waitForDrawing()
            // draw to view, before showing drawing, avoid stutter
            drawCanvas.refreshManager.drawCanvasToView(null)
            touchHelper!!.setRawDrawingEnabled(false)
        }
    }

    fun updateActiveSurface() {
        // Takes at least 50ms on Note 4c,
        // and I don't think that we need it immediately
        log.i("Update editable surface")
        coroutineScope.launch {
            onSurfaceInit(drawCanvas)
            val toolbarHeight =
                if (toolbarState.isToolbarOpen) convertDpToPixel(40.dp, drawCanvas.context).toInt() else 0
            setupSurface(
                drawCanvas,
                touchHelper,
                toolbarHeight
            )
            // setupSurface resets the framework stroke style to firmware defaults. Re-send the
            // pen style here, inside the same coroutine and after the surface is armed: a caller
            // that invokes updatePenAndStroke() right after updateActiveSurface() would otherwise
            // race this launch and have its style overwritten.
            updatePenAndStroke()
        }
    }
    private fun onRawDrawingList(plist: TouchPointList) {
        if (touchHelper == null) return
        val currentLastStrokeEndTime = lastStrokeEndTime
        lastStrokeEndTime = System.currentTimeMillis()
        val startTime = System.currentTimeMillis()

        when (toolbarState.mode) {
            Mode.Erase -> onRawErasingList(plist)
            Mode.Select -> {
                thread {
                    val points =
                        copyInputToSimplePointF(plist.points, page.scroll, page.zoomLevel.value)
                    handleSelect(
                        scope = coroutineScope,
                        page = drawCanvas.page,
                        viewModel = viewModel,
                        points = points
                    )
                    val boundingBox = calculateBoundingBox(points) { Pair(it.x, it.y) }.toRect()
                    val padding = 10
                    val dirtyRect = Rect(
                        boundingBox.left - padding,
                        boundingBox.top - padding,
                        boundingBox.right + padding,
                        boundingBox.bottom + padding
                    )
                    drawCanvas.refreshManager.refreshUi(dirtyRect)
                }
            }

            Mode.Line -> {
                coroutineScope.launch(Dispatchers.Main.immediate) {
                    CanvasEventBus.drawingInProgress.withLock {
                        val lock = System.currentTimeMillis()
                        log.d("lock obtained in ${lock - startTime} ms")


                        val (startPoint, endPoint) = getModifiedStrokeEndpoints(
                            plist.points,
                            page.scroll,
                            page.zoomLevel.value
                        )
                        val linePoints = transformToLine(startPoint, endPoint)

                        handleDraw(
                            drawCanvas.page,
                            strokeHistoryBatch,
                            toolbarState.activePenSetting.strokeSize,
                            toolbarState.activePenSetting.color,
                            toolbarState.pen,
                            linePoints
                        )

                        coroutineScope.launch(Dispatchers.Default) {
                            val dirtyRect = Rect(
                                min(startPoint.x, endPoint.x).toInt(),
                                min(startPoint.y, endPoint.y).toInt(),
                                max(startPoint.x, endPoint.x).toInt(),
                                max(startPoint.y, endPoint.y).toInt()
                            )
                            drawCanvas.refreshManager.refreshUi(dirtyRect)
                            CanvasEventBus.commitHistorySignal.emit(Unit)
                        }
                    }

                }
            }

            Mode.Draw -> {
                coroutineScope.launch(Dispatchers.Main.immediate) {
                    CanvasEventBus.drawingInProgress.withLock {
                        val lock = System.currentTimeMillis()
                        log.d("lock obtained in ${lock - startTime} ms")

                        val scaledPoints =
                            copyInput(plist.points, page.scroll, page.zoomLevel.value)
                        val firstPointTime = plist.points.first().timestamp
                        val erasedByScribbleDirtyRect = handleScribbleToErase(
                            page,
                            scaledPoints,
                            history,
                            toolbarState.pen,
                            toolbarState.activePenSetting.strokeSize,
                            toolbarState.activePenSetting.color,
                            currentLastStrokeEndTime,
                            firstPointTime
                        )
                        if (erasedByScribbleDirtyRect.isNullOrEmpty()) {
                            log.d("Drawing...")
                            // draw the stroke
                            handleDraw(
                                drawCanvas.page,
                                strokeHistoryBatch,
                                toolbarState.activePenSetting.strokeSize,
                                toolbarState.activePenSetting.color,
                                toolbarState.pen,
                                scaledPoints
                            )
                        } else {
                            log.d("Erased by scribble, $erasedByScribbleDirtyRect")
                            // Union the scribble track (firmware screen coords) with the erased
                            // strokes' bounds so commitErase overwrites both in one pass while
                            // still frozen. Scribble is not drawn into the page bitmap — we only
                            // need the region to cover the firmware's live track.
                            // See docs/onyx-sdk/onyx-scribble-to-erase.md.
                            val padding = 10
                            val trackBox =
                                calculateBoundingBox(plist.points) { Pair(it.x, it.y) }.toRect()
                            val dirty = Rect(
                                trackBox.left - padding,
                                trackBox.top - padding,
                                trackBox.right + padding,
                                trackBox.bottom + padding
                            )
                            erasedByScribbleDirtyRect.let { dirty.union(it) }
                            // Use areaErase=true for the longer 500ms settle (scribble is a large gesture).
                            drawCanvas.refreshManager.commitErase(dirty, areaErase = true)
                        }

                    }
                    coroutineScope.launch(Dispatchers.Default) {
                        CanvasEventBus.commitHistorySignal.emit(Unit)
                    }
                }
            }
        }
    }

    private fun onRawErasingList(plist: TouchPointList?) {
        isErasing = false

        if (plist == null) return
        val points = copyInputToSimplePointF(plist.points, page.scroll, page.zoomLevel.value)

        val padding = 10
        val boundingBox = (calculateBoundingBox(plist.points) { Pair(it.x, it.y) }).toRect()
        val strokeArea = Rect(
            boundingBox.left - padding,
            boundingBox.top - padding,
            boundingBox.right + padding,
            boundingBox.bottom + padding
        )
        val zoneEffected = handleErase(
            drawCanvas.page,
            history,
            points,
            eraser = toolbarState.eraser
        )

        // Single atomic commit of the whole touched region: the native eraser indicator
        // track spans strokeArea, the erased strokes' bounds are zoneEffected, so repainting
        // their union both wipes the indicator and shows the erased result in one pass.
        // commitErase blocks input, draws synchronously, then drops the firmware overlay so
        // indicator + strokes disappear together (no double refresh, no gap to draw into).
        // See docs/onyx-sdk/onyx-pen-up-refresh-and-screen-freeze.md.
        val dirty = Rect(strokeArea)
        if (zoneEffected != null) dirty.union(zoneEffected)
        // Area (lasso/select) erase needs the longer 500ms settle the official app uses; the
        // pen/marker erase uses the 150ms stroke settle.
        drawCanvas.refreshManager.commitErase(dirty, areaErase = toolbarState.eraser == Eraser.SELECT)
    }

    private val motionEventPoints = mutableListOf<com.ethran.notable.data.db.StrokePoint>()
    private var isMotionEventActive = false
    private var motionEventStartTime = 0L

    fun handleMotionEvent(event: android.view.MotionEvent): Boolean {
        val action = event.actionMasked
        val scale = page.zoomLevel.value
        val scroll = page.scroll

        fun createStrokePoint(x: Float, y: Float, rawPressure: Float, timeMs: Long): com.ethran.notable.data.db.StrokePoint {
            val pressure = if (rawPressure > 0f) rawPressure.coerceIn(0f, 1f) else 1f
            val dt = if (motionEventStartTime > 0L) (timeMs - motionEventStartTime).coerceIn(0L, 65534L).toUShort() else 0.toUShort()
            return com.ethran.notable.data.db.StrokePoint(
                x = x / scale + scroll.x,
                y = y / scale + scroll.y,
                pressure = pressure,
                tiltX = event.getAxisValue(android.view.MotionEvent.AXIS_TILT).toInt(),
                tiltY = 0,
                dt = dt
            )
        }

        when (action) {
            android.view.MotionEvent.ACTION_DOWN -> {
                isMotionEventActive = true
                motionEventPoints.clear()
                motionEventStartTime = event.eventTime

                val p = createStrokePoint(event.x, event.y, event.pressure, event.eventTime)
                motionEventPoints.add(p)

                drawCanvas.glRenderer.clearPointBuffer()
                drawCanvas.glRenderer.onTouchListener.onTouch(drawCanvas, event)
            }

            android.view.MotionEvent.ACTION_MOVE -> {
                if (!isMotionEventActive) return false
                val historySize = event.historySize

                for (h in 0 until historySize) {
                    val hx = event.getHistoricalX(h)
                    val hy = event.getHistoricalY(h)
                    val hp = event.getHistoricalPressure(h)
                    val ht = event.getHistoricalEventTime(h)
                    val p = createStrokePoint(hx, hy, hp, ht)
                    motionEventPoints.add(p)
                }

                val p = createStrokePoint(event.x, event.y, event.pressure, event.eventTime)
                motionEventPoints.add(p)

                drawCanvas.glRenderer.onTouchListener.onTouch(drawCanvas, event)
            }

            android.view.MotionEvent.ACTION_UP -> {
                if (!isMotionEventActive) return false
                isMotionEventActive = false

                val p = createStrokePoint(event.x, event.y, event.pressure, event.eventTime)
                motionEventPoints.add(p)

                drawCanvas.glRenderer.onTouchListener.onTouch(drawCanvas, event)

                val pointsToCommit = motionEventPoints.toList()
                motionEventPoints.clear()

                if (pointsToCommit.isNotEmpty()) {
                    processNonOnyxPoints(pointsToCommit)
                }
            }

            android.view.MotionEvent.ACTION_CANCEL -> {
                isMotionEventActive = false
                motionEventPoints.clear()
                drawCanvas.glRenderer.onTouchListener.onTouch(drawCanvas, event)
            }
        }
        return true
    }

    private fun processNonOnyxPoints(points: List<com.ethran.notable.data.db.StrokePoint>) {
        if (points.isEmpty()) return
        lastStrokeEndTime = System.currentTimeMillis()

        when (toolbarState.mode) {
            Mode.Erase -> {
                val simplePoints = points.map { com.ethran.notable.data.model.SimplePointF(it.x, it.y) }
                val padding = 10
                val boundingBox = calculateBoundingBox(points) { Pair(it.x, it.y) }.toRect()
                val zoneEffected = handleErase(
                    drawCanvas.page,
                    history,
                    simplePoints,
                    eraser = toolbarState.eraser
                )
                val dirtyRect = Rect(
                    boundingBox.left.toInt() - padding,
                    boundingBox.top.toInt() - padding,
                    boundingBox.right.toInt() + padding,
                    boundingBox.bottom.toInt() + padding
                )
                zoneEffected?.let { dirtyRect.union(it) }
                drawCanvas.refreshManager.refreshUi(dirtyRect)
                coroutineScope.launch(Dispatchers.Default) {
                    CanvasEventBus.commitHistorySignal.emit(Unit)
                }
            }

            Mode.Select -> {
                val simplePoints = points.map { com.ethran.notable.data.model.SimplePointF(it.x, it.y) }
                handleSelect(
                    scope = coroutineScope,
                    page = drawCanvas.page,
                    viewModel = viewModel,
                    points = simplePoints
                )
                val boundingBox = calculateBoundingBox(simplePoints) { Pair(it.x, it.y) }.toRect()
                val padding = 10
                val dirtyRect = Rect(
                    boundingBox.left - padding,
                    boundingBox.top - padding,
                    boundingBox.right + padding,
                    boundingBox.bottom + padding
                )
                drawCanvas.refreshManager.refreshUi(dirtyRect)
            }

            Mode.Line -> {
                if (points.size >= 2) {
                    val startPoint = points.first()
                    val endPoint = points.last()
                    val linePoints = transformToLine(startPoint, endPoint)
                    handleDraw(
                        drawCanvas.page,
                        strokeHistoryBatch,
                        toolbarState.activePenSetting.strokeSize,
                        toolbarState.activePenSetting.color,
                        toolbarState.pen,
                        linePoints
                    )
                    val dirtyRect = Rect(
                        min(startPoint.x, endPoint.x).toInt(),
                        min(startPoint.y, endPoint.y).toInt(),
                        max(startPoint.x, endPoint.x).toInt(),
                        max(startPoint.y, endPoint.y).toInt()
                    )
                    drawCanvas.refreshManager.refreshUi(dirtyRect)
                    coroutineScope.launch(Dispatchers.Default) {
                        CanvasEventBus.commitHistorySignal.emit(Unit)
                    }
                }
            }

            Mode.Draw -> {
                handleDraw(
                    drawCanvas.page,
                    strokeHistoryBatch,
                    toolbarState.activePenSetting.strokeSize,
                    toolbarState.activePenSetting.color,
                    toolbarState.pen,
                    points
                )
                val boundingBox = calculateBoundingBox(points) { Pair(it.x, it.y) }.toRect()
                val padding = (toolbarState.activePenSetting.strokeSize * 2).toInt()
                val dirtyRect = Rect(
                    boundingBox.left.toInt() - padding,
                    boundingBox.top.toInt() - padding,
                    boundingBox.right.toInt() + padding,
                    boundingBox.bottom.toInt() + padding
                )
                drawCanvas.refreshManager.refreshUi(dirtyRect)
                coroutineScope.launch(Dispatchers.Default) {
                    CanvasEventBus.commitHistorySignal.emit(Unit)
                }
            }
        }
    }
}