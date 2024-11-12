这是一个OpenAI 类服务端程序

由👇分析而来

	https://play.google.com/store/apps/details?id=ai.chat.gpt.bot


本项目是一个类 OpenAI 服务端程序，向接入OpenAI、Anthropic的某个API发送请求，然后模拟OpenAI API标准的响应

作为服务端无需提供Authorization头，可与多种前端应用（如 NextChat、ChatBox 等）无缝集成

本项目核心是解决其内部算法Bearer生成逻辑

支持的模型

gpt-4o✅

gpt-4o-mini✅

claude 3.5 sonnet✅(claude-3-5-sonnet)

claude Haiku✅(claude)

几乎无限使用，几乎没有频率限制，他们的API对max_tokens不作判断要求，推测在8000左右。 适合有高频请求的需求

支持的功能

Completions: （均可联网搜索）

	/v1/chat/completions


TextToImage:（仅限于 gpt-4o 和 gpt-4o-mini 模型，目前固定为gpt-4o）

	/v1/images/generations

ImageToText：需要图片直链，本项目暂未实现。
由于需要直链，服务要部署在服务器上，本项目但未做Authorization验证，因此不适合部署在服务器上，除非开启防火墙白名单

已用Python实现(未提供bearer_token.py)，未进行高并发测试


为避免项目被take down，Bearer核心算法可联系📧patches.camera_0m@icloud.com有偿获取


