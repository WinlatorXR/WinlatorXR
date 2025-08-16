package com.winlator.cmod.winhandler;

import static com.winlator.cmod.inputcontrols.ExternalController.TRIGGER_IS_AXIS;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

import androidx.preference.PreferenceManager;

import com.winlator.cmod.XServerDisplayActivity;
import com.winlator.cmod.XrActivity;
import com.winlator.cmod.contentdialog.ContentDialog;
import com.winlator.cmod.contentdialog.ControllerAssignmentDialog;
import com.winlator.cmod.core.StringUtils;
import com.winlator.cmod.inputcontrols.ControllerManager;
import com.winlator.cmod.inputcontrols.ControlsProfile;
import com.winlator.cmod.inputcontrols.ExternalController;
import com.winlator.cmod.inputcontrols.GamepadState;
import com.winlator.cmod.math.Mathf;
import com.winlator.cmod.xserver.XServer;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * WinHandler
 * - UDP protocol with winhandler.exe
 * - Publishes controller state to UDP and memory-mapped files
 * - Applies gyro to P1 RX/RY
 * - Applies per-slot turbo (autofire) when writing to SHM
 */
public class WinHandler {
    private static final String TAG = "WinHandler";

    // --- Networking ----------------------------------------------------------
    private static final short SERVER_PORT = 7947;
    private static final short CLIENT_PORT = 7946;

    public static final byte FLAG_INPUT_TYPE_XINPUT = 0x04;
    public static final byte DEFAULT_INPUT_TYPE = FLAG_INPUT_TYPE_XINPUT;

    private DatagramSocket socket;
    private InetAddress localhost;
    private final ByteBuffer sendData = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
    private final ByteBuffer receiveData = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
    private final DatagramPacket sendPacket = new DatagramPacket(sendData.array(), 64);
    private final DatagramPacket receivePacket = new DatagramPacket(receiveData.array(), 64);

    private final ArrayDeque<Runnable> actions = new ArrayDeque<>();
    private final List<Integer> gamepadClients = new CopyOnWriteArrayList<>();

    private boolean initReceived = false;
    private boolean running = false;
    private byte inputType = DEFAULT_INPUT_TYPE;

    // --- Context / managers --------------------------------------------------
    private final XServerDisplayActivity activity;
    private final ControllerManager controllerManager;
    private SharedPreferences preferences;
    private OnGetProcessInfoListener onGetProcessInfoListener;

    // --- Controller assignment ----------------------------------------------
    public static final int MAX_PLAYERS = 4; // P1..P4
    private final MappedByteBuffer[] extraGamepadBuffers = new MappedByteBuffer[MAX_PLAYERS - 1]; // P2..P4
    private final ExternalController[] extraControllers   = new ExternalController[MAX_PLAYERS - 1];
    private MappedByteBuffer gamepadBuffer; // P1

    private ExternalController currentController; // P1
    private byte triggerType;                     // trigger mapping
    private boolean xinputDisabled;               // for exclusive mouse mode
    private boolean xinputDisabledInitialized = false;

    private final java.util.Set<String>  ignoredGroups    = new java.util.HashSet<>();
    private final java.util.Set<Integer> ignoredDeviceIds = new java.util.HashSet<>();
    private boolean isShowingAssignDialog = false;

    private boolean virtualExclusiveP1 = true;

    // --- Gyro state ----------------------------------------------------------
    private boolean gyroEnabled = false;
    private boolean isToggleMode = false;
    private boolean isGyroActive = false;       // live armed state (toggle/hold)
    private boolean processGyroWithLeftTrigger = false;
    private int     gyroTriggerButton;

    // tuning
    private float gyroSensitivityX = 0.35f;
    private float gyroSensitivityY = 0.25f;
    private float smoothingFactor  = 0.45f;
    private boolean invertGyroX    = true;
    private boolean invertGyroY    = false;
    private float gyroDeadzone     = 0.01f;

    // filtered output applied to RX/RY
    private float smoothGyroX = 0f;
    private float smoothGyroY = 0f;
    private float gyroX = 0f;
    private float gyroY = 0f;

    private float lastSentGX = 0f;
    private float lastSentGY = 0f;
    private static final float EPS = 1e-4f; // snap-to-zero threshold

    // virtual gamepad cache (for when no physical P1 controller exists)
    private GamepadState lastVirtualState;
    private volatile boolean hasVirtualState = false;

    private boolean gyroToLeftStick = false;   // new
    public void setGyroToLeftStick(boolean v) { this.gyroToLeftStick = v; }

    private boolean lastVirtualActivatorPressed = false;

    // --- Rumble cache per-slot ----------------------------------------------
    private final short[] lastLow  = new short[MAX_PLAYERS];
    private final short[] lastHigh = new short[MAX_PLAYERS];
    private Thread rumblePollerThread;

    // --- Turbo (autofire) ----------------------------------------------------
    private static final int BUTTON_COUNT = 15; // length of sdlButtons
    private static final int MAX_SLOTS = MAX_PLAYERS; // 4
    private final boolean[][] turboEnabled = new boolean[MAX_SLOTS][BUTTON_COUNT];
    private final boolean[] includeTriggers = new boolean[MAX_SLOTS];
    private final long[] lastTurboTick = new long[MAX_SLOTS];
    // Shared on/off phase: simple and light. All slots flip together.
    private volatile boolean turboPhaseOn = true;
    private static final int TURBO_PERIOD_MS = 33;   // ~15 Hz
    private static final int TURBO_TICK_MS   = 8;    // check often; flip every TURBO_PERIOD_MS
    private volatile long turboLastFlipMs = 0;

    private volatile boolean anyTurboEnabled = false;

    // ------------------------------------------------------------------------

    public WinHandler(XServerDisplayActivity activity) {
        this.activity = activity;
        this.controllerManager = ControllerManager.getInstance();
        this.preferences = PreferenceManager.getDefaultSharedPreferences(activity.getBaseContext());
    }

    // ========================== Public control ===============================

