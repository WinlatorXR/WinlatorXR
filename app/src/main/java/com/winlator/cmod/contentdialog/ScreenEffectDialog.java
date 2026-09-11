package com.winlator.cmod.contentdialog;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.preference.PreferenceManager;

import com.winlator.cmod.R;
import com.winlator.cmod.XServerDisplayActivity;
import com.winlator.cmod.core.AppUtils;
import com.winlator.xr.XrActivity;
import com.winlator.xr.ui.XrDialog;
import com.winlator.cmod.core.KeyValueSet;
import com.winlator.cmod.core.SessionSettings;
import com.winlator.cmod.renderer.GLRenderer;
import com.winlator.cmod.renderer.effects.BloomEffect;
import com.winlator.cmod.renderer.effects.CASEffect;
import com.winlator.cmod.renderer.effects.ColorEffect;
import com.winlator.cmod.renderer.effects.CRTEffect;
import com.winlator.cmod.renderer.effects.FXAAEffect;
import com.winlator.cmod.renderer.effects.FakeReflectionsEffect;
import com.winlator.cmod.renderer.effects.NTSCCombinedEffect;
import com.winlator.cmod.renderer.effects.ToonEffect;
import com.winlator.cmod.widget.SeekBar;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;

public class ScreenEffectDialog extends ContentDialog {

    private final XServerDisplayActivity activity;
    private final CheckBox cbEnableCRTShader;
    private final CheckBox cbEnableBloom;
    private final CheckBox cbEnableFakeReflections;
    private final CheckBox cbEnableFXAA;
    private final CheckBox cbEnableToonShader;
    private final CheckBox cbEnableNTSCEffect;
    private final CheckBox cbEnableCAS;
    private final CheckBox cbEnableDLS;
    private final SharedPreferences preferences;
    private final Spinner sProfile;
    private final SeekBar sbBrightness;
    private final SeekBar sbContrast;
    private final SeekBar sbGamma;
    private final SeekBar sbSharpnessLevel;
    private final SeekBar sbSharpnessDenoise;

    private static final String TAG = "ScreenEffectDialog";

    /** Where a game's own effect settings are kept. See {@link SessionSettings}. */
    public static final String KEY_EFFECTS = "screenEffects";

    /** Every setting this dialog can pin to a game. See XrActivity.SESSION_KEYS. */
    public static final String[] SESSION_KEYS = {
            KEY_EFFECTS, "screenEffectProfile", "sharpening_level", "edge_glow_level"};


