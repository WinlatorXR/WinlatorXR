package com.winlator.cmod.xserver.extensions;

import static com.winlator.cmod.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import android.util.SparseArray;

import com.winlator.cmod.renderer.GPUImage;
import com.winlator.cmod.renderer.Texture;
import com.winlator.cmod.xconnector.XInputStream;
import com.winlator.cmod.xconnector.XOutputStream;
import com.winlator.cmod.xconnector.XStreamLock;
import com.winlator.cmod.xserver.Bitmask;
import com.winlator.cmod.xserver.Drawable;
import com.winlator.cmod.xserver.Pixmap;
import com.winlator.cmod.xserver.Window;
import com.winlator.cmod.xserver.XClient;
import com.winlator.cmod.xserver.XLock;
import com.winlator.cmod.xserver.XServer;
import com.winlator.cmod.xserver.errors.BadImplementation;
import com.winlator.cmod.xserver.errors.BadMatch;
import com.winlator.cmod.xserver.errors.BadPixmap;
import com.winlator.cmod.xserver.errors.BadWindow;
import com.winlator.cmod.xserver.errors.XRequestError;
import com.winlator.cmod.xserver.events.PresentCompleteNotify;
import com.winlator.cmod.xserver.events.PresentIdleNotify;

import java.io.IOException;

public class PresentExtension implements Extension {
    public static final byte MAJOR_OPCODE = -103;
    private static final int FAKE_INTERVAL = 1000000 / 72;
    private static final long WAIT_FENCE_TIMEOUT_MS = 33;
    public enum Kind {PIXMAP, MSC_NOTIFY}
    public enum Mode {COPY, FLIP, SKIP}
    private final SparseArray<Event> events = new SparseArray<>();
    private final SparseArray<PendingIdle> pendingIdles = new SparseArray<>();
    private SyncExtension syncExtension;

    private static class PendingIdle {
        private Window window;
        private Pixmap pixmap;
        private int serial;
        private int idleFence;
    }

    private static abstract class ClientOpcodes {
        private static final byte QUERY_VERSION = 0;
        private static final byte PRESENT_PIXMAP = 1;
        private static final byte SELECT_INPUT = 3;
    }

    private static class Event {
        private Window window;
        private XClient client;
        private int id;
        private Bitmask mask;
    }

    @Override
    public String getName() {
        return "Present";
    }

    @Override
    public byte getMajorOpcode() {
        return MAJOR_OPCODE;
    }

    @Override
    public byte getFirstErrorId() {
        return 0;
    }

    @Override
    public byte getFirstEventId() {
        return 0;
    }

    private void sendIdleNotify(Window window, Pixmap pixmap, int serial, int idleFence) {
        if (idleFence != 0) syncExtension.setTriggered(idleFence);

        synchronized (events) {
            for (int i = 0; i < events.size(); i++) {
                Event event = events.valueAt(i);
                if (event.window == window && event.mask.isSet(PresentIdleNotify.getEventMask())) {
                    event.client.sendEvent(new PresentIdleNotify(event.id, window, pixmap, serial, idleFence));
                }
            }
        }
    }

    private void sendCompleteNotify(Window window, int serial, Kind kind, Mode mode, long ust, long msc) {
        synchronized (events) {
            for (int i = 0; i < events.size(); i++) {
                Event event = events.valueAt(i);
                if (event.window == window && event.mask.isSet(PresentCompleteNotify.getEventMask())) {
                    event.client.sendEvent(new PresentCompleteNotify(event.id, window, serial, kind, mode, ust, msc));
                }
            }
        }
    }

