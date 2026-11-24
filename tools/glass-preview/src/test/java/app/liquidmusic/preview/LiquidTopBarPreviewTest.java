package app.liquidmusic.preview;

import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import eightbitlab.com.blurview.BlurTarget;
import eightbitlab.com.blurview.BlurView;

/** The liquid tab bar on the top strip of large screens (layout-w600dp). */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 35, qualifiers = "w760dp-h800dp-notnight-xxhdpi")
public class LiquidTopBarPreviewTest extends PreviewScene {

    private BlurView stripGlass;
    private FrameLayout strip;
    private StandInNavigationView drawer;

    /**
     * Measures a child at zero width the first time it is laid out, as Apple's ConstraintLayout
     * can do with a view added to it, then as a FrameLayout.
     */
    static final class ZeroFirstLayout extends FrameLayout {
        private final java.util.Set<View> seen = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
        private final java.util.Set<View> zeroed = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

        ZeroFirstLayout(android.content.Context context) {
            super(context);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                if (seen.add(child) && i > 0) {
                    child.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.EXACTLY), heightMeasureSpec);
                    zeroed.add(child);
                }
            }
        }

        @Override
        protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            super.onLayout(changed, left, top, right, bottom);
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                if (zeroed.remove(child)) {
                    child.layout(child.getLeft(), child.getTop(), child.getLeft(), child.getBottom());
                    post(child::requestLayout);
                }
            }
        }
    }

    @Test
    public void topBarShowsTabs() throws Exception {
        activity.getWindow().setBackgroundDrawable(new ColorDrawable(Color.WHITE));
        FrameLayout root = new ZeroFirstLayout(activity);
        BlurTarget target = new BlurTarget(activity);
        target.setId(R.id.blur_target);
        View page = content();
        page.setBackgroundColor(Color.WHITE);
        page.setPadding(dp(16), dp(20), 0, 0);
        target.addView(page, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(target, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        stripGlass = glass(R.id.top_nav_blur);
        FrameLayout.LayoutParams pill = new FrameLayout.LayoutParams(dp(395), dp(45), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        pill.topMargin = dp(144);
        root.addView(stripGlass, pill);

        strip = new FrameLayout(activity);
        strip.setId(R.id.top_nav_tabs);
        strip.addView(label("Apple's strip (hidden)", Color.RED, 14));
        FrameLayout.LayoutParams stripParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(60), Gravity.TOP);
        stripParams.topMargin = dp(136);
        root.addView(strip, stripParams);

        drawer = new StandInNavigationView(activity);
        drawer.setId(R.id.drawer_nav_view);
        int[] ids = {R.id.top_level_home_page, R.id.top_level_new_page, R.id.top_level_radio_page,
                R.id.top_level_library_page, R.id.top_level_search_page};
        String[] titles = {"Home", "New", "Radio", "Library", "Search"};
        for (int i = 0; i < ids.length; i++) drawer.getMenu().add(0, ids[i], i, titles[i]).setIcon(R.drawable.tab_search);
        drawer.setCheckedItem(ids[0]);
        root.addView(drawer, new FrameLayout.LayoutParams(1, 1));

        // As on the phone: "Hide New" on, and the drawer's labels in a font of their own.
        app.liquidmusic.extension.LiquidSettings.INSTANCE.init(activity);
        app.liquidmusic.extension.LiquidSettings.INSTANCE.set("hideNewTab", true);
        android.widget.TextView drawerLabel = label("Home", Color.BLACK, 14);
        drawerLabel.setTypeface(android.graphics.Typeface.create("serif", android.graphics.Typeface.NORMAL));
        drawer.addView(drawerLabel);
        BlurTarget source = target;
        if (Boolean.getBoolean("preview.outerTarget")) {
            // As layout-w600dp/activity_main.xml: a BlurTarget around the whole screen.
            BlurTarget outer = new BlurTarget(activity);
            if (Boolean.getBoolean("preview.outerSource")) source = outer;
            outer.addView(root, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            activity.setContentView(outer);
        } else {
            activity.setContentView(root);
        }
        stripGlass.setupWith(source, (ColorDrawable) activity.getWindow().getDecorView().getBackground());
        shadowOf(Looper.getMainLooper()).idle();
        render(null);
        render(null);
        Bitmap bitmap = render(System.getProperty("preview.name", "liquid-top-bar"));
        assertTrue("the top bar was set up", countComposeViews(root) > 0);
        // The tabs draw dark labels in the middle of the white glass.
        int dark = 0;
        int top = stripGlass.getTop() + dp(24); // Below the window's top inset too.
        for (int x = stripGlass.getLeft() + stripGlass.getHeight(); x < stripGlass.getRight() - stripGlass.getHeight(); x += 2) {
            for (int y = stripGlass.getTop(); y < stripGlass.getBottom(); y += 2) {
                if (luminance(bitmap.getPixel(x, y + dp(24))) < 100) dark++;
            }
        }
        assertTrue("the tabs are drawn (" + dark + " dark pixels)", dark > 50);

        // Search is the last tab: a tap moves the glass onto it and selects it in Apple's drawer.
        int[] location = new int[2];
        stripGlass.getLocationInWindow(location);
        // Tabs: menu, Home, Radio, Library, Search ("Hide New" is on).
        float tabsLeft = location[0] + dp(4);
        float tabWidth = (stripGlass.getWidth() - dp(8)) / 5f;
        float centerY = location[1] + stripGlass.getHeight() / 2f;
        tap(new float[]{tabsLeft + 4.5f * tabWidth, centerY});
        advance(800);
        render("liquid-top-bar-search");
        assertTrue("search was selected in Apple's drawer, got " + drawer.selections,
                drawer.selections.contains(R.id.top_level_search_page));

        // The menu button is a tab too: the drop goes onto it, then back to the current page.
        int[] stripTaps = {0};
        strip.setOnTouchListener((view, event) -> {
            // Only the bar's own taps (down and up at the same time), not the finger going through.
            if (event.getActionMasked() == android.view.MotionEvent.ACTION_UP
                    && event.getEventTime() == event.getDownTime()) stripTaps[0]++;
            return true;
        });
        tap(new float[]{tabsLeft + 0.5f * tabWidth, centerY});
        render("liquid-top-bar-menu-pressed"); // 400 ms later: the drop rests on the menu button.
        assertTrue("the menu tab of the bar opened the drawer through Apple's strip, taps: " + stripTaps[0], stripTaps[0] == 1);
        advance(900);
        render(null); // A frame, which sends the drop back (the preview only draws on demand).
        render("liquid-top-bar-menu-back");
    }
}