    public ScreenEffectDialog(XServerDisplayActivity activity) {
        super(activity, R.layout.screen_effect_dialog);
        this.activity = activity;

        preferences = PreferenceManager.getDefaultSharedPreferences(activity);

        boolean isDarkMode = preferences.getBoolean("dark_mode", false);

        TextView lblColorAdjustment = findViewById(R.id.LBLColorAdjustment);
        applyFieldSetLabelStyle(lblColorAdjustment, isDarkMode);

        sProfile = findViewById(R.id.SProfile);
        sbBrightness = findViewById(R.id.SBBrightness);
        sbContrast = findViewById(R.id.SBContrast);
        sbGamma = findViewById(R.id.SBGamma);
        sbSharpnessLevel = findViewById(R.id.SBSharpnessLevel);
        sbSharpnessDenoise = findViewById(R.id.SBSharpnessDenoise);
        cbEnableBloom = findViewById(R.id.CBEnableBloom);
        cbEnableFakeReflections = findViewById(R.id.CBEnableFakeReflections);
        cbEnableFXAA = findViewById(R.id.CBEnableFXAA);
        cbEnableCRTShader = findViewById(R.id.CBEnableCRTShader);

        cbEnableToonShader = findViewById(R.id.CBEnableToonShader);
        cbEnableNTSCEffect = findViewById(R.id.CBEnableNTSCEffect);
        cbEnableCAS = findViewById(R.id.CBEnableCAS);
        cbEnableDLS = findViewById(R.id.CBEnableDLS);

        // Compositor sharpening (VR-only; requires XR_FB_composition_layer_settings, e.g. Quest).
        // Applied by the OpenXR compositor, independent of the GL effect composer below.
        // Slider snaps to 0 = Off, 50 = Balanced, 100 = Quality (native levels 0/1/2).
        View llSharpening = findViewById(R.id.LLSharpening);
        SeekBar sbSharpening = findViewById(R.id.SBSharpening);
        if (XrActivity.isActive() && XrActivity.getInstance().nativeIsSharpeningSupported()) {
            sbSharpening.setValue(SessionSettings.getInt(activity, "sharpening_level", 0) * 50);
            sbSharpening.setOnValueChangeListener((seekBar, value) -> {
                int level = Math.round(value / 50);
                SessionSettings.putInt(activity, "sharpening_level", level);
                if (XrActivity.isActive()) XrActivity.getInstance().nativeSetSharpening(level);
            });
        } else {
            llSharpening.setVisibility(View.GONE);
        }


        // Edge glow (VR-only). Costs a 64x64 reduction of the screen per frame plus one
        // extra compositor layer, so it stays off unless the user asks for it.
        View llEdgeGlow = findViewById(R.id.LLEdgeGlow);
        TextView tvEdgeGlow = findViewById(R.id.TVEdgeGlow);
        SeekBar sbEdgeGlow = findViewById(R.id.SBEdgeGlow);
        if (!XrActivity.isActive()) {
            llEdgeGlow.setVisibility(View.GONE);
        } else if (XrDialog.isVRGameRunning()) {
            // The glow reduces the screen once per frame and adds a compositor layer, and a
            // native VR title covers it with a full projection layer anyway, so it is forced
            // off for the whole session. Show the stored level, but do not pretend it applies.
            tvEdgeGlow.setText(activity.getString(R.string.xr_not_available_in_vr,
                    activity.getString(R.string.use_edge_glow)));
            sbEdgeGlow.setValue(SessionSettings.getInt(activity, "edge_glow_level", 0));
            XrDialog.setUnavailableInVR(tvEdgeGlow);
            XrDialog.setUnavailableInVR(sbEdgeGlow);
        } else {
            sbEdgeGlow.setValue(SessionSettings.getInt(activity, "edge_glow_level", 0));
            sbEdgeGlow.setOnValueChangeListener((seekBar, value) -> {
                int intensity = Math.round(value);
                SessionSettings.putInt(activity, "edge_glow_level", intensity);
                if (XrActivity.isActive()) XrActivity.getInstance().nativeSetEdgeGlow(intensity);
            });
        }


        GLRenderer renderer = activity.getXServerView().getRenderer();
        if (renderer == null) {
            Log.e(TAG, "Renderer is null in ScreenEffectDialog initialization!");
            return;
        }

        BloomEffect bloomEffect = (BloomEffect) renderer.getEffectComposer().getEffect(BloomEffect.class);
        ColorEffect colorEffect = (ColorEffect) renderer.getEffectComposer().getEffect(ColorEffect.class);
        FakeReflectionsEffect fakeReflectionsEffect = (FakeReflectionsEffect) renderer.getEffectComposer().getEffect(FakeReflectionsEffect.class);
        FXAAEffect fxaaEffect = (FXAAEffect) renderer.getEffectComposer().getEffect(FXAAEffect.class);
        CRTEffect crtEffect = (CRTEffect) renderer.getEffectComposer().getEffect(CRTEffect.class);
        ToonEffect toonEffect = (ToonEffect) renderer.getEffectComposer().getEffect(ToonEffect.class);
        NTSCCombinedEffect ntscEffect = (NTSCCombinedEffect) renderer.getEffectComposer().getEffect(NTSCCombinedEffect.class);
        CASEffect casEffect = (CASEffect) renderer.getEffectComposer().getEffect(CASEffect.class);

        Log.d(TAG, "ScreenEffectDialog initialized");

        // What this game was last played with, when it has been given settings of its own.
        // Reading the live effects instead cannot tell CAS from DLS or recover a sharpness
        // that is currently switched off, so the stored set wins where there is one.
        String stored = storedSettings(activity);
        if (!stored.isEmpty()) {
            applySettingsToWidgets(new KeyValueSet(stored));
        }
        else {
            if (colorEffect != null) {
                Log.d(TAG, "ColorEffect found");
                sbBrightness.setValue(colorEffect.getBrightness() * 100);
                sbContrast.setValue(colorEffect.getContrast() * 100);
                sbGamma.setValue(colorEffect.getGamma());
            } else {
                Log.d(TAG, "ColorEffect not found, resetting settings");
                resetSettings();
            }

            if (casEffect != null) {
                cbEnableCAS.setChecked(true);
                sbSharpnessLevel.setValue(casEffect.getSharpness() * 100);
                sbSharpnessDenoise.setValue(casEffect.getDenoise() * 100);
            }

            cbEnableBloom.setChecked(bloomEffect != null);
            cbEnableFakeReflections.setChecked(fakeReflectionsEffect != null);
            cbEnableFXAA.setChecked(fxaaEffect != null);
            cbEnableCRTShader.setChecked(crtEffect != null);
            cbEnableToonShader.setChecked(toonEffect != null);
            cbEnableNTSCEffect.setChecked(ntscEffect != null);
        }

        loadProfileSpinner(sProfile, activity.getScreenEffectProfile());

        sProfile.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position > 0) {
                    loadProfile(sProfile.getSelectedItem().toString());
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        Runnable applyAll = () -> applyEffects(renderer);
        Button resetButton = findViewById(R.id.BTReset);
        resetButton.setVisibility(View.VISIBLE);
        resetButton.setOnClickListener(view -> {
            resetSettings();
            applyAll.run();
        });

        // Apply changes immediatelly
        cbEnableBloom.setOnCheckedChangeListener((compoundButton, b) -> applyAll.run());
        cbEnableFakeReflections.setOnCheckedChangeListener((compoundButton, b) -> applyAll.run());
        cbEnableFXAA.setOnCheckedChangeListener((compoundButton, b) -> applyAll.run());
        cbEnableCRTShader.setOnCheckedChangeListener((compoundButton, b) -> applyAll.run());
        cbEnableToonShader.setOnCheckedChangeListener((compoundButton, b) -> applyAll.run());
        cbEnableNTSCEffect.setOnCheckedChangeListener((compoundButton, b) -> applyAll.run());
        cbEnableCAS.setOnCheckedChangeListener((compoundButton, b) -> {
            if (b) cbEnableDLS.setChecked(false);
            applyAll.run();
        });
        cbEnableDLS.setOnCheckedChangeListener((compoundButton, b) -> {
            if (b) cbEnableCAS.setChecked(false);
            applyAll.run();
        });
        sbBrightness.setOnValueChangeListener((seekBar, value) -> applyAll.run());
        sbContrast.setOnValueChangeListener((seekBar, value) -> applyAll.run());
        sbGamma.setOnValueChangeListener((seekBar, value) -> applyAll.run());
        sbSharpnessLevel.setOnValueChangeListener((seekBar, value) -> applyAll.run());
        sbSharpnessDenoise.setOnValueChangeListener((seekBar, value) -> applyAll.run());
        findViewById(R.id.BTCancel).setVisibility(View.GONE);

        findViewById(R.id.BTConfirm).setOnClickListener(v -> {
            Log.d(TAG, "BTConfirm clicked. Preparing to save profile and apply effects.");
            saveProfile();
            Log.d(TAG, "Profile saved.");

            // Directly calling applyEffects to ensure it's triggered
            Log.d(TAG, "Calling applyEffects() directly.");
            applyEffects(renderer);

            Log.d(TAG, "Effects applied. Dismissing dialog.");
            dismiss(); // Close the dialog
            Log.d(TAG, "Dialog dismissed.");
        });



        findViewById(R.id.BTAddProfile).setOnClickListener(v -> promptAddProfile());
        findViewById(R.id.BTRemoveProfile).setOnClickListener(v -> promptDeleteProfile());

        setOnConfirmCallback(() -> {
            Log.d(TAG, "OnConfirm callback triggered. Applying effects.");
            applyEffects(renderer);
            Log.d(TAG, "Effects applied from callback.");

            // Optionally dismiss after applying effects in callback
            dismiss();
            Log.d(TAG, "Dialog dismissed after callback.");
        });

    }

