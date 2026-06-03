# Route Editor

The route editor lets you review actions captured during a template run and decide which actions should become the saved route for that template.

## When to open the route editor

Routes belong to task templates. Open the editor after the template has run at least once. Even if the template failed at the end, useful captured actions may still be available.

Frontend entries:

- Open a template from the task template list or template detail page.
- Tap **Open task route editor** in the template detail page.
- When editing a workflow step, open the referenced template first, then enter the route editor.

!!! note "Save the template first"
    The route editor entry is hidden while creating a new template. The frontend says route editing is available after the task config has been saved once.

## Page areas

| Frontend area / button | Meaning |
| --- | --- |
| Task route target | Shows which template route is being edited. |
| Saved Route | Shows the route currently saved for this template. If none exists, it says no saved route yet. |
| Current Route | Shows the latest captured actions that are currently kept for saving. |
| Move out of route | Removes an action from the current route. Removed actions will not be saved. |
| Removed from Route | Shows actions removed from the current route. They can be restored before saving. |
| Restore to route | Restores a removed action back into the current route. |
| Save manual route | Saves the kept actions as the template's official route. |
| Finish after replay | If enabled, successful route replay ends the current template run. This option is saved only when you tap Save manual route. |

## Basic workflow

1. Run the template once, or run a workflow that contains it.
2. Open that template's route editor.
3. Review actions in **Current Route**.
4. Use **Move out of route** to remove startup waits, accidental taps, or irrelevant popup handling.
5. If you removed the wrong action, use **Restore to route**.
6. Tap **Save manual route**.
7. Run the template or workflow again and confirm that route replay works.

## Removing noisy actions

Remove actions such as:

- Accidental taps.
- Temporary popup dismissals that do not always appear.
- Extra waiting or navigation that is not required.
- Actions performed after the route already reached the desired page.

Keep actions such as:

- Stable entries after the target app opens.
- Taps that enter a fixed tab or fixed page.
- Stable steps needed before the dynamic part of the task begins.

## Finish after replay

**Finish after replay** controls what happens after route replay succeeds:

- On: the current template run ends directly after route replay.
- Off: the template continues into visual execution.

This option is saved together with the route. After changing it, tap **Save manual route** for it to take effect.

!!! warning "Do not enable it by mistake"
    If the route only navigates to a target page and the model still needs to fill, judge, or submit something, leave it off. Enable it only when the route itself completes the template goal.

## Verify after saving

After saving a route, run the template or workflow again and check:

- Whether it reaches the target page faster.
- Whether unnecessary model decisions are skipped.
- Whether route replay fails.
- Whether fallback to visual execution works if route replay fails.
