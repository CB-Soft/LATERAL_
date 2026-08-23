1\. Two coordinated interfaces



LATERAL\_ has two simultaneous surfaces with different responsibilities:



┌──────────────── PHONE ────────────────┐

│                                      │

│  navigation / input / keyboard       │

│                                      │

└──────────────────┬───────────────────┘

&#x20;                  │

&#x20;                  │ controls

&#x20;                  ▼

┌──────────────── BEAST ─────────────────────────────────────────┐

│                                                               │

│  applications / task strip / launcher                         │

│                                                               │

└───────────────────────────────────────────────────────────────┘



The Beast is the workspace.



The phone is the controller.



We should resist duplicating controls between them unless mouse/keyboard users genuinely need the same operation.



2\. Beast: persistent screen structure



At the highest level:



┌──────────────────────────────────────────────────────────────┐

│ WORKSPACE                                                     │

│                                                              │

│       task       task             task        task            │

│                                                              │

│                                                              │

├──────────────────────────────────────────────────────────────┤

│ TASKBAR / MINIMAP                                            │

└──────────────────────────────────────────────────────────────┘



There are only three major UI layers:



Workspace

Task decorators

Taskbar/minimap



Plus temporary overlays such as the app launcher.



The workspace should consume essentially the entire 3840×1200 canvas.



3\. Workspace: one-dimensional canvas



Conceptually, don't make the workspace 3840 px wide.



Make it effectively unbounded:



&#x20;                    LOGICAL TASK STRIP





&#x20;    -2000       0             3840            7000

&#x20;       │        │               │                │

&#x20;       ▼        ▼               ▼                ▼





&#x20;... \[TASK A] \[TASK B] \[   TASK C   ] \[TASK D] \[TASK E] ...





&#x20;            ┌────────────────────────────┐

&#x20;            │      BEAST VIEWPORT        │

&#x20;            └────────────────────────────┘



Each task gets a horizontal slot.



The layout engine calculates:



Task

&#x20;   id

&#x20;   widthMode

&#x20;   width

&#x20;   xPosition

&#x20;   order

&#x20;   focused



There is essentially no meaningful Y-position for tasks.



All normal tasks share:



y = workspaceTop

height = workspaceHeight



This is the architectural payoff of abandoning freeform windows.



4\. Task dimensions



Rather than hardcoding physical pixels, define three semantic modes.



PHONE



Tall Android presentation:



&#x20;     chrome

┌──────────────┐

│              │

│              │

│              │

│              │

│              │

│              │

└──────────────┘



Width should derive from a preferred phone aspect ratio and the available vertical height.



For example, if usable height is \~1100 px:



PHONE ≈ 500–650 px wide



That allows perhaps five or six phone tasks to coexist visibly in ultrawide.



TABLET

&#x20;            chrome

┌──────────────────────────────┐

│                              │

│                              │

│                              │

└──────────────────────────────┘



Something approximately:



TABLET ≈ 1400–1800 px wide



I'd probably make this responsive rather than define one magic width.



FULLSCREEN



Fullscreen is special.



It temporarily stops participating visually in the strip:



Task strip:

A B \[ C ] D E

&#x20;     │

&#x20;     ▼





┌──────────────────────────────────────────────────────────────┐

│                                                              │

│                         TASK C                               │

│                                                              │

└──────────────────────────────────────────────────────────────┘



But its position in the task strip does not change.



Exit fullscreen and C returns exactly where it was.



5\. Task decorator



Every PHONE/TABLET task gets a tiny LATERAL\_ header.



I'd make it roughly:



&#x20;chrome                                      <  P  \[]  >  ×

────────────────────────────────────────────────────────────



For a tablet task:



&#x20;chrome                                      <  T  \[]  >  ×

────────────────────────────────────────────────────────────



No application icon necessary.



No rounded title bar.



No drag affordance.



No maximize/minimize convention inherited from Windows.



The decorator is structurally:



TaskDecorator

├── AppIdentity

│   └── label

│

├── Spacer

│

└── TaskControls

&#x20;   ├── MoveLeft

&#x20;   ├── SizeMode

&#x20;   ├── Fullscreen

&#x20;   ├── MoveRight

&#x20;   └── Close

Mouse behavior



Hover can reveal slightly more contrast:



