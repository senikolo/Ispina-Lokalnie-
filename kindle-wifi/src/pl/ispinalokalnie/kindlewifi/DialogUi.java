package pl.ispinalokalnie.kindlewifi;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Shared appearance for the app's native list, form and confirmation dialogs. */
final class DialogUi {
    private static final int INK = 0xff172d28;
    private static final int GREEN = 0xff175748;
    private static final int MINT = 0xffe2f0e7;
    private static final int BORDER = 0xffe5e5da;

    static void addDetails(Activity activity, LinearLayout panel, CharSequence details) {
        if (details == null || details.length() == 0) return;
        Button toggle = new Button(activity);
        toggle.setText("Pokaż szczegóły techniczne");
        toggle.setAllCaps(false);
        toggle.setSingleLine(false);
        toggle.setTextColor(GREEN);
        toggle.setMinimumHeight(dp(activity, 48));
        panel.addView(toggle, new LinearLayout.LayoutParams(-1, -2));

        TextView note = new TextView(activity);
        note.setText(details);
        note.setTextSize(13);
        note.setTextColor(INK);
        note.setTextIsSelectable(true);
        note.setVisibility(View.GONE);
        note.setPadding(dp(activity, 10), dp(activity, 8), dp(activity, 10), dp(activity, 8));
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xfff6f5ee);
        background.setCornerRadius(dp(activity, 12));
        note.setBackground(background);
        panel.addView(note, new LinearLayout.LayoutParams(-1, -2));
        toggle.setOnClickListener(v -> {
            boolean open = note.getVisibility() != View.VISIBLE;
            note.setVisibility(open ? View.VISIBLE : View.GONE);
            toggle.setText(open ? "Ukryj szczegóły techniczne" : "Pokaż szczegóły techniczne");
        });
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    static final class Builder {
        private final Activity activity;
        private final AlertDialog.Builder builder;
        private CharSequence title, message, details;
        private View custom;
        private boolean destructive;

        Builder(Activity activity) {
            this.activity = activity;
            builder = new AlertDialog.Builder(activity);
        }

        Builder setTitle(CharSequence value) { title = value; return this; }
        Builder setMessage(CharSequence value) { message = value; return this; }
        Builder setView(View value) { custom = value; return this; }
        Builder setDetails(CharSequence value) { details = value; return this; }
        Builder destructive() { destructive = true; return this; }

        Builder setPositiveButton(CharSequence label, DialogInterface.OnClickListener listener) {
            builder.setPositiveButton(label, listener);
            return this;
        }

        Builder setNegativeButton(CharSequence label, DialogInterface.OnClickListener listener) {
            builder.setNegativeButton(label, listener);
            return this;
        }

        Builder setNeutralButton(CharSequence label, DialogInterface.OnClickListener listener) {
            builder.setNeutralButton(label, listener);
            return this;
        }

        Builder setOnCancelListener(DialogInterface.OnCancelListener listener) {
            builder.setOnCancelListener(listener);
            return this;
        }

        Builder setItems(CharSequence[] items, DialogInterface.OnClickListener listener) {
            builder.setAdapter(new ArrayAdapter<CharSequence>(activity, android.R.layout.simple_list_item_1, items) {
                @Override public View getView(int position, View convertView, ViewGroup parent) {
                    LinearLayout row;
                    TextView text;
                    if (convertView instanceof LinearLayout) {
                        row = (LinearLayout) convertView;
                        text = (TextView) row.getChildAt(0);
                    } else {
                        row = new LinearLayout(activity);
                        row.setGravity(Gravity.CENTER_VERTICAL);
                        row.setPadding(dp(20), dp(10), dp(18), dp(10));
                        row.setMinimumHeight(dp(54));
                        text = label("", 16, INK);
                        row.addView(text, new LinearLayout.LayoutParams(0, -2, 1f));
                        TextView arrow = label("›", 23, GREEN);
                        arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                        arrow.setPadding(dp(10), 0, 0, 0);
                        row.addView(arrow);
                    }
                    text.setText(getItem(position));
                    return row;
                }
            }, listener);
            return this;
        }

        Builder setMultiChoiceItems(CharSequence[] items, boolean[] selected,
                                    DialogInterface.OnMultiChoiceClickListener listener) {
            builder.setMultiChoiceItems(items, selected, listener);
            return this;
        }

        AlertDialog create() {
            TextView header = label(title == null ? "Kindle przez Wi-Fi" : title, 21, INK);
            header.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            header.setPadding(dp(20), dp(17), dp(20), dp(14));
            header.setBackgroundColor(MINT);
            if (Build.VERSION.SDK_INT >= 28) header.setAccessibilityHeading(true);
            builder.setCustomTitle(header);
            if (custom != null) {
                builder.setView(custom);
                if (message != null) builder.setMessage(message);
            } else if (message != null) {
                LinearLayout panel = new LinearLayout(activity);
                panel.setOrientation(LinearLayout.VERTICAL);
                panel.setPadding(dp(20), dp(15), dp(20), dp(14));
                TextView body = label(message, 16, INK);
                body.setLineSpacing(dp(2), 1f);
                panel.addView(body);
                addDetails(activity, panel, details);
                ScrollView scroll = new ScrollView(activity);
                scroll.addView(panel);
                builder.setView(scroll);
            }

            AlertDialog dialog = builder.create();
            header.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(View view) {
                    view.post(() -> {
                        if (!dialog.isShowing()) return;
                        styleButton(dialog.getButton(AlertDialog.BUTTON_POSITIVE), true);
                        styleButton(dialog.getButton(AlertDialog.BUTTON_NEUTRAL), false);
                        styleButton(dialog.getButton(AlertDialog.BUTTON_NEGATIVE), false);
                        if (dialog.getListView() != null) {
                            dialog.getListView().setDivider(new ColorDrawable(BORDER));
                            dialog.getListView().setDividerHeight(dp(1));
                        }
                    });
                }

                @Override public void onViewDetachedFromWindow(View view) {}
            });
            return dialog;
        }

        AlertDialog show() {
            AlertDialog dialog = create();
            dialog.show();
            return dialog;
        }

        private void styleButton(Button button, boolean primary) {
            if (button == null) return;
            button.setAllCaps(false);
            button.setSingleLine(false);
            button.setMinHeight(dp(48));
            button.setMinimumHeight(dp(48));
            button.setGravity(Gravity.CENTER);
            if (primary) {
                GradientDrawable fill = new GradientDrawable();
                fill.setColor(destructive ? 0xff9b3430 : GREEN);
                fill.setCornerRadius(dp(13));
                button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x44ffffff), fill, null));
                button.setTextColor(new ColorStateList(new int[][] {{-android.R.attr.state_enabled}, {}},
                        new int[] {0xffc3d5ce, Color.WHITE}));
            } else {
                button.setTextColor(new ColorStateList(new int[][] {{-android.R.attr.state_enabled}, {}},
                        new int[] {0xff899a93, GREEN}));
            }
        }

        private TextView label(CharSequence content, int size, int color) {
            TextView view = new TextView(activity);
            view.setText(content);
            view.setTextSize(size);
            view.setTextColor(color);
            return view;
        }

        private int dp(int value) {
            return DialogUi.dp(activity, value);
        }
    }
}
