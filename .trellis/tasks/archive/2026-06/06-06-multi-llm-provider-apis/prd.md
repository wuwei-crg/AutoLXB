# Support Multiple LLM Provider APIs

## Goal

AutoLXB currently assumes an OpenAI Chat Completions-compatible endpoint for all device-side LLM/VLM calls. This task should add support for other common provider APIs, especially Gemini, while preserving existing OpenAI-compatible behavior and minimizing user configuration friction.

## What I Already Know

- User wants a new Trellis task for provider API expansion.
- User specifically mentioned Gemini as an example provider.
- Current Core model config is persisted as JSON with `api_base_url`, `api_key`, and `model`.
- `LlmClient` currently normalizes the configured base URL to `/chat/completions`.
- Text calls use OpenAI Chat Completions request shape.
- Vision calls send PNG screenshots as OpenAI-style `image_url` data URLs.
- App-side settings and profiles currently store only base URL, API key, and model for model provider configuration.
- AutoLXB visual execution needs an image-capable model; text-only providers are not enough for the main vision loop.

## Research References

- [`research/provider-api-shapes.md`](research/provider-api-shapes.md) - compares common OpenAI-compatible and native provider API shapes.

## Assumptions

- Backward compatibility with existing saved configs is required.
- OpenAI-compatible providers should remain the default path because many providers and aggregators support it.
- Gemini native support is valuable because its native endpoint, payload, auth, and response shape differ from OpenAI Chat Completions.
- Anthropic native support may be useful later, but it is larger than Gemini-only support because it needs separate auth headers and response parsing.

## Open Questions

- None for MVP implementation.

## Confirmed MVP Summary

- Add user-selectable request type, not provider presets or large vendor adapters.
- Support `openai_chat_completions`, `gemini_generate_content`, and `anthropic_messages`.
- Preserve backward compatibility by defaulting missing `request_type` to `openai_chat_completions`.
- Keep `api_base_url` as a base URL and expand endpoint paths by request type.
- Keep one `api_key` field and apply request-type-specific auth.
- Preserve current image request semantics: user prompt plus image, without newly adding image+system behavior.
- Keep the existing real image probe flow for model testing.

## Backend Behavior

- Existing configs without `request_type` must continue to behave as `openai_chat_completions`.
- The implementation should expose a user-selectable request type/protocol shape rather than building one large provider adapter per vendor.
- Request-type-specific behavior should be isolated behind the current `LlmClient.chatOnce(...)` surface so callers in FSM, notification rewrite, semantic route adaptation, and app resolution do not need provider-specific code.
- `api_base_url` remains a base URL. Core expands it by `request_type`:
  - `openai_chat_completions`: append `/chat/completions` unless already present.
  - `gemini_generate_content`: append `/models/{model}:generateContent` unless already present.
  - `anthropic_messages`: append `/messages` unless already present.
- Full endpoint input should be tolerated where practical to avoid double-appending paths.
- MVP request types are:
  - `openai_chat_completions`
  - `gemini_generate_content`
  - `anthropic_messages`
- `api_key` remains the single user-entered secret field. Core applies request-type-specific auth:
  - `openai_chat_completions`: `Authorization: Bearer <api_key>`.
  - `gemini_generate_content`: pass API key using Gemini REST API-key style.
  - `anthropic_messages`: `x-api-key: <api_key>` plus required Anthropic version header.
- The model client must support both text-only requests and image requests.
- Current repo behavior:
  - Most image/vision runtime calls pass `systemPrompt=null`.
  - The model test path passes a system prompt, but current `buildChatPayloadWithImage` ignores `systemPrompt`, so the image probe effectively sends only the user prompt plus image.
  - Pure text task decomposition and app resolution do use non-empty `systemPrompt`.
- New request types should preserve current caller semantics unless we explicitly decide to change them:
  - Non-empty text `systemPrompt` should be mapped correctly for text requests.
  - Image request behavior should not accidentally introduce a new system instruction into existing vision flows.
