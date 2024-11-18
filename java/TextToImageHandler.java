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
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import utils.BearerTokenGenerator;
import utils.utils;

public class TextToImageHandler implements HttpHandler {
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final Executor executor = Executors.newFixedThreadPool(10); // 使用固定大小的线程池

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
                String responseFormat = userInput.optString("response_format", "").trim();
                int n = userInput.optInt("n", 1); // 读取 n 的值，默认为 1

                if (userPrompt.isEmpty()) {
                    utils.sendError(exchange, "Prompt 不能为空。");
                    return;
                }

                System.out.println("Prompt: " + userPrompt);
                System.out.println("Number of images to generate (n): " + n);

                // 设置最大尝试次数为 2 * n
                int maxAttempts = 2 * n;
                System.out.println("Max Attempts: " + maxAttempts);

                // 初始化用于存储多个 URL 的线程安全列表
                List<String> finalDownloadUrls = Collections.synchronizedList(new ArrayList<>());

                // 开始尝试生成图像
                boolean success = attemptGenerateImages(userPrompt, responseFormat, n, maxAttempts, finalDownloadUrls).join();

                if (success) {
                    // 根据 response_format 返回相应的响应
                    boolean isBase64Response = "b64_json".equalsIgnoreCase(responseFormat);

                    JSONObject responseJson = new JSONObject();
                    responseJson.put("created", System.currentTimeMillis() / 1000); // 添加 created 字段
                    JSONArray dataArray = new JSONArray();

                    if (isBase64Response) {
                        // 对每个下载链接进行处理
                        for (String downloadUrl : finalDownloadUrls) {
                            try {
                                // 下载图像并编码为 Base64
                                byte[] imageBytes = downloadImage(downloadUrl);
                                if (imageBytes == null) {
                                    // 如果下载失败，跳过此链接
                                    System.err.println("无法从 URL 下载图像: " + downloadUrl);
                                    continue;
                                }

                                // 转换为 Base64
                                String imageBase64 = Base64.getEncoder().encodeToString(imageBytes);

                                JSONObject dataObject = new JSONObject();
                                dataObject.put("b64_json", imageBase64);
                                dataArray.put(dataObject);
                            } catch (Exception e) {
                                e.printStackTrace();
                                // 继续处理其他图像
                            }
                        }
                    } else {
                        // 直接返回所有图像的 URL
                        for (String downloadUrl : finalDownloadUrls) {
                            JSONObject dataObject = new JSONObject();
                            dataObject.put("url", downloadUrl);
                            dataArray.put(dataObject);
                        }
                    }

                    // 如果收集的 URL 数量不足 n，则通过复制现有的 URL 来填充
                    while (dataArray.length() < n && dataArray.length() > 0) {
                        for (int i = 0; i < dataArray.length() && dataArray.length() < n; i++) {
                            JSONObject original = dataArray.getJSONObject(i);
                            dataArray.put(original);
                        }
                    }

                    responseJson.put("data", dataArray);

                    try {
                        byte[] responseBytes = responseJson.toString().getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().add("Content-Type", "application/json");
                        exchange.sendResponseHeaders(200, responseBytes.length);
                        try (OutputStream os = exchange.getResponseBody()) {
                            os.write(responseBytes);
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                        utils.sendError(exchange, "发送响应时发生错误: " + e.getMessage());
                    }

                } else {
                    // 如果在所有尝试后仍未收集到足够的链接，则返回错误
                    utils.sendError(exchange, "无法生成足够数量的图像。");
                }

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
     * 尝试生成图像，带有重试机制。
     *
     * @param userPrompt       用户的提示
     * @param responseFormat   响应格式
     * @param n                需要生成的图像数量
     * @param maxAttempts      最大尝试次数
     * @param finalDownloadUrls 收集的最终下载链接列表
     * @return CompletableFuture<Boolean> 表示是否成功收集到足够的下载链接
     */
    private CompletableFuture<Boolean> attemptGenerateImages(String userPrompt, String responseFormat, int n,
                                                             int maxAttempts, List<String> finalDownloadUrls) {
        return CompletableFuture.supplyAsync(() -> {
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                int needed = n - finalDownloadUrls.size();
                if (needed <= 0) {
                    break;
                }

                System.out.println("Attempt " + attempt + " - 需要生成的图像数量: " + needed);

                List<CompletableFuture<String>> futures = new ArrayList<>();

                for (int i = 0; i < needed; i++) {
                    int finalAttempt = attempt;
                    CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
                        try {
                            // 构建新的 TextToImage JSON 请求体
                            JSONObject textToImageJson = new JSONObject();
                            textToImageJson.put("function_image_gen", true);
                            textToImageJson.put("function_web_search", true);
                            textToImageJson.put("image_aspect_ratio", "1:1");
                            textToImageJson.put("image_style", "anime"); // 固定 image_style
                            textToImageJson.put("max_tokens", 8000);
                            textToImageJson.put("n", 1); // 每次请求生成一张图像
                            JSONArray messages = new JSONArray();
                            JSONObject message = new JSONObject();
                            message.put("content", "You are a helpful artist, please draw a picture. Based on imagination, draw a picture with user message.");
                            message.put("role", "system");
                            JSONObject userMessage = new JSONObject();
                            userMessage.put("content", "Draw: " + userPrompt);
                            userMessage.put("role", "user");
                            messages.put(message);
                            messages.put(userMessage);
                            textToImageJson.put("messages", messages);
                            textToImageJson.put("model", "gpt-4o"); // 固定 model
                            textToImageJson.put("source", "chat/pro_image"); // 固定 source

                            String modifiedRequestBody = textToImageJson.toString();

                            // 构建请求
                            String[] tmpToken = BearerTokenGenerator.GetBearer(modifiedRequestBody);
                            System.out.println("Attempt " + finalAttempt + " - 构建的请求: " + modifiedRequestBody);
                            HttpRequest request = utils.buildHttpRequest(modifiedRequestBody, tmpToken);

                            // 发送请求并处理 SSE 流
                            HttpResponse.BodyHandler<Stream<String>> bodyHandler = HttpResponse.BodyHandlers.ofLines();
                            HttpResponse<Stream<String>> response = httpClient.send(request, bodyHandler);

                            if (response.statusCode() != 200) {
                                System.err.println("Attempt " + finalAttempt + " - API 错误: " + response.statusCode());
                                return null;
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
                                            for (int j = 0; j < choices.length(); j++) {
                                                JSONObject choice = choices.getJSONObject(j);
                                                JSONObject delta = choice.optJSONObject("delta");
                                                if (delta != null && delta.has("content")) {
                                                    String content = delta.getString("content");
                                                    urlBuilder.append(content);
                                                }
                                            }
                                        }
                                    } catch (JSONException e) {
                                        System.err.println("Attempt " + finalAttempt + " - JSON解析错误: " + e.getMessage());
                                    }
                                }
                            });

                            String imageMarkdown = urlBuilder.toString();
                            // Step 1: 检查Markdown文本是否为空
                            if (imageMarkdown.isEmpty()) {
                                System.out.println("Attempt " + finalAttempt + " - 无法从 SSE 流中构建图像 Markdown。");
                                return null;
                            }

                            // Step 2: 从Markdown中提取图像路径
                            String extractedPath = extractPathFromMarkdown(imageMarkdown);

                            // Step 3: 如果没有提取到路径，输出错误信息
                            if (extractedPath == null || extractedPath.isEmpty()) {
                                System.out.println("Attempt " + finalAttempt + " - 无法从 Markdown 中提取路径。");
                                return null;
                            }

                            // Step 4: 过滤掉 "https://spc.unk/" 前缀
                            extractedPath = extractedPath.replace("https://spc.unk/", "");

                            // 输出提取到的路径
                            System.out.println("Attempt " + finalAttempt + " - 提取的路径: " + extractedPath);

                            // Step 5: 拼接最终的存储URL
                            String storageUrl = "https://api.chaton.ai/storage/" + extractedPath;
                            System.out.println("Attempt " + finalAttempt + " - 存储URL: " + storageUrl);

                            // 请求 storageUrl 获取 JSON 数据
                            String finalDownloadUrl = fetchGetUrlFromStorage(storageUrl);
                            if (finalDownloadUrl == null || finalDownloadUrl.isEmpty()) {
                                System.out.println("Attempt " + finalAttempt + " - 无法从 storage URL 获取最终下载链接。");
                                return null;
                            }

                            System.out.println("Final Download URL: " + finalDownloadUrl);

                            return finalDownloadUrl;

                        } catch (Exception e) {
                            e.printStackTrace();
                            System.err.println("Attempt " + finalAttempt + " - 处理响应时发生错误: " + e.getMessage());
                            return null;
                        }
                    }, executor);

                    futures.add(future);
                }

                // 等待所有任务完成
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

                // 收集成功的下载链接
                for (CompletableFuture<String> future : futures) {
                    try {
                        String url = future.get();
                        if (url != null && !url.isEmpty()) {
                            finalDownloadUrls.add(url);
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                        System.err.println("Attempt " + attempt + " - 获取下载链接时发生错误: " + e.getMessage());
                    }
                }

                // 检查是否已经收集到足够的下载链接
                if (finalDownloadUrls.size() >= n) {
                    return true;
                }
            }

            // 在所有尝试后，检查是否收集到足够的链接
            if (finalDownloadUrls.size() >= n) {
                return true;
            } else {
                System.out.println("已达到最大尝试次数，仍未收集到足够数量的下载链接。");
                return false;
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