    private static void applyFieldSetLabelStyle(TextView textView, boolean isDarkMode) {
//        Context context = textView.getContext();

        if (isDarkMode) {
            // Apply dark mode-specific attributes
            textView.setTextColor(Color.parseColor("#cccccc")); // Set text color to #cccccc
            textView.setBackgroundResource(R.color.window_background_color_dark); // Set dark background color
        } else {
            // Apply light mode-specific attributes (original FieldSetLabel)
            textView.setTextColor(Color.parseColor("#bdbdbd")); // Set text color to #bdbdbd
            textView.setBackgroundResource(R.color.window_background_color); // Set light background color
        }
    }

    private void promptAddProfile() {
        ContentDialog.prompt(activity, R.string.do_you_want_to_add_a_new_profile, null, name -> addProfile(name, sProfile));
    }

    private void promptDeleteProfile() {
        if (sProfile.getSelectedItemPosition() > 0) {
            String selectedProfile = sProfile.getSelectedItem().toString();
            ContentDialog.confirm(activity, R.string.do_you_want_to_remove_this_profile, () -> removeProfile(selectedProfile, sProfile));
        } else {
            AppUtils.showToast(activity, R.string.no_profile_selected);
        }
    }

    private void addProfile(String newName, Spinner sProfile) {
        Set<String> profiles = new LinkedHashSet<>(preferences.getStringSet("screen_effect_profiles", new LinkedHashSet<>()));
        for (String profile : profiles) {
            String[] parts = profile.split(":");
            if (parts[0].equals(newName)) {
                return;
            }
        }
        profiles.add(newName + ":");
        preferences.edit().putStringSet("screen_effect_profiles", profiles).apply();
        loadProfileSpinner(sProfile, newName);
    }

