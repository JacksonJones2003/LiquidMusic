/*
 * LiquidMusic - Liquid Glass for Apple Music (patches compatible with Morphe).
 * Licensed under the GNU General Public License v3.0, see LICENSE.
 *
 * The drop follows the one of LiquidBottomTabs in Backdrop by Kyant (Apache License 2.0):
 * https://github.com/Kyant0/AndroidLiquidGlass
 */
package app.liquidmusic.extension;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RecordingCanvas;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import android.os.Build;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;

import java.util.WeakHashMap;

/**
 * The glide of the liquid tab bar, on the floating tab strip that Apple Music shows at the top of
 * large screens (unfolded foldables, tablets): press a tab, slide along the strip, and a drop of
 * glass follows the finger, bending the labels under it; lifting the finger selects the tab under
 * the drop.
 * <p>
 * The strip is Apple's own Compose view and stays in charge of the tabs: it draws them, keeps the
 * selection and navigates. The drop's view, over the strip, receives the touches that start on the
 * tabs and passes them on to the strip, so a tap reaches Apple as usual. (A touch listener on the
 * strip would never run: the ComposeView's child takes the touches.) Once the finger slides
 * further than the touch slop, Apple's press is cancelled, the drop takes over, and on release
 * Apple gets a tap where the drop is.
 * <p>
 * The drop is drawn by a view of its own, over the strip, that records the strip's glass and tabs
 * in a RenderNode and bends them with the lens of {@link GlassShaders} (Android 13+).
 */
@TargetApi(Build.VERSION_CODES.TIRAMISU)
final class TopTabsGlide {

    private static final WeakHashMap<View, TopTabsGlide> GLIDES = new WeakHashMap<>();

