package com.winlator.cmod.contentdialog;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.util.SparseBooleanArray;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

import com.winlator.cmod.R;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.Callback;
import com.winlator.cmod.inputcontrols.ControllerManager;
import com.winlator.xr.ui.XrContentDialog;

import java.util.ArrayList;

public class ContentDialog extends XrContentDialog {
    public Runnable onConfirmCallback;
    private Runnable onCancelCallback;

    private boolean isDarkMode;

    private Button gyroButton;

    public interface OnControllerInputListener {
        void onControllerInput(InputDevice device);
    }
    private OnControllerInputListener onControllerInputListener;

    public void setOnControllerInputListener(OnControllerInputListener listener) {
        this.onControllerInputListener = listener;
    }


    public ContentDialog(@NonNull Context context) {
        this(context, 0);
    }

    private View inflatedLayout;


    public ContentDialog(@NonNull Context context, int layoutResId) {
        super(context, R.style.ContentDialog);
        contentView = LayoutInflater.from(context).inflate(R.layout.content_dialog, null);


        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        isDarkMode = sharedPreferences.getBoolean("dark_mode", false);

//        contentView.setBackgroundResource(isDarkMode ? R.drawable.content_dialog_background_dark: R.drawable.content_dialog_background);

        if (isDarkMode) {
            this.getContext().setTheme(R.style.ContentDialog_Dark);
            // colorPrimary is too dark to read on the dark dialog background
            ((TextView) contentView.findViewById(R.id.TVTitle)).setTextColor(Color.WHITE);
        }


        if (layoutResId > 0) {
            FrameLayout frameLayout = contentView.findViewById(R.id.FrameLayout);
            frameLayout.setVisibility(View.VISIBLE);
            View view = LayoutInflater.from(context).inflate(layoutResId, frameLayout, false);
            frameLayout.addView(view);
        }

        View confirmButton = contentView.findViewById(R.id.BTConfirm);
        confirmButton.setOnClickListener((v) -> {
            if (onConfirmCallback != null) onConfirmCallback.run();
            dismiss();
        });

        View cancelButton = contentView.findViewById(R.id.BTCancel);
        cancelButton.setOnClickListener((v) -> {
            if (onCancelCallback != null) onCancelCallback.run();
            dismiss();
        });

        setContentView(contentView);
    }

    public View getInflatedLayout() {
        return inflatedLayout;
    }

    public void setOnConfirmCallback(Runnable onConfirmCallback) {
        this.onConfirmCallback = onConfirmCallback;
    }

    public void setOnCancelCallback(Runnable onCancelCallback) {
        this.onCancelCallback = onCancelCallback;
    }

    @Override
    public void setTitle(int titleResId) {
        setTitle(getContext().getString(titleResId));
    }

    public void setIcon(int iconResId) {
        ImageView imageView = findViewById(R.id.IVIcon);
        imageView.setImageResource(iconResId);
        imageView.setVisibility(View.VISIBLE);
    }

    public void setTitle(String title) {
        LinearLayout titleBar = findViewById(R.id.LLTitleBar);
        TextView tvTitle = findViewById(R.id.TVTitle);

        if (title != null && !title.isEmpty()) {
            tvTitle.setText(title);
            titleBar.setVisibility(View.VISIBLE);
        }
        else {
            tvTitle.setText("");
            titleBar.setVisibility(View.GONE);
        }
    }

    /**
     * The controller face buttons, in the order the action lines are listed. A and B sit on
     * the right controller, X and Y on the left, which is also which hand gets the haptic
     * tick when one of these is used.
     */
    public enum FaceButton { A, B, X, Y }

    private static final int[] FACE_BUTTON_VIEW_IDS = {
            R.id.TVFaceButtonA, R.id.TVFaceButtonB, R.id.TVFaceButtonX, R.id.TVFaceButtonY};

    private static final class FaceButtonAction {
        final Runnable pressAction;
        final Runnable holdAction;
        final long holdMillis;

        FaceButtonAction(Runnable pressAction, Runnable holdAction, long holdMillis) {
            this.pressAction = pressAction;
            this.holdAction = holdAction;
            this.holdMillis = holdMillis;
        }
    }

    private final FaceButtonAction[] faceButtonActions = new FaceButtonAction[FaceButton.values().length];

    /** Acts when the button is released, with nothing to hold for. */
    public void setFaceButtonAction(FaceButton button, CharSequence label, Runnable pressAction) {
        setFaceButtonAction(button, null, 0, null, label, pressAction);
    }

    /** Acts only once the button has been held for holdMillis. */
    public void setFaceButtonAction(FaceButton button, CharSequence label, long holdMillis,
                                    Runnable holdAction) {
        setFaceButtonAction(button, label, holdMillis, holdAction, null, null);
    }

