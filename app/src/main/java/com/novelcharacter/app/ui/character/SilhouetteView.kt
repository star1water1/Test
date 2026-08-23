package com.novelcharacter.app.ui.character

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.BodySlot
import com.novelcharacter.app.util.BodySilhouetteSpec
import com.novelcharacter.app.util.BodySilhouetteSpec.Measures
import com.novelcharacter.app.util.BodySilhouetteSpec.PointCm
import com.novelcharacter.app.util.BodySilhouetteSpec.VolumeShape
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 체형 실루엣 그림 (설계 `docs/body_visual_redesign_2026-07.md` 5-4-1).
 *
 * **이 뷰는 그리기만 한다.** 정점·상수·역산·볼륨층은 전부 [BodySilhouetteSpec]이 cm
 * 좌표로 들고 있고, 여기서는 `px = cx + xCm·k`, `py = baseY − yCm·k` 한 줄로 옮긴다
 * (`k = 그릴 높이 / [BodySilhouetteSpec.CANVAS_HEIGHT_CM]`). 그림 수치를 이 파일에서
 * 새로 만들지 말 것 — 두 벌이 되면 갈린다.
 *
 * 세 화면이 같은 뷰를 쓴다(읽기 카드 · 크게 보기 · 편집기). 차이는 전부 토글이다:
 * [showLabels]·[showPartColors]·[interactive]·[overlayMeasures]·[facing].
 *
 * ## 렌더 규격 (재저작 2차 — 목업 정본 `docs/mockups/body_silhouette_port_2026-08.html`)
 * - 채움은 마네킹 톤 솔리드, 윤곽은 테마 강조색으로 만화체 무게.
 * - 음영은 **셀(cel) 문법** — 경계가 선명한 플랫 셰이프이며 그라데이션을 쓰지 않는다.
 * - 머리는 몸통과 별도 패스다(한 패스로 합치면 목에 고리 선이 생긴다).
 * - 유령 오버레이(작품 평균)는 윤곽만·무채색이며 볼륨층·파츠 색을 그리지 않는다.
 * - 클램프는 **표시만**이다 — 핸들·가이드라인이 경고색이 될 뿐 값은 그대로다.
 */