idle





&#x20;chrome                           <  T  \[]  >  ×

───────────────────────────────────────────────









hover





&#x20;chrome                           < \[T] \[]  >  ×

───────────────────────────────────────────────



No animations beyond perhaps \~100–150 ms fades.



Keyboard shortcuts can eventually invoke every decorator operation without touching it.



6\. Focus indication



Avoid giant colored borders.



The focused task could simply have:



focused

━━━━━━━━━━━━━━━━━━━━

&#x20;chrome       T \[] ×

────────────────────

&#x20;APP









unfocused





&#x20;chrome       T \[] ×

────────────────────

&#x20;APP



Or use the single configured accent for the tiny top rule.



That's enough.



The task itself should remain visually dominant.



7\. Spacing between tasks



I wouldn't have windows physically touch.



Something like:



┌────────┐  ┌────────┐  ┌────────────────┐

│        │  │        │  │                │

│        │  │        │  │                │

└────────┘  └────────┘  └────────────────┘

&#x20;            ↑

&#x20;         8–16 dp



Enough negative space to distinguish tasks, but not enough to waste the Beast's width.



That background should simply be near-black.



8\. Horizontal viewport behavior



This needs to feel excellent because it replaces conventional window movement.



There should be discrete task-aware navigation layered over continuous scrolling.



Mouse wheel + modifier could pan horizontally.



Phone scrollbar can pan continuously.



Clicking a taskbar item centers/reveals that task.



Keyboard:



Alt + ←        previous task

Alt + →        next task





Alt + Shift + ←    move task left

Alt + Shift + →    move task right



Exact bindings can change later.



Crucially, when focus changes, LATERAL\_ should only move the viewport as much as necessary to expose the focused task.



Don't constantly center things.



Example:



BEFORE





┌──────── VIEWPORT ───────────────────────────┐

&#x20;  A       B       C       D(partial)

└────────────────────────────────────────────┘





Select D





&#x20;         ┌──────── VIEWPORT ───────────────────────────┐

&#x20;  B       C       D       E(partial)

&#x20;         └────────────────────────────────────────────┘



Small movement, spatial context preserved.



9\. Bottom taskbar becomes a workspace map



This is one of the most important LATERAL\_ components.



I'd allocate only \~30–40 dp.



LATERAL\_  01 chr:P  02 gpt:P  03 tawc:T  04 mail:P  05 yt:F    +apps

&#x20;         └──────────────── VIEW ────────────────┘



The items represent tasks in their actual horizontal order.



Mode is visible:



:P   phone

:T   tablet

:F   preferred fullscreen



Focused task can invert:



01 chr:P   02 gpt:P   \[03 tawc:T]   04 mail:P



The viewport indicator underneath represents which portion of the complete workspace is currently visible.



That makes the taskbar a genuine minimap, not merely a dock.



10\. Taskbar overflow



With many tasks, don't horizontally scroll the taskbar independently and thereby destroy its minimap function.



Instead compress representation progressively.



For example:



Normal:





01 chrome:P  02 chatgpt:P  03 messages:P









More crowded:





01 chr:P  02 gpt:P  03 msg:P









Very crowded:





01:P  02:P  03:P  04:T  05:P  06:P  07:T ...



Hover reveals the full name.



This retains an overview of the workspace.



11\. App launcher



The launcher should be an overlay, not another workspace.



I'd make it look almost like dmenu/rofi:



┌──────────────────────────────────────────────────────────────┐

│ > chr\_                                                       │

│                                                              │

│   chrome                                                     │

│   chromium                                                  │

│                                                              │

│                                            esc:close         │

└──────────────────────────────────────────────────────────────┘



Or initially:



> \_





&#x20; browser

&#x20; calculator

&#x20; chatgpt

&#x20; chrome

&#x20; discord

&#x20; files

&#x20; gmail

&#x20; maps

&#x20; messages

&#x20; settings

&#x20; tawc

&#x20; youtube



Typing immediately filters.



Mouse works.



Keyboard works.



Phone can invoke it.



No icon grid.



That would fit LATERAL\_ dramatically better than transplanting UxSpace's launcher visually. We can reuse its application enumeration/launching logic, not necessarily its presentation.



12\. Launch semantics



When an app is selected:



