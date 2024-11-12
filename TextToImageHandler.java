import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import utils.*;
public class TextToImageHandler implements HttpHandler {
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final Executor executor = Executors.newSingleThreadExecutor();

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        // 设置 CORS 头
        Headers headers = exchange.getResponseHeaders();
        headers.add("Access-Control-Allow-Origin", "*");
        headers.add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        headers.add("Access-Control-Allow-Headers", "Content-Type, Authorization");

        String requestMethod = exchange.getRequestMethod().toUpperCase();

        // 处理预检请求
        if (requestMethod.equals("OPTIONS")) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        // 只允许 POST 请求
        if (!"POST".equals(requestMethod)) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        // 异步处理请求
        CompletableFuture.runAsync(() -> {
            try {
                // 读取请求体
                InputStream is = exchange.getRequestBody();
                String requestBody = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))
                        .lines()
                        .reduce("", (acc, line) -> acc + line);

                System.out.println("Received Image Generations JSON: " + requestBody);

                JSONObject userInput = new JSONObject(requestBody);

                // 验证必需的字段
                if (!userInput.has("prompt")) {
                    utils.sendError(exchange, "缺少必需的字段: prompt");
                    return;
                }

                String userPrompt = userInput.optString("prompt", "").trim();
                String responseFormat = userInput.optString("response_format", "b64_json").trim();


                if (userPrompt.isEmpty()) {
                    utils.sendError(exchange, "Prompt 不能为空。");
                    return;
                }

                System.out.println("Prompt: " + userPrompt);

                // 构建新的 TextToImage JSON 请求体
                JSONObject textToImageJson = new JSONObject();
                textToImageJson.put("function_image_gen", true);
                textToImageJson.put("function_web_search", true);
                textToImageJson.put("image_aspect_ratio", "1:1");
                textToImageJson.put("image_style", "photographic"); // 固定 image_style
                textToImageJson.put("max_tokens", 8000);
                JSONArray messages = new JSONArray();
                JSONObject message = new JSONObject();
                message.put("content", "You are a helpful artist, please draw a picture.Based on imagination, draw a picture.");
                message.put("role", "system");
                JSONObject userMessage = new JSONObject();
                userMessage.put("content", userPrompt);
                userMessage.put("role", "user");
                messages.put(message);
                messages.put(userMessage);
                textToImageJson.put("messages", messages);
                textToImageJson.put("model", "gpt-4o"); // 固定 model
                textToImageJson.put("source", "chat/pro_image"); // 固定 source

                String modifiedRequestBody = textToImageJson.toString();

                // 构建请求
                String[] tmpToken = BearerTokenGenerator.GetBearer(modifiedRequestBody);
                HttpRequest request = utils.buildHttpRequest(modifiedRequestBody, tmpToken);

                // 发送请求并处理 SSE 流
                httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofLines())
                        .thenAccept(response -> {
                            try {
                                if (response.statusCode() != 200) {
                                    utils.sendError(exchange, "API 错误: " + response.statusCode());
                                    return;
                                }

                                // 初始化用于拼接 URL 的 StringBuilder
                                StringBuilder urlBuilder = new StringBuilder();

                                // 读取 SSE 流并拼接 URL
                                response.body().forEach(line -> {
                                    if (line.startsWith("data: ")) {
                                        String data = line.substring(6).trim();
                                        if (data.equals("[DONE]")) {
                                            return; // 完成读取
                                        }

                                        try {
                                            JSONObject sseJson = new JSONObject(data);
                                            if (sseJson.has("choices")) {
                                                JSONArray choices = sseJson.getJSONArray("choices");
                                                for (int i = 0; i < choices.length(); i++) {
                                                    JSONObject choice = choices.getJSONObject(i);
                                                    JSONObject delta = choice.optJSONObject("delta");
                                                    if (delta != null && delta.has("content")) {
                                                        String content = delta.getString("content");
                                                        urlBuilder.append(content);
                                                    }
                                                }
                                            }
                                        } catch (JSONException e) {
                                            System.err.println("JSON解析错误: " + e.getMessage());
                                        }
                                    }
                                });

                                String imageMarkdown = urlBuilder.toString();
                                // Step 1: 检查Markdown文本是否为空
                                if (imageMarkdown.isEmpty()) {
                                    System.out.println("无法从 SSE 流中构建图像 Markdown。");
                                    return;
                                }

                                // Step 2: 从Markdown中提取第一个图像路径
                                String extractedPath = extractPathFromMarkdown(imageMarkdown);

                                // Step 3: 如果没有提取到路径，输出错误信息
                                if (extractedPath == null || extractedPath.isEmpty()) {
                                    System.out.println("无法从 Markdown 中提取路径。");
                                    return;
                                }

                                // Step 4: 过滤掉 "https://spc.unk/" 前缀
                                extractedPath = extractedPath.replace("https://spc.unk/", "");

                                // 输出提取到的路径
                                System.out.println("提取的路径: " + extractedPath);

                                // Step 5: 拼接最终的存储URL
                                String storageUrl = "https://api.chaton.ai/storage/" + extractedPath;
                                System.out.println("存储URL: " + storageUrl);

                                // 请求 storageUrl 获取 JSON 数据
                                String finalDownloadUrl = fetchGetUrlFromStorage(storageUrl);
                                if (finalDownloadUrl == null || finalDownloadUrl.isEmpty()) {
                                    utils.sendError(exchange, "无法从 storage URL 获取最终下载链接。");
                                    return;
                                }

                                System.out.println("Final Download URL: " + finalDownloadUrl);

                                // 下载图像
                                byte[] imageBytes = downloadImage(finalDownloadUrl);
                                if (imageBytes == null) {
                                    utils.sendError(exchange, "无法从 URL 下载图像。");
                                    return;
                                }

                                // 转换为 Base64
                                String imageBase64 = Base64.getEncoder().encodeToString(imageBytes);

                                // 根据 response_format 返回相应的响应
                                if ("b64_json".equalsIgnoreCase(responseFormat)) {
                                    JSONObject responseJson = new JSONObject();
                                    JSONArray dataArray = new JSONArray();
                                    JSONObject dataObject = new JSONObject();
                                    dataObject.put("b64_json", imageBase64);
                                    dataArray.put(dataObject);
                                    responseJson.put("data", dataArray);

                                    byte[] responseBytes = responseJson.toString().getBytes(StandardCharsets.UTF_8);
                                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                                    exchange.sendResponseHeaders(200, responseBytes.length);
                                    try (OutputStream os = exchange.getResponseBody()) {
                                        os.write(responseBytes);
                                    }
                                } else {
                                    // 如果有其他 response_format 的需求，可以在此处理
                                    utils.sendError(exchange, "不支持的 response_format: " + responseFormat);
                                }

                            } catch (Exception e) {
                                e.printStackTrace();
                                utils.sendError(exchange, "处理响应时发生错误: " + e.getMessage());
                            }
                        })
                        .exceptionally(ex -> {
                            ex.printStackTrace();
                            utils.sendError(exchange, "请求失败: " + ex.getMessage());
                            return null;
                        });

            } catch (JSONException je) {
                je.printStackTrace();
                utils.sendError(exchange, "JSON 解析错误: " + je.getMessage());
            } catch (Exception e) {
                e.printStackTrace();
                utils.sendError(exchange, "内部服务器错误: " + e.getMessage());
            }
        }, executor);
    }

    /**
     * 提取 Markdown 图片链接中的路径
     *
     * @param markdown 图片的 Markdown 语法字符串
     * @return 提取出的路径，如果失败则返回 null
     */
    public String extractPathFromMarkdown(String markdown) {
        // 正则表达式匹配 ![Image](URL)
        Pattern pattern = Pattern.compile("!\\[.*?\\]\\((.*?)\\)");
        Matcher matcher = pattern.matcher(markdown);

        // 如果找到了匹配的路径，返回第一个URL
        if (matcher.find()) {
            return matcher.group(1);
        }

        // 如果没有找到匹配，返回null
        return null;
    }

    /**
     * 从 storage URL 获取 JSON 并提取 getUrl
     *
     * @param storageUrl 拼接后的 storage URL
     * @return getUrl 的值，如果失败则返回 null
     */
    private String fetchGetUrlFromStorage(String storageUrl) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(storageUrl))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                System.err.println("获取 storage URL 失败，状态码: " + response.statusCode());
                return null;
            }

            JSONObject jsonResponse = new JSONObject(response.body());
            if (jsonResponse.has("getUrl")) {
                return jsonResponse.getString("getUrl");
            } else {
                System.err.println("JSON 响应中缺少 'getUrl' 字段。");
                return null;
            }
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * 下载图像
     *
     * @param imageUrl 图像的最终下载 URL
     * @return 图像的字节数组，失败时返回 null
     */
    private byte[] downloadImage(String imageUrl) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(imageUrl))
                    .GET()
                    .build();

            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

            if (response.statusCode() == 200) {
                return response.body();
            } else {
                System.err.println("下载图像失败，状态码: " + response.statusCode());
                return null;
            }
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}
