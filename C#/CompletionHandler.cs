using System;
using System.Net;
using System.Threading.Tasks;
using System.IO;
using System.Text;
using System.Collections.Generic;
using System.Net.Http;
using System.Text.Json;
using System.Linq;
using System.Threading;

namespace ChatOnServer
{
    public class CompletionHandler
    {
        private readonly string[] Models = { "gpt-4o", "gpt-4o-mini", "claude-3-5-sonnet", "claude" };
        private readonly HttpClient httpClient = new HttpClient();
        private readonly string imagesDir;
        private readonly string baseURL;
        private readonly SemaphoreSlim semaphore = new SemaphoreSlim(1, 1); // 用于同步文件操作

        public CompletionHandler(string baseURL, string imagesDir)
        {
            this.imagesDir = imagesDir;
            this.baseURL = baseURL;

            // 确保images目录存在
            if (!Directory.Exists(imagesDir))
            {
                try
                {
                    Directory.CreateDirectory(imagesDir);
                }
                catch (Exception ex)
                {
                    Console.WriteLine($"创建images目录失败: {ex.Message}");
                    throw;
                }
            }
        }

        public async Task Handle(HttpListenerContext context)
        {
            var request = context.Request;
            var response = context.Response;

            try
            {
                using var reader = new StreamReader(request.InputStream, request.ContentEncoding);
                string requestBody = await reader.ReadToEndAsync();

                Console.WriteLine($"Received Completion JSON: {requestBody}");

                // 解析JSON请求体
                JsonDocument requestJson;
                try
                {
                    requestJson = JsonDocument.Parse(requestBody);
                }
                catch (JsonException je)
                {
                    await Utils.SendErrorAsync(response, $"JSON 解析错误: {je.Message}");
                    return;
                }

                JsonElement root = requestJson.RootElement;

                // 提取字段
                double temperature = root.TryGetProperty("temperature", out JsonElement tempElem) ? tempElem.GetDouble() : 0.6;
                double topP = root.TryGetProperty("top_p", out JsonElement topPElem) ? topPElem.GetDouble() : 0.9;
                int maxTokens = root.TryGetProperty("max_tokens", out JsonElement maxTokensElem) ? maxTokensElem.GetInt32() : 8000;
                string model = root.TryGetProperty("model", out JsonElement modelElem) ? modelElem.GetString() : "gpt-4o";
                bool isStream = root.TryGetProperty("stream", out JsonElement streamElem) && streamElem.GetBoolean();

                bool hasImage = false;
                List<string> imageFilenames = new List<string>();
                List<string> imageURLs = new List<string>();

                // 处理消息
                List<Dictionary<string, object>> modifiedMessages = new List<Dictionary<string, object>>();

                if (root.TryGetProperty("messages", out JsonElement messagesElem))
                {
                    var messages = root.GetProperty("messages").EnumerateArray().ToList();

                    foreach (var message in messages)
                    {
                        if (message.TryGetProperty("content", out JsonElement contentElem))
                        {
                            if (contentElem.ValueKind == JsonValueKind.Array)
                            {
                                var contentArray = contentElem.EnumerateArray().ToList();
                                StringBuilder contentBuilder = new StringBuilder();

                                foreach (var contentItem in contentArray)
                                {
                                    if (contentItem.TryGetProperty("type", out JsonElement typeElem))
                                    {
                                        string type = typeElem.GetString();
                                        if (type == "text" && contentItem.TryGetProperty("text", out JsonElement textElem))
                                        {
                                            string text = textElem.GetString();
                                            contentBuilder.Append(text + " ");
                                        }
                                        else if (type == "image_url" && contentItem.TryGetProperty("image_url", out JsonElement imageUrlElem))
                                        {
                                            string dataUrl = string.Empty;

                                            if (imageUrlElem.ValueKind == JsonValueKind.String)
                                            {
                                                dataUrl = imageUrlElem.GetString();
                                            }
                                            else if (imageUrlElem.ValueKind == JsonValueKind.Object && imageUrlElem.TryGetProperty("url", out JsonElement urlElem))
                                            {
                                                dataUrl = urlElem.GetString();
                                            }
                                            else
                                            {
                                                Console.WriteLine("image_url字段格式不正确。");
                                                continue; // 跳过不正确的image_url
                                            }

                                            string imageMarkdown = string.Empty;

                                            if (dataUrl.StartsWith("data:image/"))
                                            {
                                                // 处理base64编码的图片
                                                int base64Index = dataUrl.IndexOf("base64,") + 7;
                                                if (base64Index < 7)
                                                {
                                                    Console.WriteLine("无效的base64图片数据。");
                                                    continue; // 跳过无效的图片
                                                }
                                                string base64Data = dataUrl.Substring(base64Index);
                                                byte[] imageBytes;
                                                try
                                                {
                                                    imageBytes = Convert.FromBase64String(base64Data);
                                                }
                                                catch (FormatException fe)
                                                {
                                                    Console.WriteLine($"Base64解码错误: {fe.Message}");
                                                    continue; // 跳过无法解码的图片
                                                }

                                                string uuid = Guid.NewGuid().ToString();
                                                string extension = "jpg";
                                                if (dataUrl.StartsWith("data:image/png"))
                                                {
                                                    extension = "png";
                                                }
                                                else if (dataUrl.StartsWith("data:image/jpeg") || dataUrl.StartsWith("data:image/jpg"))
                                                {
                                                    extension = "jpg";
                                                }

                                                string imageFilename = $"{uuid}.{extension}";
                                                string imagePath = Path.Combine(imagesDir, imageFilename);

                                                // 保存图片
                                                try
                                                {
                                                    await semaphore.WaitAsync();
                                                    await File.WriteAllBytesAsync(imagePath, imageBytes);
                                                }
                                                catch (Exception ex)
                                                {
                                                    Console.WriteLine($"保存图片失败: {ex.Message}");
                                                    continue; // 跳过无法保存的图片
                                                }
                                                finally
                                                {
                                                    semaphore.Release();
                                                }

                                                string imageURL = $"{baseURL}/images/{imageFilename}";
                                                imageFilenames.Add(imageFilename);
                                                imageURLs.Add(imageURL);
                                                hasImage = true;
                                                Console.WriteLine($"图片已保存: {imageFilename}, 可访问 URL: {imageURL}");

                                                // 构建Markdown图片语法
                                                imageMarkdown = $"![Image]({imageURL})";
                                            }
                                            else
                                            {
                                                // 处理标准URL的图片
                                                string imageURL = dataUrl;
                                                imageURLs.Add(imageURL);
                                                hasImage = true;
                                                Console.WriteLine($"接收到标准图片 URL: {imageURL}");

                                                // 构建Markdown图片语法
                                                imageMarkdown = $"![Image]({imageURL})";
                                            }

                                            contentBuilder.Append(imageMarkdown + " ");
                                        }
                                    }
                                }

                                string extractedContent = contentBuilder.ToString().Trim();
                                if (string.IsNullOrEmpty(extractedContent) && !hasImage)
                                {
                                    // 移除内容为空的消息
                                    continue; // 跳过该消息
                                }

                                // 构建新的消息对象
                                var newMessage = new Dictionary<string, object>
                                {
                                    { "role", message.GetProperty("role").GetString() },
                                    { "content", extractedContent }
                                };

                                // 如果有图片，添加 "images" 字段
                                if (imageURLs.Count > 0)
                                {
                                    newMessage.Add("images", imageURLs.Select(url => new { data = url }).ToArray());
                                }

                                modifiedMessages.Add(newMessage);
                            }
                            else if (contentElem.ValueKind == JsonValueKind.String)
                            {
                                string contentStr = contentElem.GetString().Trim();
                                if (string.IsNullOrEmpty(contentStr))
                                {
                                    // 移除内容为空的消息
                                    continue; // 跳过该消息
                                }
                                else
                                {
                                    // 保留内容
                                    var newMessage = new Dictionary<string, object>
                                    {
                                        { "role", message.GetProperty("role").GetString() },
                                        { "content", contentStr }
                                    };
                                    modifiedMessages.Add(newMessage);
                                }
                            }
                        }
                    }

                    if (modifiedMessages.Count == 0)
                    {
                        await Utils.SendErrorAsync(response, "所有消息的内容均为空。");
                        return;
                    }

                    // 验证模型是否有效
                    if (!Models.Contains(model))
                    {
                        model = "claude-3-5-sonnet";
                    }

                    // 构建新的请求JSON
                    var newRequest = new
                    {
                        function_image_gen = hasImage,
                        function_web_search = true,
                        max_tokens = maxTokens,
                        model = model,
                        source = hasImage ? "chat/image_upload" : "chat/pro",
                        temperature = temperature,
                        top_p = topP,
                        messages = modifiedMessages
                    };

                    string modifiedRequestBody = JsonSerializer.Serialize(newRequest);
                    Console.WriteLine($"修改后的请求 JSON: {modifiedRequestBody}");

                    // 生成Bearer Token
                    string[] tmpToken = BearerTokenGenerator.GetBearer(modifiedRequestBody);

                    if (tmpToken == null || tmpToken.Length == 0)
                    {
                        await Utils.SendErrorAsync(response, "Bearer Token生成失败。");
                        return;
                    }

                    // 构建HTTP请求
                    var apiRequest = Utils.BuildHttpRequest(modifiedRequestBody, tmpToken);

                    // 根据是否有图片和是否为流式响应，调用不同的处理方法
                    if (hasImage && isStream)
                    {
                        await HandleVisionStreamResponse(response, apiRequest);
                    }
                    else if (hasImage && !isStream)
                    {
                        await HandleVisionNormalResponse(response, apiRequest, model);
                    }
                    else if (!hasImage && isStream)
                    {
                        await HandleStreamResponse(response, apiRequest);
                    }
                    else
                    {
                        await HandleNormalResponse(response, apiRequest, model);
                    }
                }
            }catch (Exception ex)
            {
                Console.WriteLine($"内部服务器错误: {ex.Message}");
                await Utils.SendErrorAsync(response, $"内部服务器错误: {ex.Message}");
            }

            // 处理包含图片的流式响应
            async Task HandleVisionStreamResponse(HttpListenerResponse response, HttpRequestMessage request)
            {
                try
                {
                    var apiResponse = await httpClient.SendAsync(request, HttpCompletionOption.ResponseHeadersRead);

                    if (!apiResponse.IsSuccessStatusCode)
                    {
                        await Utils.SendErrorAsync(response, $"API 错误: {apiResponse.StatusCode}");
                        return;
                    }

                    // 设置响应头为SSE
                    response.ContentType = "text/event-stream; charset=utf-8";
                    response.Headers.Add("Cache-Control", "no-cache");
                    response.Headers.Add("Connection", "keep-alive");
                    response.StatusCode = 200;
                    response.SendChunked = true;

                    using var output = response.OutputStream;
                    using var stream = await apiResponse.Content.ReadAsStreamAsync();
                    using var reader = new StreamReader(stream);

                    while (!reader.EndOfStream)
                    {
                        string line = await reader.ReadLineAsync();
                        if (line.StartsWith("data: "))
                        {
                            string data = line.Substring(6).Trim();
                            if (data.Equals("[DONE]", StringComparison.OrdinalIgnoreCase))
                            {
                                // 转发 [DONE] 信号
                                await output.WriteAsync(Encoding.UTF8.GetBytes(line + "\n"));
                                await output.FlushAsync();
                                break;
                            }

                            try
                            {
                                var sseJson = JsonDocument.Parse(data).RootElement;

                                if (sseJson.TryGetProperty("choices", out JsonElement choices))
                                {
                                    foreach (var choice in choices.EnumerateArray())
                                    {
                                        if (choice.TryGetProperty("delta", out JsonElement delta))
                                        {
                                            if (delta.TryGetProperty("content", out JsonElement contentElem))
                                            {
                                                string content = contentElem.GetString();

                                                var newSseJson = new
                                                {
                                                    choices = new[]
                                                    {
                                                        new
                                                        {
                                                            index = choice.GetProperty("index").GetInt32(),
                                                            delta = new { content = content }
                                                        }
                                                    },
                                                    created = sseJson.TryGetProperty("created", out JsonElement createdElem) ? createdElem.GetInt64() : DateTimeOffset.UtcNow.ToUnixTimeSeconds(),
                                                    id = sseJson.TryGetProperty("id", out JsonElement idElem) ? idElem.GetString() : Guid.NewGuid().ToString(),
                                                    model = sseJson.GetProperty("model").GetString(),
                                                    system_fingerprint = "fp_" + Guid.NewGuid().ToString().Replace("-", "").Substring(0, 12)
                                                };

                                                string newSseLine = "data: " + JsonSerializer.Serialize(newSseJson) + "\n\n";
                                                byte[] buffer = Encoding.UTF8.GetBytes(newSseLine);
                                                await output.WriteAsync(buffer, 0, buffer.Length);
                                                await output.FlushAsync();
                                            }

                                            if (delta.TryGetProperty("images", out JsonElement imagesElem))
                                            {
                                                foreach (var imageObj in imagesElem.EnumerateArray())
                                                {
                                                    string imageData = imageObj.GetProperty("data").GetString();

                                                    string content = $"![Image]({imageData})";

                                                    var newSseJson = new
                                                    {
                                                        choices = new[]
                                                        {
                                                            new
                                                            {
                                                                index = choice.GetProperty("index").GetInt32(),
                                                                delta = new { content = content }
                                                            }
                                                        },
                                                        created = sseJson.TryGetProperty("created", out JsonElement createdElem) ? createdElem.GetInt64() : DateTimeOffset.UtcNow.ToUnixTimeSeconds(),
                                                        id = sseJson.TryGetProperty("id", out JsonElement idElem) ? idElem.GetString() : Guid.NewGuid().ToString(),
                                                        model = sseJson.GetProperty("model").GetString(),
                                                        system_fingerprint = "fp_" + Guid.NewGuid().ToString().Replace("-", "").Substring(0, 12)
                                                    };

                                                    string newSseLineImage = "data: " + JsonSerializer.Serialize(newSseJson) + "\n\n";
                                                    byte[] bufferImage = Encoding.UTF8.GetBytes(newSseLineImage);
                                                    await output.WriteAsync(bufferImage, 0, bufferImage.Length);
                                                    await output.FlushAsync();
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            catch (JsonException je)
                            {
                                Console.WriteLine($"JSON解析错误: {je.Message}");
                            }
                            catch (IOException ioe)
                            {
                                Console.WriteLine($"响应发送失败: {ioe.Message}");
                            }
                        }
                    }
                }
                catch (Exception ex)
                {
                    Console.WriteLine($"处理流式响应时发生错误: {ex.Message}");
                    await Utils.SendErrorAsync(response, $"响应发送失败: {ex.Message}");
                }
            }

            // 处理包含图片的非流式响应
            async Task HandleVisionNormalResponse(HttpListenerResponse response, HttpRequestMessage request, string model)
            {
                try
                {
                    var apiResponse = await httpClient.SendAsync(request);
                    if (!apiResponse.IsSuccessStatusCode)
                    {
                        await Utils.SendErrorAsync(response, $"API 错误: {apiResponse.StatusCode}");
                        return;
                    }

                    // 读取所有SSE行
                    string lines = await apiResponse.Content.ReadAsStringAsync();
                    var sseLines = lines.Split(new[] { "\n" }, StringSplitOptions.RemoveEmptyEntries);

                    StringBuilder contentBuilder = new StringBuilder();
                    List<string> imageUrls = new List<string>();

                    foreach (var line in sseLines)
                    {
                        if (line.StartsWith("data: "))
                        {
                            string data = line.Substring(6).Trim();
                            if (data == "[DONE]")
                                break;

                            try
                            {
                                var sseJson = JsonDocument.Parse(data).RootElement;

                                if (sseJson.TryGetProperty("choices", out JsonElement choices))
                                {
                                    foreach (var choice in choices.EnumerateArray())
                                    {
                                        if (choice.TryGetProperty("delta", out JsonElement delta))
                                        {
                                            if (delta.TryGetProperty("content", out JsonElement contentElem))
                                            {
                                                string content = contentElem.GetString();
                                                contentBuilder.Append(content);
                                            }
                                            if (delta.TryGetProperty("images", out JsonElement imagesElem))
                                            {
                                                foreach (var imageObj in imagesElem.EnumerateArray())
                                                {
                                                    string imageData = imageObj.GetProperty("data").GetString();
                                                    imageUrls.Add(imageData);
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            catch (JsonException je)
                            {
                                Console.WriteLine($"JSON解析错误: {je.Message}");
                            }
                        }
                    }

                    // 构建OpenAI API风格的响应JSON
                    var openAIResponse = new
                    {
                        id = "chatcmpl-" + Guid.NewGuid(),
                        @object = "chat.completion",
                        created = DateTimeOffset.UtcNow.ToUnixTimeSeconds(),
                        model = model,
                        choices = new[]
                        {
                            new
                            {
                                index = 0,
                                message = new
                                {
                                    role = "assistant",
                                    content = string.Join("\n", contentBuilder.ToString(), string.Join("\n", imageUrls.Select(url => $"![Image]({url})")))
                                },
                                finish_reason = "stop"
                            }
                        }
                    };

                    string responseBody = JsonSerializer.Serialize(openAIResponse);
                    byte[] buffer = Encoding.UTF8.GetBytes(responseBody);
                    response.ContentType = "application/json; charset=UTF-8"; // 正确设置Content-Type
                    response.ContentLength64 = buffer.Length;
                    response.StatusCode = 200;
                    await response.OutputStream.WriteAsync(buffer, 0, buffer.Length);
                }
                catch (Exception ex)
                {
                    Console.WriteLine($"处理非流式响应时发生错误: {ex.Message}");
                    await Utils.SendErrorAsync(response, $"处理响应时发生错误: {ex.Message}");
                }
            }

            // 处理流式响应
            async Task HandleStreamResponse(HttpListenerResponse response, HttpRequestMessage request)
            {
                try
                {
                    var apiResponse = await httpClient.SendAsync(request, HttpCompletionOption.ResponseHeadersRead);
                    if (!apiResponse.IsSuccessStatusCode)
                    {
                        await Utils.SendErrorAsync(response, $"API 错误: {apiResponse.StatusCode}");
                        return;
                    }

                    // 设置响应头为SSE
                    response.ContentType = "text/event-stream; charset=utf-8";
                    response.Headers.Add("Cache-Control", "no-cache");
                    response.Headers.Add("Connection", "keep-alive");
                    response.StatusCode = 200;
                    response.SendChunked = true;

                    using var output = response.OutputStream;
                    using var stream = await apiResponse.Content.ReadAsStreamAsync();
                    using var reader = new StreamReader(stream);

                    while (!reader.EndOfStream)
                    {
                        string line = await reader.ReadLineAsync();
                        if (line.StartsWith("data: "))
                        {
                            string data = line.Substring(6).Trim();
                            if (data.Equals("[DONE]", StringComparison.OrdinalIgnoreCase))
                            {
                                // 转发 [DONE] 信号
                                await output.WriteAsync(Encoding.UTF8.GetBytes(line + "\n"));
                                await output.FlushAsync();
                                break;
                            }

                            try
                            {
                                var sseJson = JsonDocument.Parse(data).RootElement;

                                if (sseJson.TryGetProperty("choices", out JsonElement choices))
                                {
                                    foreach (var choice in choices.EnumerateArray())
                                    {
                                        if (choice.TryGetProperty("delta", out JsonElement delta))
                                        {
                                            if (delta.TryGetProperty("content", out JsonElement contentElem))
                                            {
                                                string content = contentElem.GetString();

                                                var newSseJson = new
                                                {
                                                    choices = new[]
                                                    {
                                                        new
                                                        {
                                                            index = choice.GetProperty("index").GetInt32(),
                                                            delta = new { content = content }
                                                        }
                                                    },
                                                    created = sseJson.TryGetProperty("created", out JsonElement createdElem) ? createdElem.GetInt64() : DateTimeOffset.UtcNow.ToUnixTimeSeconds(),
                                                    id = sseJson.TryGetProperty("id", out JsonElement idElem) ? idElem.GetString() : Guid.NewGuid().ToString(),
                                                    model = sseJson.GetProperty("model").GetString(),
                                                    system_fingerprint = "fp_" + Guid.NewGuid().ToString().Replace("-", "").Substring(0, 12)
                                                };

                                                string newSseLine = "data: " + JsonSerializer.Serialize(newSseJson) + "\n\n";
                                                byte[] bufferSse = Encoding.UTF8.GetBytes(newSseLine);
                                                await output.WriteAsync(bufferSse, 0, bufferSse.Length);
                                                await output.FlushAsync();
                                            }
                                        }
                                    }
                                }
                            }
                            catch (JsonException je)
                            {
                                Console.WriteLine($"JSON解析错误: {je.Message}");
                            }
                            catch (IOException ioe)
                            {
                                Console.WriteLine($"响应发送失败: {ioe.Message}");
                            }
                        }
                    }
                }
                catch (Exception ex)
                {
                    Console.WriteLine($"处理流式响应时发生错误: {ex.Message}");
                    await Utils.SendErrorAsync(response, $"响应发送失败: {ex.Message}");
                }
            }

            // 处理非流式响应
            async Task HandleNormalResponse(HttpListenerResponse response, HttpRequestMessage request, string model)
            {
                try
                {
                    var apiResponse = await httpClient.SendAsync(request);
                    if (!apiResponse.IsSuccessStatusCode)
                    {
                        await Utils.SendErrorAsync(response, $"API 错误: {apiResponse.StatusCode}");
                        return;
                    }

                    // 读取所有SSE行
                    string lines = await apiResponse.Content.ReadAsStringAsync();
                    var sseLines = lines.Split(new[] { "\n" }, StringSplitOptions.RemoveEmptyEntries);

                    StringBuilder contentBuilder = new StringBuilder();

                    foreach (var line in sseLines)
                    {
                        if (line.StartsWith("data: "))
                        {
                            string data = line.Substring(6).Trim();
                            if (data == "[DONE]")
                                break;

                            try
                            {
                                var sseJson = JsonDocument.Parse(data).RootElement;

                                if (sseJson.TryGetProperty("choices", out JsonElement choices))
                                {
                                    foreach (var choice in choices.EnumerateArray())
                                    {
                                        if (choice.TryGetProperty("delta", out JsonElement delta))
                                        {
                                            if (delta.TryGetProperty("content", out JsonElement contentElem))
                                            {
                                                string content = contentElem.GetString();
                                                contentBuilder.Append(content);
                                            }
                                        }
                                    }
                                }
                            }
                            catch (JsonException je)
                            {
                                Console.WriteLine($"JSON解析错误: {je.Message}");
                            }
                        }
                    }

                    var openAIResponse = new
                    {
                        id = "chatcmpl-" + Guid.NewGuid(),
                        @object = "chat.completion",
                        created = DateTimeOffset.UtcNow.ToUnixTimeSeconds(),
                        model = model,
                        choices = new[]
                        {
                            new
                            {
                                index = 0,
                                message = new
                                {
                                    role = "assistant",
                                    content = contentBuilder.ToString()
                                },
                                finish_reason = "stop"
                            }
                        }
                    };

                    string responseBody = JsonSerializer.Serialize(openAIResponse);
                    byte[] buffer = Encoding.UTF8.GetBytes(responseBody);
                    response.ContentType = "application/json; charset=UTF-8"; // 正确设置Content-Type
                    response.ContentLength64 = buffer.Length;
                    response.StatusCode = 200;
                    await response.OutputStream.WriteAsync(buffer, 0, buffer.Length);
                }
                catch (Exception ex)
                {
                    Console.WriteLine($"处理非流式响应时发生错误: {ex.Message}");
                    await Utils.SendErrorAsync(response, $"处理响应时发生错误: {ex.Message}");
                }
            }
        }
    }
}
