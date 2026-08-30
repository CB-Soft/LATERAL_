# LATERAL_ architecture charter

**Status:** canonical design authority for the v0.2 implementation plan
**Scope:** the target-hardware dual-display workspace, its shared state model, and
the boundaries around platform-specific hosting

This document normalizes the original `LATERAL_ Outline.md` into repository
invariants. It describes the intended architecture; individual implementation
pieces may still be incomplete or constrained by Android and device firmware.

## Product boundary

LATERAL_ is a device-specific workspace for a Nubia NX789J phone and a VITURE
Beast external display. It is not a general-purpose Android desktop or a
freeform window manager.

The target geometry is a wide, horizontal Beast canvas (initially 3840 x 1200)
with existing Android tasks as the units of work. The phone is an auxiliary
controller and configuration surface; the Beast is the workspace where app
content is presented.

## Non-negotiable invariants

1. **Two coordinated surfaces, one workspace.** PhoneUI and BeastUI are two
   views of one workspace state. They must not maintain competing task order,
   focus, presentation, or viewport state.
2. **The Beast is the workspace; the phone is the controller.** BeastUI owns
   the visual task strip and app content. PhoneUI owns touchpad, navigation,
   keyboard, settings, and coarse-grained commands. Controls should not be
   duplicated without a clear mouse/keyboard use case.
3. **Android task ID is task identity.** Package name is not a unique task key.
   Multiple instances of one package remain distinct and closing a task acts on
   that exact Android task.
4. **The workspace is one-dimensional.** Normal tasks have a logical x slot,
   share one workspace top and height, and do not have meaningful independent
   y positions. The logical strip is effectively unbounded; the viewport is
   only the visible window onto it.
5. **Presentation is semantic.** A task is presented as `PHONE`, `TABLET`, or
   `FULLSCREEN`. There is no drag-resize model and no general-purpose freeform
   window placement.
6. **Fullscreen preserves order.** Fullscreen temporarily removes task chrome
   and visual participation from the strip, but exiting fullscreen restores the
   task to its previous logical position.
7. **Focus reveals; it does not recenter.** Focus changes move the viewport only
   as far as needed to expose the focused task. Spatial context must be
   preserved.
8. **Closing means closing.** The `x` operation removes the selected Android task
   from the workspace/open tasks. A future “return to phone” operation must be
   distinct from close and is not implied by hiding a tile.
9. **The taskbar is a minimap.** It represents tasks in actual strip order and
   includes compact presentation-mode markers. It must not become an unrelated,
   independently scrolling dock.
10. **Platform boundaries stay explicit.** Android task hosting, trusted display
    creation, input injection, IME routing, and display-mode controls are
    replaceable platform adapters. The workspace model and UI contracts must
    remain understandable without depending on a particular hosting primitive.

## Shared workspace model

The conceptual state ownership is:

```text
LateralApplication
├── DisplayController
│   ├── PhoneSurface
│   └── ExternalSurface
├── WorkspaceController
│   ├── TaskRepository       (Android task snapshots and task identity)
│   ├── TaskOrder             (logical strip order)
│   ├── PresentationState     (PHONE/TABLET/FULLSCREEN)
│   ├── FocusController
│   └── ViewportController
├── BeastUI                   (views only)
│   ├── WorkspaceView
│   ├── TaskDecorator
│   ├── TaskSurface
│   ├── TaskbarMinimap
│   └── LauncherOverlay
└── PhoneUI                   (views and input adapters only)
    ├── StatusLine
    ├── WorkspaceNavigator
    ├── Touchpad
    ├── CommandRow
    └── ImeController
```

BeastUI and PhoneUI render and dispatch actions into shared controllers; they do
not own authoritative copies of workspace state. A phone viewport drag updates
`ViewportController.position`, which then updates both surfaces. A Beast focus
change updates PhoneUI's navigator and focused-task commands through the same
state path.

Each task layout record conceptually contains:

- Android task ID and display/task metadata;
- semantic width mode, computed width, logical x position, and strip order;
- focused/fullscreen state and hosting/presentation status;
- enough lifecycle information to distinguish a missing Android task from a
  temporarily unavailable surface.

Android task snapshots remain authoritative for task existence. Reconciliation
may filter system, Home, LATERAL_, and known device-ghost records, but it must
not silently merge distinct task IDs.

## BeastUI contract

The Beast canvas has three persistent layers:

1. **Workspace:** consumes nearly all available display height and shows the
   ordered task content.
2. **Task decorators:** thin per-task headers above `PHONE` and `TABLET` tiles.
3. **Taskbar/minimap:** a compact map of the full logical strip.

The app launcher is a temporary overlay, not a fourth workspace. It should be a
keyboard-first filtered list (dmenu/rofi-like), with mouse and phone invocation
supported and no icon grid requirement.

### Task dimensions

- `PHONE` derives width from a preferred phone aspect ratio and usable app
  height. It should typically occupy about 500–650 px at roughly 1100 px of
  usable height, subject to responsive layout.
- `TABLET` is wider and responsive, roughly 1400–1800 px in the target
  geometry. It is not a magic fixed pixel width.
- `FULLSCREEN` occupies the Beast viewport with minimal or hidden chrome. The
  task's strip position is retained while fullscreen is active.

Normal task gaps should be visibly separated, approximately 8–16 dp, against a
near-black background. The application surface remains visually dominant.

### Task decorator

Every normal task has a restrained header containing app identity and these
operations, in structural order:

