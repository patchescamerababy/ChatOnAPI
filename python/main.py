import asyncio
import json
import sys
import uuid
import base64
import re
import os
import argparse
from datetime import datetime, timezone, timedelta
from typing import List, Optional

import httpx
import uvicorn
from fastapi import (
    BackgroundTasks,
    FastAPI,
    HTTPException,
    Request,
    Response,
    status,
)
from fastapi.responses import HTMLResponse, JSONResponse, StreamingResponse
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles

from bearer_token import BearerTokenGenerator

# 模型列表
MODELS = ["gpt-4o", "gpt-4o-mini", "claude-3-5-sonnet", "claude"]

# 默认端口
INITIAL_PORT = 8080

# 外部API的URL
EXTERNAL_API_URL = "https://api.chaton.ai/chats/stream"

# 初始化FastAPI应用
app = FastAPI()

# 添加CORS中间件
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],  # 允许所有来源
    allow_credentials=True,
    allow_methods=["GET", "POST", "OPTIONS"],  # 允许GET, POST, OPTIONS方法
    allow_headers=["Content-Type", "Authorization"],  # 允许的头部
)

# 挂载静态文件路由以提供 images 目录的内容
app.mount("/images", StaticFiles(directory="images"), name="images")

# 辅助函数
def send_error_response(message: str, status_code: int = 400):
    """构建错误响应，并确保包含CORS头"""
    error_json = {"error": message}
    headers = {
        "Access-Control-Allow-Origin": "*",
        "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
        "Access-Control-Allow-Headers": "Content-Type, Authorization",
    }
    return JSONResponse(status_code=status_code, content=error_json, headers=headers)

def extract_path_from_markdown(markdown: str) -> Optional[str]:
    """
    提取 Markdown 图片链接中的路径，匹配以 https://spc.unk/ 开头的 URL
    """
    pattern = re.compile(r'!\[.*?\]\(https://spc\.unk/(.*?)\)')
    match = pattern.search(markdown)
    if match:
        return match.group(1)
    return None

async def fetch_get_url_from_storage(storage_url: str) -> Optional[str]:
    """
    从 storage URL 获取 JSON 并提取 getUrl
    """
    async with httpx.AsyncClient() as client:
        try:
            response = await client.get(storage_url)
            if response.status_code != 200:
                print(f"获取 storage URL 失败，状态码: {response.status_code}")
                return None
            json_response = response.json()
            return json_response.get("getUrl")
        except Exception as e:
            print(f"Error fetching getUrl from storage: {e}")
            return None

async def download_image(image_url: str) -> Optional[bytes]:
    """
    下载图像
    """
    async with httpx.AsyncClient() as client:
        try:
            response = await client.get(image_url)
            if response.status_code == 200:
                return response.content
            else:
                print(f"下载图像失败，状态码: {response.status_code}")
                return None
        except Exception as e:
            print(f"Error downloading image: {e}")
            return None

def cleanup_images(images_dir: str = "images", age_seconds: int = 60):
    """
    清理 images 目录中创建时间超过指定秒数的图片
    """
    now = datetime.now(timezone.utc)
    cutoff_time = now - timedelta(seconds=age_seconds)
    
    if not os.path.exists(images_dir):
        return
    
    for filename in os.listdir(images_dir):
        file_path = os.path.join(images_dir, filename)
        if os.path.isfile(file_path):
            try:
                file_creation_time = datetime.fromtimestamp(os.path.getctime(file_path), timezone.utc)
                if file_creation_time < cutoff_time:
                    os.remove(file_path)
                    print(f"已删除旧图片: {filename}")
            except Exception as e:
                print(f"无法删除文件 {filename}: {e}")

