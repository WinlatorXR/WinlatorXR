# DXVK interop probe

A throwaway diagnostic, not a shipped component. It answers one question:

> Does the DXVK we ship expose enough Vulkan interop for a Windows-side XR
> runtime to hand the Android compositor a game's rendered image without a
> CPU copy?

That question gates everything in GameNative's
`docs/xr/native-openvr-runtime-plan.md` approach. Nothing else is worth
designing until it is answered on real hardware.

## What it checks

| # | Check | Why it matters |
|---|---|---|
| 1 | `ntdll!__wine_unix_call_dispatcher` | The PE→native transport a Wine-side runtime would call through, instead of a socket. Absent means no unixlib path. |
| 2 | `d3d11.dll` identity + version | WineD3D exposes no interop at all; only DXVK does. |
| 3 | `IDXGIVkInteropDevice` / `...Device1` | Yields `VkInstance`, `VkPhysicalDevice`, `VkDevice`, submission queue + family/index. |
| 4 | vtable **slot 10** (`CreateTexture2DFromVkImage`) | The native-allocates / PE-wraps design depends on it. **Upstream DXVK's vtable ends at slot 9**, so this only exists on a patched DXVK. |
| 5 | `IDXGIVkInteropSurface::GetVulkanImageInfo` | The stock, unpatched path: the `VkImage` behind any `ID3D11Texture2D`, plus its real `VkImageCreateInfo` (usage flags included). |
| 6 | External-memory / semaphore entry points on DXVK's `VkDevice` | Physical-device *support* is not enough. If DXVK did not enable the extension at `vkCreateDevice`, the entry point does not resolve — `vkGetDeviceProcAddr` is the only honest test. |
| 7 | **Round trip**: allocate a `VkImage`, wrap it through slot 10, read the handle back off the texture | The one that decides the zero-copy design. Check 4 only proves slot 10 takes a descriptor and returns an object describing it — it is reached by passing `VkImage 0` and getting `S_OK`, so it says nothing about whether the image argument is honoured. Only a matching handle proves a natively allocated image can be the surface a game renders into. |

It also does a plain PE-side `vkCreateImage` on DXVK's `VkDevice` to confirm
winevulkan lets the Windows side drive that device at all.

The probe renders nothing, writes nothing into the prefix beyond its log, and
creates its own throwaway D3D11 device — it does not touch a running game's.

### The slot-10 gamble

Checks 4 and 5 are alternatives, and which one answers decides the design:

- **Slot 10 present** → native side allocates the `VkImage`, PE side wraps it as
  an `ID3D11Texture2D`, and the game renders straight into the headset
  swapchain image. This is what GameNative does, and it means shipping a
  patched DXVK.
- **Slot 10 absent, surface interop present** → we can only go the other
  direction: take the `VkImage` the game already rendered into and export it.
  No DXVK patch, but the sharing then depends entirely on check 6.

Slot 10 cannot simply be called to find out: reading past a nine-slot vtable
lands in whatever `.rdata` follows it. So by default the probe *inspects* the
pointer (committed? executable? same module as slot 3?) and reports a verdict
without calling. `--try-create` opts into an actual guarded call, and needs an
SEH-capable build (llvm-mingw or MSVC — mingw-w64's GCC has no `__try`).

Worth knowing before trusting a positive: GameNative's own two copies of this
declaration disagree on the signature — `gamenative_dxvk.c` has three arguments,
the newer `DrvGameNative.cpp` has four. The probe calls the four-argument form.

## Build

```sh
LLVM_MINGW=/path/to/llvm-mingw ./build.sh
```

llvm-mingw is preferred: it covers `arm64ec` (needed for the
`proton-9.0-arm64ec` containers) and implements SEH. Plain mingw-w64 builds
x86_64 only and degrades `--try-create` to inspection-only.

Output lands in `build/`, with the architecture in the filename rather than in a
parent directory — the device side is flat (drive D is one Downloads folder), so
identically-named builds would collide there. Match the DLL to the container's
Wine build:

| Container | Use |
|---|---|
| `proton-9.0-x86_64` | `dxvk_interop_probe-x86_64.dll` (runs under FEX) |
| `proton-9.0-arm64ec` | `dxvk_interop_probe-arm64ec.dll` (runs natively) |

The `aarch64` build is pure ARM64 rather than arm64ec and will not load
alongside emulated x86 code; it is built only for completeness.

Each build writes its own `wxr_dxvk_probe-<arch>.log` for the same reason, so
probing two containers of different architectures does not overwrite one run
with the other.

## Run

Put the DLLs in the device's `Download` folder. `Container.DEFAULT_DRIVES` maps
`D:` there, so they show up as `D:\dxvk_interop_probe-<arch>.dll` inside the
container with no root and no adb. Every architecture can live there at once.
That is the only manual step — the app finds them from there.

**Standalone** — its own D3D11 device, no game involved. In the app, open the
Containers tab, tap a container's menu, and pick **Run DXVK Interop Probe**
(`DxvkProbeRunner`). It picks the arch-matched DLL from the container's Wine
version, warns if the DX wrapper is not DXVK, runs the probe through
`GuestScriptRunner`, and closes the container when it finishes.

There is no environment-variable route. `XServerDisplayActivity` does read an
`EXTRA_EXEC_ARGS` key, but only from `overrideEnvVars`, which nothing populates
from container settings — a container's own env vars go the other way, into the
guest process. A shortcut's `[Extra Data] execArgs` is the only thing that
reaches the launch command line, and `EnvVars.putAll` splits on spaces anyway,
so a value with arguments in it could not survive as an env var regardless.

**Inside a real game** — set `WXR_DXVK_PROBE=1` in the shortcut's Extra Data
`envVars` and get the DLL loaded into the process (`AppInit_DLLs`, or a
forwarding `dxgi.dll` proxy alongside the exe). On `DLL_PROCESS_ATTACH` it
spawns a worker thread rather than probing on the loader lock. Set
`WXR_DXVK_PROBE=try-create` to include check 4's live call.

### Output

Three destinations, so at least one is always reachable:

- `D:\wxr_dxvk_probe-<arch>.log` — i.e. in `Download/`, the one to copy off the
  device. Falls back to `C:\` if D: is unmapped; `WXR_DXVK_PROBE_LOG` overrides.
  (`C:` lives under `/data/data/` and needs root to read.)
- The in-container **Logs** dialog, via stdout.
- `OutputDebugStringA`, for a debugger.

## Reading the output

Three verdict lines carry the answer:

```
  verdict: ... IDXGIVkInteropDevice ...        <- is this DXVK at all
  verdict: ... slot 10 ...                     <- patched DXVK needed?
  verdict: ... VkImage IS reachable ...        <- stock path viable?
```

then the `entry points on DXVK's VkDevice` block. If every external-memory
entry point there says `absent`, neither design works without rebuilding DXVK
with those extensions enabled, whatever the verdicts above said.