class SilhouetteView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** 어느 쪽에서 본 그림인가. 측면은 표시 전용이다(핸들 없음 — 설계 P10). */
    enum class Facing { FRONT, SIDE }

    private val density = resources.displayMetrics.density

    private val figureColor = context.getColor(R.color.silhouette_figure)
    private val armColor = context.getColor(R.color.silhouette_figure_arm)
    private val legColor = context.getColor(R.color.silhouette_figure_leg)
    private val accentColor = context.getColor(R.color.primary)
    private val ghostColor = context.getColor(R.color.silhouette_ghost)
    private val warnColor = context.getColor(R.color.silhouette_warn)
    private val labelColor = context.getColor(R.color.text_secondary)
    private val guideColor = context.getColor(R.color.outline)
    private val handleFillColor = context.getColor(R.color.silhouette_handle_fill)

    // ── 입력 ────────────────────────────────────────────────────────────────

    /** 그릴 수치. null이면 아무것도 그리지 않는다(미매핑 폴백은 호스트의 몫 — 설계 D6). */
    var measures: Measures? = null
        set(value) {
            field = value
            updateContentDescription()
            invalidate()
        }

    /** 작품 평균 유령 실루엣. null이면 그리지 않는다. */
    var overlayMeasures: Measures? = null
        set(value) { field = value; invalidate() }

    var facing: Facing = Facing.FRONT
        set(value) { field = value; invalidate() }

    /** 부위 가이드라인과 수치 라벨(카드 기본 끔 / 크게 보기·편집기 기본 켬). */
    var showLabels: Boolean = false
        set(value) { field = value; invalidate() }

    /** 몸통·팔·다리를 색으로 구분한다(기본 켬 — 설계 5-4-1). */
    var showPartColors: Boolean = true
        set(value) { field = value; invalidate() }

    /** 핸들 표시와 드래그. 측면에서는 무시된다(표시 전용). */
    var interactive: Boolean = false
        set(value) { field = value; invalidate() }

    /** 드래그 중 실시간으로 되돌아오는 값(cm). 뷰는 자기 [measures]도 함께 갱신한다. */
    var onHandleDrag: ((BodySlot, Double) -> Unit)? = null

    /** 드래그를 놓았을 때. */
    var onHandleDragEnd: ((BodySlot) -> Unit)? = null

    /**
     * 그림이 표현 한계에 닿았는가 — 호스트가 "그림의 표현 한계입니다" 캡션을 단다(설계 4-2).
     * **값이 한계에 닿은 것이 아니다.**
     */
    val isClamped: Boolean
        get() = measures?.let { m ->
            val sc = BodySilhouetteSpec.scales(m)
            sc.anyClamped || BodySilhouetteSpec.arcEdge(sc, BodySilhouetteSpec.bustGeom(m)).third
        } ?: false

    // ── 페인트 ──────────────────────────────────────────────────────────────

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = labelColor
        textSize = 11f * density
    }
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val bubbleTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12f * density
        textAlign = Paint.Align.CENTER
    }

    private val bodyPath = Path()
    private val scratchPath = Path()
    private val scratchRect = RectF()

    // ── 파생 기하 캐시 ──────────────────────────────────────────────────────
    //
    // **`onDraw`는 그리기만 한다.** 종전에는 프레임마다 `front(m)`·`side(m)`·`bustGeom(m)`·
    // `volumeShapes(m)`·`handles(m)`(둘)을 새로 지었다 — 전부 cm 정점 목록을 새로 만드는
    // 함수라, **손가락이 핸들을 끄는 동안** 프레임마다 리스트 수십 개가 나고 죽는다.
    // 관계도(`RelationshipGraphView`)가 같은 판에서 옮겨 간 그 처방이다.
    //
    // **입력이 그대로면 다시 짓지 않는다** — 무효화를 세터마다 손으로 적으면 새 입력이
    // 생길 때 하나를 빠뜨리고, 그 증상은 *"값을 바꿨는데 그림이 안 바뀐다"*라 원인이
    // 그림 코드로 안 보인다. [Measures]가 데이터 클래스라 `==`이 그 판정을 대신한다.
    private var geomFor: Measures? = null
    private var geomFacing: Facing? = null
    private var frontFigure: BodySilhouetteSpec.FrontFigure? = null
    private var sideFigure: BodySilhouetteSpec.SideFigure? = null
    private var bustGeomCache: BodySilhouetteSpec.BustGeom? = null
    private var volumeCache: List<VolumeShape> = emptyList()
    private var handlesCache: List<BodySilhouetteSpec.HandleAnchor> = emptyList()

    private var ghostFor: Measures? = null
    private var ghostFacing: Facing? = null
    private var ghostFront: BodySilhouetteSpec.FrontFigure? = null
    private var ghostSide: BodySilhouetteSpec.SideFigure? = null
    /** 유령 위팔의 바깥 모서리 — `armEdge`도 새 목록을 만든다. */
    private var ghostArmEdges: List<List<PointCm>> = emptyList()

    // 파선 효과 셋은 `density`만 쓴다 — 프레임마다 새로 만들 이유가 없다.
    private val dashOutline = DashPathEffect(floatArrayOf(4f * density, 3f * density), 0f)
    private val dashGuide = DashPathEffect(floatArrayOf(3f * density, 3f * density), 0f)
    private val dashHandle = DashPathEffect(floatArrayOf(3f * density, 2f * density), 0f)

    /** 현재 [measures]·[facing]에 맞는 기하를 갖춘다. 이미 맞으면 아무 일도 하지 않는다. */
    private fun ensureGeometry(m: Measures) {
        if (geomFor == m && geomFacing == facing) return
        val bg = BodySilhouetteSpec.bustGeom(m)
        bustGeomCache = bg
        handlesCache = BodySilhouetteSpec.handles(m, BodySilhouetteSpec.scales(m), bg)
        when (facing) {
            Facing.FRONT -> {
                frontFigure = BodySilhouetteSpec.front(m)
                sideFigure = null
                volumeCache = BodySilhouetteSpec.volumeShapes(m)
            }
            Facing.SIDE -> {
                sideFigure = BodySilhouetteSpec.side(m)
                frontFigure = null
                volumeCache = emptyList()
            }
        }
        geomFor = m
        geomFacing = facing
    }

    private fun ensureGhostGeometry(ghost: Measures) {
        if (ghostFor == ghost && ghostFacing == facing) return
        when (facing) {
            Facing.FRONT -> {
                val f = BodySilhouetteSpec.front(ghost)
                ghostFront = f
                ghostSide = null
                ghostArmEdges = f.arms.map { BodySilhouetteSpec.armEdge(it, true) }
            }
            Facing.SIDE -> {
                ghostSide = BodySilhouetteSpec.side(ghost)
                ghostFront = null
                ghostArmEdges = emptyList()
            }
        }
        ghostFor = ghost
        ghostFacing = facing
    }

    // ── 좌표 변환 ───────────────────────────────────────────────────────────

    private var k = 1f
    private var cx = 0f
    private var baseY = 0f

    private fun px(xCm: Double) = cx + (xCm * k).toFloat()
    private fun py(yCm: Double) = baseY - (yCm * k).toFloat()

    /** 몸통 윤곽선 굵기(px) — 240dp 폭에서 2dp가 기준이다(목업 정본과 같은 식). */
    private val outlineWidth: Float
        get() = max(1.4f * density, width * 2f / 240f)

    // ── 드래그 상태 ─────────────────────────────────────────────────────────

    private var draggingSlot: BodySlot? = null
    private var dragValue: Double = 0.0

    // ── 그리기 ──────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val m = measures ?: return

        val left = paddingLeft.toFloat()
        val top = paddingTop.toFloat()
        val right = width - paddingRight.toFloat()
        val bottom = height - paddingBottom.toFloat()
        if (right <= left || bottom <= top) return

        k = ((bottom - top) / BodySilhouetteSpec.CANVAS_HEIGHT_CM).toFloat()
        cx = (left + right) / 2f
        baseY = bottom

        ensureGeometry(m)
        overlayMeasures?.let { drawGhost(canvas, it) }
        when (facing) {
            Facing.FRONT -> drawFront(canvas, m)
            Facing.SIDE -> drawSide(canvas, m)
        }
        if (showLabels) drawGuides(canvas, m)
        if (interactive && facing == Facing.FRONT) drawHandles(canvas, m)
    }

    private fun drawFront(canvas: Canvas, m: Measures) {
        val f = frontFigure ?: return
        val ow = outlineWidth

        // 위팔 — 몸통 뒤에 깔린다.
        for (arm in f.arms) drawLimb(canvas, arm, capped = true, ow = ow)

        buildPath(f.body, closed = true, into = bodyPath)
        fillPaint.color = figureColor
        canvas.drawPath(bodyPath, fillPaint)

        if (showPartColors) {
            canvas.save()
            canvas.clipPath(bodyPath)
            fillPaint.color = legColor
            scratchPath.reset()
            val yTop = py(m.height * BodySilhouetteSpec.LEG_TINT_SIDE_F)
            val yMid = py(m.height * BodySilhouetteSpec.LEG_TINT_CENTER_F)
            scratchPath.moveTo(0f, yTop)
            scratchPath.lineTo(cx, yMid)
            scratchPath.lineTo(width.toFloat(), yTop)
            scratchPath.lineTo(width.toFloat(), height.toFloat())
            scratchPath.lineTo(0f, height.toFloat())
            scratchPath.close()
            canvas.drawPath(scratchPath, fillPaint)
            canvas.restore()
        }

        strokeOutline(canvas, f.body, m, bustGeomCache ?: BodySilhouetteSpec.bustGeom(m), ow)
        drawVolume(canvas, volumeCache)

        buildPath(f.head, closed = true, into = scratchPath)
        fillPaint.color = figureColor
        canvas.drawPath(scratchPath, fillPaint)
        strokePaint.color = accentColor
        strokePaint.strokeWidth = ow
        strokePaint.pathEffect = null
        canvas.drawPath(scratchPath, strokePaint)

        // 전완 — 몸 앞에 다시 그려 z-순서를 만든다.
        for (arm in f.forearms) drawLimb(canvas, arm, capped = false, ow = ow)
    }

    private fun drawSide(canvas: Canvas, m: Measures) {
        val f = sideFigure ?: return
        val ow = outlineWidth

        buildPath(f.body, closed = true, into = bodyPath)
        fillPaint.color = figureColor
        canvas.drawPath(bodyPath, fillPaint)

        if (showPartColors) {
            canvas.save()
            canvas.clipPath(bodyPath)
            fillPaint.color = legColor
            scratchPath.reset()
            val yFront = py(m.height * BodySilhouetteSpec.LEG_TINT_SIDE_FRONT_F)
            val yBack = py(m.height * BodySilhouetteSpec.LEG_TINT_SIDE_BACK_F)
            scratchPath.moveTo(width.toFloat(), yFront)
            scratchPath.lineTo(0f, yBack)
            scratchPath.lineTo(0f, height.toFloat())
            scratchPath.lineTo(width.toFloat(), height.toFloat())
            scratchPath.close()
            canvas.drawPath(scratchPath, fillPaint)
            canvas.restore()
        }

        strokePaint.color = accentColor
        strokePaint.strokeWidth = ow
        strokePaint.pathEffect = null
        canvas.drawPath(bodyPath, strokePaint)
        drawVolume(canvas, BodySilhouetteSpec.sideVolumeShapes(m))

        buildPath(f.head, closed = true, into = scratchPath)
        fillPaint.color = figureColor
        canvas.drawPath(scratchPath, fillPaint)
        strokePaint.color = accentColor
        strokePaint.strokeWidth = ow
        canvas.drawPath(scratchPath, strokePaint)
    }

    /** 팔 하나 — 채움(닫힌 고리) + 윤곽(절단변을 뺀 열린 패스). */
    private fun drawLimb(canvas: Canvas, loop: List<PointCm>, capped: Boolean, ow: Float) {
        if (loop.isEmpty()) return
        buildPath(loop, closed = true, into = scratchPath)
        fillPaint.color = if (showPartColors) armColor else figureColor
        canvas.drawPath(scratchPath, fillPaint)
        buildPath(BodySilhouetteSpec.armEdge(loop, capped), closed = false, into = scratchPath)
        strokePaint.color = accentColor
        strokePaint.strokeWidth = ow
        strokePaint.pathEffect = null
        canvas.drawPath(scratchPath, strokePaint)
    }

    /**
     * 몸통 윤곽 — 보간된 부위 구간만 점선으로 긋는다(추정임을 가시화, 설계 4-1).
     *
     * 닫힌 패스 한 번에 긋지 않고 구간별로 나눠 긋는 이유가 그것이다.
     */
    private fun strokeOutline(
        canvas: Canvas,
        loop: List<PointCm>,
        m: Measures,
        bg: BodySilhouetteSpec.BustGeom,
        ow: Float
    ) {
        strokePaint.color = accentColor
        strokePaint.strokeWidth = ow
        if (m.estimated.isEmpty()) {
            strokePaint.pathEffect = null
            canvas.drawPath(bodyPath, strokePaint)
            return
        }
        val bands = m.estimated.mapNotNull { BodySilhouetteSpec.estimatedBand(it, bg) }
        val inBand = { p: PointCm ->
            val frac = if (m.height > 0) p.y / m.height else 0.0
            bands.any { frac in it }
        }
        var i = 0
        while (i < loop.size) {
            val state = inBand(loop[i])
            var j = i
            while (j + 1 < loop.size && inBand(loop[j + 1]) == state) j++
            // 구간 양끝을 한 점씩 겹쳐 이어 붙인 자리에 틈이 생기지 않게 한다.
            val from = max(0, i - 1)
            val to = min(loop.size - 1, j + 1)
            buildPath(loop.subList(from, to + 1), closed = false, into = scratchPath)
            strokePaint.pathEffect = if (state) dashOutline else null
            canvas.drawPath(scratchPath, strokePaint)
            i = j + 1
        }
        strokePaint.pathEffect = null
    }

    /** 유령 오버레이 — 윤곽만, 무채색, 항상 뒤에(설계 5-4-1). */
    private fun drawGhost(canvas: Canvas, ghost: Measures) {
        // **기하를 먼저 갖춘 뒤에 페인트를 물들인다.** 물들인 채 중간에 빠져나가면
        // 알파 204와 파선이 다음 그림에 그대로 얹힌다(이 함수 끝의 두 줄이 되돌리는 것들이다).
        ensureGhostGeometry(ghost)
        strokePaint.color = ghostColor
        strokePaint.strokeWidth = max(1.3f * density, outlineWidth * .65f)
        strokePaint.pathEffect = dashOutline
        strokePaint.alpha = 204
        when (facing) {
            Facing.FRONT -> {
                val f = ghostFront
                if (f != null) {
                    for (edge in ghostArmEdges) {
                        buildPath(edge, closed = false, into = scratchPath)
                        canvas.drawPath(scratchPath, strokePaint)
                    }
                    buildPath(f.body, closed = true, into = scratchPath)
                    canvas.drawPath(scratchPath, strokePaint)
                    buildPath(f.head, closed = true, into = scratchPath)
                    canvas.drawPath(scratchPath, strokePaint)
                }
            }
            Facing.SIDE -> {
                val f = ghostSide
                if (f != null) {
                    buildPath(f.body, closed = true, into = scratchPath)
                    canvas.drawPath(scratchPath, strokePaint)
                    buildPath(f.head, closed = true, into = scratchPath)
                    canvas.drawPath(scratchPath, strokePaint)
                }
            }
        }
        strokePaint.alpha = 255
        strokePaint.pathEffect = null
    }

    /** 볼륨 표기층 — 전부 몸 외곽에 클립해 얹는다. */
    private fun drawVolume(canvas: Canvas, shapes: List<VolumeShape>) {
        canvas.save()
        canvas.clipPath(bodyPath)
        for (shape in shapes) {
            when (shape) {
                is VolumeShape.Shade -> {
                    buildPath(shape.points, closed = true, into = scratchPath, spline = shape.spline)
                    fillPaint.color = Color.BLACK
                    fillPaint.alpha = alphaOf(shape.alpha)
                    canvas.drawPath(scratchPath, fillPaint)
                    fillPaint.alpha = 255
                }
                is VolumeShape.Contour -> {
                    buildPath(shape.points, closed = false, into = scratchPath)
                    strokePaint.color = if (shape.accent) accentColor else Color.BLACK
                    strokePaint.strokeWidth = max(.5f, (shape.widthCm * k).toFloat())
                    strokePaint.alpha = alphaOf(shape.alpha)
                    strokePaint.pathEffect = null
                    canvas.drawPath(scratchPath, strokePaint)
                    strokePaint.alpha = 255
                }
                is VolumeShape.Blob -> {
                    val ccx = px(shape.center.x)
                    val ccy = py(shape.center.y)
                    val rx = (shape.rxCm * k).toFloat()
                    val ry = (shape.ryCm * k).toFloat()
                    scratchRect.set(ccx - rx, ccy - ry, ccx + rx, ccy + ry)
                    fillPaint.color = Color.BLACK
                    fillPaint.alpha = alphaOf(shape.alpha)
                    canvas.drawOval(scratchRect, fillPaint)
                    fillPaint.alpha = 255
                }
            }
        }
        canvas.restore()
    }

    private fun alphaOf(a: Double): Int = (a.coerceIn(0.0, 1.0) * 255).toInt()

    /** 부위 가이드라인 — 가는 수평선 + 좌측 부위명 + 우측 수치. */
    private fun drawGuides(canvas: Canvas, m: Measures) {
        for (h in handlesCache) {
            val y = py(m.height * h.heightFraction)
            val halfPx = (h.halfCm * k).toFloat()
            val warn = h.warn
            strokePaint.color = if (warn) warnColor else guideColor
            strokePaint.strokeWidth = 1f * density
            strokePaint.pathEffect = if (h.estimated) dashGuide else null
            canvas.drawLine(cx - halfPx, y, cx + halfPx, y, strokePaint)
            strokePaint.pathEffect = null

            labelPaint.color = if (warn) warnColor else labelColor
            labelPaint.textAlign = Paint.Align.LEFT
            val name = slotName(h.slot) ?: continue
            canvas.drawText(name, paddingLeft.toFloat(), y - 2f * density, labelPaint)
            labelPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(
                valueText(slotValue(m, h.slot)),
                width - paddingRight.toFloat(), y - 2f * density, labelPaint
            )
        }
    }

    /** 핸들 — 한쪽만 잡아도 좌우 대칭으로 움직인다(설계 5-4-4). */
    private fun drawHandles(canvas: Canvas, m: Measures) {
        val radius = 6f * density
        for (h in handlesCache) {
            val y = py(m.height * h.heightFraction)
            val halfPx = (h.halfCm * k).toFloat()
            for (side in intArrayOf(-1, 1)) {
                val x = cx + side * halfPx
                fillPaint.color = handleFillColor
                fillPaint.alpha = if (h.estimated) 120 else 255
                canvas.drawCircle(x, y, radius, fillPaint)
                fillPaint.alpha = 255
                strokePaint.color = if (h.warn) warnColor else accentColor
                strokePaint.strokeWidth = 2f * density
                strokePaint.pathEffect = if (h.estimated) dashHandle else null
                canvas.drawCircle(x, y, radius, strokePaint)
                strokePaint.pathEffect = null
            }
            if (draggingSlot == h.slot) drawDragBubble(canvas, cx + halfPx, y)
        }
    }

    /** 드래그 중 수치 말풍선 — 손가락이 가리는 자리를 피해 핸들 위에 띄운다. */
    private fun drawDragBubble(canvas: Canvas, x: Float, y: Float) {
        val text = valueText(dragValue)
        val padH = 6f * density
        val padV = 4f * density
        val w = bubbleTextPaint.measureText(text) + padH * 2
        val h = bubbleTextPaint.textSize + padV * 2
        val bx = x.coerceIn(w / 2, width - w / 2)
        val by = y - 14f * density
        scratchRect.set(bx - w / 2, by - h, bx + w / 2, by)
        bubblePaint.color = accentColor
        canvas.drawRoundRect(scratchRect, 4f * density, 4f * density, bubblePaint)
        bubbleTextPaint.color = context.getColor(R.color.on_primary)
        canvas.drawText(text, bx, by - padV - bubbleTextPaint.descent(), bubbleTextPaint)
    }

    // ── 경로 만들기 ─────────────────────────────────────────────────────────

    /**
     * cm 정점 배열을 화면 [Path]로 옮긴다.
     *
     * 곡선은 [BodySilhouetteSpec.cubicSegments]가 낸 베지어 구간 그대로다 —
     * 뷰가 다른 보간을 쓰면 목업에서 검증한 형태와 갈린다.
     */
    private fun buildPath(
        points: List<PointCm>, closed: Boolean, into: Path, spline: Boolean = true
    ) {
        into.reset()
        if (points.isEmpty()) return
        into.moveTo(px(points[0].x), py(points[0].y))
        if (points.size == 1) return
        if (!spline) {
            for (i in 1 until points.size) into.lineTo(px(points[i].x), py(points[i].y))
            if (closed) into.close()
            return
        }
        for (s in BodySilhouetteSpec.cubicSegments(points, closed)) {
            into.cubicTo(px(s.c1x), py(s.c1y), px(s.c2x), py(s.c2y), px(s.x), py(s.y))
        }
        if (closed) into.close()
    }

    // ── 조작 ────────────────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!interactive || facing != Facing.FRONT) return super.onTouchEvent(event)
        val m = measures ?: return super.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val hit = hitHandle(m, event.x, event.y) ?: return super.onTouchEvent(event)
                draggingSlot = hit
                dragValue = slotValue(m, hit)
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val slot = draggingSlot ?: return super.onTouchEvent(event)
                applyDrag(m, slot, event.x)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val slot = draggingSlot ?: return super.onTouchEvent(event)
                draggingSlot = null
                parent?.requestDisallowInterceptTouchEvent(false)
                onHandleDragEnd?.invoke(slot)
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /** 터치 반경 48dp 안의 핸들. 보간된 부위는 잡히지 않는다(값이 없는 것을 끌 수 없다). */
    private fun hitHandle(m: Measures, x: Float, y: Float): BodySlot? {
        val touch = 24f * density
        var best: BodySlot? = null
        var bestDist = Float.MAX_VALUE
        ensureGeometry(m)
        for (h in handlesCache) {
            if (h.estimated) continue
            val hy = py(m.height * h.heightFraction)
            val halfPx = (h.halfCm * k).toFloat()
            for (side in intArrayOf(-1, 1)) {
                val hx = cx + side * halfPx
                val d = kotlin.math.hypot(x - hx, y - hy)
                if (d <= touch && d < bestDist) {
                    bestDist = d
                    best = h.slot
                }
            }
        }
        return best
    }

    /** 핸들 위치를 값으로 되돌린다 — 역산도 [BodySilhouetteSpec]의 상수를 쓴다. */
    private fun applyDrag(m: Measures, slot: BodySlot, x: Float) {
        if (k <= 0f) return
        val halfCm = (abs(x - cx) / k).toDouble()
        val value = when (slot) {
            BodySlot.BUST -> BodySilhouetteSpec.bustFromHandle(m, halfCm).bust
            else -> BodySilhouetteSpec.valueFromHandle(slot, halfCm) ?: return
        }
        dragValue = value
        measures = when (slot) {
            BodySlot.SHOULDER -> m.copy(shoulder = value)
            BodySlot.BUST -> m.copy(bust = value)
            BodySlot.WAIST -> m.copy(waist = value)
            BodySlot.HIP -> m.copy(hip = value)
            else -> m
        }
        onHandleDrag?.invoke(slot, value)
    }

    // ── 값·문구 ─────────────────────────────────────────────────────────────

    private fun slotValue(m: Measures, slot: BodySlot): Double = when (slot) {
        BodySlot.SHOULDER -> m.shoulder
        BodySlot.BUST -> m.bust
        BodySlot.WAIST -> m.waist
        BodySlot.HIP -> m.hip
        else -> 0.0
    }

    private fun slotName(slot: BodySlot): String? = when (slot) {
        BodySlot.SHOULDER -> context.getString(R.string.silhouette_slot_shoulder)
        BodySlot.BUST -> context.getString(R.string.silhouette_slot_bust)
        BodySlot.WAIST -> context.getString(R.string.silhouette_slot_waist)
        BodySlot.HIP -> context.getString(R.string.silhouette_slot_hip)
        else -> null
    }

    private fun valueText(value: Double): String =
        context.getString(R.string.silhouette_value_cm, "%.0f".format(value))

    /** 그림을 못 보는 사용자에게도 같은 정보가 가야 한다(설계 5-4-6). */
    private fun updateContentDescription() {
        val m = measures
        contentDescription = if (m == null) {
            context.getString(R.string.silhouette_content_description)
        } else {
            val parts = listOf(BodySlot.SHOULDER, BodySlot.BUST, BodySlot.WAIST, BodySlot.HIP)
                .mapNotNull { slot -> slotName(slot)?.let { "$it ${valueText(slotValue(m, slot))}" } }
                .joinToString(", ")
            "${context.getString(R.string.silhouette_content_description)}: $parts"
        }
    }

    init {
        updateContentDescription()
    }
}
