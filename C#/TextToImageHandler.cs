using System;
using System.Net;
using System.Threading.Tasks;
using System.IO;
using System.Text;
using System.Net.Http;
using System.Text.Json;
using System.Text.RegularExpressions;
using System.Linq;
using System.Threading;

namespace ChatOnServer
{
    public class TextToImageHandler
    {
        private readonly HttpClient httpClient = new HttpClient();
        private readonly SemaphoreSlim semaphore = new SemaphoreSlim(1, 1); // 用于同步文件操作

        public async Task Handle(HttpListenerContext context)
        {
            var request = context.Request;
            var response = context.Response;

            try
            {
                // 设置 CORS 头
                response.Headers.Add("Access-Control-Allow-Origin", "*");
                response.Headers.Add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
                response.Headers.Add("Access-Control-Allow-Headers", "Content-Type, Authorization");

                // 处理预检请求
                if (request.HttpMethod.Equals("OPTIONS", StringComparison.OrdinalIgnoreCase))
                {
                    response.StatusCode = 204; // No Content
                    response.Close();
                    return;
                }

                // 只允许 POST 请求
                if (!request.HttpMethod.Equals("POST", StringComparison.OrdinalIgnoreCase))
                {
                    response.StatusCode = 405; // Method Not Allowed
                    byte[] methodNotAllowedBytes = Encoding.UTF8.GetBytes("Method Not Allowed");
                    await response.OutputStream.WriteAsync(methodNotAllowedBytes, 0, methodNotAllowedBytes.Length);
                    response.Close();
                    return;
                }

                // 读取请求体
                string requestBody;
                using (var reader = new StreamReader(request.InputStream, request.ContentEncoding))
                {
                    requestBody = await reader.ReadToEndAsync();
                }

                Console.WriteLine($"Received Image Generations JSON: {requestBody}");

                // 解析 JSON 请求
                JsonDocument requestJson;
                try
                {
                    requestJson = JsonDocument.Parse(requestBody);
                }
                catch (JsonException je)
                {
                    Utils.SendError(response, $"JSON 解析错误: {je.Message}");
                    return;
                }

                JsonElement root = requestJson.RootElement;

                // 验证 'prompt' 字段是否存在
                if (!root.TryGetProperty("prompt", out JsonElement promptElem))
                {
                    Utils.SendError(response, "缺少必需的字段: prompt");
                    return;
                }

                string prompt = promptElem.GetString()?.Trim() ?? string.Empty;
                if (string.IsNullOrEmpty(prompt))
                {
                    Utils.SendError(response, "Prompt 不能为空。");
                    return;
                }

                // 处理 'response_format'，默认值为 'b64_json'
                string responseFormat = root.TryGetProperty("response_format", out JsonElement formatElem)
                    ? formatElem.GetString()?.Trim() ?? "b64_json"
                    : "b64_json";

                Console.WriteLine($"Prompt: {prompt}");
                Console.WriteLine($"Response Format: {responseFormat}");

                // 构建新的 JSON 负载
                var newRequest = new
                {
                    image_aspect_ratio = "1:1",
                    function_image_gen = true,
                    max_tokens = 8000,
                    function_web_search = true,
                    messages = new[]
                    {
                        new { role = "system", content = "You are a helpful artist, please draw a picture.Based on imagination, draw a picture with user message." },
                        new { role = "user", content = $"Draw: {prompt}" }
                    },
                    model = "gpt-4o",
                    image_style = "anime",
                    source = "chat/pro_image"
                };

                string modifiedRequestBody = JsonSerializer.Serialize(newRequest);
                Console.WriteLine($"构建的请求 JSON: {modifiedRequestBody}");

                // 生成 Bearer Token
                string[] tmpToken = BearerTokenGenerator.GetBearer(modifiedRequestBody);
                if (tmpToken == null || tmpToken.Length == 0)
                {
                    Utils.SendError(response, "Bearer Token 生成失败。");
                    return;
                }

                // 构建 HTTP 请求
                var apiRequest = Utils.BuildHttpRequest(modifiedRequestBody, tmpToken);

                // 发送请求并处理 SSE 流
                using (var apiResponse = await httpClient.SendAsync(apiRequest, HttpCompletionOption.ResponseHeadersRead))
                {
                    if (!apiResponse.IsSuccessStatusCode)
                    {
                        Utils.SendError(response, $"API 错误: {apiResponse.StatusCode}");
                        return;
                    }

                    // 读取响应流
                    using (var stream = await apiResponse.Content.ReadAsStreamAsync())
                    using (var reader = new StreamReader(stream))
                    {
                        StringBuilder urlBuilder = new StringBuilder();

                        string line;
                        while ((line = await reader.ReadLineAsync()) != null)
                        {
                            if (line.StartsWith("data: "))
                            {
                                string data = line.Substring(6).Trim();
                                if (data.Equals("[DONE]", StringComparison.OrdinalIgnoreCase))
                                {
                                    break; // SSE 流结束
                                }

                                try
                                {
                                    JsonDocument sseJson = JsonDocument.Parse(data);
                                    JsonElement sseRoot = sseJson.RootElement;

                                    if (sseRoot.TryGetProperty("choices", out JsonElement choices))
                                    {
                                        foreach (JsonElement choice in choices.EnumerateArray())
                                        {
                                            if (choice.TryGetProperty("delta", out JsonElement delta))
                                            {
                                                if (delta.TryGetProperty("content", out JsonElement contentElem))
                                                {
                                                    string content = contentElem.GetString();
                                                    urlBuilder.Append(content);
                                                }
                                            }
                                        }
                                    }
                                }
                                catch (JsonException je)
                                {
                                    Console.WriteLine($"SSE 中的 JSON 解析错误: {je.Message}");
                                }
                            }
                        }

                        string imageMarkdown = urlBuilder.ToString();
                        Console.WriteLine($"Image Markdown: {imageMarkdown}");

                        // 验证构建的 Markdown
                        if (string.IsNullOrEmpty(imageMarkdown))
                        {
                            Utils.SendError(response, "无法从 SSE 流中构建图像 Markdown。");
                            return;
                        }

                        // 使用正则表达式提取图像路径
                        string extractedPath = ExtractPathFromMarkdown(imageMarkdown);
                        if (string.IsNullOrEmpty(extractedPath))
                        {
                            Utils.SendError(response, "无法从 Markdown 中提取图像路径。");
                            return;
                        }

                        Console.WriteLine($"提取的路径: {extractedPath}");

                        string prefix = "https://spc.unk/";
                        if (extractedPath.StartsWith(prefix, StringComparison.OrdinalIgnoreCase))
                        {
                            extractedPath = extractedPath.Substring(prefix.Length);
                        }

                        Console.WriteLine($"过滤后的路径: {extractedPath}");

                        // 构建存储 URL
                        string storageUrl = $"https://api.chaton.ai/storage/{extractedPath}";
                        Console.WriteLine($"存储 URL: {storageUrl}");

                        // 获取最终下载 URL
                        string finalDownloadUrl = await FetchGetUrlFromStorageAsync(storageUrl);
                        if (string.IsNullOrEmpty(finalDownloadUrl))
                        {
                            Utils.SendError(response, "无法从存储中获取最终下载 URL。");
                            return;
                        }

                        Console.WriteLine($"最终下载 URL: {finalDownloadUrl}");

                        // 下载图像
                        byte[] imageBytes = await DownloadImageAsync(finalDownloadUrl);
                        if (imageBytes == null)
                        {
                            Utils.SendError(response, "无法从最终 URL 下载图像。");
                            return;
                        }

                        // 将图像编码为 Base64
                        string imageBase64 = Convert.ToBase64String(imageBytes);

                        // 根据请求的格式构建响应
                        if (responseFormat.Equals("b64_json", StringComparison.OrdinalIgnoreCase))
                        {
                            var responseJson = new
                            {
                                data = new[]
                                {
                                    new { b64_json = imageBase64 }
                                }
                            };

                            string responseBody = JsonSerializer.Serialize(responseJson);
                            byte[] responseBytes = Encoding.UTF8.GetBytes(responseBody);

                            response.ContentType = "application/json; charset=UTF-8";
                            response.ContentLength64 = responseBytes.Length;
                            response.StatusCode = 200;
                            await response.OutputStream.WriteAsync(responseBytes, 0, responseBytes.Length);
                            response.OutputStream.Close(); // 在这里关闭响应
                        }
                        else
                        {
                            Utils.SendError(response, $"不支持的 response_format: {responseFormat}");
                            return;
                        }
                    }
                }
            }
            catch (Exception ex)
            {
                Console.WriteLine($"内部服务器错误: {ex.Message}");
                Utils.SendError(response, $"内部服务器错误: {ex.Message}");
            }
        }

