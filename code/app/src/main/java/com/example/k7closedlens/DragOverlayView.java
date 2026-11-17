package com.example.k7closedlens;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 드래그 영역 번역 캔버스 오버레이 뷰
 */
public class DragOverlayView extends View {

    public interface Listener {
        void onSelected(Rect rect);
    }

    private static class Box {
        Rect rect;
        String text;
        Box(Rect r, String t) { this.rect = r; this.text = t; }
    }

    private Listener listener;
    private final List<Box> boxes = new ArrayList<>();
    private float sX, sY, cX, cY;
    private boolean dragging = false;

    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);

    public DragOverlayView(Context ctx, @Nullable AttributeSet attrs) {
        super(ctx, attrs);
        borderPaint.setColor(0xFF7C4DFF);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(4f);
        borderPaint.setPathEffect(new DashPathEffect(new float[]{16, 8}, 0));

        fillPaint.setColor(0x337C4DFF);
        fillPaint.setStyle(Paint.Style.FILL);

        bgPaint.setColor(0xC81E1E1E);
        bgPaint.setStyle(Paint.Style.FILL);

        textPaint.setColor(Color.WHITE);
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
    }

    public void setListener(Listener l) { this.listener = l; }
    public void addBox(Rect r, String t) { boxes.add(new Box(r, t)); invalidate(); }
    public void clear() { boxes.clear(); invalidate(); }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getAction()) {
            case MotionEvent.ACTION_DOWN:
                sX = e.getX(); sY = e.getY(); cX = sX; cY = sY;
                dragging = true;
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (dragging) { cX = e.getX(); cY = e.getY(); invalidate(); }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragging) {
                    dragging = false;
                    int l = (int) Math.min(sX, cX), t = (int) Math.min(sY, cY);
                    int r = (int) Math.max(sX, cX), b = (int) Math.max(sY, cY);
                    invalidate();
                    if (r - l >= 25 && b - t >= 20 && listener != null) {
                        listener.onSelected(new Rect(l, t, r, b));
                    }
                }
                return true;
        }
        return super.onTouchEvent(e);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float d = getResources().getDisplayMetrics().density;

        for (Box item : boxes) {
            Rect r = item.rect;
            int bW = r.width(), bH = r.height();
            float tSize = Math.max(10.5f * d, Math.min(bH * 0.38f, 13.5f * d));
            textPaint.setTextSize(tSize);

            int pX = (int) (4 * d), pY = (int) (3 * d);
            int rW = Math.max(bW, (int) (40 * d));

            StaticLayout l = StaticLayout.Builder.obtain(item.text, 0, item.text.length(), textPaint, rW)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setIncludePad(false)
                    .build();

            int bBottom = Math.max(r.bottom + pY, r.top + l.getHeight() + pY * 2);
            RectF card = new RectF(r.left - pX, r.top - pY, r.left + rW + pX, bBottom);
            canvas.drawRoundRect(card, 6 * d, 6 * d, bgPaint);

            canvas.save();
            canvas.translate(r.left, r.top + pY / 2f);
            l.draw(canvas);
            canvas.restore();
        }

        if (dragging) {
            float l = Math.min(sX, cX), t = Math.min(sY, cY);
            float r = Math.max(sX, cX), b = Math.max(sY, cY);
            canvas.drawRect(l, t, r, b, fillPaint);
            canvas.drawRect(l, t, r, b, borderPaint);
        }
    }
}
