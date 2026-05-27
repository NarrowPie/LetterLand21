package com.example.letterland;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

// ML Kit Ink import
import com.google.mlkit.vision.digitalink.Ink;

public class DrawingView extends View {
    private Path drawPath;
    private Paint drawPaint, canvasPaint;
    private Canvas drawCanvas;
    private Bitmap canvasBitmap;

    // MAGNIFYING GLASS VARIABLES
    private boolean isDrawing = false;
    private float currentX = 0f;
    private float currentY = 0f;
    private Paint magnifierBorderPaint;
    private Paint crosshairPaint;

    // A layer that floats above the entire app!
    private View magnifierOverlay;

    // TRACKS THE EXACT EDGES OF THEIR DRAWING
    private float minX = Float.MAX_VALUE;
    private float minY = Float.MAX_VALUE;
    private float maxX = 0;
    private float maxY = 0;

    // ML KIT DIGITAL INK TRACKERS
    private Ink.Builder inkBuilder = Ink.builder();
    private Ink.Stroke.Builder strokeBuilder;

    // BACKGROUND TRACING TEMPLATE VARIABLES
    private String tracingWord = "";
    private Paint tracingPaint;

    // SIZING FIX: Base scale size made larger for young fine-motor accessibility
    private final float baseTracingTextSize = 240f;

    // OPTIMIZATION FIX: Permanent single-instance fields to eliminate drawing loop allocation jank
    private final int[] magnifierLocation = new int[2];
    private final Path magnifierClipPath = new Path();

    public interface OnDrawListener {
        void onDrawStarted();
        void onDrawFinished();
    }
    private OnDrawListener drawListener;

    public void setOnDrawListener(OnDrawListener listener) {
        this.drawListener = listener;
    }