Does existing task exist?

&#x20;       │

&#x20;  ┌────┴────┐

&#x20;  YES       NO

&#x20;   │         │

bring task   launch app

into Lateral    │

&#x20;   │            │

&#x20;   └──────┬─────┘

&#x20;          ▼

read preferred presentation

&#x20;          │

&#x20;          ▼

insert task into strip

&#x20;          │

&#x20;          ▼

reveal + focus



Default insertion should probably be immediately right of the currently focused task, rather than always at the end.



That makes launching a related app spatially predictable.



13\. Closing tasks



× means genuinely close/remove the task, not merely hide the tile.



If we want "return to phone" later, that should be a different command.



Potentially:



×     close

↓     return to phone



But I would omit that control from v1 until we've established what task migration Android permits cleanly.



14\. Fullscreen UI



True fullscreen should be almost completely chrome-free:



┌──────────────────────────────────────────────────────────────┐

│                                                              │

│                                                              │

│                         APPLICATION                          │

│                                                              │

│                                                              │

└──────────────────────────────────────────────────────────────┘



Move pointer into, say, the upper-right 20×20 dp hotspot:



&#x20;                                                    ┌──────┐

&#x20;                                                    │ \[T]  │

&#x20;                                                    └──────┘



After \~1–2 seconds without hover, it disappears again.



No persistent taskbar.



Esc could also exit fullscreen where it doesn't conflict with app behavior; a dedicated shortcut may ultimately be safer.



15\. Phone UI: normal state



The phone should be even simpler than the Beast.



I'd divide it into:



┌────────────────────────────────┐

│ LATERAL\_              beast:uw │  status

├────────────────────────────────┤

│ 01  02  03  04  05  06        │  minimap

│       └── VIEW ──┘             │

├───┬────────────────────────┬───┤

│   │                        │   │

│ ↑ │                        │ ↑ │

│   │                        │   │

│   │       TOUCHPAD         │   │

│   │                        │   │

│ ↓ │                        │ ↓ │

│   │                        │   │

├───┴────────────────────────┴───┤

│ keyboard / controls            │

└────────────────────────────────┘



The touchpad consumes the overwhelming majority of the screen.



16\. Phone task navigator



This should correspond directly to the Beast taskbar but prioritize touch.



Something like:



01      02      03      04      05

CHR     GPT     TAWC    MSG     YT





──────────\[ BEAST VIEW ]────────────



Drag the viewport indicator:



finger → right

&#x20;       ↓

workspace pans → right



Tap a task:



tap 04

&#x20;  ↓

focus task 04

&#x20;  ↓

ensure task visible



Long-press might eventually expose task operations, but I'd keep v1 simpler.



17\. Phone touchpad



The center region produces relative pointer motion.



It should support familiar laptop gestures only where they don't conflict with our dedicated controls:



1 finger move       pointer

tap                 left click

double tap          double click

tap-drag            click + drag

2 finger tap        right click



I'd avoid two-finger scrolling because we already have something better.



18\. Edge scrolling



Reserve narrow strips:



┌────┬──────────────────────────┬────┐

│    │                          │    │

│ ↑  │                          │ ↑  │

│    │                          │    │

│    │        TOUCHPAD          │    │

│    │                          │    │

│ ↓  │                          │ ↓  │

│    │                          │    │

└────┴──────────────────────────┴────┘



Dragging vertically in either edge strip generates:



AXIS\_VSCROLL



rather than pointer movement.



This gives you very precise document scrolling while retaining the entire center for mouse control.



We can add acceleration based on distance/speed.



19\. Software keyboard



I wouldn't permanently reserve keyboard space.



Normal:



┌──────────────────┐

│                  │

│     TOUCHPAD     │

│                  │

└──────────────────┘



When Beast app requests text input:



┌──────────────────┐

│    TOUCHPAD      │

├──────────────────┤

│                  │

│   ANDROID IME    │

│                  │

└──────────────────┘



The touchpad simply resizes vertically.



LATERAL\_ should explicitly force/route the IME to display 0 rather than allow Android/Nubia to decide opportunistically.



20\. Phone command row



There are a handful of operations useful enough to warrant persistent access, but I'd avoid a traditional toolbar.



Maybe:



\[apps]      \[P/T]      \[full]      \[kbd]



Or, more aesthetically:



apps     mode     full     kbd



Monospace text, no icons.



These operate on the currently focused Beast task.



This means you can run LATERAL\_ entirely from the phone without reaching for tiny Beast decorators.



21\. Connection / mode status



Keep hardware information terse:



LATERAL\_                           beast:uw



Other states:



beast:1920

beast:uw

beast:120

display:none

display:init



Clicking status can open a tiny configuration panel if necessary.



We don't need a giant UxSpace-style settings window floating on the Beast.



22\. Settings should live on the phone



This is another structural decision I'd make now.



Configuration belongs to display 0.



Not the Beast.



Settings might eventually contain:



LATERAL\_ / config





display

&#x20; beast mode        ultrawide

&#x20; refresh           60 hz





appearance

&#x20; accent            system

&#x20; font scale        100%

&#x20; task labels       compact





input

&#x20; pointer speed     1.0

&#x20; scroll speed      1.0

&#x20; natural scroll    off





applications

&#x20; chrome             tablet

&#x20; chatgpt            phone

&#x20; youtube            fullscreen



The Beast remains a workspace.



23\. Structural UI hierarchy



At the code/UI level, I would aim for something conceptually like:



LateralApplication





├── DisplayController

│   ├── PhoneSurface

│   └── ExternalSurface

│

├── WorkspaceController

│   ├── TaskRepository

│   ├── TaskOrder

│   ├── PresentationState

│   ├── FocusController

│   └── ViewportController

│

├── BeastUI

│   ├── WorkspaceView

│   │   └── TaskTile\[]

│   │       ├── TaskDecorator

│   │       └── TaskSurface

│   │

│   ├── TaskbarView

│   └── LauncherOverlay

│

└── PhoneUI

&#x20;   ├── StatusLine

&#x20;   ├── WorkspaceNavigator

&#x20;   ├── Touchpad

&#x20;   │   ├── PointerRegion

&#x20;   │   ├── LeftScrollRegion

&#x20;   │   └── RightScrollRegion

&#x20;   ├── CommandRow

&#x20;   └── ImeController



And importantly, BeastUI and PhoneUI do not own state.



They're two views onto the same WorkspaceController.



So when you drag the viewport on the phone:



PhoneUI

&#x20;  ↓

ViewportController.position

&#x20;  ↓

Workspace state changes

&#x20;  ↓

┌───────────────┬────────────────┐

│ BeastUI       │ PhoneUI        │

│ pans tasks    │ moves minimap  │

└───────────────┴────────────────┘



Likewise clicking a task on the Beast immediately updates the phone's focused-task controls.



That shared-state model will prevent a lot of dual-display weirdness.



24\. The overall interaction philosophy



The final result should feel less like "Android desktop mode" and more like a very polished tiling WM designed for exactly one monitor geometry.



That's a huge advantage.



We know the target:



3840 × 1200

horizontal

mouse + keyboard

phone available as auxiliary input

existing Android tasks



We don't need to solve window management generally.



Visually, I'd aim for the strange but appealing situation where someone familiar with Linux sees LATERAL\_ and immediately understands it:



LATERAL\_    01:chr:P  02:gpt:P  \[03:tawc:T]  04:msg:P       +apps

─────────────────────────────────────────────────────────────────





┌─────────┐  ┌─────────┐  ┌──────────────────────┐  ┌─────────┐

│ chrome  │  │ chatgpt │  │ tawc            <T> │  │messages │

├─────────┤  ├─────────┤  ├──────────────────────┤  ├─────────┤

│         │  │         │  │                      │  │         │

│         │  │         │  │                      │  │         │

│         │  │         │  │                      │  │         │

│         │  │         │  │                      │  │         │

└─────────┘  └─────────┘  └──────────────────────┘  └─────────┘



Almost everything you see is actual application content. LATERAL\_ itself is just the thin layer that tells you where things are and what you can do to them.



The biggest implementation question beneath this UI remains how we host multiple existing Android tasks simultaneously while preserving MultiDisplay's continuity behavior. I would resolve that before locking in the exact TaskSurface implementation, because everything above it can stay essentially the same regardless of whether the eventual mechanism is task embedding, task reparenting, or another Android display primitive.