        /// <summary>
        /// 使用正则表达式从 Markdown 字符串中提取图像路径。
        /// </summary>
        private string ExtractPathFromMarkdown(string markdown)
        {
            // 匹配 ![Image](URL) 的正则表达式
            Regex regex = new Regex(@"!\[.*?\]\((.*?)\)");
            Match match = regex.Match(markdown);

            if (match.Success && match.Groups.Count > 1)
            {
                return match.Groups[1].Value;
            }

            return null;
        }

        /// <summary>
        /// 从存储 URL 的 JSON 响应中获取 'getUrl'。
        /// </summary>
        private async Task<string> FetchGetUrlFromStorageAsync(string storageUrl)
        {
            try
            {
                HttpRequestMessage request = new HttpRequestMessage(HttpMethod.Get, storageUrl);
                HttpResponseMessage response = await httpClient.SendAsync(request);

                if (response.StatusCode != HttpStatusCode.OK)
                {
                    Console.WriteLine($"获取存储 URL 失败。状态码: {response.StatusCode}");
                    return null;
                }

                string responseBody = await response.Content.ReadAsStringAsync();
                JsonDocument jsonResponse = JsonDocument.Parse(responseBody);
                JsonElement root = jsonResponse.RootElement;

                if (root.TryGetProperty("getUrl", out JsonElement getUrlElem))
                {
                    return getUrlElem.GetString();
                }
                else
                {
                    Console.WriteLine("JSON 响应中不包含 'getUrl' 字段。");
                    return null;
                }
            }
            catch (Exception ex)
            {
                Console.WriteLine($"从存储 URL 获取 'getUrl' 时出错: {ex.Message}");
                return null;
            }
        }

        /// <summary>
        /// 从指定的 URL 下载图像。
        /// </summary>
        private async Task<byte[]> DownloadImageAsync(string imageUrl)
        {
            try
            {
                HttpRequestMessage request = new HttpRequestMessage(HttpMethod.Get, imageUrl);
                HttpResponseMessage response = await httpClient.SendAsync(request);

                if (response.StatusCode != HttpStatusCode.OK)
                {
                    Console.WriteLine($"下载图像失败。状态码: {response.StatusCode}");
                    return null;
                }

                return await response.Content.ReadAsByteArrayAsync();
            }
            catch (Exception ex)
            {
                Console.WriteLine($"下载图像时出错: {ex.Message}");
                return null;
            }
        }
    }
}