- This task should not change current image-request system prompt semantics:
  - Image requests send user prompt plus image.
  - Non-empty `systemPrompt` is not newly introduced into image request payloads in this MVP.
  - Text-only requests continue to support non-empty `systemPrompt` where existing callers use it.
- Response parsing behavior:
  - `openai_chat_completions` keeps the existing parser behavior and fallbacks for backward compatibility with OpenAI-compatible providers/proxies.
  - `gemini_generate_content` parses the official `candidates[0].content.parts[].text` response path.
  - `anthropic_messages` parses the official top-level `content[].text` response path.
  - New native request types should report a clear parse/config error instead of returning raw JSON when official response text cannot be extracted.
- Errors should identify provider/config problems clearly enough for the model config test screen and Trace logs.

## Frontend/User-Facing Interface

- Users currently configure Base URL, API Key, and Model.
- The model config UI should expose Request Type directly and should not include Provider Presets in the MVP.
- Users remain responsible for entering Base URL, API Key, and Model manually.
- The model config screen should still show the resolved endpoint or equivalent request target in a provider-aware way.
- Vision capability should continue to be tested the current way: after syncing config, the APK calls the configured model with a small probe image containing digits and expects the model to return the digits exactly.
- Request Type UI labels should be human-readable protocol names:
  - OpenAI Chat Completions
  - Gemini generateContent
  - Anthropic Messages
- Config and logs should keep stable enum values:
  - `openai_chat_completions`
  - `gemini_generate_content`
  - `anthropic_messages`
- Base URL helper text should change by selected request type and show examples:
  - OpenAI Chat Completions: `https://api.openai.com/v1`
  - Gemini generateContent: `https://generativelanguage.googleapis.com/v1beta`
  - Anthropic Messages: `https://api.anthropic.com/v1`

## Requirements

- Preserve OpenAI-compatible behavior as the default.
- Investigate common provider API shapes before implementation.
- Add a durable request-type/config design before writing code.
- Let users switch request type/protocol shape themselves instead of relying only on provider inference.
- Do not add provider presets in the MVP.
- Support common request shapes broadly without creating a large vendor-specific adapter layer.
- Support these MVP request types: OpenAI Chat Completions-compatible, Gemini native generateContent, and Anthropic native Messages.
- Keep visual capability validation as an actual image probe test, not a static capability flag.
- Use unit tests to define request-type behavior. Do not require real external provider calls in automated tests.

## Acceptance Criteria

- [ ] Existing OpenAI-compatible configs continue to parse and call `/chat/completions` as before.
- [ ] Configs without `request_type` default to `openai_chat_completions`.
- [ ] `api_base_url` is expanded to the correct endpoint path for each supported request type.
- [ ] Full endpoint URLs are not double-appended for the supported request types.
- [ ] Each supported request type applies the correct default authentication/header pattern using the existing `api_key` field.
- [ ] Model config UI lets users select request type directly.
- [ ] Model config UI does not require or expose provider presets in the MVP.
- [ ] Request Type UI uses human-readable protocol labels while persisted config uses stable enum values.
- [ ] Base URL supporting text and resolved endpoint preview update when Request Type changes.
- [ ] Test LLM still validates vision capability by sending a small image challenge and checking the returned digits.
- [ ] OpenAI-compatible parsing retains existing fallback behavior.
- [ ] Gemini native parsing extracts text from the official candidates/content/parts response shape.
- [ ] Anthropic native parsing extracts text from the official content text response shape.
- [ ] Gemini/Anthropic native parsers fail clearly when response text cannot be extracted.
- [ ] Unit tests cover config defaults and request type parsing.
- [ ] Unit tests cover endpoint expansion for all supported request types.
- [ ] Unit tests cover request-type-specific auth/header behavior without real network calls.
- [ ] Unit tests cover text and image payload shape for all supported request types.
- [ ] Unit tests cover response parsing for all supported request types.
- [ ] New request-type config can represent Gemini support.
- [ ] Gemini image requests can be serialized in the provider's expected shape or through a documented compatibility mode.
- [ ] Response parsing returns assistant text for each supported provider path.
- [ ] Unit tests cover config backward compatibility and provider request/response shape behavior.
- [ ] User-facing model configuration explains/selects provider mode without making existing users reconfigure.

