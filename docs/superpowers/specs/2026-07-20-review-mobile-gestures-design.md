# Review Mode, Panels, and Reader Gestures

## Goal

Make Review mode an unobtrusive annotation overlay, make selection actions
editorial and compact, and replace persistent chapter navigation controls with
edge gestures and the Contents panel.

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
- The Pencil action starts an edit draft for the selection.
- Do not show a textual `Edit` action in this flyout.
- Existing platform actions such as Copy and Select all remain available.

## Phone interaction

- Remove the persistent Previous and Next chapter buttons from the bottom of
  the reader.
- Swiping inward from the left edge opens Contents.
- Swiping inward from the right edge opens the Review panel.
- If Review is off, the right-edge gesture first enables the annotation overlay
  and then opens the Review panel.
- A circular Review FAB sits at the lower right while Review is enabled. It
  opens the Review panel and provides a discoverable alternative to the
  right-edge gesture.
- Chapter changes are made through Contents.

## Tablet interaction

- Keep the same edge-opening gestures on tablet layouts: left edge opens
  Contents and right edge opens Review.
- A leftward swipe inside the open Contents panel closes Contents.
- A rightward swipe inside the open Review panel does nothing. Review contains
  input controls and must retain its horizontal gestures for text selection and
  editing.
- The Review toggle still controls only the annotation overlay.
- The mobile Review FAB is not required on tablet layouts because edge gestures
  and visible panel controls are the primary affordances.

## Testing

- Update Compose interaction tests to prove toggling Review does not open the
  Review panel.
- Test each edge gesture and the asymmetrical panel-dismissal behavior on phone
  and tablet configurations.
- Test that the selection flyout exposes exactly four signal actions and an
  icon-labelled edit action while retaining platform selection actions.
- Update screenshot coverage for Review-on reader state, the mobile FAB, and
  opened Contents/Review surfaces.