    /** Called while the strip's BlurView draws: sets the glide up after this frame. */
    static void install(View pill, String tabsIdName) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || !LiquidSettings.liquidTabBar()) return;
        if (!(pill.getParent() instanceof ViewGroup)) return;
        final ViewGroup parent = (ViewGroup) pill.getParent();
        final int id = LiquidGlassPatch.idOf(pill, tabsIdName);
        if (id == 0) return;
        final View tabs = parent.findViewById(id);
        if (tabs == null || tabs.getParent() != parent || GLIDES.containsKey(tabs)) return;
        GLIDES.put(tabs, null); // Pending until the next message.
        pill.post(() -> {
            try {
                if (GLIDES.get(tabs) == null && tabs.isAttachedToWindow()) {
                    GLIDES.put(tabs, new TopTabsGlide(parent, pill, tabs));
                }
            } catch (Throwable t) {
                Log.e(LiquidGlassPatch.TAG, "Tab glide disabled", t);
            }
        });
    }

    private final View pill;
    private final View tabs;
    private final Drop drop;
    private final int touchSlop;

    private boolean tracking;
    private boolean gliding;
    private float downX;

    private TopTabsGlide(ViewGroup parent, View pill, View tabs) {
        this.pill = pill;
        this.tabs = tabs;
        touchSlop = ViewConfiguration.get(tabs.getContext()).getScaledTouchSlop();
        drop = new Drop(tabs.getContext());
        parent.addView(drop, parent.indexOfChild(tabs) + 1,
                new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    // region Touches

    /**
     * A touch on the drop's view, in the coordinates of the strip.
     *
     * @return false for a touch that does not start on the tabs, which then goes to the views
     * under the drop's view as if it was not there.
     */
    boolean onTouch(MotionEvent event) {
        try {
            return handle(event);
        } catch (Throwable t) {
            Log.e(LiquidGlassPatch.TAG, "Tab glide failed", t);
            if (tracking && !gliding) {
                final MotionEvent cancel = MotionEvent.obtain(event);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                forward(cancel);
            }
            tracking = gliding = false;
            drop.hide();
            return false;
        }
    }

    private boolean handle(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                gliding = false;
                tracking = LiquidSettings.liquidTabBar() && tabs.isShown() && pill.isShown()
                        && inTabs(event.getX(), event.getY());
                if (!tracking) return false;
                downX = event.getX();
                forward(MotionEvent.obtain(event)); // Apple shows its press.
                return true;

            case MotionEvent.ACTION_MOVE:
                if (!tracking) return false;
                if (!gliding) {
                    if (Math.abs(event.getX() - downX) <= touchSlop) {
                        forward(MotionEvent.obtain(event));
                        return true;
                    }
                    gliding = true;
                    // Apple's press would select the tab the finger started on: cancel it.
                    final MotionEvent cancel = MotionEvent.obtain(event);
                    cancel.setAction(MotionEvent.ACTION_CANCEL);
                    forward(cancel);
                    if (drop.getParent() != null) drop.getParent().requestDisallowInterceptTouchEvent(true);
                    if (LiquidSettings.tabHaptics()) tabs.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    drop.show(clampedX(event.getX()));
                    return true;
                }
                drop.moveTo(clampedX(event.getX()));
                return true;

            case MotionEvent.ACTION_UP:
                if (!tracking) return false;
                if (!gliding) {
                    tracking = false;
                    forward(MotionEvent.obtain(event)); // A plain tap: Apple's.
                    return true;
                }
                tracking = gliding = false;
                final float x = clampedX(event.getX());
                final float y = pillTop() + pill.getHeight() / 2f;
                tap(event, x, y);
                if (LiquidSettings.tabHaptics()) tabs.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                drop.hide();
                return true;

            case MotionEvent.ACTION_CANCEL:
                if (!tracking) return false;
                if (!gliding) forward(MotionEvent.obtain(event));
                tracking = gliding = false;
                drop.hide();
                return true;

            default:
                // Other pointers: Apple's until the glide starts.
                if (tracking && !gliding) forward(MotionEvent.obtain(event));
                return tracking;
        }
    }

    /** A tap on Apple's strip, as if the finger had been lifted where the drop is. */
    private void tap(MotionEvent template, float x, float y) {
        final MotionEvent down = MotionEvent.obtain(template);
        down.setAction(MotionEvent.ACTION_DOWN);
        down.setLocation(x, y);
        forward(down);
        final MotionEvent up = MotionEvent.obtain(template);
        up.setAction(MotionEvent.ACTION_UP);
        up.setLocation(x, y);
        forward(up);
    }

    private void forward(MotionEvent event) {
        try {
            tabs.dispatchTouchEvent(event);
        } finally {
            event.recycle();
        }
    }

    // endregion

    // region Geometry, in the coordinates of the strip

    private float pillLeft() {
        return pill.getLeft() + pill.getTranslationX() - tabs.getLeft() - tabs.getTranslationX();
    }

    private float pillTop() {
        return pill.getTop() + pill.getTranslationY() - tabs.getTop() - tabs.getTranslationY();
    }

    /**
     * The tabs, between the round buttons at both ends of the strip (the menu and search), which
     * are as wide as the strip is high.
     */
    private boolean inTabs(float x, float y) {
        final float left = pillLeft();
        final float top = pillTop();
        final float height = pill.getHeight();
        return y >= top && y <= top + height
                && x >= left + height && x <= left + pill.getWidth() - height;
    }

    private float clampedX(float x) {
        final float half = drop.baseWidth() / 2f;
        final float left = pillLeft() + pill.getHeight() + half;
        final float right = pillLeft() + pill.getWidth() - pill.getHeight() - half;
        return left >= right ? (left + right) / 2f : Math.max(left, Math.min(right, x));
    }

    // endregion

    /** The drop of glass, drawn over the strip while the finger glides. */
    @SuppressLint("ViewConstructor")
    private final class Drop extends View {

        private static final float LENS_HEIGHT_DP = 10f;
        private static final float LENS_AMOUNT_DP = 14f;
        /** How much bigger the drop gets than Apple's selection, as LiquidBottomTabs (78/56). */
        private static final float PRESSED_SCALE = 1.3f;

        private final float density;
        private final RenderNode content = new RenderNode("LiquidDrop");
        private final RectF rect = new RectF();
        private final Path shape = new Path();
        private final Paint surface = new Paint();
        private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final GlassRim rim;
        private ValueAnimator animator;
        private float progress;
        private boolean showing;
        /** Centre of the drop, in the coordinates of the strip. */
        private float centerX;
        private int effectWidth = -1;
        private int effectHeight = -1;

        Drop(Context context) {
            super(context);
            density = context.getResources().getDisplayMetrics().density;
            rim = new GlassRim(density, 100);
            surface.setColor(0x08000000); // Black at 3%, as the pressed drop of LiquidBottomTabs.
            shadow.setColor(0x1A000000);
            shadow.setMaskFilter(new BlurMaskFilter(12f * density, BlurMaskFilter.Blur.NORMAL));
            setWillNotDraw(false);
            // Visible all the time, to receive the touches on the strip, but draws nothing at rest.
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @SuppressLint("ClickableViewAccessibility") // Taps go on to Apple's strip and its semantics.
        @Override
        public boolean onTouchEvent(MotionEvent event) {
            // To the coordinates of the strip.
            final float dx = tabs.getLeft() + tabs.getTranslationX() - getLeft() - getTranslationX();
            final float dy = tabs.getTop() + tabs.getTranslationY() - getTop() - getTranslationY();
            final MotionEvent local = MotionEvent.obtain(event);
            local.offsetLocation(-dx, -dy);
            try {
                return TopTabsGlide.this.onTouch(local);
            } finally {
                local.recycle();
            }
        }

        /** Width of the drop at rest, close to Apple's selection. */
        float baseWidth() {
            return baseHeight() * 2.05f;
        }

        private float baseHeight() {
            return Math.max(0f, pill.getHeight() - 8f * density);
        }

        void show(float x) {
            centerX = x;
            showing = true;
            animateTo(1f, null);
            invalidate();
        }

        void moveTo(float x) {
            centerX = x;
            invalidate();
        }

        void hide() {
            if (!showing) return;
            showing = false;
            animateTo(0f, null);
        }

        private void animateTo(float target, Runnable end) {
            if (animator != null) animator.cancel();
            final ValueAnimator next = ValueAnimator.ofFloat(progress, target);
            next.setDuration(target > progress ? 160 : 220);
            next.setInterpolator(new DecelerateInterpolator());
            next.addUpdateListener(a -> {
                progress = (float) a.getAnimatedValue();
                invalidate();
            });
            if (end != null) {
                next.addListener(new android.animation.AnimatorListenerAdapter() {
                    private boolean cancelled;

                    @Override
                    public void onAnimationCancel(android.animation.Animator animation) {
                        cancelled = true;
                    }

                    @Override
                    public void onAnimationEnd(android.animation.Animator animation) {
                        if (!cancelled) end.run();
                    }
                });
            }
            animator = next;
            next.start();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (progress <= 0f || pill.getWidth() <= 0 || !tabs.isShown()) return;
            try {
                drawDrop(canvas);
            } catch (Throwable t) {
                Log.e(LiquidGlassPatch.TAG, "Tab glide drop failed", t);
                showing = false;
                progress = 0f;
            }
        }

        private void drawDrop(Canvas canvas) {
            final float scale = 1f + (PRESSED_SCALE - 1f) * progress;
            final float width = baseWidth() * scale;
            final float height = baseHeight() * scale;
            // Strip coordinates to the coordinates of this view (which fills the parent).
            final float stripX = tabs.getLeft() + tabs.getTranslationX() - getLeft();
            final float stripY = tabs.getTop() + tabs.getTranslationY() - getTop();
            final float cx = stripX + centerX;
            final float cy = stripY + pillTop() + pill.getHeight() / 2f;
            rect.set(cx - width / 2f, cy - height / 2f, cx + width / 2f, cy + height / 2f);
            final float corner = height / 2f;
            final int nodeWidth = (int) Math.ceil(width);
            final int nodeHeight = (int) Math.ceil(height);

            // What is under the drop: the glass of the strip, then its tabs.
            content.setPosition(0, 0, nodeWidth, nodeHeight);
            final RecordingCanvas recording = content.beginRecording();
            try {
                recording.translate(-rect.left, -rect.top);
                final int save = recording.save();
                recording.translate(pill.getLeft() + pill.getTranslationX() - getLeft(),
                        pill.getTop() + pill.getTranslationY() - getTop());
                pill.draw(recording);
                recording.restoreToCount(save);
                recording.translate(stripX, stripY);
                tabs.draw(recording);
            } finally {
                content.endRecording();
            }
            if (nodeWidth != effectWidth || nodeHeight != effectHeight) {
                content.setRenderEffect(lens(nodeWidth, nodeHeight, corner));
                effectWidth = nodeWidth;
                effectHeight = nodeHeight;
            }

            shape.rewind();
            shape.addRoundRect(rect, corner, corner, Path.Direction.CW);

            final int alpha = Math.round(255 * Math.min(1f, progress));
            // Shadow around the drop, never under it.
            int save = canvas.save();
            canvas.clipOutPath(shape);
            shadow.setAlpha(Math.round(0x1A * Math.min(1f, progress)));
            canvas.translate(0f, 2f * density);
            canvas.drawPath(shape, shadow);
            canvas.restoreToCount(save);

            save = canvas.saveLayerAlpha(rect, alpha);
            canvas.clipPath(shape);
            canvas.translate(rect.left, rect.top);
            canvas.drawRenderNode(content);
            canvas.translate(-rect.left, -rect.top);
            canvas.drawRect(rect, surface);
            canvas.restoreToCount(save);

            rim.setIntensity(Math.round(100 * Math.min(1f, progress)));
            rim.setCornerRadius(corner);
            rim.setBounds(Math.round(rect.left), Math.round(rect.top), Math.round(rect.right), Math.round(rect.bottom));
            rim.draw(canvas);

            // The strip and its glass change while the finger moves (Apple's press, scrolling).
            if (showing) postInvalidateOnAnimation();
        }

        private RenderEffect lens(int width, int height, float corner) {
            final float lensHeight = Math.min(LENS_HEIGHT_DP * density, Math.min(width, height) * GlassRenderer.MAX_REFRACTION_FRACTION);
            final float lensAmount = Math.min(LENS_AMOUNT_DP * density, Math.min(width, height) / 2f - 1f);
            final RuntimeShader shader = new RuntimeShader(GlassShaders.REFRACTION_WITH_DISPERSION);
            shader.setFloatUniform("size", width, height);
            shader.setFloatUniform("offset", 0f, 0f);
            shader.setFloatUniform("cornerRadii", corner, corner, corner, corner);
            shader.setFloatUniform("refractionHeight", lensHeight);
            shader.setFloatUniform("refractionAmount", -lensAmount);
            shader.setFloatUniform("depthEffect", 0f);
            shader.setFloatUniform("chromaticAberration", 1f);
            return RenderEffect.createRuntimeShaderEffect(shader, "content");
        }
    }
}
