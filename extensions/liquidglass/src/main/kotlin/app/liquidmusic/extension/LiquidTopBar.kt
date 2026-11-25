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
 * the strip is still Apple's BlurView, drawn by [GlassRenderer]; this bar only draws the tabs and
 * the drop, in a ComposeView next to the strip that follows it on every frame.
 *
 * On these screens Apple also keeps its tabs in the menu of the navigation drawer
 * (drawer_nav_view, top_level_*_page), and checks the current one there. The bar reads the tabs
 * and the selection from that menu and selects tabs through it, which is what Apple's drawer does.
 *
 * The first tab is the page's "More Options" button (⋮), moved into the bar: it taps Apple's own
 * button, hidden at the top right of the page, so Apple's popup opens. The strip's menu button
 * (the drawer) is not part of the bar.
 */
@RequiresApi(Build.VERSION_CODES.S)
internal class LiquidTopBar private constructor(
    private val pill: View,
    private val strip: View,
    private val drawer: View,
) : ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener {

    /**
     * A tab: a title, an icon (search), or the more button (⋮), which is a tab of the bar so the
     * drop can reach it too, but opens Apple's "More Options" popup instead of a page.
     */
    private class Tab(val id: Int, val title: String, val icon: Drawable? = null, val more: Boolean = false)

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
    private val labelTypeface: Typeface? = findTypeface(drawer)

    // State read by the composition.
    private var tabs by mutableStateOf(emptyList<Tab>())
    private var selectedIndex by mutableIntStateOf(0)
    /** Until when the drop stays on the more button after it opened Apple's popup. */
    private var moreButton = java.lang.ref.WeakReference<View>(null)
    private var nextMoreSearch = 0L
    /** Whether the page on screen has a more button (the tab is dimmed otherwise). */
    private var moreAvailable by mutableStateOf(false)
    private val clippedContainers = WeakHashMap<View, Boolean>()
    private var moreReturnAt = 0L
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
                                val icon = tab.icon
                                if (tab.more) {
                                    Box(Modifier.size(24f.dp).drawBehind { drawMoreIcon(contentColor) })
                                } else if (icon != null) {
                                    // Search: Apple's magnifying glass, as a tab the drop can reach.
                                    Box(Modifier.size(24f.dp).drawBehind { drawIcon(icon, contentColor) })
                                } else {
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
                    }
                }
            }
        }
    }

    private fun DrawScope.drawMoreIcon(color: Color) {
        val radius = 2.6f * density
        val gap = 7f * density
        val center = Offset(size.width / 2f, size.height / 2f)
        val dots = if (moreAvailable) color else color.copy(alpha = color.alpha * 0.35f)
        for (i in -1..1) drawCircle(dots, radius, Offset(center.x, center.y + i * gap))
    }

    private fun DrawScope.drawIcon(icon: Drawable, color: Color) {
        drawIntoCanvas { canvas ->
            icon.setBounds(0, 0, size.width.roundToInt(), size.height.roundToInt())
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
        if (tab.more) {
            // The drop moves onto the more button, then onTabSelected opens Apple's popup. Until
            // then, the frame sync must not send it back to the current page.
            if (index == selectedIndex) {
                openMore(index)
            } else {
                moreReturnAt = SystemClock.uptimeMillis() + MORE_SLIDE_TIMEOUT_MS
                selectedIndex = index
            }
            return
        }
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
        if (tab.more) {
            openMore(index)
            return
        }
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
                if (tab.icon != null) {
                    tapStrip(height = pill.height, start = false)
                } else {
                    // Apple's text tabs share the strip between its two round buttons.
                    val textTabs = tabs.filter { it.icon == null && !it.more }
                    val position = textTabs.indexOf(tab)
                    val tabWidth = (pill.width - 2f * pill.height) / textTabs.size.coerceAtLeast(1)
                    tapStripAt(pill.height + tabWidth * (position + 0.5f))
                }
            }
        }, if (handled) 250 else 0)
    }

    /**
     * Opens Apple's "More Options" popup with its own button, hidden at the top of the page. The
     * drop stays on the tab a moment, then goes back to the current page.
     */
    private fun openMore(index: Int) {
        selectedIndex = index
        moreReturnAt = SystemClock.uptimeMillis() + MORE_RETURN_DELAY_MS
        if (LiquidSettings.tabHaptics()) overlay.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        tapMore()
        overlay.postDelayed({
            if (SystemClock.uptimeMillis() < moreReturnAt || tabs.getOrNull(selectedIndex)?.more != true) return@postDelayed
            val checked = checkedId()
            val current = if (checked == View.NO_ID) -1 else tabs.indexOfFirst { !it.more && it.id == checked }
            if (current >= 0) selectedIndex = current
        }, MORE_RETURN_DELAY_MS)
    }

    // region Apple's more button

    /**
     * Apple's "More Options" button (⋮) floats at the top right of each page: the glass
     * option_button_blur, in an AndroidView of the page's floating_app_bar_container ComposeView,
     * which draws the dots and handles the tap. Finds the one of the page on screen.
     */
    private fun findMoreButton(): View? {
        moreButton.get()?.let { if (it.isAttachedToWindow && it.isShown && it.width > 0) return it }
        val now = SystemClock.uptimeMillis()
        if (now < nextMoreSearch) return null
        nextMoreSearch = now + MORE_SEARCH_INTERVAL_MS
        val id = LiquidGlassPatch.idOf(pill, "option_button_blur")
        if (id == 0) return null
        val found = findShown(strip.rootView, id)
        moreButton = java.lang.ref.WeakReference(found)
        return found
    }

    private fun findShown(view: View, id: Int): View? {
        if (view.visibility != View.VISIBLE) return null
        if (view.id == id && view.width > 0 && view.isShown) return view
        if (view is ViewGroup) {
            for (i in view.childCount - 1 downTo 0) findShown(view.getChildAt(i), id)?.let { return it }
        }
        return null
    }

    /** The page's floating bar that holds the button, which receives its taps. */
    private fun moreContainer(button: View): View? {
        val id = LiquidGlassPatch.idOf(pill, "floating_app_bar_container")
        var parent = button.parent
        while (parent is View) {
            if (parent.id == id) return parent
            parent = parent.parent
        }
        return null
    }

    /**
     * Hides Apple's floating more button, which is now a tab of the bar: clips the page's
     * floating bar just before it, so the buttons on its left (back, share) stay.
     */
    private fun hideMoreButton(button: View?) {
        val container = button?.let(::moreContainer) ?: return
        button.getLocationInWindow(location)
        val buttonLeft = location[0]
        container.getLocationInWindow(location)
        val clip = Rect(0, 0, (buttonLeft - location[0]).coerceAtLeast(0), container.height)
        if (container.clipBounds != clip) container.clipBounds = clip
        clippedContainers[container] = true
    }

    private fun showMoreButtons() {
        for (container in clippedContainers.keys.toList()) container.clipBounds = null
        clippedContainers.clear()
    }

    /** A tap on Apple's more button, which opens its popup where the button is. */
    private fun tapMore() {
        val button = findMoreButton() ?: return
        val container = moreContainer(button) ?: return
        button.getLocationInWindow(location)
        val x = location[0] + button.width / 2f
        val y = location[1] + button.height / 2f
        container.getLocationInWindow(location)
        val time = SystemClock.uptimeMillis()
        for (action in intArrayOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(time, time, action, x - location[0], y - location[1], 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try {
                container.dispatchTouchEvent(event)
            } finally {
                event.recycle()
            }
        }
    }

    // endregion

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
        tabs = listOf(Tab(View.NO_ID, "", more = true)) + all.filter { item ->
            val name = names[item]!!
            name.startsWith("top_level_") && !isHidden(name)
        }.map { item ->
            // Search is a tab too, as on the bottom bar, drawn with Apple's magnifying glass.
            val icon = if (names[item]!!.contains("search")) {
                item.icon?.let { it.constantState?.newDrawable(resources)?.mutate() ?: it }
            } else null
            Tab(item.itemId, item.title?.toString().orEmpty(), icon)
        }
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
        if (!replace && strip.clipBounds != null) {
            strip.clipBounds = null
            showMoreButtons()
        }

        val more = if (replace) findMoreButton() else null
        if (moreAvailable != (more != null)) moreAvailable = more != null
        hideMoreButton(more)

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
        val index = if (checked == View.NO_ID) -1 else tabs.indexOfFirst { !it.more && it.id == checked }
        // Back to the current page once the drop has shown the more button pressed.
        if (index >= 0 && index != selectedIndex && SystemClock.uptimeMillis() >= moreReturnAt) selectedIndex = index
    }

    /** Gives the strip back to Apple (the setting was turned off). */
    private fun remove() {
        strip.viewTreeObserver.removeOnPreDrawListener(this)
        strip.removeOnAttachStateChangeListener(this)
        parent.removeView(overlay)
        strip.clipBounds = null
        showMoreButtons()
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
        private const val MORE_RETURN_DELAY_MS = 600L
        /** How often to look for the more button of the page on screen when there is none. */
        private const val MORE_SEARCH_INTERVAL_MS = 300L
        /** Longest the drop may take to reach the more button before the sync takes over again. */
        private const val MORE_SLIDE_TIMEOUT_MS = 1500L
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
        // (Search stays: it is the button at the end of Apple's strip.)

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
