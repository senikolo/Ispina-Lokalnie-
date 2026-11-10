package pl.ispinalokalnie.kindlewifi;

import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.widget.TextView;

/** Compact line icons that inherit the color and enabled state of their labels. */
final class ActionIcon extends Drawable {
    enum Glyph {
        SEARCH, BOOK, UPLOAD, DOWNLOAD, CLOSE, INFO, HELP, HISTORY, SETTINGS, WIFI, HOTSPOT,
        CHECK, PREVIEW, FOLDER, LEVEL_UP, REFRESH, RENAME, DELETE, MOVE, NEW_FOLDER
    }

    private final Glyph glyph;
    private final int size;
    private final ColorStateList colors;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private int alpha = 255;

    private ActionIcon(Glyph glyph, int size, ColorStateList colors) {
        this.glyph = glyph;
        this.size = size;
        this.colors = colors;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.7f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    static void attach(TextView view, Glyph glyph) {
        attach(view, glyph, 19, false);
    }

    static void attach(TextView view, Glyph glyph, int sizeDp, boolean above) {
        float density = view.getResources().getDisplayMetrics().density;
        ActionIcon icon = new ActionIcon(glyph, Math.round(sizeDp * density), view.getTextColors());
        view.setCompoundDrawablePadding(Math.round((above ? 3 : 7) * density));
        if (above) view.setCompoundDrawablesRelativeWithIntrinsicBounds(null, icon, null, null);
        else view.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, null, null);
    }

