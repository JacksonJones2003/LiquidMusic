/*
 * LiquidMusic - Liquid Glass for Apple Music (patches compatible with Morphe).
 * Licensed under the GNU General Public License v3.0, see LICENSE.
 *
 * The tabs are LiquidBottomTabs from Backdrop by Kyant (Apache License 2.0):
 * https://github.com/Kyant0/AndroidLiquidGlass (see libs/README.md).
 */
package app.liquidmusic.extension

import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.Menu
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.liquidmusic.extension.compose.ComposeHost
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.catalog.components.LiquidBottomTab
import com.kyant.backdrop.catalog.components.LiquidBottomTabs
import com.kyant.backdrop.catalog.components.LiquidBottomTabsGlass
import kotlinx.coroutines.withTimeoutOrNull
import java.util.WeakHashMap
import kotlin.math.roundToInt

/**
 * The liquid tab bar of large screens (unfolded foldables, tablets), where Apple Music shows its
 * tabs in a floating strip at the top (top_nav_tabs, a ComposeView over the top_nav_blur glass)
 * instead of the bottom bar: the same LiquidBottomTabs as [LiquidTabBar], so the selection is a
 * drop of glass that lifts when pressed, can be dragged from tab to tab, and settles on the tab.
 *
 * Apple's strip stays where it is, laid out but no longer drawn (empty clip bounds). The glass of
 * the strip is still Apple's BlurView, drawn by [GlassRenderer]; this bar only draws the tabs, the
 * drop, the menu button and the search button, in a ComposeView next to the strip that follows it
 * on every frame.
 *
 * On these screens Apple also keeps its tabs in the menu of the navigation drawer
 * (drawer_nav_view, top_level_*_page), and checks the current one there. The bar reads the tabs
 * and the selection from that menu and selects tabs through it, which is what Apple's drawer does.
 * The menu and search buttons tap Apple's strip at their places.
 */