def save_base64_image(base64_str: str, images_dir: str = "images") -> str:
    """
    将Base64编码的图片保存到images目录，返回文件名
    """
    # 先清理1分钟前的所有图片
    cleanup_images(images_dir, age_seconds=60)
    
    if not os.path.exists(images_dir):
        os.makedirs(images_dir)
    image_data = base64.b64decode(base64_str)
    filename = f"{uuid.uuid4()}.png"  # 默认保存为png格式
    file_path = os.path.join(images_dir, filename)
    with open(file_path, "wb") as f:
        f.write(image_data)
    print(f"保存图片: {filename}")
    return filename

def is_base64_image(url: str) -> bool:
    """
    判断URL是否为Base64编码的图片
    """
    return url.startswith("data:image/")

# 根路径GET请求处理
@app.get("/", response_class=HTMLResponse)
async def read_root():
    """返回欢迎页面"""
    html_content = """
    <html>
        <head>
            <title>Welcome to API</title>
        </head>
        <body>
            <h1>Welcome to API</h1>
            <p>You can send messages to the model and receive responses.</p>
        </body>
    </html>
    """
    return HTMLResponse(content=html_content, status_code=200)

# 聊天完成处理
@app.post("/v1/chat/completions")
async def chat_completions(request: Request, background_tasks: BackgroundTasks):
    """
    处理聊天完成请求
    """
    try:
        request_body = await request.json()
    except json.JSONDecodeError:
        raise HTTPException(status_code=400, detail="Invalid JSON")

    # 打印接收到的请求
    print("Received Completion JSON:", json.dumps(request_body, ensure_ascii=False))

    # 处理消息内容
    messages = request_body.get("messages", [])
    temperature = request_body.get("temperature", 1.0)
    top_p = request_body.get("top_p", 1.0)
    max_tokens = request_body.get("max_tokens", 8000)
    model = request_body.get("model", "gpt-4o")
    is_stream = request_body.get("stream", False)  # 获取 stream 字段

    # 验证模型
    if model not in MODELS:
        raise HTTPException(status_code=400, detail=f"无效的 model: {model}. 可用的模型有: {', '.join(MODELS)}")

    has_image = False
    has_text = False

    # 清理和提取消息内容
    cleaned_messages = []
    for message in messages:
        content = message.get("content", "")
        if isinstance(content, list):
            text_parts = []
            images = []
            for item in content:
                if "text" in item:
                    text_parts.append(item.get("text", ""))
                elif "image_url" in item:
                    has_image = True
                    image_info = item.get("image_url", {})
                    url = image_info.get("url", "")
                    if is_base64_image(url):
                        # 解码并保存图片
                        base64_str = url.split(",")[1]
                        filename = save_base64_image(base64_str)
                        base_url = app.state.base_url
                        image_url = f"{base_url}/images/{filename}"
                        images.append({"data": image_url})
                    else:
                        images.append({"data": url})
            extracted_content = " ".join(text_parts).strip()
            if extracted_content:
                has_text = True
                message["content"] = extracted_content
                if images:
                    message["images"] = images
                cleaned_messages.append(message)
                print("Extracted:", extracted_content)
            else:
                if images:
                    has_image = True
                    message["content"] = ""
                    message["images"] = images
                    cleaned_messages.append(message)
                    print("Extracted image only.")
                else:
                    print("Deleted message with empty content.")
        elif isinstance(content, str):
            content_str = content.strip()
            if content_str:
                has_text = True
                message["content"] = content_str
                cleaned_messages.append(message)
                print("Retained content:", content_str)
            else:
                print("Deleted message with empty content.")
        else:
            print("Deleted non-expected type of content message.")

    if not cleaned_messages:
        raise HTTPException(status_code=400, detail="所有消息的内容均为空。")

    # 构建新的请求JSON
    new_request_json = {
        "function_image_gen": False,
        "function_web_search": True,
        "max_tokens": max_tokens,
        "model": model,
        "source": "chat/pro",
        "temperature": temperature,
        "top_p": top_p,
        "messages": cleaned_messages,
    }

    modified_request_body = json.dumps(new_request_json, ensure_ascii=False)
    print("Modified Request JSON:", modified_request_body)

    # 获取Bearer Token
    tmp_token = BearerTokenGenerator.get_bearer(modified_request_body)
    if not tmp_token:
        raise HTTPException(status_code=500, detail="无法生成 Bearer Token")

    bearer_token, formatted_date = tmp_token

    headers = {
        "Date": formatted_date,
        "Client-time-zone": "-05:00",
        "Authorization": bearer_token,
        "User-Agent": "ChatOn_Android/1.53.502",
        "Accept-Language": "en-US",
        "X-Cl-Options": "hb",
        "Content-Type": "application/json; charset=UTF-8",
    }

    if is_stream:
        # 流式响应处理
        async def event_generator():
            async with httpx.AsyncClient(timeout=None) as client_stream:
                try:
                    async with client_stream.stream("POST", EXTERNAL_API_URL, headers=headers, content=modified_request_body) as streamed_response:
                        async for line in streamed_response.aiter_lines():
                            if line.startswith("data: "):
                                data = line[6:].strip()
                                if data == "[DONE]":
                                    # 通知客户端流结束
                                    yield "data: [DONE]\n\n"
                                    break
                                try:
                                    sse_json = json.loads(data)
                                    if "choices" in sse_json:
                                        for choice in sse_json["choices"]:
                                            delta = choice.get("delta", {})
                                            content = delta.get("content")
                                            if content:
                                                new_sse_json = {
                                                    "choices": [
                                                        {
                                                            "index": choice.get("index", 0),
                                                            "delta": {"content": content},
                                                        }
                                                    ],
                                                    "created": sse_json.get(
                                                        "created", int(datetime.now(timezone.utc).timestamp())
                                                    ),
                                                    "id": sse_json.get(
                                                        "id", str(uuid.uuid4())
                                                    ),
                                                    "model": model,  # 使用用户传入的model
                                                    "system_fingerprint": f"fp_{uuid.uuid4().hex[:12]}",
                                                }
                                                new_sse_line = f"data: {json.dumps(new_sse_json, ensure_ascii=False)}\n\n"
                                                yield new_sse_line
                                except json.JSONDecodeError:
                                    print("JSON解析错误")
                                    continue
                except httpx.RequestError as exc:
                    print(f"外部API请求失败: {exc}")
                    yield f"data: {{\"error\": \"外部API请求失败: {str(exc)}\"}}\n\n"

        return StreamingResponse(
            event_generator(),
            media_type="text/event-stream",
            headers={
                "Cache-Control": "no-cache",
                "Connection": "keep-alive",
                # CORS头已通过中间件处理，无需在这里重复添加
            },
        )
    else:
        # 非流式响应处理
        async with httpx.AsyncClient(timeout=None) as client:
            try:
                response = await client.post(
                    EXTERNAL_API_URL,
                    headers=headers,
                    content=modified_request_body,
                    timeout=None
                )

                if response.status_code != 200:
                    raise HTTPException(
                        status_code=response.status_code,
                        detail=f"API 错误: {response.status_code}",
                    )

                sse_lines = response.text.splitlines()
                content_builder = ""
                images_urls = []

                for line in sse_lines:
                    if line.startswith("data: "):
                        data = line[6:].strip()
                        if data == "[DONE]":
                            break
                        try:
                            sse_json = json.loads(data)
                            if "choices" in sse_json:
                                for choice in sse_json["choices"]:
                                    if "delta" in choice:
                                        delta = choice["delta"]
                                        if "content" in delta:
                                            content_builder += delta["content"]
                        except json.JSONDecodeError:
                            print("JSON解析错误")
                            continue

                openai_response = {
                    "id": f"chatcmpl-{uuid.uuid4()}",
                    "object": "chat.completion",
                    "created": int(datetime.now(timezone.utc).timestamp()),
                    "model": model,  # 使用用户传入的model
                    "choices": [
                        {
                            "index": 0,
                            "message": {
                                "role": "assistant",
                                "content": content_builder,
                            },
                            "finish_reason": "stop",
                        }
                    ],
                }

                # 处理图片（如果有）
                if has_image:
                    images = []
                    for message in cleaned_messages:
                        if "images" in message:
                            for img in message["images"]:
                                images.append({"data": img["data"]})
                    openai_response["choices"][0]["message"]["images"] = images

                return JSONResponse(content=openai_response, status_code=200)
            except httpx.RequestError as exc:
                raise HTTPException(status_code=500, detail=f"请求失败: {str(exc)}")
            except Exception as exc:
                raise HTTPException(status_code=500, detail=f"内部服务器错误: {str(exc)}")

