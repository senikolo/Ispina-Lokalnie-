package com.ispinalokalnie.redaktor;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.text.InputFilter;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import java.io.InputStream;

/** Edits an in-memory copy. The original phone image is never changed. */
final class PhotoEditor extends Dialog {
    interface Upload { void accept(Bitmap bitmap, String caption, String author, String alt); }
    private final Bitmap original;
    private Bitmap rotated, preview;
    private final ImageView image;
    private final TextView size;
    private final SeekBar horizontal, vertical;
    private float ratio=0;
    private int angle=0;
    private boolean released=false;

    static Bitmap decode(Context context, Uri uri) throws Exception {
        BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
        try(InputStream in=context.getContentResolver().openInputStream(uri)){BitmapFactory.decodeStream(in,null,bounds);}
        if(bounds.outWidth<=0||bounds.outHeight<=0)throw new Exception("Nie rozpoznano zdjęcia.");
        BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=1;
        while(Math.max(bounds.outWidth/options.inSampleSize,bounds.outHeight/options.inSampleSize)>1600)options.inSampleSize*=2;
        Bitmap raw;try(InputStream in=context.getContentResolver().openInputStream(uri)){raw=BitmapFactory.decodeStream(in,null,options);}
        if(raw==null)throw new Exception("Nie można odczytać obrazu.");
        int orientation=ExifInterface.ORIENTATION_NORMAL;
        try(InputStream in=context.getContentResolver().openInputStream(uri)){orientation=new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL);}catch(java.io.IOException ignored){}
        Matrix matrix=new Matrix();
        switch(orientation){
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:matrix.setScale(-1,1);break;
            case ExifInterface.ORIENTATION_ROTATE_180:matrix.setRotate(180);break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:matrix.setScale(1,-1);break;
            case ExifInterface.ORIENTATION_TRANSPOSE:matrix.setRotate(90);matrix.postScale(-1,1);break;
            case ExifInterface.ORIENTATION_ROTATE_90:matrix.setRotate(90);break;
            case ExifInterface.ORIENTATION_TRANSVERSE:matrix.setRotate(270);matrix.postScale(-1,1);break;
            case ExifInterface.ORIENTATION_ROTATE_270:matrix.setRotate(270);break;
        }
        if(matrix.isIdentity())return raw;
        Bitmap fixed=Bitmap.createBitmap(raw,0,0,raw.getWidth(),raw.getHeight(),matrix,true);if(fixed!=raw)raw.recycle();return fixed;
    }
    static Bitmap crop(Bitmap source,float ratio,int xPercent,int yPercent){
        int width=source.getWidth(),height=source.getHeight(),w=width,h=height;
        if(ratio>0){if((float)width/height>ratio)w=Math.max(1,Math.round(height*ratio));else h=Math.max(1,Math.round(width/ratio));}
        w=Math.min(w,width);h=Math.min(h,height);
        int x=Math.round((width-w)*Math.max(0,Math.min(100,xPercent))/100f),y=Math.round((height-h)*Math.max(0,Math.min(100,yPercent))/100f);
        return Bitmap.createBitmap(source,x,y,w,h);
    }
    private int dp(int n){return Math.round(n*getContext().getResources().getDisplayMetrics().density);}
    private TextView label(LinearLayout parent,String text){TextView t=new TextView(getContext());t.setText(text);t.setTextSize(16);t.setPadding(0,dp(10),0,dp(5));parent.addView(t);return t;}
    private EditText field(LinearLayout parent,String label,int limit){label(parent,label);EditText e=new EditText(getContext());e.setTextSize(16);e.setSingleLine(false);e.setMaxLines(3);e.setFilters(new InputFilter[]{new InputFilter.LengthFilter(limit)});parent.addView(e,new LinearLayout.LayoutParams(-1,-2));return e;}
    private void button(LinearLayout parent,String title,Runnable action){Button b=new Button(getContext());b.setText(title);parent.addView(b,new LinearLayout.LayoutParams(-1,-2));b.setOnClickListener(v->action.run());}
    PhotoEditor(Activity activity,Bitmap bitmap,Upload upload){
        super(activity);requestWindowFeature(Window.FEATURE_NO_TITLE);original=bitmap;rotated=original;
        ScrollView scroll=new ScrollView(activity);LinearLayout body=new LinearLayout(activity);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(18),dp(16),dp(18),dp(20));scroll.addView(body);setContentView(scroll);
        label(body,"Przygotuj zdjęcie do artykułu");label(body,"Edytujesz kopię. Oryginał w telefonie pozostaje bez zmian. Wysłanie nastąpi dopiero po wybraniu przycisku na dole.");
        image=new ImageView(activity);image.setAdjustViewBounds(true);image.setScaleType(ImageView.ScaleType.FIT_CENTER);image.setContentDescription("Podgląd kadrowanego zdjęcia");body.addView(image,new LinearLayout.LayoutParams(-1,dp(230)));size=label(body,"");
        button(body,"Obróć o 90°",()->{angle=(angle+90)%360;releasePreview();if(rotated!=original)rotated.recycle();Matrix m=new Matrix();m.setRotate(angle);rotated=angle==0?original:Bitmap.createBitmap(original,0,0,original.getWidth(),original.getHeight(),m,true);render();});
        button(body,"Pełne zdjęcie",()->{ratio=0;render();});button(body,"Kadr panoramiczny 16:9",()->{ratio=16f/9f;render();});button(body,"Kadr kwadratowy 1:1",()->{ratio=1;render();});
        label(body,"Położenie kadru: lewo — prawo");horizontal=new SeekBar(activity);horizontal.setMax(100);horizontal.setProgress(50);body.addView(horizontal);
        label(body,"Położenie kadru: góra — dół");vertical=new SeekBar(activity);vertical.setMax(100);vertical.setProgress(50);body.addView(vertical);
        SeekBar.OnSeekBarChangeListener move=new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar b,int value,boolean user){if(user)render();}public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}};horizontal.setOnSeekBarChangeListener(move);vertical.setOnSeekBarChangeListener(move);
        EditText caption=field(body,"Podpis pod zdjęciem (opcjonalnie)",500),author=field(body,"Autor / źródło zdjęcia (opcjonalnie)",180),alt=field(body,"Opis alternatywny: co widać na zdjęciu?",300);
        label(body,"Podpis i autor trafią do metadanych zdjęcia w WordPressie. Ich widoczność przy zdjęciu wyróżniającym zależy od motywu. Opis alternatywny pomaga osobom korzystającym z czytnika ekranu.");
        button(body,"Dodaj do biblioteki WordPress",()->{Bitmap result=preview.copy(Bitmap.Config.ARGB_8888,false);String c=caption.getText().toString().trim(),a=author.getText().toString().trim(),t=alt.getText().toString().trim();dismiss();upload.accept(result,c,a,t);});
        button(body,"Anuluj",this::dismiss);setOnDismissListener(d->release());render();
        Window w=getWindow();if(w!=null)w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT);
    }
    private void releasePreview(){image.setImageDrawable(null);if(preview!=null&&preview!=rotated&&preview!=original&&!preview.isRecycled())preview.recycle();preview=null;}
    private void render(){if(released)return;releasePreview();preview=crop(rotated,ratio,horizontal.getProgress(),vertical.getProgress());image.setImageBitmap(preview);size.setText("Podgląd: "+preview.getWidth()+" × "+preview.getHeight()+" px. Format JPEG, kompresja przed wysłaniem.");}
    private void release(){if(released)return;releasePreview();if(rotated!=original&&!rotated.isRecycled())rotated.recycle();if(!original.isRecycled())original.recycle();released=true;}
}
