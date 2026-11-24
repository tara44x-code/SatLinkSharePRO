package com.satlink.sharepro;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/**
 * شريط تقدم مقسّم: عند تقسيم الملف لأجزاء يظهر كل جزء بخانته ونسبته الخاصة،
 * وإلا يظهر شريط واحد عادي.
 */
public class PartsBar extends View {
    private static final int[] COLORS = {Color.rgb(150, 70, 255), Color.rgb(40, 140, 255), Color.rgb(0, 220, 255), Color.rgb(80, 255, 120)};
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private int percent;
    private int[] parts;
    private int lastWidth = -1;

    public PartsBar(Context context, boolean light) {
        super(context);
        track.setColor(light ? Color.argb(40, 0, 0, 0) : Color.argb(55, 255, 255, 255));
    }

    public void set(int percent, int[] parts) {
        this.percent = Math.max(0, Math.min(100, percent));
        this.parts = parts == null || parts.length < 2 ? null : parts.clone();
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        if (lastWidth != (int) w) {
            lastWidth = (int) w;
            fill.setShader(new LinearGradient(0, 0, w, 0, COLORS, null, Shader.TileMode.CLAMP));
        }
        float radius = h / 2f;
        boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        if (parts == null) {
            draw(canvas, 0, w, percent, radius, rtl);
            return;
        }
        float gap = h * 0.8f;
        float seg = (w - gap * (parts.length - 1)) / parts.length;
        for (int i = 0; i < parts.length; i++) {
            // الجزء الأول (بداية الملف) يبدأ من جهة بداية القراءة: يمين بالعربي ويسار بالإنكليزي.
            int index = rtl ? parts.length - 1 - i : i;
            float left = i * (seg + gap);
            draw(canvas, left, left + seg, parts[index], radius, rtl);
        }
    }

    private void draw(Canvas canvas, float left, float right, int pct, float radius, boolean rtl) {
        float h = getHeight();
        rect.set(left, 0, right, h);
        canvas.drawRoundRect(rect, radius, radius, track);
        if (pct <= 0) return;
        float len = (right - left) * Math.min(100, pct) / 100f;
        if (rtl) rect.set(right - len, 0, right, h); else rect.set(left, 0, left + len, h);
        canvas.drawRoundRect(rect, radius, radius, fill);
    }
}
