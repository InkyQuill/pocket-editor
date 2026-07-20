# Review Mode, Panels, and Reader Gestures

## Goal

Make Review mode an unobtrusive annotation overlay, make selection actions
editorial and compact, and replace persistent chapter navigation controls with
clear, platform-safe panel controls and the Contents panel.

## Review mode

- The Review toggle only shows or hides review annotations in the reader text.
- Enabling Review must not automatically open the Review panel, drawer, or
  bottom sheet.
- The Review panel is an independent surface for chapter notes, signals, and
  edits. Opening it does not change whether annotations are visible.

## Selection actions

When the reader has an active text selection, show an application action flyout
adjacent to the platform selection controls. It contains four icon-only signal
actions using the existing signal colors and a Pencil icon for an edit.

- The four signal actions create a draft with the selected signal color.
- The Pencil action starts an edit draft for the selection; do not show a
  textual `Edit` action in this flyout.
- Existing platform actions such as Copy and Select all remain available.
- Every application action has a 44dp minimum touch target, a concise TalkBack
  label, and an accessible tooltip. The five actions share one compact visual
  group distinct from the platform selection actions.

## Phone interaction

- Remove the persistent Previous and Next chapter buttons from the bottom of
  the reader.
- Contents has an explicit top-app-bar button on the left.
- The Review toggle is an explicit top-app-bar action on the right.
- A circular Review FAB sits at the lower right while Review is enabled. It
  opens the Review panel and is the discoverable shortcut to review tools.
- Chapter changes are made through Contents.
- Do not use edge-swipe gestures. They conflict with Android system Back and
  with text-selection drags near the reader boundary.

## Tablet interaction

- Keep explicit Contents and Review controls in the tablet chrome and retain
  the existing visible side panels.
- Do not add horizontal open or close gestures to either tablet panel. Review
  must retain its horizontal gestures for text selection and editing.
- The Review toggle still controls only the annotation overlay.
- The mobile Review FAB is not required on tablet layouts because visible panel
  controls are the primary affordances.

## Testing

- Update Compose interaction tests to prove toggling Review does not open the
  Review panel.
- Test that the top-app-bar buttons and mobile FAB open the intended surfaces
  without changing the Review overlay unless the user explicitly toggles it.
- Test that the selection flyout exposes exactly four signal actions and an
  icon-labelled edit action while retaining platform selection actions; verify
  each has a TalkBack label and a 44dp touch target.
- Test that selection drags and Android Back still work at both reader edges.
- Update screenshot coverage for Review-on reader state, the mobile FAB, and
  opened Contents/Review surfaces on phone and tablet.
