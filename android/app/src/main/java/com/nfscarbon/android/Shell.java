// Launcher shell drawables (dark slanted panel, parallelogram button, line icons). SPDX-License-Identifier: GPL-3.0-only
package com.nfscarbon.android;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/** Everything is drawn with Path/Paint, so the public repo carries no images. */
final class Shell {
    static final int ACCENT = 0xFF6DC5C9, ACCENT_DARK = 0xFF2E8C90, PANEL = 0xF20B0D0F;
    private Shell() {}

    private abstract static class Base extends Drawable {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final float d;
        Base(float density) { d = density; }
        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(ColorFilter filter) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        /** We draw focus ourselves; without this the system lays its grey default focus highlight on top. */
        @Override public boolean hasFocusStateSpecified() { return isStateful(); }
    }

    /** Near-black panel docked left over the full height; right edge slants inwards, thin accent line along it.
     *  The header band (accent bar + accent tint fading right) is drawn here so it follows the slanted edge. */
    static final class Panel extends Base {
        final float slant; float bandTop, bandBottom;
        Panel(float density, float slantDp) { super(density); slant = slantDp * density; }
        void band(int top, int bottom) { bandTop = top; bandBottom = bottom; invalidateSelf(); }
        private float edge(float y) { Rect b = getBounds(); return b.right - slant * (y - b.top) / Math.max(1, b.height()); }
        @Override public void draw(Canvas canvas) {
            Rect b = getBounds();
            Path path = new Path();
            path.moveTo(b.left, b.top); path.lineTo(b.right, b.top); path.lineTo(b.right - slant, b.bottom); path.lineTo(b.left, b.bottom);
            path.close();
            paint.setShader(null); paint.setStyle(Paint.Style.FILL); paint.setColor(PANEL);
            canvas.drawPath(path, paint);
            if (bandBottom > bandTop) {
                Path band = new Path();
                band.moveTo(b.left, bandTop); band.lineTo(edge(bandTop), bandTop); band.lineTo(edge(bandBottom), bandBottom);
                band.lineTo(b.left, bandBottom); band.close();
                paint.setShader(new LinearGradient(b.left, 0, b.right, 0, 0x406DC5C9, 0x0A6DC5C9, Shader.TileMode.CLAMP));
                canvas.drawPath(band, paint);
                paint.setShader(null); paint.setColor(ACCENT);
                canvas.drawRect(b.left, bandTop, b.left + 4 * d, bandBottom, paint);
            }
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1.5f * d); paint.setColor(ACCENT);
            canvas.drawLine(b.right, b.top, b.right - slant, b.bottom, paint);
        }
    }

    /** Main button: parallelogram, accent gradient (light top, dark bottom), soft glow, two slanted stripes right. */
    static final class PlayButton extends Base {
        PlayButton(float density) { super(density); }
        @Override public boolean isStateful() { return true; }
        @Override protected boolean onStateChange(int[] state) { invalidateSelf(); return true; }
        private Path quad(float l, float t, float r, float bottom, float skew) {
            Path p = new Path();
            p.moveTo(l + skew, t); p.lineTo(r, t); p.lineTo(r - skew, bottom); p.lineTo(l, bottom); p.close();
            return p;
        }
        @Override public void draw(Canvas canvas) {
            Rect b = getBounds();
            float glow = 10 * d, t = b.top + glow, bottom = b.bottom - glow, h = bottom - t, skew = h * .28f;
            float stripe = 12 * d, gap = 9 * d, bodyRight = b.right - glow - 2 * (stripe + gap) - skew * .2f;
            boolean focused = false, pressed = false;
            for (int s : getState()) {
                if (s == android.R.attr.state_focused) focused = true;
                if (s == android.R.attr.state_pressed) pressed = true;
            }
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(new LinearGradient(0, t, 0, bottom, pressed ? ACCENT_DARK : ACCENT, pressed ? 0xFF1F6E72 : ACCENT_DARK,
                    Shader.TileMode.CLAMP));
            paint.setShadowLayer((focused ? 14 : 9) * d, 0, 0, focused ? 0xC06DC5C9 : 0x706DC5C9);
            canvas.drawPath(quad(b.left + glow, t, bodyRight, bottom, skew), paint);
            paint.clearShadowLayer();
            for (int i = 0; i < 2; i++) {
                float l = bodyRight + gap + i * (stripe + gap) - skew * .6f;
                canvas.drawPath(quad(l, t, l + stripe + skew, bottom, skew), paint);
            }
            paint.setShader(null);
            if (focused) {  // Controller focus: white outline on top of the glow.
                paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2 * d); paint.setColor(0xFFFFFFFF);
                canvas.drawPath(quad(b.left + glow, t, bodyRight, bottom, skew), paint);
            }
        }
    }

    /** Row/card background: idle fill (may be transparent); focused or pressed = lighter fill plus accent bar
     *  (bar = left strip for list rows, outline for cards). */
    static final class Focus extends Base {
        final int idle; final float radius; final boolean card;
        Focus(float density, int idle, float radiusDp, boolean card) { super(density); this.idle = idle; radius = radiusDp * density; this.card = card; }
        @Override public boolean isStateful() { return true; }
        @Override protected boolean onStateChange(int[] state) { invalidateSelf(); return true; }
        @Override public void draw(Canvas canvas) {
            boolean on = false;
            for (int s : getState()) if (s == android.R.attr.state_focused || s == android.R.attr.state_pressed) on = true;
            RectF r = new RectF(getBounds());
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(on ? (card ? 0xFF1B2326 : 0x16FFFFFF) : idle);
            canvas.drawRoundRect(r, radius, radius, paint);
            if (card) {
                float w = (on ? 2 : 1) * d; r.inset(w / 2, w / 2);
                paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(w); paint.setColor(on ? ACCENT : 0x2E6DC5C9);
                canvas.drawRoundRect(r, radius, radius, paint);
            } else if (on) {
                paint.setColor(ACCENT); canvas.drawRect(r.left, r.top, r.left + 4 * d, r.bottom, paint);
            }
        }
    }

    enum Glyph { GAUGE, FOLDER, KEY, SEND, CODE, PLAY, CHEVRON }

    /** Simple line icons on a 24-unit grid, stroked (PLAY is filled). Colour follows setTint via the tint list. */
    static final class Icon extends Base {
        final Glyph glyph; final int size; int color;
        Icon(float density, Glyph glyph, int sizeDp, int color) { super(density); this.glyph = glyph; size = Math.round(sizeDp * density); this.color = color; setBounds(0, 0, size, size); }
        @Override public int getIntrinsicWidth() { return size; }
        @Override public int getIntrinsicHeight() { return size; }
        @Override public void setTintList(android.content.res.ColorStateList tint) { tintList = tint; onStateChange(getState()); }
        private android.content.res.ColorStateList tintList;
        @Override public boolean isStateful() { return tintList != null; }
        @Override protected boolean onStateChange(int[] state) {
            if (tintList == null) return false;
            color = tintList.getColorForState(state, color); invalidateSelf(); return true;
        }
        @Override public void draw(Canvas canvas) {
            Rect b = getBounds();
            canvas.save();
            canvas.translate(b.left, b.top);
            float u = b.width() / 24f;
            canvas.scale(u, u);
            paint.setColor(color);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1.7f); paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            Path p = new Path();
            switch (glyph) {
                case GAUGE:
                    canvas.drawArc(new RectF(3, 4, 21, 22), 150, 240, false, paint);
                    for (int a = 150; a <= 390; a += 48) {
                        double r = Math.toRadians(a);
                        canvas.drawLine(12 + 7.2f * (float) Math.cos(r), 13 + 7.2f * (float) Math.sin(r),
                                12 + 5.6f * (float) Math.cos(r), 13 + 5.6f * (float) Math.sin(r), paint);
                    }
                    canvas.drawLine(12, 13, 16.5f, 8.5f, paint);
                    paint.setStyle(Paint.Style.FILL); canvas.drawCircle(12, 13, 1.6f, paint);
                    break;
                case FOLDER:  // Folder with a down arrow: import.
                    p.moveTo(3, 7); p.lineTo(3, 19); p.lineTo(21, 19); p.lineTo(21, 9); p.lineTo(12, 9); p.lineTo(10, 6); p.lineTo(3, 6);
                    p.close(); canvas.drawPath(p, paint);
                    canvas.drawLine(12, 11.5f, 12, 16.5f, paint);
                    canvas.drawLine(9.5f, 14, 12, 16.5f, paint); canvas.drawLine(14.5f, 14, 12, 16.5f, paint);
                    break;
                case KEY:
                    canvas.drawCircle(8, 12, 4, paint);
                    canvas.drawLine(12, 12, 21, 12, paint); canvas.drawLine(18, 12, 18, 15.5f, paint);
                    canvas.drawLine(21, 12, 21, 15, paint);
                    break;
                case SEND:  // Paper plane.
                    p.moveTo(3, 11); p.lineTo(21, 4); p.lineTo(15, 20); p.lineTo(11, 13); p.close();
                    canvas.drawPath(p, paint); canvas.drawLine(11, 13, 21, 4, paint);
                    break;
                case CODE:
                    canvas.drawLine(8, 7, 3, 12, paint); canvas.drawLine(3, 12, 8, 17, paint);
                    canvas.drawLine(16, 7, 21, 12, paint); canvas.drawLine(21, 12, 16, 17, paint);
                    canvas.drawLine(13.5f, 5, 10.5f, 19, paint);
                    break;
                case PLAY:
                    p.moveTo(5, 3); p.lineTo(21, 12); p.lineTo(5, 21); p.close();
                    paint.setStyle(Paint.Style.FILL); canvas.drawPath(p, paint);
                    break;
                case CHEVRON:
                    canvas.drawLine(9, 6, 15, 12, paint); canvas.drawLine(15, 12, 9, 18, paint);
                    break;
            }
            canvas.restore();
        }
    }
}
