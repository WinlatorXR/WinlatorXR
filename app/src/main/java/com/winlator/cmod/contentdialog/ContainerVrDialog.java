package com.winlator.cmod.contentdialog;

import android.content.Context;
import android.view.View;
import android.widget.CheckBox;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.winlator.cmod.R;
import com.winlator.cmod.container.Container;
import com.winlator.xr.utils.PcvrRuntime;

import java.util.Arrays;

// The shortcut settings' PC VR options, kept on the container for launches with no shortcut
public class ContainerVrDialog extends ContentDialog {
    public ContainerVrDialog(@NonNull Context context, Container container, Runnable onSaved) {
        super(context, R.layout.container_vr_dialog);
        setTitle(R.string.enable_vr_for_container);
        setIcon(R.drawable.icon_settings);

        final CheckBox cbPcvrRuntime = findViewById(R.id.CBPcvrRuntime);
        cbPcvrRuntime.setChecked(PcvrRuntime.isEnabled(container));

        final CheckBox cbPcvrDirectTransport = findViewById(R.id.CBPcvrDirectTransport);
        cbPcvrDirectTransport.setChecked(PcvrRuntime.isDirectTransport(container));

        final View llPcvrController = findViewById(R.id.LLPcvrController);
        final Spinner sPcvrController = findViewById(R.id.SPcvrController);
        sPcvrController.setSelection(Math.max(0, Arrays.asList(PcvrRuntime.CONTROLLER_PROFILES).indexOf(PcvrRuntime.getControllerProfile(container))));

        final View llPcvrRenderScale = findViewById(R.id.LLPcvrRenderScale);
        final SeekBar sbPcvrRenderScale = findViewById(R.id.SBPcvrRenderScale);
        final TextView tvPcvrRenderScale = findViewById(R.id.TVPcvrRenderScale);
        int pcvrScale = PcvrRuntime.getRenderScale(container);
        sbPcvrRenderScale.setProgress(Math.max(0, Math.min(16, Math.round((pcvrScale - 20) / 5.0f))));
        tvPcvrRenderScale.setText((20 + 5 * sbPcvrRenderScale.getProgress()) + "%");
        sbPcvrRenderScale.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvPcvrRenderScale.setText((20 + 5 * progress) + "%");
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        final View llPcvrFovScale = findViewById(R.id.LLPcvrFovScale);
        final SeekBar sbPcvrFovScale = findViewById(R.id.SBPcvrFovScale);
        final TextView tvPcvrFovScale = findViewById(R.id.TVPcvrFovScale);
        sbPcvrFovScale.setProgress(Math.round((PcvrRuntime.getFovScale(container) - 30) / 5.0f));
        tvPcvrFovScale.setText((30 + 5 * sbPcvrFovScale.getProgress()) + "%");
        sbPcvrFovScale.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvPcvrFovScale.setText((30 + 5 * progress) + "%");
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        final SeekBar sbPcvrFovScaleY = findViewById(R.id.SBPcvrFovScaleY);
        final TextView tvPcvrFovScaleY = findViewById(R.id.TVPcvrFovScaleY);
        sbPcvrFovScaleY.setProgress(Math.round((PcvrRuntime.getFovScaleY(container) - 30) / 5.0f));
        tvPcvrFovScaleY.setText((30 + 5 * sbPcvrFovScaleY.getProgress()) + "%");
        sbPcvrFovScaleY.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvPcvrFovScaleY.setText((30 + 5 * progress) + "%");
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        cbPcvrDirectTransport.setVisibility(cbPcvrRuntime.isChecked() ? View.VISIBLE : View.GONE);
        llPcvrController.setVisibility(cbPcvrRuntime.isChecked() ? View.VISIBLE : View.GONE);
        llPcvrRenderScale.setVisibility(cbPcvrRuntime.isChecked() && cbPcvrDirectTransport.isChecked()
                ? View.VISIBLE : View.GONE);
        llPcvrFovScale.setVisibility(cbPcvrRuntime.isChecked() ? View.VISIBLE : View.GONE);
        cbPcvrRuntime.setOnCheckedChangeListener((buttonView, isChecked) -> {
            cbPcvrDirectTransport.setVisibility(isChecked ? View.VISIBLE : View.GONE);
            llPcvrController.setVisibility(isChecked ? View.VISIBLE : View.GONE);
            llPcvrRenderScale.setVisibility(isChecked && cbPcvrDirectTransport.isChecked() ? View.VISIBLE : View.GONE);
            llPcvrFovScale.setVisibility(isChecked ? View.VISIBLE : View.GONE);
        });
        cbPcvrDirectTransport.setOnCheckedChangeListener((buttonView, isChecked) ->
                llPcvrRenderScale.setVisibility(isChecked && cbPcvrRuntime.isChecked() ? View.VISIBLE : View.GONE));

        setOnConfirmCallback(() -> {
            container.putExtra(PcvrRuntime.EXTRA_KEY, cbPcvrRuntime.isChecked() ? "1" : null);
            container.putExtra(PcvrRuntime.DIRECT_KEY, cbPcvrRuntime.isChecked()
                    ? (cbPcvrDirectTransport.isChecked() ? "1" : "0") : null);
            String pcvrController = PcvrRuntime.CONTROLLER_PROFILES[sPcvrController.getSelectedItemPosition()];
            container.putExtra(PcvrRuntime.CONTROLLER_KEY, cbPcvrRuntime.isChecked() && !pcvrController.isEmpty() ? pcvrController : null);
            container.putExtra(PcvrRuntime.RENDER_SCALE_KEY, cbPcvrRuntime.isChecked()
                    ? String.valueOf(20 + 5 * sbPcvrRenderScale.getProgress()) : null);
            int pcvrFov = 30 + 5 * sbPcvrFovScale.getProgress();
            container.putExtra(PcvrRuntime.FOV_SCALE_KEY, cbPcvrRuntime.isChecked() && pcvrFov != PcvrRuntime.DEFAULT_FOV_SCALE
                    ? String.valueOf(pcvrFov) : null);
            int pcvrFovY = 30 + 5 * sbPcvrFovScaleY.getProgress();
            container.putExtra(PcvrRuntime.FOV_SCALE_Y_KEY, cbPcvrRuntime.isChecked() && pcvrFovY != pcvrFov
                    ? String.valueOf(pcvrFovY) : null);
            container.saveData();
            if (onSaved != null) onSaved.run();
        });
    }
}