# 图像生成处理
@app.post("/v1/images/generations")
async def images_generations(request: Request):
    """
    处理图像生成请求
    """
    try:
        request_body = await request.json()
    except json.JSONDecodeError:
        return send_error_response("Invalid JSON", status_code=400)

    print("Received Image Generations JSON:", json.dumps(request_body, ensure_ascii=False))

    # 验证必需的字段
    if "prompt" not in request_body:
        return send_error_response("缺少必需的字段: prompt", status_code=400)

    user_prompt = request_body.get("prompt", "").strip()
    response_format = request_body.get("response_format", "b64_json").strip()
    model = request_body.get("model", "gpt-4o")  # 获取用户传入的model

    if not user_prompt:
        return send_error_response("Prompt 不能为空。", status_code=400)

    # 验证模型
    if model not in MODELS:
        return send_error_response(f"无效的 model: {model}. 可用的模型有: {', '.join(MODELS)}", status_code=400)

    print(f"Prompt: {user_prompt}")
    print(f"Model: {model}")

    # 构建新的 TextToImage JSON 请求体
    text_to_image_json = {
        "function_image_gen": True,
        "function_web_search": True,
        "image_aspect_ratio": "1:1",  # 图片比例可选：1:1/9:19/16:9/4:3
        "image_style": "photographic",  # 风格可选：photographic、anime、digital-art、digital-art、cinematic、digital-art等
        "max_tokens": 8000,
        "messages": [
            {
                "content": "You are a helpful artist, please based on imagination draw a picture.",
                "role": "system"
            },
            {
                "content": "Draw: " + user_prompt,
                "role": "user"
            }
        ],
        "model": "gpt-4o",  # 固定 model，画图只能gpt-4o或gpt-4o-mini
        "source": "chat/pro_image"  # 固定 source
    }

    modified_request_body = json.dumps(text_to_image_json, ensure_ascii=False)
    print("Modified Request JSON:", modified_request_body)

    # 获取Bearer Token
    tmp_token = BearerTokenGenerator.get_bearer(modified_request_body, path="/chats/stream")
    if not tmp_token:
        return send_error_response("无法生成 Bearer Token", status_code=500)

    bearer_token, formatted_date = tmp_token

    headers = {
        "Date": formatted_date,
        "Client-time-zone": "-05:00",
        "Authorization": bearer_token,
        "User-Agent": "ChatOn_Android/1.53.502",
        "Accept-Language": "en-US",
        "X-Cl-Options": "hb",
        "Content-Type": "application/json; charset=UTF-8",
    }

    async with httpx.AsyncClient(timeout=None) as client:
        try:
            response = await client.post(
                EXTERNAL_API_URL, headers=headers, content=modified_request_body, timeout=None
            )
            if response.status_code != 200:
                return send_error_response(f"API 错误: {response.status_code}", status_code=500)

            # 初始化用于拼接 URL 的字符串
            url_builder = ""

            # 读取 SSE 流并拼接 URL
            async for line in response.aiter_lines():
                if line.startswith("data: "):
                    data = line[6:].strip()
                    if data == "[DONE]":
                        break
                    try:
                        sse_json = json.loads(data)
                        if "choices" in sse_json:
                            for choice in sse_json["choices"]:
                                delta = choice.get("delta", {})
                                content = delta.get("content")
                                if content:
                                    url_builder += content
                    except json.JSONDecodeError:
                        print("JSON解析错误")
                        continue

            image_markdown = url_builder
            # Step 1: 检查Markdown文本是否为空
            if not image_markdown:
                print("无法从 SSE 流中构建图像 Markdown。")
                return send_error_response("无法从 SSE 流中构建图像 Markdown。", status_code=500)

            # Step 2, 3, 4, 5: 处理图像
            extracted_path = extract_path_from_markdown(image_markdown)
            if not extracted_path:
                print("无法从 Markdown 中提取路径。")
                return send_error_response("无法从 Markdown 中提取路径。", status_code=500)

            print(f"提取的路径: {extracted_path}")

            # Step 5: 拼接最终的存储URL
            storage_url = f"https://api.chaton.ai/storage/{extracted_path}"
            print(f"存储URL: {storage_url}")

            # 获取最终下载URL
            final_download_url = await fetch_get_url_from_storage(storage_url)
            if not final_download_url:
                return send_error_response("无法从 storage URL 获取最终下载链接。", status_code=500)

            print(f"Final Download URL: {final_download_url}")

            # 下载图像
            image_bytes = await download_image(final_download_url)
            if not image_bytes:
                return send_error_response("无法从 URL 下载图像。", status_code=500)

            # 转换为 Base64
            image_base64 = base64.b64encode(image_bytes).decode('utf-8')

            # 将图片保存到images目录并构建可访问的URL
            filename = save_base64_image(image_base64)
            base_url = app.state.base_url
            accessible_url = f"{base_url}/images/{filename}"

            # 根据 response_format 返回相应的响应
            if response_format.lower() == "b64_json":
                response_json = {
                    "model": model,  # 返回用户传入的model
                    "data": [
                        {
                            "b64_json": image_base64
                        }
                    ]
                }
                return JSONResponse(content=response_json, status_code=200)
            else:
                # 构建包含可访问URL的响应
                response_json = {
                    "model": model,  # 返回用户传入的model
                    "data": [
                        {
                            "url": accessible_url
                        }
                    ]
                }
                return JSONResponse(content=response_json, status_code=200)
        except httpx.RequestError as exc:
            print(f"请求失败: {exc}")
            return send_error_response(f"请求失败: {str(exc)}", status_code=500)
        except Exception as exc:
            print(f"内部服务器错误: {exc}")
            return send_error_response(f"内部服务器错误: {str(exc)}", status_code=500)

# 运行服务器
def main():
    parser = argparse.ArgumentParser(description="启动ChatOn API服务器")
    parser.add_argument('--base_url', type=str, default='http://localhost', help='Base URL for accessing images')
    parser.add_argument('--port', type=int, default=INITIAL_PORT, help='服务器监听端口')
    args = parser.parse_args()

    base_url = args.base_url
    port = args.port

    # 确保 images 目录存在
    if not os.path.exists("images"):
        os.makedirs("images")

    # 设置 FastAPI 应用的 state
    app.state.base_url = base_url

    print(f"Server started on port {port} with base_url: {base_url}")

    # 运行FastAPI应用
    uvicorn.run(app, host="0.0.0.0", port=port)

async def get_available_port(start_port: int = INITIAL_PORT, end_port: int = 65535) -> int:
    """查找可用的端口号"""
    for port in range(start_port, end_port + 1):
        try:
            server = await asyncio.start_server(lambda r, w: None, host="0.0.0.0", port=port)
            server.close()
            await server.wait_closed()
            return port
        except OSError:
            continue
    raise RuntimeError(f"No available ports between {start_port} and {end_port}")

if __name__ == "__main__":
    main()
