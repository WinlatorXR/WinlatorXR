package com.winlator.cmod.core;

import android.app.Activity;
import android.os.Environment;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.contentdialog.ContentDialog;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs tools/dxvk_interop_probe inside a container.
 *
 * The probe reports whether the container's DXVK exposes enough Vulkan interop for a
 * Windows-side XR runtime to share rendered images with the Android compositor without a CPU
 * copy. It is a diagnostic: it renders nothing, changes nothing, and the container is left
 * exactly as it was.
 *
 * The probe is a DLL rather than an executable so the same binary can later be injected into a
 * real game and inspect that game's D3D11 device. Standalone it needs rundll32, which means
 * passing arguments, and a shortcut's [Extra Data] execArgs is the only path in this app that
 * carries them -- {@link GuestScriptRunner} already builds exactly that, so this class only has
 * to choose the right binary and hand over a one-line script.
 */
public abstract class DxvkProbeRunner {
    /** Where the probe DLLs are expected, matching {@link Container#DEFAULT_DRIVES}' D: mapping. */
    private static final String PROBE_PREFIX = "dxvk_interop_probe-";
    private static final String PROBE_SUFFIX = ".dll";

    /**
     * The probe is built per architecture and the log it writes is named to match, so probing an
     * arm64ec and an x86_64 container in turn leaves two logs rather than one overwriting the
     * other. Both live in Downloads, which is what D: maps to.
     */
    private static String archOf(Container container) {
        String wineVersion = container.getWineVersion();
        // WineInfo's identifier pattern ends in the architecture; arm64ec is the only one of the
        // 64-bit pair that runs natively, everything else here goes through emulation as x86_64.
        return wineVersion != null && wineVersion.contains("arm64ec") ? "arm64ec" : "x86_64";
    }

    private static File probeFile(String arch) {
        File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        return new File(downloads, PROBE_PREFIX + arch + PROBE_SUFFIX);
    }

    /**
     * @param tryCreate also call the patched-DXVK-only CreateTexture2DFromVkImage slot. Off by
     *                  default: on a stock DXVK that vtable slot does not exist, and while the
     *                  probe guards the call, there is no reason to make it until the inspection
     *                  pass says the slot might be real.
     */
    public static void run(Activity activity, Container container, boolean tryCreate) {
        String arch = archOf(container);
        File probe = probeFile(arch);

        if (!probe.isFile()) {
            ContentDialog.alert(activity,
                "Put " + probe.getName() + " in the Downloads folder first.\n\n"
                    + "This container runs " + arch + ", and the probe has to match. "
                    + "Build it with tools/dxvk_interop_probe/build.sh.", null);
            return;
        }

        // Not fatal: the probe reports the wrapper it finds either way, and being told plainly
        // that WineD3D has no interop is itself a valid result. But it is almost always a
        // mis-set container rather than an intended run, so it is worth a word first.
        String wrapper = container.getDXWrapper();
        if (wrapper != null && !wrapper.equals("dxvk") && !wrapper.equals("vkd3d")) {
            ContentDialog.confirm(activity,
                "This container's DirectX emulation is set to \"" + wrapper + "\", not DXVK.\n\n"
                    + "The probe will report no interop at all, which tells you nothing about "
                    + "DXVK. Run it anyway?",
                () -> launch(activity, container, arch, tryCreate));
            return;
        }

        launch(activity, container, arch, tryCreate);
    }

    /**
     * Names the run inside the log.
     *
     * The point of running the probe more than once is to compare one DXVK build or graphics
     * driver against another, and nothing in the log identifies which was in use -- the probe
     * reports the driver Vulkan hands it, but not the container settings that chose it. Without
     * this every run in the appended log looks alike.
     */
    private static String tagFor(Container container) {
        StringBuilder sb = new StringBuilder();
        sb.append(container.getName());
        sb.append(" | dx=").append(container.getDXWrapper());

        // dxwrapperConfig is a flat "key=value,key=value" list; the DXVK build is the part that
        // distinguishes two runs of the same wrapper.
        String config = container.getDXWrapperConfig();
        if (config != null) {
            for (String part : config.split(",")) {
                if (part.startsWith("version=")) {
                    sb.append("-").append(part.substring("version=".length()));
                    break;
                }
            }
        }

        sb.append(" | gfx=").append(container.getGraphicsDriver());
        String driverConfig = container.getGraphicsDriverConfig();
        if (driverConfig != null && !driverConfig.isEmpty()) sb.append("-").append(driverConfig);

        sb.append(" | wine=").append(container.getWineVersion());
        return sb.toString();
    }

    private static void launch(Activity activity, Container container, String arch, boolean tryCreate) {
        String dll = "D:\\" + PROBE_PREFIX + arch + PROBE_SUFFIX;

        List<String> body = new ArrayList<>();
        // Set in the script rather than passed as a session env var: EnvVars splits on spaces,
        // so a readable tag could not survive that route.
        //
        // The quoted set form is what makes the tag's separators survive: unquoted, cmd reads
        // the "|" between fields as a pipe and everything after the container name is parsed as
        // a command instead of being assigned. Quotes make |, &, < and > literal; % is expanded
        // even inside them, and a quote of its own would close the assignment early, so those
        // two go.
        String tag = tagFor(container).replace("%", "").replace("\"", "");
        body.add("set \"WXR_DXVK_PROBE_TAG=" + tag + "\"");

        // When device creation fails, DXVK knows exactly which Vulkan feature it wanted and did
        // not get, and says so in its own log -- far better than inferring it from an E_FAIL.
        // Pointed at D: so the log lands in Downloads beside the probe's, readable without root.
        body.add("set \"DXVK_LOG_LEVEL=info\"");
        body.add("set \"DXVK_LOG_PATH=D:\\\"");
        // rundll32's entry point takes "<dll>,<export>" as a single unspaced argument; anything
        // after it is the command line the export receives.
        body.add("rundll32.exe " + dll + ",Probe" + (tryCreate ? " --try-create" : ""));
        // The probe writes its own log to D: as well, but echoing the location beats making
        // someone go looking for it after the console has closed.
        body.add("echo Probe log: D:\\wxr_dxvk_probe-" + arch + ".log");

        GuestScriptRunner.run(activity, container, "DXVK interop probe (" + arch + ")",
            "dxvkprobe", body);
    }
}