    private static void queryVersion(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        inputStream.skip(8);

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeInt(1);
            outputStream.writeInt(0);
            outputStream.writePad(16);
        }
    }

    private void presentPixmap(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int windowId = inputStream.readInt();
        int pixmapId = inputStream.readInt();
        int serial = inputStream.readInt();
        inputStream.skip(8);
        short xOff = inputStream.readShort();
        short yOff = inputStream.readShort();
        inputStream.skip(4); // target-crtc, unused
        int waitFence = inputStream.readInt();
        int idleFence = inputStream.readInt();
        inputStream.skip(client.getRemainingRequestLength());

        final Window window = client.xServer.windowManager.getWindow(windowId);
        if (window == null) throw new BadWindow(windowId);

        final Pixmap pixmap = client.xServer.pixmapManager.getPixmap(pixmapId);
        if (pixmap == null) throw new BadPixmap(pixmapId);

        Drawable content = window.getContent();
        if (content.visual.depth != pixmap.drawable.visual.depth) throw new BadMatch();

        // The client's GPU write to this pixmap may still be in flight. Waiting on the
        // real wait-fence here (instead of discarding it) is what makes a zero-copy flip
        // safe below - without it the GL consumer can sample a buffer before the producer's
        // write to it has actually completed on the GPU.
        if (syncExtension != null) syncExtension.awaitFenceById(waitFence, WAIT_FENCE_TIMEOUT_MS);

        boolean canFlip = xOff == 0 && yOff == 0
                && pixmap.drawable.width == content.width && pixmap.drawable.height == content.height
                && content.getTexture() instanceof GPUImage && pixmap.drawable.getTexture() instanceof GPUImage;

        long ust = System.nanoTime() / 1000;
        long msc = ust / FAKE_INTERVAL;

        synchronized (content.renderLock) {
            if (canFlip) {
                // Alias content directly onto the pixmap's own buffer - do NOT reassign
                // pixmap.drawable's texture. The client keeps writing into this same
                // physical buffer across every future reuse of this pixmap id; the server
                // must never repoint what buffer a pixmap id refers to, only what the
                // window's content currently displays.
                content.setTexture(pixmap.drawable.getTexture());
                content.getTexture().setNeedsUpdate(true);

                sendCompleteNotify(window, serial, Kind.PIXMAP, Mode.FLIP, ust, msc);

                // Defer idle-notify by one present cycle: `content` was still aliased onto
                // the *previous* pixmap's buffer up until the line above, so that buffer
                // isn't safe to hand back to the client until the compositor has had a full
                // cycle to finish consuming it (i.e. until this window presents again).
                PendingIdle previous = pendingIdles.get(windowId);
                if (previous != null) sendIdleNotify(previous.window, previous.pixmap, previous.serial, previous.idleFence);

                PendingIdle current = new PendingIdle();
                current.window = window;
                current.pixmap = pixmap;
                current.serial = serial;
                current.idleFence = idleFence;
                pendingIdles.put(windowId, current);
            } else {
                content.copyArea((short)0, (short)0, xOff, yOff, pixmap.drawable.width, pixmap.drawable.height, pixmap.drawable);
                sendIdleNotify(window, pixmap, serial, idleFence);
                sendCompleteNotify(window, serial, Kind.PIXMAP, Mode.COPY, ust, msc);
            }
        }
    }

    private void selectInput(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int eventId = inputStream.readInt();
        int windowId = inputStream.readInt();
        Bitmask mask = new Bitmask(inputStream.readInt());

        Window window = client.xServer.windowManager.getWindow(windowId);
        if (window == null) throw new BadWindow(windowId);

        if (GPUImage.isSupported() && !mask.isEmpty()) {
            Drawable content = window.getContent();
            final Texture oldTexture = content.getTexture();
            client.xServer.getRenderer().xServerView.queueEvent(oldTexture::destroy);
            content.setTexture(new GPUImage(content.width, content.height));
        }

        synchronized (events) {
            Event event = events.get(eventId);
            if (event != null) {
                if (event.window != window || event.client != client) throw new BadMatch();

                if (!mask.isEmpty()) {
                    event.mask = mask;
                }
                else events.remove(eventId);
            }
            else {
                event = new Event();
                event.id = eventId;
                event.window = window;
                event.client = client;
                event.mask = mask;
                events.put(eventId, event);
            }
        }
    }

    @Override
    public void handleRequest(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int opcode = client.getRequestData();
        if (syncExtension == null) syncExtension = client.xServer.getExtension(SyncExtension.MAJOR_OPCODE);

        switch (opcode) {
            case ClientOpcodes.QUERY_VERSION :
                queryVersion(client, inputStream, outputStream);
                break;
            case ClientOpcodes.PRESENT_PIXMAP:
                try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.PIXMAP_MANAGER)) {
                    presentPixmap(client, inputStream, outputStream);
                }
                break;
            case ClientOpcodes.SELECT_INPUT:
                try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
                    selectInput(client, inputStream, outputStream);
                }
                break;
            default:
                throw new BadImplementation();
        }
    }
}