@RequiresApi(Build.VERSION_CODES.S)
internal class LiquidTopBar private constructor(
    private val pill: View,
    private val strip: View,
    private val drawer: View,
) : ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener {

    private class Tab(val id: Int, val title: String)

    private val parent = strip.parent as ViewGroup
    private val overlay: ComposeView = ComposeHost.createView(strip.context)
    private val content = AppleContent(pill)
    private val density = strip.resources.displayMetrics.density
    /** Room around the bar for the drop, which grows when pressed. */
    private val overflow = (24 * density).roundToInt()
    private val location = IntArray(2)
    private var errorLogged = false
    private var tabsSignature = 0
    private var tabsRevision = -1
    private var searchIcon: Drawable? = null
    private val labelTypeface: Typeface? = findTypeface(drawer)

    // State read by the composition.
    private var tabs by mutableStateOf(emptyList<Tab>())
    private var selectedIndex by mutableIntStateOf(0)
    private var barX by mutableIntStateOf(0)
    private var barWidth by mutableIntStateOf(0)
    private var barHeight by mutableIntStateOf(0)
    private var overlayX by mutableIntStateOf(0)
    private var overlayY by mutableIntStateOf(0)

    init {
        overlay.outlineProvider = null
        overlay.setContent { Bar() }
        parent.addView(overlay, parent.indexOfChild(strip) + 1, ViewGroup.LayoutParams(parent.width, pill.height + 2 * overflow))
        refreshTabs()
        sync()
        strip.viewTreeObserver.addOnPreDrawListener(this)
        strip.addOnAttachStateChangeListener(this)
    }

    // region Composition

    @Composable
    private fun Bar() {
        val appleContent = rememberLayerBackdrop()
        val contentColor = if (isSystemInDarkTheme()) Color.White else Color.Black
        val fontFamily = remember(labelTypeface) {
            labelTypeface?.let { FontFamily(androidx.compose.ui.text.font.Typeface(it)) }
        }
        val selected = remember { { selectedIndex } }
        val glass = LiquidSettings.revision.let { glass() }
        val tabs = tabs
        Box(Modifier.fillMaxSize()) {
            // What is behind the bar, recorded for the drop but never shown itself.
            Box(
                Modifier
                    .matchParentSize()
                    .alpha(0f)
                    .layerBackdrop(appleContent)
                    .drawBehind { content.draw(this, overlayX, overlayY) }
            )
            if (tabs.isNotEmpty() && barWidth > 0 && barHeight > 0) {
                val localDensity = LocalDensity.current
                val width = with(localDensity) { barWidth.toDp() }
                val height = with(localDensity) { barHeight.toDp() }
                Row(
                    Modifier
                        .align(Alignment.CenterStart)
                        .offset { IntOffset(barX, 0) }
                        .width(width)
                        .height(height)
                        .pointerInput(Unit) { detectLongPress(::openSettings) },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Apple's menu button: opens the drawer.
                    Box(
                        Modifier
                            .size(height)
                            .clickable(interactionSource = null, indication = null) { tapStrip(height = barHeight, start = true) }
                            .drawBehind { drawMenuIcon(contentColor) }
                    )
                    SideEffect { logComposition(tabs.size) }
                    LiquidBottomTabs(
                        selectedTabIndex = selected,
                        onTabSelected = ::onTabSelected,
                        backdrop = appleContent,
                        tabsCount = tabs.size,
                        modifier = Modifier.weight(1f),
                        glass = glass,
                        barHeight = height,
                        showBar = false,
                    ) {
                        tabs.forEachIndexed { index, tab ->
                            LiquidBottomTab({ onTabClicked(index) }) {
                                SideEffect { logTab(index, tab.title) }
                                BasicText(
                                    tab.title,
                                    style = TextStyle(contentColor, 17f.sp, fontFamily = fontFamily),
                                    overflow = TextOverflow.Ellipsis,
                                    softWrap = false,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                    // Apple's search button.
                    Box(
                        Modifier
                            .size(height)
                            .clickable(interactionSource = null, indication = null) { tapStrip(height = barHeight, start = false) }
                            .drawBehind { drawSearchIcon(contentColor) }
                    )
                }
            }
        }
    }

    private var compositionLogs = 0
    private fun logComposition(count: Int) {
        if (compositionLogs++ < 3) Log.i(LiquidGlassPatch.TAG, "Top bar composed: $count tabs, bar $barX/$barWidth x $barHeight, selected $selectedIndex")
    }

    private var tabLogs = 0
    private fun logTab(index: Int, title: String) {
        if (tabLogs++ < 12) Log.i(LiquidGlassPatch.TAG, "Top bar tab $index: '$title'")
    }

    private fun DrawScope.drawMenuIcon(color: Color) {
        val lineWidth = 20f * density
        val stroke = 2.4f * density
        val gap = 6.5f * density
        val left = (size.width - lineWidth) / 2f
        val centerY = size.height / 2f
        for (i in -1..1) {
            val y = centerY + i * gap
            drawLine(color, Offset(left, y), Offset(left + lineWidth, y), stroke, StrokeCap.Round)
        }
    }

    private fun DrawScope.drawSearchIcon(color: Color) {
        val icon = searchIcon ?: return
        val iconSize = (24f * density).roundToInt()
        val left = ((size.width - iconSize) / 2f).roundToInt()
        val top = ((size.height - iconSize) / 2f).roundToInt()
        drawIntoCanvas { canvas ->
            icon.setBounds(left, top, left + iconSize, top + iconSize)
            icon.colorFilter = PorterDuffColorFilter(color.toArgb(), PorterDuff.Mode.SRC_IN)
            icon.draw(canvas.nativeCanvas)
        }
    }

    private fun glass(): LiquidBottomTabsGlass {
        val heightDp = pill.height / density
        val maxRefraction = heightDp * GlassRenderer.MAX_REFRACTION_FRACTION
        val refractionHeight = LiquidSettings.refractionHeightDp().coerceAtLeast(0).toFloat().coerceAtMost(maxRefraction)
        val refractionAmount = LiquidSettings.refractionAmountDp().coerceAtLeast(0).toFloat().coerceAtMost(refractionHeight)
        return LiquidBottomTabsGlass(
            blurRadius = LiquidSettings.blurRadiusDp().coerceAtLeast(0).dp,
            refractionHeight = refractionHeight.dp,
            refractionAmount = refractionAmount.dp,
            saturation = LiquidSettings.saturationPercent().coerceAtLeast(0) / 100f,
            surfaceAlpha = LiquidSettings.tintOpacityPercent().coerceIn(0, 100) / 100f,
        )
    }

    private fun openSettings() {
        overlay.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        SettingsSheet.show(pill, content)
    }

    // endregion

    // region Navigation

    private fun menu(): Menu? = invoke(drawer, "getMenu") as? Menu

    private fun checkedId(): Int = (invoke(drawer, "getCheckedItem") as? android.view.MenuItem)?.itemId ?: View.NO_ID

    private fun onTabClicked(index: Int) {
        val tab = tabs.getOrNull(index) ?: return
        if (index == selectedIndex) {
            // Same tab again: Apple scrolls its page back to the top.
            select(index, tab)
        } else {
            // The drop moves, then LiquidBottomTabs reports the new tab to onTabSelected.
            selectedIndex = index
        }
    }

    private fun onTabSelected(index: Int) {
        val tab = tabs.getOrNull(index) ?: return
        selectedIndex = index
        if (checkedId() != tab.id) {
            select(index, tab)
            if (LiquidSettings.tabHaptics()) overlay.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    /**
     * Selects a tab as Apple's drawer does. If Apple did not take it, taps the tab on Apple's
     * strip instead, at its place (the tabs share the strip between its two round buttons).
     */
    private fun select(index: Int, tab: Tab) {
        val handled = runCatching { menu()?.performIdentifierAction(tab.id, 0) == true }.getOrDefault(false)
        strip.postDelayed({
            if (!handled || (checkedId() != tab.id && index == selectedIndex)) {
                val count = tabs.size.coerceAtLeast(1)
                val start = pill.height.toFloat()
                val tabWidth = (pill.width - 2f * pill.height) / count
                tapStripAt(start + tabWidth * (index + 0.5f))
            }
        }, if (handled) 250 else 0)
    }

    /** Taps one of the round buttons at the ends of Apple's strip. */
    private fun tapStrip(height: Int, start: Boolean) {
        tapStripAt(if (start) height / 2f else pill.width - height / 2f)
    }

    /** Taps Apple's strip at a distance from the left of the glass, in its middle. */
    private fun tapStripAt(xInPill: Float) {
        val x = pill.left + pill.translationX - strip.left - strip.translationX + xInPill
        val y = pill.top + pill.translationY - strip.top - strip.translationY + pill.height / 2f
        val time = SystemClock.uptimeMillis()
        for (action in intArrayOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(time, time, action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try {
                strip.dispatchTouchEvent(event)
            } finally {
                event.recycle()
            }
        }
    }

    private fun refreshTabs() {
        tabsRevision = LiquidSettings.revision
        val menu = menu() ?: return
        tabsSignature = signature(menu)
        val resources = drawer.resources
        val all = (0 until menu.size()).map(menu::getItem).filter { it.isVisible }
        val names = all.associateWith { runCatching { resources.getResourceEntryName(it.itemId) }.getOrDefault("") }
        searchIcon = all.firstOrNull { names[it]!!.contains("search") }?.icon?.let {
            it.constantState?.newDrawable(resources)?.mutate() ?: it
        }
        tabs = all.filter { item ->
            val name = names[item]!!
            name.startsWith("top_level_") && !name.contains("search") && !isHidden(name)
        }.map { Tab(it.itemId, it.title?.toString().orEmpty()) }
        Log.i(LiquidGlassPatch.TAG, "Top bar tabs: " + all.joinToString { names[it] + "=" + it.title + (if (it.isChecked) "*" else "") } +
            " -> " + tabs.joinToString { it.title })
    }

    private fun signature(menu: Menu): Int {
        var signature = menu.size()
        for (i in 0 until menu.size()) {
            val item = menu.getItem(i)
            signature = 31 * signature + item.itemId
            signature = 31 * signature + (if (item.isVisible) 1 else 0)
            signature = 31 * signature + (item.title?.toString()?.hashCode() ?: 0)
        }
        return signature
    }

    // endregion

    // region Frame tracking

    override fun onPreDraw(): Boolean {
        try {
            if (LiquidSettings.liquidTabBar()) sync() else remove()
        } catch (t: Throwable) {
            if (!errorLogged) Log.e(LiquidGlassPatch.TAG, "Liquid top bar update failed", t)
            errorLogged = true
        }
        return true
    }

    private fun sync() {
        val menu = menu()
        if (menu != null && (tabsRevision != LiquidSettings.revision || signature(menu) != tabsSignature)) refreshTabs()

        // Apple's strip keeps its place and its touch targets (for the taps passed on), but
        // draws nothing while the bar has tabs to show.
        val replace = tabs.isNotEmpty()
        if (replace && strip.clipBounds?.isEmpty != true) strip.clipBounds = EMPTY
        if (!replace && strip.clipBounds != null) strip.clipBounds = null

        val shown = replace && strip.isShown && pill.isShown
        val visibility = if (shown) View.VISIBLE else View.INVISIBLE
        if (overlay.visibility != visibility) overlay.visibility = visibility
        val alpha = strip.alpha * pill.alpha
        if (overlay.alpha != alpha) overlay.alpha = alpha

        val params = overlay.layoutParams
        val width = parent.width
        val height = pill.height + 2 * overflow
        if (params.width != width || params.height != height) {
            params.width = width
            params.height = height
            overlay.layoutParams = params
        }
        overlay.translationX = -overlay.left.toFloat()
        overlay.translationY = pill.top + pill.translationY - overflow - overlay.top
        barX = (pill.left + pill.translationX).roundToInt()
        barWidth = pill.width
        barHeight = pill.height

        content.update()
        overlay.getLocationInWindow(location)
        overlayX = location[0]
        overlayY = location[1]

        val checked = checkedId()
        val index = tabs.indexOfFirst { it.id == checked }
        if (index >= 0 && index != selectedIndex) selectedIndex = index
    }

    /** Gives the strip back to Apple (the setting was turned off). */
    private fun remove() {
        strip.viewTreeObserver.removeOnPreDrawListener(this)
        strip.removeOnAttachStateChangeListener(this)
        parent.removeView(overlay)
        strip.clipBounds = null
        BARS.remove(strip)
    }

    override fun onViewAttachedToWindow(view: View) {
        strip.viewTreeObserver.addOnPreDrawListener(this)
    }

    override fun onViewDetachedFromWindow(view: View) {
        strip.viewTreeObserver.removeOnPreDrawListener(this)
    }

    // endregion

    companion object {
        private val EMPTY = Rect()
        private val BARS = WeakHashMap<View, LiquidTopBar?>()
        private val FAILED = WeakHashMap<View, Boolean>()
        private val METHODS = HashMap<String, java.lang.reflect.Method>()

        /** Called while the strip's BlurView draws: sets the bar up after this frame. */
        @JvmStatic
        fun install(pill: View, stripIdName: String) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !LiquidSettings.liquidTabBar()) return
            val parent = pill.parent as? ViewGroup ?: return
            val stripId = LiquidGlassPatch.idOf(pill, stripIdName)
            if (stripId == 0) return
            val strip = parent.findViewById<View>(stripId) ?: return
            if (strip.parent !== parent || BARS.containsKey(strip) || FAILED.containsKey(strip)) return
            val drawerId = LiquidGlassPatch.idOf(pill, "drawer_nav_view")
            val drawer = if (drawerId != 0) pill.rootView.findViewById<View>(drawerId) else null
            if (drawer == null) return
            BARS[strip] = null // Pending until the next message.
            pill.post {
                try {
                    if (BARS[strip] == null && strip.isAttachedToWindow && LiquidSettings.liquidTabBar()) {
                        BARS[strip] = LiquidTopBar(pill, strip, drawer)
                    } else if (BARS[strip] == null) {
                        BARS.remove(strip)
                    }
                } catch (t: Throwable) {
                    Log.e(LiquidGlassPatch.TAG, "Liquid top bar disabled", t)
                    FAILED[strip] = true
                    BARS.remove(strip)
                    strip.clipBounds = null
                }
            }
        }

        private fun isHidden(name: String) =
            (name.contains("new") && LiquidSettings.hideNewTab()) ||
                (name.contains("radio") && LiquidSettings.hideRadioTab())

        private fun findTypeface(view: View): Typeface? {
            if (view is TextView) return view.typeface
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) findTypeface(view.getChildAt(i))?.let { return it }
            }
            return null
        }

        private fun invoke(target: Any, name: String): Any? {
            val method = METHODS[name] ?: target.javaClass.methods.first {
                it.name == name && it.parameterTypes.isEmpty()
            }.also { METHODS[name] = it }
            return method.invoke(target)
        }

        /** Same as LiquidTabBar: a long press on the bar opens the Liquid Glass settings. */
        private suspend fun PointerInputScope.detectLongPress(onLongPress: () -> Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val released = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    while (true) {
                        val change = awaitPointerEvent(PointerEventPass.Initial).changes
                            .firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull
                        if (!change.pressed) return@withTimeoutOrNull
                        if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                            return@withTimeoutOrNull
                        }
                    }
                }
                if (released != null) return@awaitEachGesture
                onLongPress()
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    event.changes.forEach { it.consume() }
                } while (event.changes.any { it.pressed })
            }
        }
    }
}