    @Override public int getIntrinsicWidth() { return size; }
    @Override public int getIntrinsicHeight() { return size; }
    @Override public boolean isStateful() { return colors.isStateful(); }
    @Override protected boolean onStateChange(int[] state) {
        invalidateSelf();
        return true;
    }
    @Override public void setAlpha(int value) {
        alpha = value;
        invalidateSelf();
    }
    @Override public void setColorFilter(ColorFilter filter) {
        paint.setColorFilter(filter);
        invalidateSelf();
    }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }

    @Override public void draw(Canvas canvas) {
        int color = colors.getColorForState(getState(), colors.getDefaultColor());
        paint.setColor(color);
        paint.setAlpha((color >>> 24) * alpha / 255);
        int saved = canvas.save();
        canvas.translate(getBounds().left, getBounds().top);
        canvas.scale(getBounds().width() / 24f, getBounds().height() / 24f);
        switch (glyph) {
            case SEARCH:
                canvas.drawCircle(10.5f, 10.5f, 5.5f, paint);
                canvas.drawLine(14.5f, 14.5f, 20, 20, paint);
                break;
            case BOOK:
                line(canvas, 12, 6, 6, 4, 3, 5, 3, 18, 6, 17, 12, 19, 18, 17, 21, 18, 21, 5, 18, 4, 12, 6, 12, 19);
                break;
            case UPLOAD:
                line(canvas, 12, 15, 12, 4, 8, 8);
                canvas.drawLine(12, 4, 16, 8, paint);
                line(canvas, 4, 14, 4, 20, 20, 20, 20, 14);
                break;
            case DOWNLOAD:
                line(canvas, 12, 4, 12, 15, 8, 11);
                canvas.drawLine(12, 15, 16, 11, paint);
                line(canvas, 4, 14, 4, 20, 20, 20, 20, 14);
                break;
            case CLOSE:
                canvas.drawLine(6, 6, 18, 18, paint);
                canvas.drawLine(6, 18, 18, 6, paint);
                break;
            case INFO:
            case HELP:
                canvas.drawCircle(12, 12, 9, paint);
                if (glyph == Glyph.INFO) {
                    canvas.drawLine(12, 11, 12, 16, paint);
                    canvas.drawPoint(12, 8, paint);
                } else {
                    canvas.drawArc(9, 7, 15, 12, 180, 245, false, paint);
                    canvas.drawLine(12, 12, 12, 14, paint);
                    canvas.drawPoint(12, 17, paint);
                }
                break;
            case HISTORY:
                canvas.drawArc(4, 4, 20, 20, 200, 325, false, paint);
                line(canvas, 3, 6, 4, 10, 8, 9);
                line(canvas, 12, 8, 12, 12, 15, 14);
                break;
            case SETTINGS:
                canvas.drawLine(4, 7, 9, 7, paint);
                canvas.drawCircle(11, 7, 2, paint);
                canvas.drawLine(13, 7, 20, 7, paint);
                canvas.drawLine(4, 17, 13, 17, paint);
                canvas.drawCircle(15, 17, 2, paint);
                canvas.drawLine(17, 17, 20, 17, paint);
                break;
            case WIFI:
                canvas.drawArc(1, 5, 23, 24, 225, 90, false, paint);
                canvas.drawArc(5, 9, 19, 22, 225, 90, false, paint);
                canvas.drawArc(9, 13, 15, 20, 225, 90, false, paint);
                canvas.drawPoint(12, 18.5f, paint);
                break;
            case HOTSPOT:
                canvas.drawCircle(12, 11, 2, paint);
                canvas.drawArc(6, 5, 18, 17, 130, 100, false, paint);
                canvas.drawArc(6, 5, 18, 17, -50, 100, false, paint);
                canvas.drawArc(2, 1, 22, 21, 130, 100, false, paint);
                canvas.drawArc(2, 1, 22, 21, -50, 100, false, paint);
                canvas.drawLine(12, 13, 12, 21, paint);
                break;
            case CHECK:
                line(canvas, 5, 12, 10, 17, 19, 7);
                break;
            case PREVIEW:
                path.reset();
                path.moveTo(2, 12);
                path.cubicTo(7, 4, 17, 4, 22, 12);
                path.cubicTo(17, 20, 7, 20, 2, 12);
                canvas.drawPath(path, paint);
                canvas.drawCircle(12, 12, 2.5f, paint);
                break;
            case FOLDER:
            case NEW_FOLDER:
                line(canvas, 3, 18, 3, 5, 10, 5, 12, 8, 21, 8, 21, 19, 3, 19, 3, 18);
                if (glyph == Glyph.NEW_FOLDER) {
                    canvas.drawLine(12, 10, 12, 17, paint);
                    canvas.drawLine(8.5f, 13.5f, 15.5f, 13.5f, paint);
                }
                break;
            case LEVEL_UP:
                line(canvas, 12, 19, 12, 5, 6, 11);
                canvas.drawLine(12, 5, 18, 11, paint);
                break;
            case REFRESH:
                canvas.drawArc(4, 4, 20, 20, -70, 305, false, paint);
                line(canvas, 14, 7, 20, 8, 20, 3);
                break;
            case RENAME:
                line(canvas, 5, 15, 15, 5, 19, 9, 9, 19, 4, 20, 5, 15);
                canvas.drawLine(12.5f, 7.5f, 16.5f, 11.5f, paint);
                break;
            case DELETE:
                line(canvas, 6, 7, 7, 20, 17, 20, 18, 7);
                canvas.drawLine(4, 7, 20, 7, paint);
                line(canvas, 9, 7, 9, 4, 15, 4, 15, 7);
                canvas.drawLine(10, 10, 10, 17, paint);
                canvas.drawLine(14, 10, 14, 17, paint);
                break;
            case MOVE:
                line(canvas, 4, 12, 20, 12, 15, 7);
                canvas.drawLine(20, 12, 15, 17, paint);
                break;
        }
        canvas.restoreToCount(saved);
    }

    private void line(Canvas canvas, float... coordinates) {
        path.reset();
        path.moveTo(coordinates[0], coordinates[1]);
        for (int i = 2; i < coordinates.length; i += 2) {
            path.lineTo(coordinates[i], coordinates[i + 1]);
        }
        canvas.drawPath(path, paint);
    }
}
