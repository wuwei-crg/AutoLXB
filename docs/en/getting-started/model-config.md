# Configure the Model

AutoLXB needs an LLM / VLM endpoint that supports text and image input. Visual execution and semantic route adaptation require **image understanding**.

## Entry

Open: `Config -> Device-side LLM Config`

Fill in:

- `Request Type`: the request protocol shape, such as OpenAI Chat Completions, Gemini generateContent, or Anthropic Messages.
- `API Base URL`
- `API Key`
- `Model`

The config page shows the final resolved request URL based on the selected Request Type. `API Base URL` is still the base address, for example:

- OpenAI Chat Completions: `https://api.openai.com/v1`
- Gemini generateContent: `https://generativelanguage.googleapis.com/v1beta`
- Anthropic Messages: `https://api.anthropic.com/v1`

## Test the model

After filling in the fields, tap the test button.

The test sends a small image challenge and asks the model to read the digits in the image. A successful test means:

- The app can reach the model service.
- The API key and model name are usable.
- The model can process image input.

!!! warning "Choose a model with image understanding"
    If the model only supports text, a basic connection test may pass, but visual execution, screen observation, and semantic adaptation will not work properly.
