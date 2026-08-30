# Roadmap

## v0.2 foundation status

- **Architecture charter:** documented and canonicalized in
  [ARCHITECTURE_CHARTER.md](ARCHITECTURE_CHARTER.md). Shared task identity,
  one-dimensional workspace behavior, PhoneUI/BeastUI state ownership,
  lifecycle semantics, and phased implementation intent are the current design
  contract.
- **Termux/Box64 build worker:** contract and conservative reference worker are
  documented in [termux/README.md](../termux/README.md). It is a delegated build
  seam only; Android app integration remains future work.

## Future releases

- **SDK-free monitor build:** after additional testing with non-VITURE external
  displays, publish a sanitized APK that contains no VITURE SDK or proprietary
  native runtime. It should retain standard external-monitor features—PhoneUI,
  BeastUI workspace behavior, pointer/touch injection where supported, task
  hosting, scrolling, and ordinary resolution/framerate controls—while omitting
  VITURE-specific display-mode and glasses controls.
- Document the tested non-VITURE phones, displays, Android versions, and the
  exact feature differences between the standard-monitor APK and the
  VITURE-enabled source build.
