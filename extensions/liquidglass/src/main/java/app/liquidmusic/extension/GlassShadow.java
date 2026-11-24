/*
 * LiquidMusic - Liquid Glass for Apple Music (patches compatible with Morphe).
 * Licensed under the GNU General Public License v3.0, see LICENSE.
 *
 * The shadow follows Shadow.Default of Backdrop by Kyant (Apache License 2.0):
 * https://github.com/Kyant0/AndroidLiquidGlass
 */
package app.liquidmusic.extension;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;
import android.view.ViewGroup;

/**
 * The drop shadow of a floating glass surface, the same as the one Backdrop draws under the liquid
 * tab bar (24 dp blur, 4 dp down, 10% black). Without it the mini player has no edge on white
 * pages, while the tab bar under it does.
 * <p>
 * BlurView clips what it draws to its bounds, so the shadow lives in a view of its own, added to
 * the same parent just below the BlurView and moved over it on every frame. The shape of the
 * surface is cut out of the shadow, so it never darkens the glass.
 */
@SuppressLint("ViewConstructor")
final class GlassShadow extends View {

    private static final float RADIUS_DP = 24f;
    private static final float OFFSET_DP = RADIUS_DP / 6f;
    private static final int COLOR = 0x1A000000; // Black at 10%.

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path shape = new Path();
    private final RectF rect = new RectF();
    /** Room around the surface for the blur and the offset. */
    private final int margin;
    private final float offset;
    private int surfaceWidth = -1;
    private int surfaceHeight = -1;
    private float surfaceCorner = -1f;

    GlassShadow(Context context) {
        super(context);
        final float density = context.getResources().getDisplayMetrics().density;
        final float radius = RADIUS_DP * density;
        offset = OFFSET_DP * density;
        margin = (int) Math.ceil(2f * radius + offset);
        paint.setColor(COLOR);
        paint.setMaskFilter(new BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL));
        setWillNotDraw(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setClickable(false);
        setFocusable(false);
    }

    /** Adds the shadow just below the surface, or returns false if its parent cannot hold it. */
    boolean attachBelow(View surface) {
        if (!(surface.getParent() instanceof ViewGroup)) return false;
        final ViewGroup parent = (ViewGroup) surface.getParent();
        if (getParent() == parent) return true;
        if (getParent() instanceof ViewGroup) ((ViewGroup) getParent()).removeView(this);
        parent.addView(this, parent.indexOfChild(surface), new ViewGroup.LayoutParams(0, 0));
        return true;
    }

    void detach() {
        if (getParent() instanceof ViewGroup) ((ViewGroup) getParent()).removeView(this);
    }

    /** Follows the position, size, corners, visibility and opacity of the surface. */
    void follow(View surface, float corner) {
        final int width = surface.getWidth();
        final int height = surface.getHeight();
        final boolean shown = surface.isShown() && width > 0 && height > 0;
        final int visibility = shown ? VISIBLE : INVISIBLE;
        if (getVisibility() != visibility) setVisibility(visibility);
        if (!shown) return;

        final ViewGroup.LayoutParams params = getLayoutParams();
        if (params != null && (params.width != width + 2 * margin || params.height != height + 2 * margin)) {
            params.width = width + 2 * margin;
            params.height = height + 2 * margin;
            setLayoutParams(params);
        }
        setTranslationX(surface.getLeft() + surface.getTranslationX() - margin - getLeft());
        setTranslationY(surface.getTop() + surface.getTranslationY() - margin - getTop());
        if (getAlpha() != surface.getAlpha()) setAlpha(surface.getAlpha());
        setPivotX(margin + surface.getPivotX());
        setPivotY(margin + surface.getPivotY());
        if (getScaleX() != surface.getScaleX()) setScaleX(surface.getScaleX());
        if (getScaleY() != surface.getScaleY()) setScaleY(surface.getScaleY());

        if (width != surfaceWidth || height != surfaceHeight || corner != surfaceCorner) {
            surfaceWidth = width;
            surfaceHeight = height;
            surfaceCorner = corner;
            rect.set(margin, margin, margin + width, margin + height);
            shape.rewind();
            shape.addRoundRect(rect, corner, corner, Path.Direction.CW);
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (surfaceWidth <= 0 || surfaceHeight <= 0) return;
        final int save = canvas.save();
        // Never under the glass itself, as Backdrop clears the shape from its shadow.
        canvas.clipOutPath(shape);
        canvas.translate(0f, offset);
        canvas.drawPath(shape, paint);
        canvas.restoreToCount(save);
    }
}