    /**
     * Both, where the press works on whatever the hold names: the line says so rather than
     * describing the press separately.
     */
    public void setFaceButtonAction(FaceButton button, CharSequence label, long holdMillis,
                                    Runnable holdAction, Runnable pressAction) {
        setFaceButtonAction(button, label, holdMillis, holdAction, null, pressAction);
    }

    /**
     * Puts an action on a face button and lists it under the dialog's buttons.
     *
     * A button can carry both. The press then acts on release, so that a hold on its way
     * past holdMillis is not read as a press first.
     *
     * A hold is what stands in for a confirmation prompt: there is no cursor on these
     * buttons and no way to take a press back, so anything destructive, or anything that
     * undoes what repeated presses have built up, should ask for one rather than fire on a
     * knock.
     *
     * All four lines are listed whether or not they have an action, so what is free is as
     * visible as what is taken.
     */
    public void setFaceButtonAction(FaceButton button, CharSequence holdLabel, long holdMillis,
                                    Runnable holdAction, CharSequence pressLabel,
                                    Runnable pressAction) {
        holdMillis = holdAction != null ? Math.max(0, holdMillis) : 0;
        faceButtonActions[button.ordinal()] = (pressAction == null && holdAction == null) ? null
                : new FaceButtonAction(pressAction, holdAction, holdMillis);

        Context context = getContext();
        for (FaceButton each : FaceButton.values()) {
            TextView view = findViewById(FACE_BUTTON_VIEW_IDS[each.ordinal()]);
            if (each == button) {
                view.setText(faceButtonLabel(context, each, holdLabel, holdMillis,
                        pressLabel, pressAction));
            }
            else if (view.getText().length() == 0) {
                view.setText(context.getString(R.string.xr_face_button_unassigned, each.name()));
            }
            // An unassigned line is there to show the slot is free, not to be read first.
            view.setAlpha(faceButtonActions[each.ordinal()] != null ? 1.0f : 0.4f);
        }
        findViewById(R.id.LLActionLines).setVisibility(View.VISIBLE);
    }

    /**
     * Puts a back action on B for a window opened from the XR menu. Listed as the only line,
     * rather than with the three free buttons beside it, since this is a hint on someone
     * else's page and not a menu of its own.
     */
    public void setBackAction(CharSequence label, Runnable action) {
        faceButtonActions[FaceButton.B.ordinal()] = new FaceButtonAction(action, null, 0);
        for (FaceButton each : FaceButton.values()) {
            TextView view = findViewById(FACE_BUTTON_VIEW_IDS[each.ordinal()]);
            if (each == FaceButton.B) {
                view.setText(getContext().getString(R.string.xr_face_button_action, each.name(), label));
                view.setAlpha(0.6f);
            } else {
                view.setVisibility(View.GONE);
            }
        }
        findViewById(R.id.LLActionLines).setVisibility(View.VISIBLE);
    }

    /**
     * Lists a control that is not on a face button and cannot be reassigned, alongside the
     * face button lines. The user looks in one place for what the controller does while a
     * dialog is up, so a fixed gesture belongs in the same list as the assignable ones.
     */
    public void setControlHint(CharSequence text) {
        TextView view = findViewById(R.id.TVControlHint);
        view.setText(text);
        view.setVisibility(View.VISIBLE);
        findViewById(R.id.LLActionLines).setVisibility(View.VISIBLE);
    }

    /**
     * A press with a label of its own is named separately, because it is then acting on
     * something other than what the hold names. Without one it is taken to work on the same
     * thing, and the line just says a press will change it.
     */
    private String faceButtonLabel(Context context, FaceButton button, CharSequence holdLabel,
                                   long holdMillis, CharSequence pressLabel, Runnable pressAction) {
        if (faceButtonActions[button.ordinal()] == null) {
            return context.getString(R.string.xr_face_button_unassigned, button.name());
        }
        long holdSeconds = Math.round(holdMillis / 1000.0);
        if (holdMillis <= 0) {
            return context.getString(R.string.xr_face_button_action, button.name(), pressLabel);
        }
        if (pressAction == null) {
            return context.getString(R.string.xr_face_button_action_hold,
                    button.name(), holdLabel, holdSeconds);
        }
        if (pressLabel == null) {
            return context.getString(R.string.xr_face_button_action_hold_press,
                    button.name(), holdLabel, holdSeconds);
        }
        return context.getString(R.string.xr_face_button_action_hold_press_split,
                button.name(), holdLabel, holdSeconds, pressLabel);
    }

    public boolean hasFaceButtonAction(FaceButton button) {
        return faceButtonActions[button.ordinal()] != null;
    }

