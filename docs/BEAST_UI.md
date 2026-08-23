# BeastUI architecture

The Beast is an ordered, one-dimensional task workspace. It is deliberately not a
freeform window manager.

## Invariants

- `WorkspaceState` owns task order, focus, presentation mode, and viewport position.
  PhoneUI and BeastUI are observers of that state.
- Every task owns one trusted virtual display for its lifetime. Moving a task changes
  only its position in the strip; it does not migrate or relaunch the Android task.
- Task displays use destroy-on-removal semantics. If BeastUI or its process stops,
  Android destroys hosted task content instead of reparenting it onto the phone display.
- Presentation size is semantic: `PHONE`, `TABLET`, or `FULLSCREEN`. There are no user
  supplied rectangles and no drag-resize path.
- A virtual display's buffer size follows the task surface's real pixel size. Mode and
  monitor changes call `VirtualDisplay.resize`, causing a normal Android configuration
  change and app reflow. The compositor never stretches an old aspect ratio.
- `FULLSCREEN` is visual state only. The task keeps its index in the strip and returns
  to that same position when fullscreen ends.
- Focus revelation is minimal: BeastUI scrolls only far enough to reveal the focused
  task rather than continually centering it.
- PhoneUI owns the software-keyboard experience. BeastUI is never an Android IME
  target; launcher text still accepts PhoneUI and physical-keyboard injection.
- PhoneUI's rendered Beast cursor is also the input coordinate source. Button
  transitions are injected as absolute mouse events on the physical Beast display,
  where the shell can either consume them or forward them into a task display.
- Synthetic clicks retain a short down interval so apps receive a normal tap rather
  than a zero-duration pulse. Two-finger PhoneUI movement becomes wheel input; the
  hosted app under the cursor consumes it first, with launcher/workspace scrolling as
  the fallback. Every BeastUI shell action shares one short debounce window; hosted
  app input bypasses that gate.
- Two-finger recognition uses an activation slop and drops the transition sample.
  Finger-count changes reset the gesture centroid, cancel any held drag, cap individual
  scroll samples, and impose a short tap/cursor quiet period after multi-touch ends.

## UxSpace reference boundary

The implementation reuses the proven UxSpace mechanism of one bare trusted virtual
display per third-party app. It does not reuse UxSpace's floating geometry, drag state,
edge hit-testing, snap zones, deferred resize scaling, or GL window compositor.

LATERAL_ renders virtual displays into `TextureView` surfaces. Device validation showed
that the NX789J/VITURE hardware-composer path accepted live VirtualDisplay frames into
nested `SurfaceView` queues but presented those queues as black. TextureView uses the
activity's GPU composition path and avoids that OEM failure while preserving explicit
pixel dimensions. Surface reattachment is supported so reordering a view does not
discard its virtual display.

## Main components

- `BeastActivity`: external-display shell, horizontal viewport, taskbar/minimap, and
  launcher overlay. The launcher places open packages first by workspace interaction
  recency, then lists unopened packages alphabetically; search retains that order.
- `WorkspaceState`: process-wide task model shared by both displays.
- `TaskSurfaceView`: trusted-display lifecycle, resizing, app launch, and mapped input.
- `BeastTaskCard`: thin task decorator and the controls that mutate `WorkspaceState`.
- `ViewportMapView`: visible-workspace indicator for the Beast taskbar.

## Next platform checks

Before expanding behavior, verify on the target Nubia NX789J firmware that:

1. Multiple trusted displays produce live frames simultaneously.
2. `VirtualDisplay.resize` preserves each app task and produces a clean configuration
   change for both phone and tablet transitions.
3. Replacing the display surface after strip reordering resumes frames without relaunch.
4. Mouse/touch events mapped from the physical Beast display reach the intended virtual
   display while keyboard focus remains predictable.

Those checks define the boundary between UI work and device-specific task-hosting work.