## Definition of Done

- Tests added or updated for new provider config and client behavior.
- Lint, type-check, and relevant Gradle tests pass.
- Docs or model config copy updated if provider setup changes.
- Spec update reviewed after implementation.

## Out of Scope

- Streaming responses.
- Tool calling/function calling.
- Provider SDK dependencies unless they clearly reduce complexity.
- Full native support for every provider listed in research.
- Server-side proxy service.

## Technical Notes

- Current core files likely affected:
  - `android/LXB-Ignition/lxb-core/src/main/java/com/lxb/server/cortex/LlmClient.java`
  - `android/LXB-Ignition/lxb-core/src/main/java/com/lxb/server/cortex/LlmConfig.java`
  - `android/LXB-Ignition/lxb-core/src/test/java/com/lxb/server/cortex/LlmConfigTest.java`
- Current app files likely affected:
  - `android/LXB-Ignition/app/src/main/java/com/example/lxb_ignition/core/DeviceConfigSyncer.kt`
  - `android/LXB-Ignition/app/src/main/java/com/example/lxb_ignition/MainViewModel.kt`
  - `android/LXB-Ignition/app/src/main/java/com/example/lxb_ignition/MainActivity.kt`
- Current docs likely affected:
  - `docs/getting-started/model-config.md`
  - `docs/en/getting-started/model-config.md`
- `systemPrompt` usage discovered during brainstorm:
  - Non-null in text-only task decomposition and app resolution.
  - Null in normal screenshot-driven vision execution, semantic adaptation, visual resolver, and route recovery.
  - Non-null in APK model test call, but currently ignored for image payloads by `buildChatPayloadWithImage`.

## Decision Log

- Task created from user request on 2026-06-06.
- Initial research suggests supporting OpenAI-compatible presets plus native Gemini is the best default MVP candidate.
- User clarified scope: support the common API shapes fully, but expose user-selectable request types instead of implementing a large provider adapter per vendor. PRD impact: design around protocol/request type enum and small shape-specific builders/parsers.
- Request type MVP scope chosen: `openai_chat_completions`, `gemini_generate_content`, and `anthropic_messages`. PRD impact: implement three protocol shapes, not vendor adapters.
- Backward compatibility decision: missing `request_type` defaults to `openai_chat_completions`. PRD impact: old configs continue working without migration.
- URL semantics decision: keep `api_base_url` as a base URL and let Core expand endpoint paths by request type, while tolerating already-complete endpoint URLs when practical.
- Auth decision: keep one `api_key` field and use request-type-specific default auth/header behavior. PRD impact: no separate `auth_type` or custom headers in MVP.
- UI decision: expose only Request Type, without Provider Presets. PRD impact: user-facing config remains protocol-oriented and users manually enter Base URL / API Key / Model.
- Vision test decision: keep the current actual image probe flow rather than adding a `supports_vision` config flag.
- Request Type UI copy decision: show human-readable protocol names in the UI, keep enum values in config/logs, and show request-type-specific Base URL examples.
- Implementation fact check: current vision calls mostly do not use system prompts; the APK image probe passes one but the current image payload builder ignores it. PRD impact: do not assume every request type needs image+system support unless explicitly chosen.
- Image/system behavior decision: preserve current image request semantics and do not add image+system support in this MVP. PRD impact: text requests map system prompts, image requests remain user prompt + image.
- Response parsing decision: OpenAI-compatible keeps existing fallbacks; Gemini and Anthropic native paths parse only their official primary text shapes and fail clearly when missing.
- Test scope decision: use unit tests for config, endpoint expansion, auth/header selection, payload building, and response parsing. Do not depend on real external provider calls in automated tests.