    /** How long the button must be held, or 0 when it has nothing to hold for. */
    public long getFaceButtonHoldMillis(FaceButton button) {
        FaceButtonAction entry = faceButtonActions[button.ordinal()];
        return entry != null ? entry.holdMillis : 0;
    }

    public boolean hasFaceButtonPressAction(FaceButton button) {
        FaceButtonAction entry = faceButtonActions[button.ordinal()];
        return entry != null && entry.pressAction != null;
    }

    /** Called from the XR render thread, so the action itself is posted to the UI thread. */
    public void runFaceButtonPress(FaceButton button) {
        FaceButtonAction entry = faceButtonActions[button.ordinal()];
        if (entry != null && entry.pressAction != null) postToUi(entry.pressAction);
    }

    /** Called from the XR render thread, so the action itself is posted to the UI thread. */
    public void runFaceButtonHold(FaceButton button) {
        FaceButtonAction entry = faceButtonActions[button.ordinal()];
        if (entry != null && entry.holdAction != null) postToUi(entry.holdAction);
    }

    private static void postToUi(Runnable action) {
        new Handler(Looper.getMainLooper()).post(action);
    }

    public void setBottomBarText(String bottomBarText) {
        TextView tvBottomBarText = findViewById(R.id.TVBottomBarText);

        if (bottomBarText != null && !bottomBarText.isEmpty()) {
            tvBottomBarText.setText(bottomBarText);
            tvBottomBarText.setVisibility(View.VISIBLE);
        }
        else {
            tvBottomBarText.setText("");
            tvBottomBarText.setVisibility(View.GONE);
        }
    }

    public void setMessage(int msgResId) {
        setMessage(getContext().getString(msgResId));
    }

    public void setMessage(String message) {
        TextView tvMessage = findViewById(R.id.TVMessage);

        if (message != null && !message.isEmpty()) {
            tvMessage.setText(message);
            tvMessage.setVisibility(View.VISIBLE);
        }
        else {
            tvMessage.setText("");
            tvMessage.setVisibility(View.GONE);
        }
    }

    public static void alert(Context context, int msgResId, Runnable callback) {
        ContentDialog dialog = new ContentDialog(context);
        dialog.setMessage(msgResId);
        dialog.setOnConfirmCallback(callback);
        dialog.findViewById(R.id.BTCancel).setVisibility(View.GONE);
        dialog.show();
    }

    public static void alert(Context context, String msg, Runnable callback) {
        ContentDialog dialog = new ContentDialog(context);
        dialog.setMessage(msg);
        dialog.setOnConfirmCallback(callback);
        dialog.findViewById(R.id.BTCancel).setVisibility(View.GONE);
        dialog.show();
    }

    public static void confirm(Context context, int msgResId, Runnable callback) {
        ContentDialog dialog = new ContentDialog(context);
        dialog.setMessage(msgResId);
        dialog.setOnConfirmCallback(callback);
        dialog.show();
    }

    public static void confirm(Context context, String msg, Runnable callback) {
        ContentDialog dialog = new ContentDialog(context);
        dialog.setMessage(msg);
        dialog.setOnConfirmCallback(callback);
        dialog.show();
    }

    public static void info(Context context, int msgResId, OnDismissListener dismiss) {
        ContentDialog dialog = new ContentDialog(context);
        dialog.setMessage(msgResId);
        dialog.setOnDismissListener(dismiss);
        dialog.findViewById(R.id.BTCancel).setVisibility(View.GONE);
        dialog.show();
    }

    public static void info(Context context, String msgResId, OnDismissListener dismiss) {
        ContentDialog dialog = new ContentDialog(context);
        dialog.setMessage(msgResId);
        dialog.setOnDismissListener(dismiss);
        dialog.findViewById(R.id.BTCancel).setVisibility(View.GONE);
        dialog.show();
    }

    public static ContentDialog message(Context context, String msg) {
        ContentDialog dialog = new ContentDialog(context);
        dialog.setMessage(msg);
        dialog.findViewById(R.id.BTConfirm).setVisibility(View.GONE);
        dialog.findViewById(R.id.BTCancel).setVisibility(View.GONE);
        dialog.show();
        return dialog;
    }

    public static void prompt(Context context, int titleResId, String defaultText, Callback<String> callback) {
        ContentDialog dialog = new ContentDialog(context);

        final EditText editText = dialog.findViewById(R.id.EditText);

        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        boolean isDarkMode = sharedPreferences.getBoolean("dark_mode", false);
        applyDarkThemeToEditText(editText, isDarkMode);

        editText.setHint(R.string.untitled);
        if (defaultText != null) editText.setText(defaultText);
        editText.setVisibility(View.VISIBLE);

        dialog.setTitle(titleResId);
        dialog.setOnConfirmCallback(() -> {
            String text = editText.getText().toString().trim();
            if (!text.isEmpty()) callback.call(text);
        });

        dialog.show();
    }

