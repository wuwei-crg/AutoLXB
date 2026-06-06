# 配置模型

AutoLXB 需要一个支持文本和图片输入的 LLM / VLM 接口。视觉执行和语义适配都依赖模型的 **图像理解能力**。

## 配置入口

进入：`配置 -> 设备端 LLM 配置`

需要填写：

- `Request Type`：请求协议形态，例如 OpenAI Chat Completions、Gemini generateContent、Anthropic Messages。
- `API Base URL`
- `API Key`
- `Model`

配置页会按 Request Type 实时展示最终请求地址，方便确认接口路径是否正确。`API Base URL` 仍填写基础地址，例如：

- OpenAI Chat Completions：`https://api.openai.com/v1`
- Gemini generateContent：`https://generativelanguage.googleapis.com/v1beta`
- Anthropic Messages：`https://api.anthropic.com/v1`

## 测试模型

填写完成后点击测试。

测试会发送一张小图片挑战，让模型读取图片中的数字。测试成功代表：

- App 可以连接到模型服务。
- API Key 和模型名可用。
- 模型能够处理图片输入。

!!! warning "请选择支持图像理解的模型"
    如果模型只能处理文本，普通配置测试可能通过，但视觉执行、页面观察和语义适配无法正常工作。