    public void start() {
        // Create/Map shared memory files (P1 + P2..P4)
        try {
            localhost = InetAddress.getLocalHost();

            // P1
            String p1Path = "/data/data/com.winlator.cmod/files/imagefs/tmp/gamepad.mem";
            File p1 = new File(p1Path);
            p1.getParentFile().mkdirs();
            try (RandomAccessFile raf = new RandomAccessFile(p1, "rw")) {
                raf.setLength(64);
                gamepadBuffer = raf.getChannel().map(FileChannel.MapMode.READ_WRITE, 0, 64);
                gamepadBuffer.order(ByteOrder.LITTLE_ENDIAN);
                Log.i(TAG, "Mapped SHM for Player 1");
            }

            // P2..P4
            for (int i = 0; i < extraGamepadBuffers.length; i++) {
                String path = "/data/data/com.winlator.cmod/files/imagefs/tmp/gamepad" + (i + 1) + ".mem";
                File f = new File(path);
                try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) {
                    raf.setLength(64);
                    extraGamepadBuffers[i] = raf.getChannel().map(FileChannel.MapMode.READ_WRITE, 0, 64);
                    extraGamepadBuffers[i].order(ByteOrder.LITTLE_ENDIAN);
                    Log.i(TAG, "Mapped SHM for Player " + (i + 2));
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "FATAL: Failed to create memory-mapped file(s).", e);
        }

        running = true;
        startSendThread();

        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                socket = new DatagramSocket(null);
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress((InetAddress) null, SERVER_PORT));