```text
TaskDecorator
├── AppIdentity (label)
├── Spacer
└── TaskControls
    ├── MoveLeft
    ├── SizeMode
    ├── Fullscreen
    ├── MoveRight
    └── Close
```

The decorator is not a rounded desktop title bar, drag handle, maximize/minimize
system, or required app-icon surface. Focus may be shown with a small accent
rule or restrained contrast. Hover can reveal modest contrast changes; motion
should be limited to short fades of roughly 100–150 ms.

### Viewport, taskbar, and overflow

Viewport navigation combines continuous panning with task-aware navigation:

- phone scrollbar and touchpad edge controls may pan continuously;
- taskbar selection focuses and reveals the selected task;
- keyboard/mouse bindings may provide previous/next and move-left/move-right
  operations;
- focus reveal uses the minimum translation needed to make the task visible.

The taskbar preserves strip order and shows compact task labels plus `P`, `T`, or
`F` mode markers. As task count grows, labels compress progressively (`chrome`
to `chr`, then to numbered mode markers); it does not become independently
scrollable. Hover or focus may expose the full label.

## PhoneUI contract

PhoneUI should devote most of its surface to the touchpad. Its stable regions
are:

- a terse connection/mode status line (`beast:uw`, `beast:1920`,
  `display:none`, or `display:init` style states);
- a task navigator that mirrors Beast task order and shows the current viewport;
- a center pointer region for relative motion and click/drag gestures;
- narrow left/right edge regions for precise vertical scrolling;
- a compact command row for apps, presentation mode, fullscreen, and keyboard;
- settings and IME controls, which belong on the phone rather than the Beast.

The normal touch vocabulary is one-finger pointer motion, tap for left click,
double tap for double click, tap-drag for click/drag, and two-finger tap for
right click. Two-finger scrolling should be reserved for the established
workspace/app routing behavior rather than competing with the edge-scroll
regions. Gesture guards must prevent ordinary taps from becoming long presses.

When text input is active, the touchpad may resize to make room for the embedded
Android IME. IME routing should explicitly target the phone display and retain
the hosted editor's real `InputConnection`; shell text injection is not the
architecture.

## Task lifecycle and launch semantics

Selecting an app follows this sequence:

```text
select app
  ├─ existing task → bring that task into LATERAL_
  └─ no task        → launch app
             ↓
read preferred presentation
             ↓
insert into strip (default: immediately right of focused task)
             ↓
reveal and focus
```

Launching an already-running app must not create an unnecessary duplicate task;
launching a genuinely separate Android instance must preserve its separate task
ID. Task close removes the exact task. A future phone-return/migration action,
if Android permits it cleanly, must be specified and implemented separately.

## Platform and implementation boundaries

The architecture relies on a privileged helper for trusted virtual displays,
task movement, pointer/gesture injection, and display lifecycle. These are
capabilities, not reasons for UI code to depend on shell commands. Failures in
the helper or Binder path must surface as explicit unavailable/error states and
must leave shared workspace state coherent.

The embedded FlorisBoard session is similarly an adapter: it supplies the
keyboard surface and editor engine while LATERAL_ controls session lifetime and
restores the prior system IME with compare-and-set semantics. The Android/Nubia
firmware and connected Beast display remain part of the supported-environment
contract; re-test the privileged path after system updates.

## Phased implementation intent

The phases are intentionally ordered around stable contracts rather than visual
polish:

1. **Charter and seams:** preserve this document, define shared task/presentation
   types, and keep display/platform adapters behind explicit interfaces.
2. **Workspace shell:** implement the Beast layered structure, one-dimensional
   task layout, semantic dimensions, focus/reveal behavior, and taskbar minimap
   against deterministic/fake task data.
3. **Phone controller:** connect the mirrored navigator, touchpad, edge scrolling,
   command row, status line, and phone-owned settings to the same workspace
   state.
4. **Real task lifecycle:** reconcile Android task IDs, implement launch/bring
   into workspace/close semantics, and validate insertion and ordering behavior.
5. **Hosting adapters:** integrate virtual-display/task hosting, input injection,
   display-mode changes, surface recovery, and IME session routing. Treat
   firmware-specific failures as explicit states rather than changing the model.
6. **Interaction completion:** add launcher filtering, keyboard shortcuts,
   fullscreen escape/reveal behavior, progressive taskbar compression, and
   accessibility/focus details.
7. **Device validation and release split:** test the target NX789J/Beast path,
   then document the tested non-VITURE monitor subset before considering an
   SDK-free monitor build.

Every phase should be testable without requiring the next platform capability.
In particular, fake task repositories and fake hosting/display adapters should
exercise ordering, focus, viewport, close, and failure recovery before device
integration is treated as complete.

## Acceptance checks for v0.2

The v0.2 implementation is aligned with this charter when:

- PhoneUI and BeastUI visibly agree on task order, focus, presentation mode,
  and viewport;
- multiple tasks from one package remain independently addressable by task ID;
- changing focus reveals only the minimum necessary strip movement;
- changing mode or entering/exiting fullscreen preserves the intended order;
- taskbar compression retains overview rather than becoming a second dock;
- close reports/removes the exact Android task and does not force-stop the package;
- helper, display, surface, and IME failures are recoverable and observable;
- the core state behavior is covered with fake adapters before device-only tests.

The [Termux/Box64 Gradle worker contract](../termux/README.md) provides the
conservative build/test seam for environments where the Android build is
delegated to a phone-side worker.
