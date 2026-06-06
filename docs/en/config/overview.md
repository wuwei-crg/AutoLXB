# Configuration

The Config page controls how AutoLXB runs on this device. For first-time use, focus on three areas: **control mode**, **model configuration**, and **unlock / lock policy**.

## Control mode

This area decides how AutoLXB clicks, swipes, and inputs text.

### Touch mode

Common options include:

- **Shell**: generally compatible and a good first choice for most devices.
- **UIAutomator**: may be more precise on some devices.

If taps do nothing, swipes fail, or positions are inaccurate, switch the mode and test again.

### Input mode

Installing and using **ADB Keyboard** is recommended. It is usually more stable than clipboard or shell input, especially for Chinese, emoji, and longer text.

If ADB Keyboard is not installed, AutoLXB tries fallback input methods, but some apps may not receive non-ASCII text correctly.

### Do Not Disturb during tasks

New notifications, sounds, or popups may interrupt automation. Enable a suitable Do Not Disturb behavior during task execution if interruptions are common.

## Device-side LLM configuration

This area decides which model service AutoLXB uses for task understanding, screen observation, and visual execution.

Fill in:

- **Request Type**: the request protocol shape, such as OpenAI Chat Completions, Gemini generateContent, or Anthropic Messages.
- **API Base URL**
- **API Key**
- **Model**

!!! warning "The model must support image understanding"
    AutoLXB visual execution depends on screenshots. If the model cannot process images, tasks may fail to observe pages or adapt semantic route steps.

After configuration, run the test. The test sends a small image challenge to confirm the model can process images and return a valid result.

## Unlock and lock policy

This area controls lock-screen behavior before and after tasks.

### Auto unlock before route execution

When enabled, AutoLXB tries to unlock the phone before running the task if the device is locked.

The unlock flow expects a normal lock screen. Music players, system dialogs, or other overlays on the lock screen may cause unlock failure.

### Auto lock after task

When enabled, if AutoLXB unlocked the device before the task, it can lock the screen again after the task finishes.

### Lock-screen PIN / password

If swipe alone cannot unlock the phone, configure the lock-screen PIN. The current flow is best suited for numeric PIN screens.

## Route-related settings

Task templates may show a **Use route** switch. When enabled, the template run first tries to use its saved task route.

Recommended use:

- Keep route execution off during the first trial of a new template.
- After the template succeeds and a route is saved, enable route execution.
- If the route often fails, edit the route again or temporarily disable route execution.

## App-map related options

Some map source or sync options may still exist in the Config page. For normal user workflows, focus on task template routes. If you are creating templates or composing workflows, you usually do not need to manage app-map options manually.
