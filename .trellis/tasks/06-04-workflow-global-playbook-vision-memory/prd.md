# Workflow Global Playbook and Vision Prompt Memory Cleanup

## Goal

Add a workflow-level global playbook that applies shared operational guidance to every template executed inside that workflow, and remove the current free-form VISION_ACT `carry_context` / `memory_write` mechanism because it is unreliable and token-expensive for the intended information-carrying use cases.

## What I Already Know

* The user wants two simple improvements before larger multi-agent/content-extraction work.
* Current template playbook describes how to complete one template.
* New workflow playbook should describe cross-template operational guidance, such as "during challenge screens, tap the bottom-right skip button to skip battle."
* Workflow playbook should be injected into prompts for all template executions in that workflow.
* Current `carry_context` and `memory_write` rely too much on the model deciding what information is useful.
* The desired long-term information-carrying use case is multi-screen content assembly, such as long quiz questions that require scrolling to collect stem and options.

## Backend Behavior (evolving)

* Add workflow-level playbook storage to `WorkflowDef`.
* Preserve existing workflow/template compatibility: old workflows without this field should load as empty workflow playbook.
* Inject workflow playbook into VISION_ACT prompts for every template task spawned by that workflow.
* Template `userPlaybook` remains task-specific guidance.
* Workflow and template playbooks are injected as separate prompt blocks.
* Conflict rule: template playbook governs the current template objective; workflow playbook provides general operational tips. When they conflict, template playbook wins.
* Workflow playbook does not participate in task route / task map identity. Route matching continues to use the existing template/task route key behavior.
* Workflow playbook is included in portable workflow export/import bundles.
* Backward compatibility: old workflow JSON / portable bundles without workflow playbook load with an empty workflow playbook.
* Workflow playbook has no hard save-time or prompt-injection length limit for this MVP.
* Remove VISION_ACT `carry_context` and `memory_write` output requirements from prompt and parsing.
* Remove `carry_context` from the model contract only: VISION_ACT no longer asks for it, parses it, or treats it as model-provided context.
* Keep the internal `CortexExecutionHistory` `carry_context` field for compatibility with existing history/task-map/trace structures, but write an empty/`none` value from new VISION_ACT turns.
* Remove `memory_write` from the model contract: VISION_ACT no longer asks for it, parses it, or writes model-provided free-form memory.
* Remove `[WORKING_MEMORY_BLOCK]` from VISION_ACT prompts and stop mutating `ctx.workingMemory` from VISION_ACT.
* Keep `ctx.workingMemory` field and helper methods if that avoids broad cleanup, but they become unused by this flow.
* Keep `executionHistory` as the main action/expected/actual/judgement loop.

## Frontend/User-Facing Interface (evolving)

* Add an editable workflow-level playbook field to the workflow editor.
* Place the workflow playbook field directly below workflow name and above workflow steps.
* Use a multi-line optional text field. Suggested wording: `Workflow playbook (optional)` / `工作流操作提示（可选）`.
* Keep existing template playbook behavior.
* Workflow portable import/export preserves workflow playbook.

## Requirements (evolving)

* Workflow save/load/list APIs expose workflow playbook.
* Workflow manual, scheduled, and notification-triggered runs all pass the workflow playbook into template execution.
* Workflow portable export/import includes workflow playbook with backward compatibility for older bundles.
* Workflow playbook text is preserved as entered; no MVP truncation or validation length cap.
* VISION_ACT no longer asks the model to output `carry_context` or `memory_write`.
* VISION_ACT no longer includes `[WORKING_MEMORY_BLOCK]`.
* VISION_ACT no longer uses model-written `memory_write` to mutate `workingMemory`.
* Recent history remains present and useful after removing those fields.

## Acceptance Criteria (evolving)

* [ ] A workflow can save and reload a workflow-level playbook.
* [ ] Running a workflow injects the workflow playbook into every template's VISION_ACT prompt.
* [ ] Running a template directly does not invent a workflow playbook.
* [ ] VISION_ACT prompt no longer includes `<carry_context>` or `<memory_write>`.
* [ ] VISION_ACT parser no longer requires or records those two tags.
* [ ] VISION_ACT prompt no longer includes `[WORKING_MEMORY_BLOCK]` or prior `workingMemory` facts.
* [ ] Workflow portable export/import preserves workflow playbook, while old bundles without the field still import.
* [ ] Existing tests for workflows/templates continue to pass; add/update tests for workflow playbook persistence, portable codec, and prompt injection.

## Definition of Done

* Tests added/updated where behavior changes.
* Lint / typecheck / relevant Gradle tests pass or failures are reported.
* Docs/spec notes updated if the behavior introduces a durable convention.

## Out of Scope

* Full multi-agent split for action decision vs content extraction.
* Structured content fragment cache for quiz/question assembly.
* Prompt optimization beyond removing `carry_context` / `memory_write` and adding workflow playbook guidance.

## Open Questions

* Final confirmation before implementation.

## Technical Notes

* `WorkflowDef` currently has `name`, `description`, trigger fields, failure policy, legacy ids, timestamps, and steps, but no playbook field.
* `TaskTemplate` already has `userPlaybook` serialized as `user_playbook`.
* `CortexTaskManager.executeWorkflow` currently submits each template with `template.userPlaybook`.
* `CortexFsmEngine.Context` has one `userPlaybook` string, injected into `[GUIDANCE_BLOCK]`.
* `CortexFsmEngine` stores `workingMemory` from `<memory_write>` and includes `[WORKING_MEMORY_BLOCK]` in VISION_ACT prompt.
* `CortexExecutionHistory` currently stores `carry_context` in history rows and pending rows.

## Decision Log

* Initial request → Add workflow global playbook and remove `carry_context` / `memory_write` → Start backend behavior clarification before implementation.
* How should workflow and template playbooks be combined? → Use two separate prompt blocks; template guidance wins conflicts → Add distinct workflow/template playbook prompt handling.
* Should workflow playbook affect task-route identity / task map matching? → No; it only affects prompt injection → Preserve route reuse across workflows.
* Should workflow playbook be included in portable workflow export/import bundles? → Yes, include it and treat missing old-bundle values as empty → Preserve shared workflow guidance when workflows are shared.
* Where should workflow playbook appear in the workflow editor? → Name below / Steps above as an optional multiline field → Make workflow-level guidance visible before step composition.
* Should `carry_context` be removed from the internal history row schema, or only from model prompt/output? → Remove only from VISION_ACT model contract; keep internal field for compatibility with empty/none values → Avoid broad task-map/history schema churn.
* Should `memory_write` / `workingMemory` be removed completely from runtime state, or only from prompt/output behavior? → Remove from VISION_ACT prompt/output and actual mutation; leave runtime fields if needed to keep diff small → Eliminate token cost and unreliable free-form memory without broad cleanup.
* Should workflow playbook have a hard length limit before it is saved or injected? → No limit for this MVP → Preserve user-entered global guidance exactly; accept prompt-token risk for now.