    public DrawingView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setupDrawing();
    }

    private void setupDrawing() {
        drawPath = new Path();
        drawPaint = new Paint();
        drawPaint.setColor(Color.BLACK);
        drawPaint.setAntiAlias(true);
        drawPaint.setStrokeWidth(20f);
        drawPaint.setStyle(Paint.Style.STROKE);
        drawPaint.setStrokeJoin(Paint.Join.ROUND);
        drawPaint.setStrokeCap(Paint.Cap.ROUND);

        canvasPaint = new Paint(Paint.DITHER_FLAG);

        magnifierBorderPaint = new Paint();
        magnifierBorderPaint.setColor(Color.parseColor("#29B6F6"));
        magnifierBorderPaint.setStyle(Paint.Style.STROKE);
        magnifierBorderPaint.setStrokeWidth(15f);
        magnifierBorderPaint.setAntiAlias(true);

        crosshairPaint = new Paint();
        crosshairPaint.setColor(Color.argb(120, 255, 0, 0)); // Semi-transparent Red
        crosshairPaint.setStyle(Paint.Style.FILL);
        crosshairPaint.setAntiAlias(true);

        // INITIALIZE FAINT BACKGROUND TEXT DESIGN FOR KIDS
        tracingPaint = new Paint();
        tracingPaint.setColor(Color.parseColor("#E0E0E0")); // Faint gray visual template
        tracingPaint.setAntiAlias(true);
        tracingPaint.setTextSize(baseTracingTextSize);
        tracingPaint.setTextAlign(Paint.Align.CENTER);
        tracingPaint.setStyle(Paint.Style.FILL);
        tracingPaint.setFakeBoldText(true);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        ViewGroup root = (ViewGroup) getRootView().findViewById(android.R.id.content);
        if (root != null && magnifierOverlay == null) {
            magnifierOverlay = new View(getContext()) {
                @Override
                protected void onDraw(Canvas canvas) {
                    drawMagnifierOnOverlay(canvas);
                }
            };
            magnifierOverlay.setClickable(false);
            magnifierOverlay.setFocusable(false);
            magnifierOverlay.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            root.addView(magnifierOverlay, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            ));
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        if (magnifierOverlay != null) {
            final View overlayToRemove = magnifierOverlay;
            final ViewGroup parent = (ViewGroup) overlayToRemove.getParent();

            if (parent != null) {
                new Handler(Looper.getMainLooper()).post(() -> {
                    try {
                        parent.removeView(overlayToRemove);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                });
            }
            magnifierOverlay = null;
        }
        super.onDetachedFromWindow();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);

        // CRITICAL PERFORMANCE GUARD: Block initialization if sizes are zero to stop initialization crash bounds
        if (w <= 0 || h <= 0) return;

        canvasBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        drawCanvas = new Canvas(canvasBitmap);
        drawCanvas.drawColor(Color.TRANSPARENT);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        // 1. Draw solid white page canvas background first
        canvas.drawColor(Color.WHITE);

        // 2. Render background tracing template guides UNDER finger strokes
        if (tracingWord != null && !tracingWord.isEmpty()) {
            float xPos = getWidth() / 2f;
            float yPos = (getHeight() / 2f) - ((tracingPaint.descent() + tracingPaint.ascent()) / 2f);
            canvas.drawText(tracingWord, xPos, yPos, tracingPaint);
        }

        // 3. Render completed user pencil lines overlaying on top of the text template
        if (canvasBitmap != null) {
            canvas.drawBitmap(canvasBitmap, 0, 0, canvasPaint);
        }

        // 4. Render active stroke path currently being drawn
        canvas.drawPath(drawPath, drawPaint);
    }

    private void drawMagnifierOnOverlay(Canvas canvas) {
        if (!isDrawing) return;
        float magRadius = 240f;

        // OPTIMIZATION FIX: Reads coordinates directly into persistent field layout arrays
        getLocationInWindow(magnifierLocation);

        float screenX = currentX + magnifierLocation[0];
        float screenY = currentY + magnifierLocation[1];

        float magX = screenX;
        float magY = screenY - 320f;

        int screenW = magnifierOverlay.getWidth();
        if (magX - magRadius < 0) magX = magRadius;
        if (magX + magRadius > screenW) magX = screenW - magRadius;
        if (magY - magRadius < 0) magY = magRadius;

        canvas.save();

        // OPTIMIZATION FIX: Recycles path calculations to maintain flawless high-FPS rendering
        magnifierClipPath.reset();
        magnifierClipPath.addCircle(magX, magY, magRadius, Path.Direction.CW);
        canvas.clipPath(magnifierClipPath);

        canvas.drawColor(Color.WHITE);

        float zoom = 2.0f;
        canvas.translate(magX, magY);
        canvas.scale(zoom, zoom);
        canvas.translate(-screenX, -screenY);
        canvas.translate(magnifierLocation[0], magnifierLocation[1]);

        if (tracingWord != null && !tracingWord.isEmpty()) {
            float xPos = getWidth() / 2f;
            float yPos = (getHeight() / 2f) - ((tracingPaint.descent() + tracingPaint.ascent()) / 2f);
            canvas.drawText(tracingWord, xPos, yPos, tracingPaint);
        }

        if (canvasBitmap != null) {
            canvas.drawBitmap(canvasBitmap, 0, 0, canvasPaint);
        }
        canvas.drawPath(drawPath, drawPaint);

        canvas.restore();

        canvas.drawCircle(magX, magY, magRadius, magnifierBorderPaint);
        canvas.drawCircle(magX, magY, 15f, crosshairPaint);
    }

    private void updateBounds(float x, float y) {
        float padding = 50f;
        if (x - padding < minX) minX = x - padding;
        if (y - padding < minY) minY = y - padding;
        if (x + padding > maxX) maxX = x + padding;
        if (y + padding > maxY) maxY = y + padding;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float brushRadius = drawPaint.getStrokeWidth() / 2f;
        float safetyBuffer = brushRadius + 2f;

        currentX = Math.max(safetyBuffer, Math.min(event.getX(), getWidth() - safetyBuffer));
        currentY = Math.max(safetyBuffer, Math.min(event.getY(), getHeight() - safetyBuffer));
        long t = System.currentTimeMillis();

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                isDrawing = true;
                drawPath.moveTo(currentX, currentY);
                updateBounds(currentX, currentY);

                strokeBuilder = Ink.Stroke.builder();
                strokeBuilder.addPoint(Ink.Point.create(currentX, currentY, t));

                if (drawListener != null) drawListener.onDrawStarted();
                break;
            case MotionEvent.ACTION_MOVE:
                drawPath.lineTo(currentX, currentY);
                updateBounds(currentX, currentY);

                if (strokeBuilder != null) {
                    strokeBuilder.addPoint(Ink.Point.create(currentX, currentY, t));
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                isDrawing = false;
                if (drawCanvas != null) {
                    drawCanvas.drawPath(drawPath, drawPaint);
                }
                drawPath.reset();

                if (strokeBuilder != null) {
                    strokeBuilder.addPoint(Ink.Point.create(currentX, currentY, t));
                    inkBuilder.addStroke(strokeBuilder.build());
                    strokeBuilder = null;
                }

                if (drawListener != null) drawListener.onDrawFinished();
                break;

            default:
                return false;
        }

        invalidate();
        if (magnifierOverlay != null) magnifierOverlay.invalidate();
        return true;
    }

    public void setTracingWord(String word) {
        this.tracingWord = word;

        inkBuilder = Ink.builder();
        minX = Float.MAX_VALUE;
        minY = Float.MAX_VALUE;
        maxX = 0;
        maxY = 0;

        if (word != null) {
            int length = word.length();
            if (length >= 13) {
                tracingPaint.setTextSize(baseTracingTextSize * 0.52f);
            } else if (length >= 8) {
                tracingPaint.setTextSize(baseTracingTextSize * 0.75f);
            } else {
                tracingPaint.setTextSize(baseTracingTextSize);
            }
        }

        if (canvasBitmap != null) {
            canvasBitmap.eraseColor(Color.TRANSPARENT);
        }
        invalidate();
    }

    public void clearCanvas() {
        if (canvasBitmap != null) {
            canvasBitmap.eraseColor(Color.TRANSPARENT);
        }
        minX = Float.MAX_VALUE;
        minY = Float.MAX_VALUE;
        maxX = 0;
        maxY = 0;

        inkBuilder = Ink.builder();
        invalidate();
    }

    public void resetFullCanvas() {
        this.tracingWord = "";
        clearCanvas();
    }

    public Bitmap getBitmap() {
        if (canvasBitmap == null) return null;
        Bitmap whiteBgBitmap = Bitmap.createBitmap(canvasBitmap.getWidth(), canvasBitmap.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(whiteBgBitmap);
        c.drawColor(Color.WHITE);
        c.drawBitmap(canvasBitmap, 0, 0, null);
        return whiteBgBitmap;
    }

    public Bitmap getCroppedBitmap() {
        if (maxX <= minX || maxY <= minY || canvasBitmap == null) return null;
        int left = (int) Math.max(0, minX);
        int top = (int) Math.max(0, minY);
        int right = (int) Math.min(canvasBitmap.getWidth(), maxX);
        int bottom = (int) Math.min(canvasBitmap.getHeight(), maxY);

        int width = right - left;
        int height = bottom - top;
        if (width <= 0 || height <= 0) return null;

        Bitmap cropped = Bitmap.createBitmap(canvasBitmap, left, top, width, height);
        Bitmap whiteBgCropped = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(whiteBgCropped);
        c.drawColor(Color.WHITE);
        c.drawBitmap(cropped, 0, 0, null);
        return whiteBgCropped;
    }

    public Ink getInk() {
        return inkBuilder.build();
    }
}