    private void loadProfileSpinner(Spinner sProfile, String selectedName) {
        Set<String> profiles = new LinkedHashSet<>(preferences.getStringSet("screen_effect_profiles", new LinkedHashSet<>()));
        ArrayList<String> items = new ArrayList<>();
        items.add("-- " + activity.getString(R.string.default_profile) + " --");
        int selectedPosition = 0, position = 1;
        for (String profile : profiles) {
            String[] parts = profile.split(":");
            items.add(parts[0]);
            if (parts[0].equals(selectedName)) {
                selectedPosition = position;
            }
            position++;
        }
        sProfile.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item, items));
        sProfile.setSelection(selectedPosition);
    }

    private void loadProfile(String name) {
        Set<String> profiles = new LinkedHashSet<>(preferences.getStringSet("screen_effect_profiles", new LinkedHashSet<>()));
        for (String profile : profiles) {
            String[] parts = profile.split(":");
            if (parts[0].equals(name) && parts.length > 1 && !parts[1].isEmpty()) {
                applySettingsToWidgets(new KeyValueSet(parts[1]));
                return;
            }
        }
    }

    private void removeProfile(String targetName, Spinner sProfile) {
        Set<String> profiles = new LinkedHashSet<>(preferences.getStringSet("screen_effect_profiles", new LinkedHashSet<>()));
        profiles.removeIf(profile -> profile.split(":")[0].equals(targetName));
        preferences.edit().putStringSet("screen_effect_profiles", profiles).apply();
        loadProfileSpinner(sProfile, null);
        resetSettings();
    }

    private void resetSettings() {
        sbBrightness.setValue(0);
        sbContrast.setValue(0);
        sbGamma.setValue(0.0f);
        cbEnableBloom.setChecked(false);
        cbEnableFakeReflections.setChecked(false);
        cbEnableFXAA.setChecked(false);
        cbEnableCRTShader.setChecked(false);
        cbEnableToonShader.setChecked(false);
        cbEnableNTSCEffect.setChecked(false);
        cbEnableCAS.setChecked(false);
        cbEnableDLS.setChecked(false);
        sbSharpnessLevel.setValue(0);
        sbSharpnessDenoise.setValue(0);
    }

    private void saveProfile() {
        if (sProfile.getSelectedItemPosition() > 0) {
            String selectedProfile = sProfile.getSelectedItem().toString();
            Set<String> oldProfiles = new LinkedHashSet<>(preferences.getStringSet("screen_effect_profiles", new LinkedHashSet<>()));
            Set<String> newProfiles = new LinkedHashSet<>();
            KeyValueSet settings = collectSettings();

            for (String profile : oldProfiles) {
                String[] parts = profile.split(":");
                if (parts[0].equals(selectedProfile)) {
                    newProfiles.add(selectedProfile + ":" + settings.toString());
                } else {
                    newProfiles.add(profile);
                }
            }
            preferences.edit().putStringSet("screen_effect_profiles", newProfiles).apply();
            activity.setScreenEffectProfile(selectedProfile);
        }
    }

    /**
     * Collects what the dialog is currently showing, in the same form the named profiles are
     * stored in. Also what gets pinned to the shortcut, so a game comes back with the effects
     * it was last played with.
     */
    private KeyValueSet collectSettings() {
        KeyValueSet settings = new KeyValueSet();
        settings.put("brightness", sbBrightness.getValue());
        settings.put("contrast", sbContrast.getValue());
        settings.put("gamma", sbGamma.getValue());
        settings.put("bloom", cbEnableBloom.isChecked());
        settings.put("fake_reflections", cbEnableFakeReflections.isChecked());
        settings.put("fxaa", cbEnableFXAA.isChecked());
        settings.put("crt_shader", cbEnableCRTShader.isChecked());
        settings.put("toon_shader", cbEnableToonShader.isChecked());
        settings.put("ntsc_effect", cbEnableNTSCEffect.isChecked());
        settings.put("cas_enabled", cbEnableCAS.isChecked());
        settings.put("dls_enabled", cbEnableDLS.isChecked());
        settings.put("sharpness_level", sbSharpnessLevel.getValue());
        settings.put("sharpness_denoise", sbSharpnessDenoise.getValue());
        return settings;
    }

    /** Puts a stored set of settings into the dialog's controls. */
    private void applySettingsToWidgets(KeyValueSet settings) {
        sbBrightness.setValue(settings.getFloat("brightness", 0));
        sbContrast.setValue(settings.getFloat("contrast", 0));
        sbGamma.setValue(settings.getFloat("gamma", 0.0f));
        cbEnableBloom.setChecked(settings.getBoolean("bloom", false));
        cbEnableFakeReflections.setChecked(settings.getBoolean("fake_reflections", false));
        cbEnableFXAA.setChecked(settings.getBoolean("fxaa", false));
        cbEnableCRTShader.setChecked(settings.getBoolean("crt_shader", false));
        cbEnableToonShader.setChecked(settings.getBoolean("toon_shader", false));
        cbEnableNTSCEffect.setChecked(settings.getBoolean("ntsc_effect", false));
        cbEnableCAS.setChecked(settings.getBoolean("cas_enabled", false));
        cbEnableDLS.setChecked(settings.getBoolean("dls_enabled", false));
        sbSharpnessLevel.setValue(settings.getFloat("sharpness_level", 0));
        sbSharpnessDenoise.setValue(settings.getFloat("sharpness_denoise", 0));
    }

    /** What the game being played was last left with, empty when it has nothing stored. */
    public static String storedSettings(Context context) {
        return SessionSettings.getString(context, KEY_EFFECTS, "");
    }

    /**
     * Puts the effects a game was last played with back on the renderer. Called once when the
     * session starts; before this, effects lasted only as long as the dialog's own session
     * and nothing was reapplied on the next launch.
     */
    public static void restore(Context context, GLRenderer renderer) {
        String stored = storedSettings(context);
        if (stored.isEmpty()) return;
        applySettings(new KeyValueSet(stored), renderer);
    }

    /**
     * Puts a set of settings onto the renderer. Shared by the dialog and by the restore at
     * session start, so a game looks the same whether the user has just changed something or
     * has only launched it.
     */
    public static void applySettings(KeyValueSet settings, GLRenderer renderer) {
        if (renderer == null) {
            Log.e(TAG, "Renderer is null!");
            return;
        }
        if (renderer.getEffectComposer() == null) {
            Log.e(TAG, "EffectComposer is null!");
            return;
        }

        float brightness = settings.getFloat("brightness", 0);
        float contrast = settings.getFloat("contrast", 0);
        float gamma = settings.getFloat("gamma", 0);
        boolean enableBloom = settings.getBoolean("bloom", false);
        boolean enableFakeReflections = settings.getBoolean("fake_reflections", false);
        boolean enableFXAA = settings.getBoolean("fxaa", false);
        boolean enableCRTShader = settings.getBoolean("crt_shader", false);
        boolean enableToonShader = settings.getBoolean("toon_shader", false);
        boolean enableNTSCEffect = settings.getBoolean("ntsc_effect", false);
        boolean enableCAS = settings.getBoolean("cas_enabled", false);
        boolean enableDLS = settings.getBoolean("dls_enabled", false);
        float sharpnessLevel = settings.getFloat("sharpness_level", 0);
        float sharpnessDenoise = settings.getFloat("sharpness_denoise", 0);

        Log.d(TAG, "Settings - Brightness: " + brightness + ", Contrast: " + contrast + ", Gamma: " + gamma);
        Log.d(TAG, "FXAA Enabled: " + enableFXAA + ", CRT Shader Enabled: " + enableCRTShader);

        ColorEffect colorEffect = renderer.getEffectComposer().getEffect(ColorEffect.class);
        if (colorEffect == null) {
            Log.d(TAG, "ColorEffect is null, creating new instance.");
            colorEffect = new ColorEffect();
        }

        // Apply or remove ColorEffect
        if (brightness == 0 && contrast == 0 && gamma == 0) {
            Log.d(TAG, "No adjustments are applied. Removing ColorEffect if it exists.");
            renderer.getEffectComposer().removeEffect(colorEffect);
        } else {
            Log.d(TAG, "Applying ColorEffect adjustments.");
            colorEffect.setBrightness(brightness / 100f);
            colorEffect.setContrast(contrast / 100f);
            colorEffect.setGamma(gamma);
            renderer.getEffectComposer().addEffect(colorEffect);
            Log.d(TAG, "ColorEffect added/updated.");
        }

        // Apply or remove BloomEffect
        if (enableBloom) {
            renderer.getEffectComposer().addEffect(new BloomEffect());
        } else {
            renderer.getEffectComposer().removeEffect(BloomEffect.class);
        }

        // Apply or remove FakeReflectionsEffect
        if (enableFakeReflections) {
            renderer.getEffectComposer().addEffect(new FakeReflectionsEffect());
        } else {
            renderer.getEffectComposer().removeEffect(FakeReflectionsEffect.class);
        }

        // Apply or remove FXAAEffect
        if (enableFXAA) {
            renderer.getEffectComposer().addEffect(new FXAAEffect());
        } else {
            renderer.getEffectComposer().removeEffect(FXAAEffect.class);
        }

        // Apply or remove CRTEffect
        if (enableCRTShader) {
            renderer.getEffectComposer().addEffect(new CRTEffect());
        } else {
            renderer.getEffectComposer().removeEffect(CRTEffect.class);
        }


        // Apply or remove ToonEffect
        if (enableToonShader) {
            renderer.getEffectComposer().addEffect(new ToonEffect());
        } else {
            renderer.getEffectComposer().removeEffect(ToonEffect.class);
        }


        // Apply or remove NTSCCombinedEffect
        if (enableNTSCEffect) {
            renderer.getEffectComposer().addEffect(new NTSCCombinedEffect());
        } else {
            renderer.getEffectComposer().removeEffect(NTSCCombinedEffect.class);
        }

        // Apply or remove CASEffect
        if (enableCAS || enableDLS) {
            CASEffect casEffect = renderer.getEffectComposer().getEffect(CASEffect.class);
            if (casEffect == null) {
                casEffect = new CASEffect();
                renderer.getEffectComposer().addEffect(casEffect);
            }
            casEffect.setSharpness(sharpnessLevel / 100.0f);
            casEffect.setDenoise(sharpnessDenoise / 100.0f);
        } else {
            renderer.getEffectComposer().removeEffect(CASEffect.class);
        }
    }

    /**
     * Applies what the dialog is showing and remembers it. The settings are pinned to the
     * shortcut that launched the session, so the next launch of this game gets them back and
     * no other game picks them up; with no shortcut they go to the app-wide preferences.
     */
    public void applyEffects(GLRenderer renderer) {
        Log.d(TAG, "applyEffects() called");
        if (renderer == null) {
            Log.e(TAG, "Renderer is null!");
            return;
        }

        KeyValueSet settings = collectSettings();
        SessionSettings.putString(activity, KEY_EFFECTS, settings.toString());
        applySettings(settings, renderer);

        saveProfile();
        Log.d(TAG, "Profile saved after applying effects.");
    }

    public void setOnConfirmCallback(Runnable confirmCallback) {
        Log.d(TAG, "Setting OnConfirm callback.");
        this.onConfirmCallback = confirmCallback;
    }


}
