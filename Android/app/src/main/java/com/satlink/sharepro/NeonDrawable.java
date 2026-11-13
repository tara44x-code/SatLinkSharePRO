package com.satlink.sharepro;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/** إطار نيون بحواف مشطوفة وتدرج ألوان قوس قزح، بنفس ستايل واجهة التصميم. */
public class NeonDrawable extends Drawable {
    public static final int[] RAINBOW = {
            Color.rgb(255, 64, 200), Color.rgb(150, 70, 255), Color.rgb(40, 140, 255),
            Color.rgb(0, 220, 255), Color.rgb(80, 255, 120), Color.rgb(255, 200, 0), Color.rgb(255, 90, 60)
    };
    public static final int[] COOL = {Color.rgb(150, 70, 255), Color.rgb(40, 140, 255), Color.rgb(0, 220, 255)};
    public static final int[] WARM = {Color.rgb(255, 64, 200), Color.rgb(255, 120, 40), Color.rgb(255, 200, 0)};
    public static final int[] DANGER = {Color.rgb(255, 60, 90), Color.rgb(255, 40, 150)};

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final int[] colors;
    private final float strokePx;
    private final float cutPx;
    private final float radiusPx;
    private boolean pressed;

    public NeonDrawable(int[] colors, float density, int fillColor) {
        this(colors, density, fillColor, 14);
    }

    public NeonDrawable(int[] colors, float density, int fillColor, int cutDp) {
        this.colors = colors;
        this.strokePx = 1.6f * density;
        this.cutPx = cutDp * density;
        this.radiusPx = 6 * density;
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(fillColor);
        glow.setStyle(Paint.Style.STROKE);
        glow.setStrokeWidth(strokePx * 4.5f);
        glow.setAlpha(70);
        glow.setStrokeJoin(Paint.Join.ROUND);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(strokePx);
        stroke.setStrokeJoin(Paint.Join.ROUND);
    }

    public void setPressed(boolean value) {
        if (pressed != value) { pressed = value; invalidateSelf(); }
    }

    @Override protected void onBoundsChange(Rect bounds) {
        super.onBoundsChange(bounds);
        float inset = strokePx * 3f;
        RectF r = new RectF(bounds.left + inset, bounds.top + inset, bounds.right - inset, bounds.bottom - inset);
        float c = Math.min(cutPx, Math.min(r.width(), r.height()) / 3f);
        path.reset();
        // زوايا مشطوفة (أعلى يسار وأسفل يمين) وزوايا دائرية (أعلى يمين وأسفل يسار) مثل التصميم.
        path.moveTo(r.left + c, r.top);
        path.lineTo(r.right - radiusPx, r.top);
        path.quadTo(r.right, r.top, r.right, r.top + radiusPx);
        path.lineTo(r.right, r.bottom - c);
        path.lineTo(r.right - c, r.bottom);
        path.lineTo(r.left + radiusPx, r.bottom);
        path.quadTo(r.left, r.bottom, r.left, r.bottom - radiusPx);
        path.lineTo(r.left, r.top + c);
        path.close();
        int[] cs = colors.length == 1 ? new int[]{colors[0], colors[0]} : colors;
        Shader shader = new LinearGradient(r.left, r.top, r.right, r.bottom, cs, null, Shader.TileMode.CLAMP);
        stroke.setShader(shader);
        glow.setShader(new LinearGradient(r.left, r.top, r.right, r.bottom, cs, null, Shader.TileMode.CLAMP));
    }

    @Override public void draw(Canvas canvas) {
        glow.setAlpha(pressed ? 150 : 70);
        canvas.drawPath(path, glow);
        canvas.drawPath(path, fill);
        canvas.drawPath(path, stroke);
    }

    @Override public void setAlpha(int alpha) { fill.setAlpha(alpha); stroke.setAlpha(alpha); invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter cf) { stroke.setColorFilter(cf); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