                while (running) {
                    socket.receive(receivePacket);
                    synchronized (actions) {
                        receiveData.rewind();
                        byte requestCode = receiveData.get();
                        handleRequest(requestCode, receivePacket.getPort());
                    }
                }
            } catch (IOException e) {
                if (!running) {
                    Log.d(TAG, "Socket closed for shutdown.");
                } else {
                    Log.e(TAG, "Socket error", e);
                }
            }
        });

        startRumblePoller();
    }

    public void stop() {
        running = false;

        if (socket != null) {
            socket.close();
            socket = null;
        }
        synchronized (actions) {
            actions.notify();
        }
    }

    // ========================== Gyro API (called by MotionControls) =========

    public void setGyroEnabled(boolean enabled) { this.gyroEnabled = enabled; }
    public void setGyroTriggerButton(int keycode) { this.gyroTriggerButton = keycode; }
    public void setGyroToggleMode(boolean toggle) { this.isToggleMode = toggle; }
    public void setProcessGyroWithLeftTrigger(boolean onlyLT) { this.processGyroWithLeftTrigger = onlyLT; }



    public void setGyroSensitivityX(float v) { this.gyroSensitivityX = v; }
    public void setGyroSensitivityY(float v) { this.gyroSensitivityY = v; }
    public void setSmoothingFactor(float v)   { this.smoothingFactor  = v; }
    public void setInvertGyroX(boolean v)     { this.invertGyroX = v; }
    public void setInvertGyroY(boolean v)     { this.invertGyroY = v; }
    public void setGyroDeadzone(float v)      { this.gyroDeadzone = v; }

    /** Allow UI to arm/disarm instantly (useful for toggle mode). */
    public void setGyroActive(boolean active) { this.isGyroActive = active; }

    /** Called on every sensor sample (yaw=X, pitch=Y mapping happens in MotionControls). */
    public void updateGyroData(float rawGyroX, float rawGyroY) {
        if (!gyroEnabled) {
            // Reset to zero immediately if gyro is disabled.
            if (gyroX != 0f || gyroY != 0f) {
                gyroX = gyroY = 0f;
                sendGamepadState();
                pokeSharedMemory();
                lastSentGX = gyroX;
                lastSentGY = gyroY;
            }
            return;
        }

        boolean active = isGyroActive || (processGyroWithLeftTrigger && isLeftTriggerPressed());

        if (!active) {
            // Reset immediately to zero when not active.
            if (gyroX != 0f || gyroY != 0f) {
                gyroX = gyroY = 0f;
                sendGamepadState();
                pokeSharedMemory();
                lastSentGX = gyroX;
                lastSentGY = gyroY;
            }
            return;
        }

        // ---- Active gyro processing ----

        // Apply deadzone
        rawGyroX = Math.abs(rawGyroX) < gyroDeadzone ? 0f : rawGyroX;
        rawGyroY = Math.abs(rawGyroY) < gyroDeadzone ? 0f : rawGyroY;

        // Inversion
        if (invertGyroX) rawGyroX = -rawGyroX;
        if (invertGyroY) rawGyroY = -rawGyroY;

        // Gains (TYPE_GYROSCOPE is rad/s)
        final float baseGainX = 0.60f;
        final float baseGainY = 0.45f;
        float gx = rawGyroX * baseGainX * gyroSensitivityX;
        float gy = rawGyroY * baseGainY * gyroSensitivityY;

        // Exponential smoothing, but immediate zero respect
        if (rawGyroX == 0f) smoothGyroX = 0f;
        else smoothGyroX = smoothGyroX * smoothingFactor + gx * (1f - smoothingFactor);

        if (rawGyroY == 0f) smoothGyroY = 0f;
        else smoothGyroY = smoothGyroY * smoothingFactor + gy * (1f - smoothingFactor);

        // Clamp into stick range
        gyroX = Mathf.clamp(smoothGyroX, -0.95f, 0.95f);
        gyroY = Mathf.clamp(smoothGyroY, -0.95f, 0.95f);

        // Only send updates if significant change occurs
        if (Math.abs(gyroX - lastSentGX) > EPS || Math.abs(gyroY - lastSentGY) > EPS) {
            sendGamepadState();
            pokeSharedMemory();
            lastSentGX = gyroX;
            lastSentGY = gyroY;
        }
    }
    /** Force current values into SHM immediately. Safe to call anytime. */
    public void pokeSharedMemory() {
        if (gamepadBuffer == null) return;

        final ControlsProfile profile = activity.getInputControlsView().getProfile();
        final boolean useVirtual = profile != null && profile.isVirtualGamepad();

        if (useVirtual) {
            GamepadState s = profile.getGamepadState();
            if (s != null) {
                lastVirtualState = s;
                hasVirtualState = true;
                writeStateToMappedBuffer(s, gamepadBuffer, true, 0);
            }
            return;
        }

        ensureP1Controller();
        if (currentController != null) {
            writeStateToMappedBuffer(currentController.state, gamepadBuffer, true, 0);
        } else if (hasVirtualState && lastVirtualState != null) {
            writeStateToMappedBuffer(lastVirtualState, gamepadBuffer, true, 0);
        }
    }

    // ========================== Event handling ==============================

    public boolean onGenericMotionEvent(MotionEvent event) {
        int deviceId = event.getDeviceId();
        InputDevice device = InputDevice.getDevice(deviceId);
        if (device == null || !ControllerManager.isGameController(device)) return false;

        int assignedSlot = controllerManager.getSlotForDeviceOrSibling(deviceId);

        // If virtual is active and this device is P1, ignore to avoid conflicts
        if (virtualExclusiveP1 && isVirtualActive() && assignedSlot == 0) {
            return true; // consume quietly
        }

        // Saved for reference - can re-enable if regex loop is avoided
        // Prompt for inactive/unassigned controllers
//        String groupKey = ControllerManager.makePhysicalGroupKey(device);
//        if ((assignedSlot == -1 || !controllerManager.isSlotEnabled(assignedSlot))
//                && !ignoredDeviceIds.contains(deviceId)
//                && !ignoredGroups.contains(groupKey)) {
//
//            if (!isShowingAssignDialog) {
//                isShowingAssignDialog = true;
//                activity.runOnUiThread(() -> {
//                    String checkboxMessage = "Don't prompt for this controller again.";
//                    ContentDialog.confirmWithCheckbox(
//                            activity,
//                            device.getName() + " Detected\n\nThis controller is not active. Open assignment menu?",
//                            checkboxMessage,
//                            (result) -> {
//                                if (result.confirmed) {
//                                    ControllerAssignmentDialog.show(activity);
//                                } else if (result.checkboxChecked) {
//                                    ignoredDeviceIds.add(deviceId);
//                                }
//                                isShowingAssignDialog = false;
//                            });
//                });
//            }
//            return true;
//        }

        // P1
        if (assignedSlot == 0) {
            if (currentController == null || currentController.getDeviceId() != deviceId) {
                currentController = ExternalController.getController(deviceId);
            }
            if (currentController != null) {
                boolean handled = currentController.updateStateFromMotionEvent(event);
                if (handled) {
                    sendGamepadState();
                    writeStateToMappedBuffer(currentController.state, gamepadBuffer, true, /*slot*/0);
                }

                // Trigger button as analog (L2/R2)
                if (gyroTriggerButton == KeyEvent.KEYCODE_BUTTON_L2 || gyroTriggerButton == KeyEvent.KEYCODE_BUTTON_R2) {
                    float triggerValue = (gyroTriggerButton == KeyEvent.KEYCODE_BUTTON_L2)
                            ? event.getAxisValue(MotionEvent.AXIS_LTRIGGER)
                            : event.getAxisValue(MotionEvent.AXIS_RTRIGGER);

                    boolean pressed = triggerValue > 0.5f;
                    if (pressed) {
                        if (!isGyroActive) {
                            isGyroActive = isToggleMode ? !isGyroActive : true;
                        }
                    } else if (!isToggleMode) {
                        isGyroActive = false;
                    }
                }
                return handled;
            }
        }
        // P2..P4
        else if (assignedSlot > 0) {
            int idx = assignedSlot - 1;
            ExternalController extra = extraControllers[idx];
            if (extra == null || extra.getDeviceId() != deviceId) {
                extraControllers[idx] = ExternalController.getController(deviceId);
                extra = extraControllers[idx];
            }
            if (extra != null && extra.updateStateFromMotionEvent(event)) {
                writeStateToMappedBuffer(extra.state, extraGamepadBuffers[idx], false, /*slot*/assignedSlot);
                return true;
            }
        }

        return false;
    }

    public boolean onKeyEvent(KeyEvent event) {
        final boolean isDown = event.getAction() == KeyEvent.ACTION_DOWN;
        final boolean isUp   = event.getAction() == KeyEvent.ACTION_UP;
        final int keyCode    = event.getKeyCode();

        // Ignore Android system/navigation keys for prompting
        if (isDown && isSystemNavKey(keyCode)) return false;

        final int deviceId = event.getDeviceId();
        final InputDevice device = event.getDevice();
        if (device == null || device.isVirtual() || !ControllerManager.isGameController(device)) {
            return false;
        }

        int assignedSlot = controllerManager.getSlotForDeviceOrSibling(deviceId);

        if (virtualExclusiveP1 && isVirtualActive() && assignedSlot == 0) {
            return true; // consume quietly
        }


        // Saved for reference - can re-enable if regex loop is avoided
        // Prompt for inactive/unassigned on DOWN
//        if (isDown
//                && ((assignedSlot == -1 || !controllerManager.isSlotEnabled(assignedSlot))
//                && !ignoredGroups.contains(ControllerManager.makePhysicalGroupKey(device))
//                && !ignoredDeviceIds.contains(deviceId))) {
//
//            if (!isShowingAssignDialog) {
//                isShowingAssignDialog = true;
//                activity.runOnUiThread(() -> {
//                    String msg = "This controller is not active. Open assignment menu? (" + device.getName() + ")";
//                    String checkboxMessage = "Don't prompt for this controller again.";
//                    ContentDialog.confirmWithCheckbox(activity, msg, checkboxMessage, result -> {
//                        if (result.confirmed) {
//                            ControllerAssignmentDialog.show(activity);
//                        } else if (result.checkboxChecked) {
//                            ignoredDeviceIds.add(deviceId);
//                        }
//                        isShowingAssignDialog = false;
//                    });
//                });
//            }
//            return true;
//        }

        if (assignedSlot == -1) return false;

        ExternalController controller;
        MappedByteBuffer buffer;

        if (assignedSlot == 0) {
            controller = currentController;
            buffer     = gamepadBuffer;
        } else {
            int idx = assignedSlot - 1;
            controller = extraControllers[idx];
            buffer     = extraGamepadBuffers[idx];
        }

        if (controller == null || controller.getDeviceId() != deviceId) {
            controller = ExternalController.getController(deviceId);
            if (assignedSlot == 0) {
                currentController = controller;
                refreshControllerMappings(); // keep sibling mapping sane
            } else {
                extraControllers[assignedSlot - 1] = controller;
            }
        }
        if (controller == null) return false;

        boolean handled = controller.updateStateFromKeyEvent(event);
        if (handled) {
            // Gyro trigger only for P1
            if (assignedSlot == 0 && keyCode == gyroTriggerButton && event.getRepeatCount() == 0) {
                if (isDown) {
                    isGyroActive = isToggleMode ? !isGyroActive : true;
                } else if (isUp && !isToggleMode) {
                    isGyroActive = false;
                }
            }

            writeStateToMappedBuffer(controller.state, buffer, assignedSlot == 0, /*slot*/assignedSlot);
            if (assignedSlot == 0) sendGamepadState();
        }
        return handled;
    }

    // ========================== UDP helpers =================================

    private void handleRequest(byte requestCode, final int port) {
        switch (requestCode) {
            case RequestCodes.INIT: {
                initReceived = true;

                preferences = PreferenceManager.getDefaultSharedPreferences(activity.getBaseContext());

                // Input/gyro prefs
                gyroEnabled       = preferences.getBoolean("gyro_enabled", false);
                gyroTriggerButton = preferences.getInt("gyro_trigger_button", KeyEvent.KEYCODE_BUTTON_L1);
                isToggleMode      = preferences.getInt("gyro_mode", 0) == 1;
                triggerType = (byte) preferences.getInt("trigger_type", TRIGGER_IS_AXIS);
                gyroToLeftStick   = preferences.getBoolean("gyro_to_left_stick", false);
                virtualExclusiveP1 = preferences.getBoolean("virtual_exclusive_p1", true);

                // Only set xinputDisabled if not set explicitly by Activity
                if (!xinputDisabledInitialized) {
                    xinputDisabled = preferences.getBoolean("xinput_toggle", false);
                }

                // Gyro tuning
                setGyroSensitivityX(preferences.getFloat("gyro_x_sensitivity", 1.0f));
                setGyroSensitivityY(preferences.getFloat("gyro_y_sensitivity", 1.0f));
                setSmoothingFactor (preferences.getFloat("gyro_smoothing", 0.9f));
                setInvertGyroX     (preferences.getBoolean("invert_gyro_x", false));
                setInvertGyroY     (preferences.getBoolean("invert_gyro_y", false));
                setGyroDeadzone    (preferences.getFloat("gyro_deadzone", 0.05f));

                processGyroWithLeftTrigger = preferences.getBoolean("process_gyro_with_left_trigger", false);

                refreshControllerMappings();

                synchronized (actions) { actions.notify(); }
                break;
            }

            case RequestCodes.GET_PROCESS: {
                if (onGetProcessInfoListener == null) return;
                receiveData.position(receiveData.position() + 4);
                int numProcesses = receiveData.getShort();
                int index = receiveData.getShort();
                int pid = receiveData.getInt();
                long memoryUsage = receiveData.getLong();
                int affinityMask = receiveData.getInt();
                boolean wow64 = receiveData.get() == 1;

                byte[] bytes = new byte[32];
                receiveData.get(bytes);
                String name = StringUtils.fromANSIString(bytes);

                onGetProcessInfoListener.onGetProcessInfo(index, numProcesses,
                        new ProcessInfo(pid, name, memoryUsage, affinityMask, wow64));
                break;
            }

            case RequestCodes.GET_GAMEPAD: {
                if (xinputDisabled) return;
                boolean notify = receiveData.get() == 1;

                final ControlsProfile profile = activity.getInputControlsView().getProfile();
                boolean useVirtual = profile != null && profile.isVirtualGamepad();

                if (!useVirtual && (currentController == null || !currentController.isConnected())) {
                    currentController = ExternalController.getController(0);
                    if (currentController != null) currentController.setTriggerType(triggerType);
                }

                final boolean enabled = currentController != null || useVirtual;

                if (enabled && notify) {
                    if (!gamepadClients.contains(port)) gamepadClients.add(port);
                } else {
                    gamepadClients.remove(Integer.valueOf(port));
                }

                addAction(() -> {
                    sendData.rewind();
                    sendData.put(RequestCodes.GET_GAMEPAD);

                    if (enabled) {
                        sendData.putInt(!useVirtual ? currentController.getDeviceId() : profile.id);
                        sendData.put(inputType);

                        String original = useVirtual ? profile.getName() : currentController.getName();
                        byte[] nameBytes = original.getBytes();

                        final int MAX_NAME_LENGTH = 54; // 64 total - 10 already used
                        if (nameBytes.length > MAX_NAME_LENGTH) {
                            Log.w(TAG, "Controller name too long (" + nameBytes.length + " bytes), truncating: " + original);
                            byte[] trimmed = new byte[MAX_NAME_LENGTH];
                            System.arraycopy(nameBytes, 0, trimmed, 0, MAX_NAME_LENGTH);
                            nameBytes = trimmed;
                        }

                        sendData.putInt(nameBytes.length);
                        sendData.put(nameBytes);
                    } else {
                        sendData.putInt(0);
                    }

                    sendPacket(port);
                });
                break;
            }

            case RequestCodes.GET_GAMEPAD_STATE: {
                if (xinputDisabled) return;

                int gamepadId = receiveData.getInt();
                final ControlsProfile profile = activity.getInputControlsView().getProfile();
                boolean useVirtual = profile != null && profile.isVirtualGamepad();
                final boolean enabled = currentController != null || useVirtual;

                if (currentController != null && currentController.getDeviceId() != gamepadId) {
                    currentController = null;
                }

                addAction(() -> {
                    sendData.rewind();
                    sendData.put(RequestCodes.GET_GAMEPAD_STATE);
                    sendData.put((byte) (enabled ? 1 : 0));

                    if (enabled) {
                        sendData.putInt(gamepadId);

                        GamepadState state = useVirtual
                                ? profile.getGamepadState()
                                : currentController.state;

                        // cache for SHM pokes when virtual
                        if (useVirtual && state != null) {
                            lastVirtualState = state;
                            hasVirtualState  = true;
                            // also update gyro arming from the virtual state
                            updateVirtualActivator(state);
                        }

                        // Temporarily apply gyro, write, then restore
                        float oLX = state.thumbLX, oLY = state.thumbLY;
                        float oRX = state.thumbRX, oRY = state.thumbRY;

                        if (gyroEnabled) {
                            if (gyroToLeftStick) {
                                state.thumbLX = Mathf.clamp(oLX + gyroX, -1f, 1f);
                                state.thumbLY = Mathf.clamp(oLY + gyroY, -1f, 1f);
                            } else {
                                state.thumbRX = Mathf.clamp(oRX + gyroX, -1f, 1f);
                                state.thumbRY = Mathf.clamp(oRY + gyroY, -1f, 1f);
                            }
                        }

                        state.writeTo(sendData);

                        // restore
                        state.thumbLX = oLX; state.thumbLY = oLY;
                        state.thumbRX = oRX; state.thumbRY = oRY;
                    }

                    sendPacket(port);
                });
                break;
            }

            case RequestCodes.RELEASE_GAMEPAD: {
                currentController = null;
                gamepadClients.clear();
                break;
            }

            case RequestCodes.CURSOR_POS_FEEDBACK: {
                short x = receiveData.getShort();
                short y = receiveData.getShort();
                XServer xServer = activity.getXServer();
                xServer.pointer.setX(x);
                xServer.pointer.setY(y);
                activity.getXServerView().requestRender();
                break;
            }

            default:
                break;
        }
    }

    public void sendGamepadState() {
        if (!initReceived || gamepadClients.isEmpty() || xinputDisabled) return;

        final ControlsProfile profile = activity.getInputControlsView().getProfile();
        final boolean useVirtual = profile != null && profile.isVirtualGamepad();
        final boolean enabled = currentController != null || useVirtual;

        for (final int port : gamepadClients) {
            addAction(() -> {
                sendData.rewind();
                sendData.put(RequestCodes.GET_GAMEPAD_STATE);
                sendData.put((byte) (enabled ? 1 : 0));

                if (enabled) {
                    sendData.putInt(!useVirtual ? currentController.getDeviceId() : profile.id);

                    GamepadState state = useVirtual ? profile.getGamepadState()
                            : currentController.state;

                    // cache for SHM pokes when virtual
                    if (useVirtual && state != null) {
                        lastVirtualState = state;
                        hasVirtualState  = true;
                        // also update gyro arming from the virtual state
                        updateVirtualActivator(state);
                    }

                    // Temporarily apply gyro offsets, write, then restore
                    float oLX = state.thumbLX, oLY = state.thumbLY;
                    float oRX = state.thumbRX, oRY = state.thumbRY;

                    if (gyroEnabled) {
                        if (gyroToLeftStick) {
                            state.thumbLX = Mathf.clamp(oLX + gyroX, -1f, 1f);
                            state.thumbLY = Mathf.clamp(oLY + gyroY, -1f, 1f);
                        } else {
                            state.thumbRX = Mathf.clamp(oRX + gyroX, -1f, 1f);
                            state.thumbRY = Mathf.clamp(oRY + gyroY, -1f, 1f);
                        }
                    }

                    state.writeTo(sendData);

                    // restore original values
                    state.thumbLX = oLX; state.thumbLY = oLY;
                    state.thumbRX = oRX; state.thumbRY = oRY;
                }

                sendPacket(port);
            });
        }
    }


    public void setXInputDisabled(boolean disabled) {
        this.xinputDisabled = disabled;
        this.xinputDisabledInitialized = true;
        Log.d(TAG, "XInput Disabled set to: " + xinputDisabled);
    }

    // ========================== Rumble ======================================

    private void startRumblePoller() {
        rumblePollerThread = new Thread(() -> {
            long lastTick = 0;
            while (running) {
                // Rumble
                pollSlotRumble(0, gamepadBuffer, currentController);
                for (int i = 0; i < extraGamepadBuffers.length; i++) {
                    pollSlotRumble(i + 1, extraGamepadBuffers[i], extraControllers[i]);
                }

                // Turbo tick
                if (anyTurboEnabled) {
                    // run at ~120 Hz check
                    long now = android.os.SystemClock.uptimeMillis();
                    if (now - lastTick >= TURBO_TICK_MS) {
                        lastTick = now;
                        if (maybeFlipTurboPhase()) {
                            // Re-write SHM for all slots and send UDP once for P1
                            republishForTurboPhase();
                        }
                    }
                }

                try { Thread.sleep(5); } catch (InterruptedException ignored) { break; }
            }
        });
        rumblePollerThread.start();
    }


    private void pollSlotRumble(int slot, MappedByteBuffer buf, ExternalController ctrl) {
        if (buf == null) return;
        if (!controllerManager.isSlotEnabled(slot) || !controllerManager.isVibrationEnabled(slot)) return;

        short low = buf.getShort(32);
        short high = buf.getShort(34);

        if (low == lastLow[slot] && high == lastHigh[slot]) return;
        lastLow[slot] = low; lastHigh[slot] = high;

        if (low == 0 && high == 0) {
            stopVibration(slot, ctrl);
        } else {
            startVibration(slot, ctrl, low, high);
        }
    }

    private void startVibration(int slot, ExternalController ctrl, short low, short high) {
        int amplitude = Math.max(low & 0xFFFF, high & 0xFFFF);
        if (amplitude <= 0) { stopVibration(slot, ctrl); return; }
        int a = Math.min(255, Math.round(amplitude / 65535f * 254f) + 1);

        // Prefer controller's vibrator
        if (ctrl != null) {
            InputDevice dev = InputDevice.getDevice(ctrl.getDeviceId());
            if (dev != null) {
                Vibrator v = dev.getVibrator();
                if (v != null && v.hasVibrator()) {
                    v.vibrate(VibrationEffect.createOneShot(50, a));
                    return;
                }
            }
        }

        // Optional: fall back to phone vibrator only for slot 0
        if (slot == 0) {
            Vibrator phone = (Vibrator) activity.getSystemService(Context.VIBRATOR_SERVICE);
            if (phone != null && phone.hasVibrator()) {
                float curved = (float) Math.pow(a / 255f, 0.6f);
                int pa = Math.max(0, Math.min(255, Math.round(curved * 255f)));
                if (pa > 0) phone.vibrate(VibrationEffect.createOneShot(50, pa));
            }
        }
    }

    private void stopVibration(int slot, ExternalController ctrl) {
        if (ctrl != null) {
            InputDevice dev = InputDevice.getDevice(ctrl.getDeviceId());
            if (dev != null) {
                Vibrator v = dev.getVibrator();
                if (v != null && v.hasVibrator()) v.cancel();
            }
        }
        if (slot == 0) {
            Vibrator phone = (Vibrator) activity.getSystemService(Context.VIBRATOR_SERVICE);
            if (phone != null) phone.cancel();
        }
    }

    // ========================== SHM writing =================================

    private void writeStateToMappedBuffer(GamepadState src,
                                          MappedByteBuffer buffer,
                                          boolean isP1,
                                          int slotIndex) {
        if (buffer == null || src == null) return;

        buffer.clear();

        // SHM writer: compute final LX/LY/RX/RY based on target
        float lx = src.thumbLX, ly = src.thumbLY;
        float rx = src.thumbRX, ry = src.thumbRY;

        if (isP1 && gyroEnabled) {
            if (gyroToLeftStick) {
                lx = Mathf.clamp(lx + gyroX, -1f, 1f);
                ly = Mathf.clamp(ly + gyroY, -1f, 1f);
            } else {
                rx = Mathf.clamp(rx + gyroX, -1f, 1f);
                ry = Mathf.clamp(ry + gyroY, -1f, 1f);
            }
        }

        buffer.putShort((short) (lx * 32767));
        buffer.putShort((short) (ly * 32767));
        buffer.putShort((short) (rx * 32767));
        buffer.putShort((short) (ry * 32767));

        // Triggers (curved)
        float rawL = Math.max(0f, Math.min(1f, src.triggerL));
        float rawR = Math.max(0f, Math.min(1f, src.triggerR));

        // Buttons & dpad (SDL-style ordering)
        byte[] sdlButtons = new byte[15];
        sdlButtons[0]  = src.isPressed(0)  ? (byte)1 : 0;  // A
        sdlButtons[1]  = src.isPressed(1)  ? (byte)1 : 0;  // B
        sdlButtons[2]  = src.isPressed(2)  ? (byte)1 : 0;  // X
        sdlButtons[3]  = src.isPressed(3)  ? (byte)1 : 0;  // Y
        sdlButtons[9]  = src.isPressed(4)  ? (byte)1 : 0;  // LB
        sdlButtons[10] = src.isPressed(5)  ? (byte)1 : 0;  // RB
        sdlButtons[4]  = src.isPressed(6)  ? (byte)1 : 0;  // Back
        sdlButtons[6]  = src.isPressed(7)  ? (byte)1 : 0;  // Start
        sdlButtons[7]  = src.isPressed(8)  ? (byte)1 : 0;  // LStick
        sdlButtons[8]  = src.isPressed(9)  ? (byte)1 : 0;  // RStick
        sdlButtons[11] = src.dpad[0]       ? (byte)1 : 0;  // Up
        sdlButtons[12] = src.dpad[2]       ? (byte)1 : 0;  // Down
        sdlButtons[13] = src.dpad[3]       ? (byte)1 : 0;  // Left
        sdlButtons[14] = src.dpad[1]       ? (byte)1 : 0;  // Right

        // apply turbo
        applyTurboMask(slotIndex, sdlButtons);

        // Note trigger gating uses the phase:
        if (!turboPhaseOn && slotIndex >= 0 && slotIndex < includeTriggers.length && includeTriggers[slotIndex]) {
            rawL = 0f; rawR = 0f;
        }

        // Curve & write triggers after potential gating
        float lCurve = (float) Math.sqrt(rawL);
        float rCurve = (float) Math.sqrt(rawR);
        int lAxis = Math.round(lCurve * 65_534f) - 32_767;
        int rAxis = Math.round(rCurve * 65_534f) - 32_767;
        buffer.putShort((short) lAxis);
        buffer.putShort((short) rAxis);

        buffer.put(sdlButtons);
        buffer.put((byte) 0); // HAT ignored
    }

    /** Virtual gamepad writes directly to P1 buffer, caches last state for gyro-only updates. */
    public void sendVirtualGamepadState(GamepadState state) {
        if (gamepadBuffer == null || state == null) return;
        lastVirtualState = state;
        hasVirtualState = true;
        writeStateToMappedBuffer(state, gamepadBuffer, true, /*slot*/0);
        updateVirtualActivator(state); // keep gyroActive in sync with virtual presses
    }

    // ========================== Controller mapping ==========================

    public void refreshControllerMappings() {
        Log.d(TAG, "Refreshing controller assignments from settings...");

        currentController = null;
        for (int i = 0; i < extraControllers.length; i++) extraControllers[i] = null;

        controllerManager.scanForDevices();

        // P1
        if (virtualExclusiveP1 && isVirtualActive()) {
            currentController = null; // ensure no physical P1 is kept
        } else {
            InputDevice p1Device = controllerManager.getAssignedDeviceForSlot(0);
            if (p1Device != null) {
                currentController = ExternalController.getController(p1Device.getId());
                if (currentController != null) {
                    currentController.setContext(activity);
                    currentController.setTriggerType(triggerType);
                    Log.i(TAG, "Initialized Player 1 with: " + p1Device.getName());
                }
            }
        }

        // P2..P4
        for (int i = 0; i < extraControllers.length; i++) {
            InputDevice dev = controllerManager.getAssignedDeviceForSlot(i + 1);
            if (dev != null) {
                extraControllers[i] = ExternalController.getController(dev.getId());
                Log.i(TAG, "Initialized Player " + (i + 2) + " with: " + dev.getName());
            }
        }

        loadTurboPrefsForAllSlots();
    }

    public void ensureP1Controller() {
        // If a virtual profile is active, do not resurrect a physical fallback.
        final ControlsProfile profile = activity.getInputControlsView().getProfile();
        if (profile != null && profile.isVirtualGamepad()) {
            currentController = null;
            return;
        }

        if (currentController != null) return;
        InputDevice p1 = controllerManager.getAssignedDeviceForSlot(0);
        if (p1 != null) currentController = ExternalController.getController(p1.getId());
        if (currentController == null) currentController = ExternalController.getController(0);
    }

    public void clearIgnoredDevices() {
        ignoredDeviceIds.clear();
    }

    // ========================== Exec / process mgmt =========================

    private boolean sendPacket(int port) {
        try {
            int size = sendData.position();
            if (size == 0) return false;
            sendPacket.setAddress(localhost);
            sendPacket.setPort(port);
            socket.send(sendPacket);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private void addAction(Runnable action) {
        synchronized (actions) {
            actions.add(action);
            actions.notify();
        }
    }

    private void startSendThread() {
        Executors.newSingleThreadExecutor().execute(() -> {
            while (running) {
                synchronized (actions) {
                    while (initReceived && !actions.isEmpty()) actions.poll().run();
                    try { actions.wait(); } catch (InterruptedException ignored) {}
                }
            }
        });
    }

    public void exec(String command) {
        command = command.trim();
        if (command.isEmpty()) return;

        String filename;
        String parameters;

        if (command.contains("\"")) {
            int firstQuote = command.indexOf("\"");
            int lastQuote  = command.lastIndexOf("\"");
            filename = command.substring(firstQuote + 1, lastQuote);
            parameters = (lastQuote + 1 < command.length()) ? command.substring(lastQuote + 1).trim() : "";
        } else {
            String[] cmdList = command.split(" ", 2);
            filename   = cmdList[0];
            parameters = (cmdList.length > 1) ? cmdList[1] : "";
        }

        addAction(() -> {
            byte[] filenameBytes   = filename.getBytes();
            byte[] parametersBytes = parameters.getBytes();

            sendData.rewind();
            sendData.put(RequestCodes.EXEC);
            sendData.putInt(filenameBytes.length + parametersBytes.length + 8);
            sendData.putInt(filenameBytes.length);
            sendData.putInt(parametersBytes.length);
            sendData.put(filenameBytes);
            sendData.put(parametersBytes);
            sendPacket(CLIENT_PORT);
        });
    }

    public void killProcess(final String processName) {
        addAction(() -> {
            sendData.rewind();
            sendData.put(RequestCodes.KILL_PROCESS);
            byte[] bytes = processName.getBytes();
            sendData.putInt(bytes.length);
            sendData.put(bytes);
            sendPacket(CLIENT_PORT);
        });
    }

    public void listProcesses() {
        addAction(() -> {
            sendData.rewind();
            sendData.put(RequestCodes.LIST_PROCESSES);
            sendData.putInt(0);

            if (!sendPacket(CLIENT_PORT) && onGetProcessInfoListener != null) {
                onGetProcessInfoListener.onGetProcessInfo(0, 0, null);
            }
        });
    }

    public void setProcessAffinity(final String processName, final int affinityMask) {
        addAction(() -> {
            byte[] bytes = processName.getBytes();
            sendData.rewind();
            sendData.put(RequestCodes.SET_PROCESS_AFFINITY);
            sendData.putInt(9 + bytes.length);
            sendData.putInt(0);
            sendData.putInt(affinityMask);
            sendData.put((byte) bytes.length);
            sendData.put(bytes);
            sendPacket(CLIENT_PORT);
        });
    }

    public void setProcessAffinity(final int pid, final int affinityMask) {
        addAction(() -> {
            sendData.rewind();
            sendData.put(RequestCodes.SET_PROCESS_AFFINITY);
            sendData.putInt(9);
            sendData.putInt(pid);
            sendData.putInt(affinityMask);
            sendData.put((byte) 0);
            sendPacket(CLIENT_PORT);
        });
    }

    public void mouseEvent(int flags, int dx, int dy, int wheelDelta) {
        if (!initReceived) return;
        addAction(() -> {
            sendData.rewind();
            sendData.put(RequestCodes.MOUSE_EVENT);
            sendData.putInt(10);
            sendData.putInt(flags);
            sendData.putShort((short) dx);
            sendData.putShort((short) dy);
            sendData.putShort((short) wheelDelta);
            sendData.put((byte) ((flags & MouseEventFlags.MOVE) != 0 ? 1 : 0)); // cursor pos feedback
            sendPacket(CLIENT_PORT);
        });
    }

    public void keyboardEvent(byte vkey, int flags) {
        if (!initReceived) return;
        addAction(() -> {
            sendData.rewind();
            sendData.put(RequestCodes.KEYBOARD_EVENT);
            sendData.put(vkey);
            sendData.putInt(flags);
            sendPacket(CLIENT_PORT);
        });
    }

    public void bringToFront(final String processName) {
        bringToFront(processName, 0);
    }

    public void bringToFront(final String processName, final long handle) {
        addAction(() -> {
            sendData.rewind();
            try {
                sendData.put(RequestCodes.BRING_TO_FRONT);
                byte[] bytes = processName.getBytes();
                sendData.putInt(bytes.length);
                // Note: ANSI conversion in winhandler.exe can overflow with CJK names; keep guarded
                sendData.put(bytes);
                sendData.putLong(handle);
            } catch (java.nio.BufferOverflowException e) {
                Log.e(TAG, "Buffer overflow in bringToFront", e);
                sendData.rewind();
            }
            sendPacket(CLIENT_PORT);
        });
    }

    // ========================== Misc getters/setters ========================

    public OnGetProcessInfoListener getOnGetProcessInfoListener() { return onGetProcessInfoListener; }
    public void setOnGetProcessInfoListener(OnGetProcessInfoListener l) {
        synchronized (actions) { this.onGetProcessInfoListener = l; }
    }

    public byte getInputType() { return inputType; }
    public void setInputType(byte inputType) { this.inputType = inputType; }

    public ExternalController getCurrentController() { return currentController; }

    public void execWithDelay(String command, int delaySeconds) {
        if (command == null || command.trim().isEmpty() || delaySeconds < 0) return;
        Executors.newSingleThreadScheduledExecutor()
                .schedule(() -> exec(command), delaySeconds, TimeUnit.SECONDS);
    }

    /** Public hook for MacrosDialog to apply changes instantly. */
    public void reloadTurboFromPrefs() {
        loadTurboPrefsForAllSlots();
    }

    // ========================== Helpers =====================================

    private static boolean isSystemNavKey(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_HOME:
            case KeyEvent.KEYCODE_APP_SWITCH:
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_MUTE:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                return true;
            default:
                return false;
        }
    }

    private boolean isLeftTriggerPressed() {
        // Physical P1
        if (currentController != null && currentController.state != null) {
            return currentController.state.triggerL > 0.5f;
        }

        // Virtual P1
        ControlsProfile profile = activity.getInputControlsView().getProfile();
        if (profile != null && profile.isVirtualGamepad()) {
            GamepadState s = profile.getGamepadState();
            return s != null && s.triggerL > 0.5f;
        }

        return false;
    }


    private void loadTurboPrefsForAllSlots() {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(activity);
        boolean any = false;

        for (int slot = 0; slot < MAX_SLOTS; slot++) {
            setTurbo(slot,  0, sp.getBoolean(prefKey(slot,"A"), false));        any |= turboEnabled[slot][0];
            setTurbo(slot,  1, sp.getBoolean(prefKey(slot,"B"), false));        any |= turboEnabled[slot][1];
            setTurbo(slot,  2, sp.getBoolean(prefKey(slot,"X"), false));        any |= turboEnabled[slot][2];
            setTurbo(slot,  3, sp.getBoolean(prefKey(slot,"Y"), false));        any |= turboEnabled[slot][3];
            setTurbo(slot,  9, sp.getBoolean(prefKey(slot,"LB"), false));       any |= turboEnabled[slot][9];
            setTurbo(slot, 10, sp.getBoolean(prefKey(slot,"RB"), false));       any |= turboEnabled[slot][10];
            setTurbo(slot,  4, sp.getBoolean(prefKey(slot,"Back"), false));     any |= turboEnabled[slot][4];
            setTurbo(slot,  6, sp.getBoolean(prefKey(slot,"Start"), false));    any |= turboEnabled[slot][6];
            setTurbo(slot,  7, sp.getBoolean(prefKey(slot,"L3"), false));       any |= turboEnabled[slot][7];
            setTurbo(slot,  8, sp.getBoolean(prefKey(slot,"R3"), false));       any |= turboEnabled[slot][8];
            setTurbo(slot, 11, sp.getBoolean(prefKey(slot,"DpadUp"), false));   any |= turboEnabled[slot][11];
            setTurbo(slot, 12, sp.getBoolean(prefKey(slot,"DpadDown"), false)); any |= turboEnabled[slot][12];
            setTurbo(slot, 13, sp.getBoolean(prefKey(slot,"DpadLeft"), false)); any |= turboEnabled[slot][13];
            setTurbo(slot, 14, sp.getBoolean(prefKey(slot,"DpadRight"), false));any |= turboEnabled[slot][14];

            includeTriggers[slot] = sp.getBoolean(prefKey(slot, "IncludeTriggers"), false);
            any |= includeTriggers[slot];
        }

        anyTurboEnabled = any;
    }


    private static String prefKey(int slot, String logical) {
        return com.winlator.cmod.contentdialog.MacrosDialog.PREF_PREFIX
                + "p" + (slot + 1) + "_" + logical;
    }

    private void setTurbo(int slot, int buttonIndex, boolean enabled) {
        if (slot < 0 || slot >= MAX_SLOTS || buttonIndex < 0 || buttonIndex >= BUTTON_COUNT) return;
        turboEnabled[slot][buttonIndex] = enabled;
    }

    /**
     * Flips global turbo phase if needed for this slot and applies mask to sdlButtons.
     * @return current phase (true = on-phase, presses pass through).
     */
    private boolean updateTurboPhaseAndApply(int slot, byte[] sdlButtons) {
        if (slot < 0 || slot >= MAX_SLOTS) return true;

        long now = android.os.SystemClock.uptimeMillis();
        long last = lastTurboTick[slot];
        if (now - last >= TURBO_PERIOD_MS) {
            turboPhaseOn = !turboPhaseOn; // shared phase flip
            lastTurboTick[slot] = now;
        }

        if (!turboPhaseOn) {
            boolean[] mask = turboEnabled[slot];
            for (int i = 0; i < BUTTON_COUNT && i < sdlButtons.length; i++) {
                if (mask[i] && sdlButtons[i] != 0) {
                    sdlButtons[i] = 0; // off-phase => drop press
                }
            }
        }
        return turboPhaseOn;
    }

    /** Returns true if phase flipped. */
    private boolean maybeFlipTurboPhase() {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - turboLastFlipMs >= TURBO_PERIOD_MS) {
            turboPhaseOn = !turboPhaseOn;
            turboLastFlipMs = now;
            return true;
        }
        return false;
    }

    private void applyTurboMask(int slot, byte[] sdlButtons) {
        if (slot < 0 || slot >= MAX_SLOTS) return;
        if (turboPhaseOn) return; // on-phase => pass through

        boolean[] mask = turboEnabled[slot];
        for (int i = 0; i < BUTTON_COUNT && i < sdlButtons.length; i++) {
            if (mask[i] && sdlButtons[i] != 0) {
                sdlButtons[i] = 0; // off-phase => drop press
            }
        }
    }

    private void republishForTurboPhase() {
        // P1 => SHM + UDP
        if (gamepadBuffer != null) {
            final ControlsProfile profile = activity.getInputControlsView().getProfile();
            boolean useVirtual = profile != null && profile.isVirtualGamepad();

            if (useVirtual && profile != null) {
                lastVirtualState = profile.getGamepadState();
                hasVirtualState = true;
                writeStateToMappedBuffer(lastVirtualState, gamepadBuffer, true, 0);
            } else if (currentController != null) {
                writeStateToMappedBuffer(currentController.state, gamepadBuffer, true, 0);
            }
        }

        // P2..P4
        for (int i = 0; i < extraGamepadBuffers.length; i++) {
            if (extraGamepadBuffers[i] != null && extraControllers[i] != null) {
                writeStateToMappedBuffer(extraControllers[i].state, extraGamepadBuffers[i], false, i + 1);
            }
        }

        // UDP for connected clients (P1)
        sendGamepadState();
    }

    private void updateVirtualActivator(GamepadState s) {
        if (s == null) return;
        boolean pressed = isVirtualActivatorPressed(s);

        if (isToggleMode) {
            if (pressed && !lastVirtualActivatorPressed) {
                isGyroActive = !isGyroActive;
            }
        } else {
            isGyroActive = pressed;
        }
        lastVirtualActivatorPressed = pressed;
    }

    private boolean isVirtualActivatorPressed(GamepadState s) {
        switch (gyroTriggerButton) {
            case KeyEvent.KEYCODE_BUTTON_A:      return s.isPressed(0);
            case KeyEvent.KEYCODE_BUTTON_B:      return s.isPressed(1);
            case KeyEvent.KEYCODE_BUTTON_X:      return s.isPressed(2);
            case KeyEvent.KEYCODE_BUTTON_Y:      return s.isPressed(3);
            case KeyEvent.KEYCODE_BUTTON_L1:     return s.isPressed(4); // LB
            case KeyEvent.KEYCODE_BUTTON_R1:     return s.isPressed(5); // RB
            case KeyEvent.KEYCODE_BUTTON_SELECT:
            case KeyEvent.KEYCODE_BACK:          return s.isPressed(6); // Back
            case KeyEvent.KEYCODE_BUTTON_START:  return s.isPressed(7); // Start
            case KeyEvent.KEYCODE_BUTTON_THUMBL: return s.isPressed(8); // L3
            case KeyEvent.KEYCODE_BUTTON_THUMBR: return s.isPressed(9); // R3
            case KeyEvent.KEYCODE_BUTTON_L2:     return s.triggerL > 0.5f;
            case KeyEvent.KEYCODE_BUTTON_R2:     return s.triggerR > 0.5f;
            default:                             return false;
        }
    }

    private boolean isVirtualActive() {
        final com.winlator.cmod.inputcontrols.ControlsProfile p =
                activity.getInputControlsView().getProfile();
        return p != null && p.isVirtualGamepad();
    }

}
