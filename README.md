这是一个OpenAI 类服务端程序

由👇分析而来

	https://play.google.com/store/apps/details?id=ai.chat.gpt.bot


本项目是一个类 OpenAI 服务端程序，向接入OpenAI、Anthropic的某个API发送请求，然后模拟OpenAI API标准的响应

由于是第三方接入的API，最终返回内容由他们所决定，是否降智需自行判断

可与多种前端应用（如 NextChat、ChatBox 等）无缝集成

Demo👇有限试用、需要提供任意authorization，均支持联网。如果传入的model不正确自动回落至gpt-4o

对话：支持上传图片。已手动屏蔽在此路径的画图请求，因为他们返回的URL本质不可访问的，需要由服务端提取路径、替换URL再下载

	https://api-chaton.pages.dev/v1/chat/completions
 
示例

 	curl --request POST 'https://api-chaton.pages.dev/v1/chat/completions' \
 	--header 'Content-Type: application/json' \
 	--header "Authorization: 123" \
 	--data '{"top_p":1,"stream":false,"temperature":0,"messages":[{"role":"user","content":"hello"}],"model":"gpt-4o"}'
  
画图：仅为gpt-4o/gpt-4o-mini

 	https://api-chaton.pages.dev/v1/images/generations

  

示例（这里model、style字段无意义，仅为占位）

	curl --request POST 'https://api-chaton.pages.dev/v1/images/generations' \
	--header 'Content-Type: application/json' \
	--header "Authorization: 123" \
	--data '{"prompt":"girl","response_format":"b64_json","model":"gpt-4o","style":"vivid"}'
 
或者

 	curl --request POST 'https://api-chaton.pages.dev/v1/images/generations' \
	--header 'Content-Type: application/json' \
	--header "Authorization: 123" \
	--data '{"prompt": "girl", "model": "gpt-4o", "n": 1, "size": "1024x1024"}'
  
本项目核心是解决其内部算法Bearer生成逻辑


支持的模型

gpt-4o✅

gpt-4o-mini✅

~~claude 3.5 sonnet✅(claude-3-5-sonnet)~~（已下线，暂不可用）

claude Haiku✅(claude)

几乎无限使用，几乎没有频率限制，他们的API对max_tokens不作判断要求，推测在8000左右。 适合有高频请求的需求

本项目未做Authorization验证

支持的功能

Completions: （均可联网搜索）

	/v1/chat/completions


TextToImage:（仅限于 gpt-4o 和 gpt-4o-mini 模型可画图，目前固定为gpt-4o）

	/v1/images/generations

ImageToText：可传直链，如果传base64编码的图片需要部署在公网

已用Python实现(未提供bearer_token.py)，最大并发量比Java实现的要低，

Usage:

	--port 

指定的端口，默认80

 	--base_url

OpenAI标准中有两种格式，Base64编码和URL直链，对于后者，本项目会直接将URL发送出去，对于前者则必须将本程序部署在服务器上

这是传图需要的URL，为http或https开头的url，不以/结尾，确保这个url能被外部访问，必须可被访问，否则会报错

例如:

python3 main.py --port 80 --base_url https://example.com

java -jar 80 https://example.com

程序会自动在base_url后添加/images/+随机图片名

程序自带简易http访问功能，默认将接收到的Base64图片在程序所在路径的images下，会自动清理1分钟前的图片，也可用nginx搭建http程序

例如会上传https://api-chaton.pages.dev/images/[uuid].png，则填入的base_url为https://api-chaton.pages.dev

Bearer核心算法可联系📧patches.camera_0m@icloud.com有偿获取，四位数，测请楚再考虑


