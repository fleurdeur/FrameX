package com.example.scriptengine

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

enum class ScriptUiWidgetKind {
    PANEL,
    GROUP,
    TABBED_PANEL,
    TAB,
    STATIC_TEXT,
    EDIT_TEXT,
    BUTTON,
    ICON_BUTTON,
    CHECKBOX,
    RADIO_BUTTON,
    SLIDER,
    DROPDOWN_LIST,
    LIST_BOX,
    PROGRESS_BAR,
    CUSTOM
}

sealed interface ScriptUiDrawOp {
    data class FillRect(
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
        val r: Float,
        val g: Float,
        val b: Float,
        val a: Float
    ) : ScriptUiDrawOp

    data class StrokePath(
        val points: List<Pair<Float, Float>>,
        val closed: Boolean,
        val strokeWidth: Float,
        val r: Float,
        val g: Float,
        val b: Float,
        val a: Float
    ) : ScriptUiDrawOp

    data class FillPath(
        val points: List<Pair<Float, Float>>,
        val r: Float,
        val g: Float,
        val b: Float,
        val a: Float
    ) : ScriptUiDrawOp

    data class DrawText(
        val text: String,
        val x: Float,
        val y: Float,
        val r: Float,
        val g: Float,
        val b: Float,
        val a: Float
    ) : ScriptUiDrawOp
}

data class ScriptUiWidgetSnapshot(
    val id: String,
    val kind: ScriptUiWidgetKind,
    val text: String,
    val value: Float,
    val minValue: Float,
    val maxValue: Float,
    val checked: Boolean,
    val items: List<String>,
    val selectedIndex: Int,
    val orientation: String, // "column", "row", "stack"
    val enabled: Boolean,
    val visible: Boolean,
    val preferredWidth: Int,
    val preferredHeight: Int,
    val drawOps: List<ScriptUiDrawOp>,
    val children: List<ScriptUiWidgetSnapshot>
)

/**
 * Mutable headless ScriptUI node manipulated by ExtendScript (`thisObj.add(...)`, `graphics.newPen(...)`, etc.)
 * and snapshotted into immutable [ScriptUiWidgetSnapshot] trees for Jetpack Compose rendering!
 */
class ScriptUiNode(
    val id: String = "sui_${ NEXT_ID.incrementAndGet() }",
    val kind: ScriptUiWidgetKind,
    @Volatile var text: String = "",
    @Volatile var value: Float = 0f,
    @Volatile var minValue: Float = 0f,
    @Volatile var maxValue: Float = 100f,
    @Volatile var checked: Boolean = false,
    val items: CopyOnWriteArrayList<String> = CopyOnWriteArrayList(),
    @Volatile var selectedIndex: Int = 0,
    @Volatile var orientation: String = "column",
    @Volatile var enabled: Boolean = true,
    @Volatile var visible: Boolean = true,
    @Volatile var preferredWidth: Int = 240,
    @Volatile var preferredHeight: Int = 120
) {
    val children = CopyOnWriteArrayList<ScriptUiNode>()
    val drawOps = CopyOnWriteArrayList<ScriptUiDrawOp>()

    @Volatile var onClickCallback: (() -> Unit)? = null
    @Volatile var onChangeCallback: (() -> Unit)? = null
    @Volatile var onDrawCallback: (() -> Unit)? = null

    fun addChild(
        rawType: String,
        labelOrText: String = "",
        itemList: List<String> = emptyList(),
        initVal: Float = 0f,
        minVal: Float = 0f,
        maxVal: Float = 100f
    ): ScriptUiNode {
        val kindMapped = when (rawType.lowercase().trim()) {
            "panel" -> ScriptUiWidgetKind.PANEL
            "group" -> ScriptUiWidgetKind.GROUP
            "tabbedpanel" -> ScriptUiWidgetKind.TABBED_PANEL
            "tab" -> ScriptUiWidgetKind.TAB
            "statictext" -> ScriptUiWidgetKind.STATIC_TEXT
            "edittext" -> ScriptUiWidgetKind.EDIT_TEXT
            "button" -> ScriptUiWidgetKind.BUTTON
            "iconbutton" -> ScriptUiWidgetKind.ICON_BUTTON
            "checkbox" -> ScriptUiWidgetKind.CHECKBOX
            "radiobutton" -> ScriptUiWidgetKind.RADIO_BUTTON
            "slider", "scrollbar" -> ScriptUiWidgetKind.SLIDER
            "dropdownlist" -> ScriptUiWidgetKind.DROPDOWN_LIST
            "listbox" -> ScriptUiWidgetKind.LIST_BOX
            "progressbar" -> ScriptUiWidgetKind.PROGRESS_BAR
            "customview", "customboundedvalue", "custom" -> ScriptUiWidgetKind.CUSTOM
            else -> ScriptUiWidgetKind.STATIC_TEXT
        }
        val child = ScriptUiNode(
            kind = kindMapped,
            text = labelOrText,
            value = initVal,
            minValue = minVal,
            maxValue = maxVal,
            items = CopyOnWriteArrayList(itemList)
        )
        children.add(child)
        return child
    }

    fun findById(targetId: String): ScriptUiNode? {
        if (id == targetId) return this
        for (c in children) {
            val hit = c.findById(targetId)
            if (hit != null) return hit
        }
        return null
    }

    fun toSnapshot(): ScriptUiWidgetSnapshot {
        onDrawCallback?.let { cb ->
            drawOps.clear()
            runCatching { cb() }
        }
        return ScriptUiWidgetSnapshot(
            id = id,
            kind = kind,
            text = text,
            value = value,
            minValue = minValue,
            maxValue = maxValue,
            checked = checked,
            items = items.toList(),
            selectedIndex = selectedIndex,
            orientation = orientation,
            enabled = enabled,
            visible = visible,
            preferredWidth = preferredWidth,
            preferredHeight = preferredHeight,
            drawOps = drawOps.toList(),
            children = children.map { it.toSnapshot() }
        )
    }

    private companion object {
        val NEXT_ID = AtomicInteger(0)
    }
}
