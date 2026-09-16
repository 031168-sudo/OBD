package com.example.obdmonitor;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * Simple arc gauge (270 degrees) used for RPM and speed.
 * Call setMax() once, then setValue() whenever new data arrives.
 */
public class GaugeView extends View {

    private float max = 8000f;
    private float value = 0f;
    private float animatedValue = 0f;
    private String label = "";
    private String unit = "";

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private static final float START_ANGLE = 135f;
    private static final float SWEEP_TOTAL = 270f;

    public GaugeView(Context context, AttributeSet attrs) {
        super(context, attrs);
        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeWidth(24f);
        trackPaint.setColor(Color.parseColor("#262B33"));
        trackPaint.setStrokeCap(Paint.Cap.ROUND);

        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeWidth(24f);
        arcPaint.setColor(Color.parseColor("#00E5A0"));
        arcPaint.setStrokeCap(Paint.Cap.ROUND);

        textPaint.setColor(Color.parseColor("#F5F7FA"));
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setFakeBoldText(true);

        labelPaint.setColor(Color.parseColor("#8A93A3"));
        labelPaint.setTextAlign(Paint.Align.CENTER);
    }

    public void setMax(float max) {
        this.max = max;
        invalidate();
    }

    public void setLabelAndUnit(String label, String unit) {
        this.label = label;
        this.unit = unit;
        invalidate();
    }

    /** Animate smoothly to the new value so the needle doesn't jump. */
    public void setValue(float newValue) {
        this.value = Math.max(0, Math.min(newValue, max));
        ValueAnimator animator = ValueAnimator.ofFloat(animatedValue, this.value);
        animator.setDuration(280);
        animator.addUpdateListener(a -> {
            animatedValue = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float size = Math.min(getWidth(), getHeight());
        float stroke = 24f;
        float padding = stroke;
        rect.set(padding, padding, size - padding, size - padding);

        // background track
        canvas.drawArc(rect, START_ANGLE, SWEEP_TOTAL, false, trackPaint);

        // colored progress arc
        float fraction = max > 0 ? (animatedValue / max) : 0f;
        // shift color toward red as it approaches max (redline feel)
        if (fraction > 0.85f) {
            arcPaint.setColor(Color.parseColor("#FF5252"));
        } else if (fraction > 0.6f) {
            arcPaint.setColor(Color.parseColor("#FFC24B"));
        } else {
            arcPaint.setColor(Color.parseColor("#00E5A0"));
        }
        canvas.drawArc(rect, START_ANGLE, SWEEP_TOTAL * fraction, false, arcPaint);

        float cx = size / 2f;
        float cy = size / 2f;

        textPaint.setTextSize(size * 0.20f);
        canvas.drawText(String.valueOf(Math.round(animatedValue)), cx, cy + size * 0.02f, textPaint);

        labelPaint.setTextSize(size * 0.075f);
        canvas.drawText(unit, cx, cy + size * 0.13f, labelPaint);

        labelPaint.setTextSize(size * 0.08f);
        canvas.drawText(label, cx, cy - size * 0.20f, labelPaint);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        int size = Math.min(getMeasuredWidth(), getMeasuredHeight());
        setMeasuredDimension(size, size);
    }
}
