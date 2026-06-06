# Common LLM/VLM Provider API Shapes

Date: 2026-06-06

## Scope

This research compares common text and vision-capable model API shapes that matter for AutoLXB's current device-side LLM client. The current implementation is a direct OpenAI Chat Completions-compatible client.

## Current repo constraints

- Core config currently stores only `api_base_url`, `api_key`, and `model` for model access.
- `LlmClient.buildEndpointUrl()` appends `/chat/completions` unless the configured URL already ends with that suffix.
- Text requests use OpenAI Chat Completions shape: `model`, `messages`, `max_tokens`.
- Vision requests use OpenAI-compatible multimodal content parts with `type=image_url` and a PNG `data:image/png;base64,...` URL.
- Response parsing primarily expects `choices[0].message.content`, with fallback support for `choices[0].text`, `reasoning_content`, and root `output_text`.

## Provider shapes

### OpenAI Chat Completions

- Endpoint shape: `POST /v1/chat/completions`.
- Request shape: `model` plus `messages`.
- Vision shape: OpenAI-compatible chat messages can include content arrays with text and image parts.
- Auth: bearer API key.
- Fit for current implementation: native fit.

Official docs:
- https://platform.openai.com/docs/api-reference/chat/create
- https://platform.openai.com/docs/guides/images

### OpenAI Responses API

- Endpoint shape: `POST /v1/responses`.
- Request shape differs from Chat Completions; uses `input` and can include multimodal parts.
- Fit for current implementation: not a drop-in because endpoint, request body, and response extraction differ.
- MVP impact: do not prioritize unless we want modern OpenAI-only features.

Official docs:
- https://platform.openai.com/docs/api-reference/responses/create

### Google Gemini native API

- Endpoint shape: `POST /v1beta/models/{model}:generateContent` or versioned equivalents.
- Request shape: `contents` with `parts`; image bytes are sent through inline data parts.
- Auth: API key, commonly passed as key query parameter or configured through client SDKs.
- Response extraction: candidates/content/parts text, not OpenAI `choices`.
- Fit for current implementation: requires provider-specific endpoint construction, payload builder, image part builder, and response parser.

Official docs:
- https://ai.google.dev/gemini-api/docs/text-generation
- https://ai.google.dev/gemini-api/docs/vision

### Google Gemini OpenAI compatibility

- Google provides an OpenAI-compatible Gemini API surface.
- Endpoint shape is OpenAI-style under a Gemini OpenAI compatibility base URL.
- Fit for current implementation: likely works with the existing OpenAI-compatible path if the user enters the correct compatibility base URL and model.
- MVP impact: we can support Gemini quickly by adding a provider preset/documented mode that targets compatibility first.

Official docs:
- https://ai.google.dev/gemini-api/docs/openai

### Anthropic Messages API

- Endpoint shape: `POST /v1/messages`.
- Request shape: `model`, `max_tokens`, `messages`, and optional top-level `system`.
- Vision shape: user content array parts include text parts and image parts with base64 source metadata.
- Auth/header shape differs: `x-api-key` and `anthropic-version` headers are required by the native API.
- Response extraction: top-level `content` array, not OpenAI `choices`.
- Fit for current implementation: requires native adapter. Alternatively use an aggregator or compatibility layer.

Official docs:
- https://docs.anthropic.com/en/api/messages
- https://docs.anthropic.com/en/docs/build-with-claude/vision

### OpenRouter

- Endpoint shape: OpenAI-compatible `POST /api/v1/chat/completions`.
- Request shape: OpenAI-compatible chat completions, with provider/model routing through model names.
- Vision support depends on selected model.
- Fit for current implementation: good fit as an OpenAI-compatible provider; user can set base URL to `https://openrouter.ai/api/v1`.
- MVP impact: add as preset/provider option, not a native adapter.

Official docs:
- https://openrouter.ai/docs/api-reference/chat-completion
- https://openrouter.ai/docs/features/multimodal

### DeepSeek

- Endpoint shape: OpenAI-compatible chat completions under DeepSeek's API base.
- Request shape: OpenAI-compatible.
- Vision support depends on available models; many DeepSeek use cases are text-first.
- Fit for current implementation: good fit for text-only flows; not necessarily enough for AutoLXB visual execution unless a vision-capable model is available.

Official docs:
- https://api-docs.deepseek.com/

### Alibaba Cloud DashScope / Qwen compatible mode

- Endpoint shape: OpenAI-compatible mode is available under DashScope compatible-mode base URLs.
- Request shape: OpenAI-compatible chat completions for compatible models.
- Vision support depends on selected Qwen-VL model and compatible-mode support.
- Fit for current implementation: likely good via compatibility mode; native DashScope would require an adapter.

Official docs:
- https://help.aliyun.com/zh/model-studio/

### xAI

- Endpoint shape: OpenAI-compatible chat completions.
- Fit for current implementation: likely compatible for text; vision depends on selected model and official support.

Official docs:
- https://docs.x.ai/docs

### Ollama

- Local endpoint shape: OpenAI-compatible `/v1/chat/completions` is available for local models.
- Auth: usually none for local use.
- Fit for current implementation: good for local OpenAI-compatible text and, where model/runtime supports it, multimodal use.

Official docs:
- https://docs.ollama.com/openai

## Design implications

There are two practical implementation tracks:

### Track A: Provider preset plus OpenAI-compatible first

Add a provider field and presets for common OpenAI-compatible endpoints:

- OpenAI
- Gemini OpenAI compatibility
- OpenRouter
- DeepSeek
- DashScope compatible mode
- xAI
- Ollama/local

Benefits:
- Smallest change.
- Preserves current request/response logic.
- Covers many providers immediately.

Limitations:
- Does not support native Gemini or native Anthropic without a compatibility proxy.
- Error messages may still be provider-specific and rough.

### Track B: Adapter architecture with native Gemini first

Add provider-specific adapters behind the current `chatOnce` call:

- `openai_compatible`
- `gemini_native`
- later `anthropic_native`

Benefits:
- Real Gemini support without forcing users onto the compatibility layer.
- Clean place to handle provider-specific auth, endpoint, payload, response parsing, and image formatting.

Limitations:
- More code and tests.
- UI/config needs to expose provider type.

### Track C: Adapter architecture with OpenAI-compatible presets plus native Gemini

Implement the provider field now. Ship OpenAI-compatible presets and a native Gemini adapter in the same MVP. Leave Anthropic native for a later task unless the user explicitly needs it now.

Benefits:
- Best balance for the user's stated Gemini target.
- Avoids treating every provider as one-off URL text.
- Still keeps the initial support surface manageable.

Limitations:
- Slightly larger MVP than presets only.

## Recommended MVP

Use Track C:

- Add `provider` to config with default `openai_compatible` for backward compatibility.
- Keep existing behavior when `provider` is missing.
- Add provider presets in the app UI for OpenAI-compatible providers.
- Add native Gemini adapter because Gemini is the explicit example and its native API is materially different.
- Do not implement native Anthropic in this task unless required; document that Anthropic can be used through OpenRouter or another compatibility layer for now.
