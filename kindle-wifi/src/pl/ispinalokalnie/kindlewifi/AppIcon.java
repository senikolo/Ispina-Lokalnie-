package pl.ispinalokalnie.kindlewifi;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** Lightweight line icons shared by the home header and Kindle illustration. */
final class AppIcon extends View {
    static final int BOOK = 0;
    static final int READER = 1;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path book = new Path();
    private final int kind;
    private final int foreground;
    private final int fill;

    AppIcon(Context context, int kind, int foreground, int fill) {
        super(context);
        this.kind = kind;
        this.foreground = foreground;
        this.fill = fill;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        book.moveTo(16, 27);
        book.cubicTo(12, 24, 8, 24, 5, 25);
        book.lineTo(5, 9);
        book.cubicTo(9, 8, 13, 9, 16, 11);
        book.cubicTo(19, 9, 23, 8, 27, 9);
        book.lineTo(27, 25);
        book.cubicTo(23, 24, 20, 24, 16, 27);
        book.close();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int size = Math.min(getWidth(), getHeight());
        canvas.save();
        canvas.translate((getWidth() - size) / 2f, (getHeight() - size) / 2f);
        canvas.scale(size / 48f, size / 48f);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(fill);
        canvas.drawRoundRect(0, 0, 48, 48, 15, 15, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(foreground);
        paint.setStrokeWidth(1.65f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        if (kind == BOOK) {
            canvas.translate(8, 6);
            canvas.drawPath(book, paint);
            canvas.drawLine(16, 11, 16, 27, paint);
        } else {
            canvas.drawRoundRect(12, 8, 32, 39, 3, 3, paint);
            canvas.drawLine(17, 30, 27, 30, paint);
            canvas.drawLine(17, 25, 24, 25, paint);
            canvas.drawLine(21, 34, 23, 34, paint);
            canvas.drawArc(26, 9, 42, 25, -88, 83, false, paint);
            canvas.drawArc(29, 13, 38, 22, -88, 83, false, paint);
        }
        canvas.restore();
    }
}
