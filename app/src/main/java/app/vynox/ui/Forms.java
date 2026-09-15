package app.vynox.ui;

import android.app.*;
import android.content.Context;
import android.graphics.Color;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.util.*;

final class Forms {

  final Context context;
  final LinearLayout fields;
  final Map<String, EditText> inputs = new LinkedHashMap<>();

  Forms(Context c) {
    context = c;
    fields = new LinearLayout(c);
    fields.setOrientation(LinearLayout.VERTICAL);
    fields.setPadding(24, 12, 24, 12);
  }

  Forms add(String label, Object value, boolean numeric) {
    TextView title = new TextView(context);
    title.setText(label);
    title.setTextColor(0xffb3f56a);
    fields.addView(title);
    EditText e = new EditText(context);
    e.setText(String.valueOf(value));
    e.setTextColor(Color.WHITE);
    e.setTextSize(16);
    e.setInputType(
      numeric
        ? InputType.TYPE_CLASS_NUMBER |
            InputType.TYPE_NUMBER_FLAG_DECIMAL |
            InputType.TYPE_NUMBER_FLAG_SIGNED
        : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
    );
    fields.addView(e, new LinearLayout.LayoutParams(-1, -2));
    inputs.put(label, e);
    return this;
  }

  String text(String name) {
    return inputs.get(name).getText().toString();
  }

  double number(String name) {
    double v = Double.parseDouble(text(name));
    if (!Double.isFinite(v)) throw new IllegalArgumentException(
      "Enter a finite number for " + name
    );
    return v;
  }

  int integer(String name) {
    return Integer.parseInt(text(name));
  }

  void show(String title, Runnable save) {
    ScrollView scroll = new ScrollView(context);
    scroll.addView(fields);
    AlertDialog d = new AlertDialog.Builder(context)
      .setTitle(title)
      .setView(scroll)
      .setNegativeButton("Cancel", null)
      .setPositiveButton("Apply", null)
      .create();
    d.setOnShowListener(x ->
      d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
        try {
          save.run();
          d.dismiss();
        } catch (Exception e) {
          Toast.makeText(context, e.getMessage(), Toast.LENGTH_LONG).show();
        }
      })
    );
    d.show();
  }
}