    private static void applyDarkThemeToEditText(EditText editText, boolean isDarkMode) {
        if (isDarkMode) {
            editText.setTextColor(Color.WHITE); // Set text color to white for dark theme
            editText.setHintTextColor(Color.GRAY); // Set hint color to gray
            editText.setBackgroundResource(R.drawable.edit_text_dark); // Custom dark background drawable
        } else {
            editText.setTextColor(Color.BLACK); // Default text color
            editText.setHintTextColor(Color.GRAY); // Default hint color
            editText.setBackgroundResource(R.drawable.edit_text); // Custom light background drawable
        }
    }

    public static void showMultipleChoiceList(Context context, int titleResId, final String[] items, Callback<ArrayList<Integer>> callback) {
        ContentDialog dialog = new ContentDialog(context);

        final ListView listView = dialog.findViewById(R.id.ListView);
        listView.getLayoutParams().width = AppUtils.getPreferredDialogWidth(context);
        listView.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
        listView.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_list_item_multiple_choice, items));
        listView.setVisibility(View.VISIBLE);

        dialog.setTitle(titleResId);
        dialog.setOnConfirmCallback(() -> {
            ArrayList<Integer> result = new ArrayList<>();
            SparseBooleanArray checkedItemPositions = listView.getCheckedItemPositions();
            for (int i = 0; i < checkedItemPositions.size(); i++) {
                if (checkedItemPositions.valueAt(i)) result.add(checkedItemPositions.keyAt(i));
            }
            callback.call(result);
        });

        dialog.show();
    }

    public static void showSingleChoiceList(Context context, int titleResId, final String[] items, Callback<Integer> callback) {
        showSingleChoiceList(context, context.getString(titleResId), items, callback);
    }

    /** The same, for a title that names what is being chosen for rather than only what it is. */
    public static void showSingleChoiceList(Context context, String title, final String[] items, Callback<Integer> callback) {
        ContentDialog dialog = new ContentDialog(context);
        dialog.getContentView().findViewById(R.id.BTConfirm).setVisibility(View.GONE);

        final ListView listView = dialog.findViewById(R.id.ListView);
        listView.getLayoutParams().width = AppUtils.getPreferredDialogWidth(context);
        listView.setChoiceMode(ListView.CHOICE_MODE_NONE);
        listView.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_list_item_single_choice, items));
        listView.setVisibility(View.VISIBLE);
        listView.setOnItemClickListener((parent, view, position, id) -> {
            callback.call(position);
            dialog.dismiss();
        });

        dialog.setTitle(title);
        dialog.show();
    }

    @Override
    public boolean dispatchKeyEvent(@NonNull KeyEvent event) {
        // If we are actively listening for controller input...
        if (onControllerInputListener != null && event.getAction() == KeyEvent.ACTION_DOWN) {
            InputDevice device = event.getDevice();
            // And the event is from a real, physical game controller...
            if (device != null && !device.isVirtual() && ControllerManager.isGameController(device)) {
                // ...then trigger our callback and consume the event so it doesn't do anything else.
                onControllerInputListener.onControllerInput(device);
                return true;
            }
        }
        // Otherwise, process the key event normally (e.g., for keyboard input in an EditText).
        return super.dispatchKeyEvent(event);
    }

    public static class ConfirmationResult {
        public final boolean confirmed;
        public final boolean checkboxChecked;

        public ConfirmationResult(boolean confirmed, boolean checkboxChecked) {
            this.confirmed = confirmed;
            this.checkboxChecked = checkboxChecked;
        }
    }

    public static void confirmWithCheckbox(Context context, String message, String checkboxText, Callback<ConfirmationResult> callback) {
        ContentDialog dialog = new ContentDialog(context);
        dialog.setMessage(message);

        final CheckBox checkBox = dialog.findViewById(R.id.CBExtraOption);
        final View confirmButton = dialog.findViewById(R.id.BTConfirm);

        checkBox.setText(checkboxText);
        checkBox.setVisibility(View.VISIBLE);
        checkBox.setChecked(false);

        // Link the checkbox to the OK button's state
        checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            confirmButton.setEnabled(!isChecked);
            // Add this line to visually fade the button when disabled
            confirmButton.setAlpha(isChecked ? 0.5f : 1.0f);
        });

        dialog.setOnConfirmCallback(() -> {
            callback.call(new ConfirmationResult(true, false));
        });
        dialog.setOnCancelCallback(() -> {
            callback.call(new ConfirmationResult(false, checkBox.isChecked()));
        });

        dialog.show();
    }
}
