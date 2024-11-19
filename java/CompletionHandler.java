import java.io.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import com.sun.net.httpserver.*;

import org.json.*;

import utils.*;

import static utils.utils.buildHttpRequest;
import static utils.utils.sendError;

/**
 * 处理聊天补全请求的处理器
 */
public class CompletionHandler implements HttpHandler {
    // 支持的模型列表
    public final String[] models = {"gpt-4o", "gpt-4o-mini", "claude"};
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ExecutorService executor = Executors.newFixedThreadPool(10);

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        // 设置 CORS 头
        Headers headers = exchange.getResponseHeaders();
        headers.add("Access-Control-Allow-Origin", "*");
        headers.add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        headers.add("Access-Control-Allow-Headers", "Content-Type, Authorization");

        String requestMethod = exchange.getRequestMethod().toUpperCase();

        if (requestMethod.equals("OPTIONS")) {
            // 处理预检请求
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        if ("GET".equals(requestMethod)) {
            // 返回欢迎页面
            String response = "<html><head><title>欢迎使用API</title></head><body><h1>欢迎使用API</h1><p>此 API 用于与 ChatGPT / Claude 模型交互。您可以发送消息给模型并接收响应。</p></body></html>";

            exchange.getResponseHeaders().add("Content-Type", "text/html");
            exchange.sendResponseHeaders(200, response.getBytes(StandardCharsets.UTF_8).length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes(StandardCharsets.UTF_8));
            }
            return;
        }

        if (!"POST".equals(requestMethod)) {
            // 不支持的方法
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        // 异步处理请求
        CompletableFuture.runAsync(() -> {
            try {
                //读取请求头
                Headers requestHeaders = exchange.getRequestHeaders();
                String Authorization = requestHeaders.getFirst("Authorization");
                //截取Bearer 后的内容
                try{
                    String ReceivedToken = Authorization.substring(7);
                }catch (StringIndexOutOfBoundsException e){

                    e.printStackTrace();
                }


                // 读取请求体
                InputStream is = exchange.getRequestBody();
                String requestBody = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))
                        .lines()
                        .reduce("", (acc, line) -> acc + line);

                JSONObject requestJson = new JSONObject(requestBody);

                //System.out.println("接收到的补全请求 JSON: " + requestJson.toString());

                StringBuilder contentBuilder = new StringBuilder();
                JSONArray messages = requestJson.optJSONArray("messages");
                double temperature = requestJson.optDouble("temperature", 0.6);
                double topP = requestJson.optDouble("top_p", 0.9);
                int maxTokens = requestJson.optInt("max_tokens", 8000);
                String model = requestJson.optString("model", "gpt-4o");
                boolean isStream = requestJson.optBoolean("stream", false);
                boolean hasImage = false;
                String imageFilename = null;
                String imageURL = null;

                if (requestJson.has("messages")) {
                    messages = requestJson.getJSONArray("messages");
                    Iterator<Object> iterator = messages.iterator();
                    while (iterator.hasNext()) {
                        JSONObject message = (JSONObject) iterator.next();
                        if (message.has("content")) {
                            Object contentObj = message.get("content");
                            if (contentObj instanceof JSONArray contentArray) {
                                for (int j = 0; j < contentArray.length(); j++) {
                                    JSONObject contentItem = contentArray.getJSONObject(j);
                                    if (contentItem.has("type")) {
                                        String type = contentItem.getString("type");
                                        if (type.equals("text") && contentItem.has("text")) {
                                            // 处理文本内容
                                            String text = contentItem.getString("text");
                                            contentBuilder.append(text);
                                            if (j < contentArray.length() - 1) {
                                                contentBuilder.append(" ");
                                            }
                                        } else if (type.equals("image_url") && contentItem.has("image_url")) {
                                            // 处理图片内容
                                            JSONObject imageUrlObj = contentItem.getJSONObject("image_url");
                                            String dataUrl = imageUrlObj.getString("url");
                                            if (dataUrl.startsWith("data:image/")) {
                                                // 处理 base64 编码的图片
                                                String base64Data = dataUrl.substring(dataUrl.indexOf("base64,") + 7);
                                                byte[] imageBytes = java.util.Base64.getDecoder().decode(base64Data);
                                                // 生成 UUID 文件名
                                                String uuid = UUID.randomUUID().toString();
                                                String extension = "jpg"; // 默认扩展名
                                                if (dataUrl.startsWith("data:image/png")) {
                                                    extension = "png";
                                                } else if (dataUrl.startsWith("data:image/jpeg") || dataUrl.startsWith("data:image/jpg")) {
                                                    extension = "jpg";
                                                }
                                                //按时间搜索一分钟前图片并删除
                                                //删除Image目录所有文件
                                                File imagesDir = new File("images");
//                                                if (imagesDir.exists()) {
//                                                    File[] files = imagesDir.listFiles();
//                                                    //按时间搜索
//                                                    for (File file : files) {
//                                                        if (file.isFile()) {
//                                                            long time = file.lastModified();
//                                                            long now = System.currentTimeMillis();
//                                                            if (now - time > 60000) {
//                                                                file.delete();
//                                                            }
//                                                        }
//                                                    }
//                                                }
                                                imageFilename = uuid + "." + extension;
                                                // 保存图片到 images 目录

                                                if (!imagesDir.exists()) {
                                                    imagesDir.mkdir();
                                                }
                                                File imageFile = new File(imagesDir, imageFilename);
                                                try (FileOutputStream fos = new FileOutputStream(imageFile)) {
                                                    fos.write(imageBytes);
                                                }
                                                // 构建可访问的 URL
                                                imageURL = Main.baseURL + "/images/" + imageFilename;
                                                hasImage = true;
                                                System.out.println("图片已保存: " + imageFilename + ", 可访问 URL: " + imageURL);

                                                // 在消息中添加 images 字段
                                                JSONArray imagesArray = new JSONArray();
                                                JSONObject imageObj = new JSONObject();
                                                imageObj.put("data", imageURL);
                                                imagesArray.put(imageObj);
                                                message.put("images", imagesArray);
                                            } else {
                                                // 处理标准 URL 的图片
                                                imageURL = dataUrl;
                                                hasImage = true;
                                                System.out.println("接收到标准图片 URL: " + imageURL);

                                                // 在消息中添加 images 字段
                                                JSONArray imagesArray = new JSONArray();
                                                JSONObject imageObj = new JSONObject();
                                                imageObj.put("data", imageURL);
                                                imagesArray.put(imageObj);
                                                message.put("images", imagesArray);
                                            }
                                        }
                                    }
                                }

                                // 处理完 contentArray 后，设置消息的 content 字段
                                String extractedContent = contentBuilder.toString().trim();
                                if (extractedContent.isEmpty() && !hasImage) {
                                    // 如果内容为空且没有图片，则移除该消息
                                    iterator.remove();
                                    System.out.println("移除内容为空的消息。");
                                } else {
                                    // 否则，更新内容
                                    message.put("content", extractedContent);
                                    System.out.println("提取的内容: " + extractedContent);
                                }
                            } else if (contentObj instanceof String) {
                                // 处理纯文本内容
                                String contentStr = ((String) contentObj).trim();
                                if (contentStr.isEmpty()) {
                                    iterator.remove();
                                    System.out.println("移除内容为空的消息。");
                                } else {
                                    message.put("content", contentStr);
                                    System.out.println("保留的内容: " + contentStr);
                                }
                            } else {
                                // 移除不符合预期类型的消息
                                iterator.remove();
                                System.out.println("移除非预期类型的消息。");
                            }
                        }
                    }

                    if (messages.isEmpty()) {
                        sendError(exchange, "所有消息的内容均为空。");
                        return;
                    }
                }

                // 验证模型是否有效，如果无效则回退到默认模型
                boolean modelValid = false;
                for (String m : models) {
                    if (model.equals(m)) {
                        modelValid = true;
                        break;
                    }
                }
                if (!modelValid) {
                    model = "gpt-4o";
                }

                // 构建新的请求 JSON，替换相关内容
                JSONObject newRequestJson = new JSONObject();
                newRequestJson.put("function_image_gen", false);
                newRequestJson.put("function_web_search", true);
                newRequestJson.put("max_tokens", maxTokens);
                newRequestJson.put("model", model);
                if (hasImage) {
                    newRequestJson.put("source", "chat/image_upload");
                } else {
                    newRequestJson.put("source", "chat/pro");
                }
                newRequestJson.put("temperature", temperature);
                newRequestJson.put("top_p", topP);
                newRequestJson.put("messages", messages);

                String modifiedRequestBody = newRequestJson.toString();
                System.out.println("修改后的请求 JSON: " + newRequestJson.toString());
                // 获取一次性 Bearer Token
                String[] tmpToken = BearerTokenGenerator.GetBearer(modifiedRequestBody);
                // 使用通用的 HttpRequest 构建方法
                HttpRequest request = buildHttpRequest(modifiedRequestBody, tmpToken);

                // 根据是否有图片和是否为流式响应，调用不同的处理方法
                if (hasImage && isStream) {
                    handleVisionStreamResponse(exchange, request);
                } else if (hasImage && !isStream) {
                    handleVisionNormalResponse(exchange, request, model);
                } else if (!hasImage && isStream) {
                    handleStreamResponse(exchange, request);
                } else {
                    handleNormalResponse(exchange, request, model);
                }

            } catch (Exception e) {
                e.printStackTrace();
                sendError(exchange, "内部服务器错误: " + e.getMessage());
            }
        }, executor);
    }

    /**
     * 处理包含图片的流式响应
     *
     * @param exchange 当前的 HttpExchange 对象
     * @param request  构建好的 HttpRequest 对象
     */
    private void handleVisionStreamResponse(HttpExchange exchange, HttpRequest request) throws IOException {
        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofLines())
                .thenAccept(response -> {
                    try {
                        if (response.statusCode() != 200) {
                            sendError(exchange, "API 错误: " + response.statusCode());
                            return;
                        }

                        Headers responseHeaders = exchange.getResponseHeaders();
                        responseHeaders.add("Content-Type", "text/event-stream; charset=utf-8");
                        responseHeaders.add("Cache-Control", "no-cache");
                        responseHeaders.add("Connection", "keep-alive");
                        exchange.sendResponseHeaders(200, 0);

                        try (OutputStream os = exchange.getResponseBody()) {
                            response.body().forEach(line -> {
                                if (line.startsWith("data: ")) {
                                    String data = line.substring(6).trim();
                                    if (data.equals("[DONE]")) {
                                        try {
                                            // 转发 [DONE] 信号
                                            os.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                                            os.flush();
                                        } catch (IOException e) {
                                            e.printStackTrace();
                                        }
                                        return;
                                    }

                                    try {
                                        JSONObject sseJson = new JSONObject(data);

                                        // 检查是否包含 'choices' 数组
                                        if (sseJson.has("choices")) {
                                            JSONArray choices = sseJson.getJSONArray("choices");
                                            for (int i = 0; i < choices.length(); i++) {
                                                JSONObject choice = choices.getJSONObject(i);
                                                JSONObject delta = choice.optJSONObject("delta");
                                                if (delta != null) {
                                                    // 处理 'content'
                                                    if (delta.has("content")) {
                                                        String content = delta.getString("content");

                                                        // 构建新的 SSE JSON
                                                        JSONObject newSseJson = new JSONObject();
                                                        JSONArray newChoices = new JSONArray();
                                                        JSONObject newChoice = new JSONObject();
                                                        newChoice.put("index", choice.optInt("index", i));

                                                        // 添加 'content' 字段
                                                        JSONObject newDelta = new JSONObject();
                                                        newDelta.put("content", content);
                                                        newChoice.put("delta", newDelta);

                                                        newChoices.put(newChoice);
                                                        newSseJson.put("choices", newChoices);

                                                        // 添加其他字段
                                                        if (sseJson.has("created")) {
                                                            newSseJson.put("created", sseJson.getLong("created"));
                                                        } else {
                                                            newSseJson.put("created", Instant.now().getEpochSecond());
                                                        }

                                                        if (sseJson.has("id")) {
                                                            newSseJson.put("id", sseJson.getString("id"));
                                                        } else {
                                                            newSseJson.put("id", UUID.randomUUID().toString());
                                                        }

                                                        newSseJson.put("model", sseJson.optString("model", "gpt-4o"));
                                                        newSseJson.put("system_fingerprint", "fp_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));

                                                        // 构建新的 SSE 行
                                                        String newSseLine = "data: " + newSseJson + "\n\n";
                                                        os.write(newSseLine.getBytes(StandardCharsets.UTF_8));
                                                        os.flush();
                                                    }

                                                    // 处理 'images'
                                                    if (delta.has("images")) {
                                                        JSONArray imagesArray = delta.getJSONArray("images");
                                                        for (int j = 0; j < imagesArray.length(); j++) {
                                                            JSONObject imageObj = imagesArray.getJSONObject(j);
                                                            String imageData = imageObj.getString("data");

                                                            // 假设 data 是可访问的 URL
                                                            String content = "[Image at " + imageData + "]";

                                                            JSONObject newSseJson = new JSONObject();
                                                            JSONArray newChoices = new JSONArray();
                                                            JSONObject newChoice = new JSONObject();
                                                            newChoice.put("index", choice.optInt("index", i));

                                                            JSONObject newDelta = new JSONObject();
                                                            newDelta.put("content", content);
                                                            newChoice.put("delta", newDelta);

                                                            newChoices.put(newChoice);
                                                            newSseJson.put("choices", newChoices);

                                                            newSseJson.put("created", sseJson.optLong("created", Instant.now().getEpochSecond()));
                                                            newSseJson.put("id", sseJson.optString("id", UUID.randomUUID().toString()));
                                                            newSseJson.put("model", sseJson.optString("model", "gpt-4o"));
                                                            newSseJson.put("system_fingerprint", "fp_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));

                                                            String newSseLine = "data: " + newSseJson + "\n\n";
                                                            os.write(newSseLine.getBytes(StandardCharsets.UTF_8));
                                                            os.flush();
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    } catch (JSONException e) {
                                        System.err.println("JSON解析错误: " + e.getMessage());
                                    } catch (IOException e) {
                                        System.err.println("响应发送失败: " + e.getMessage());
                                        e.printStackTrace();
                                    }
                                }
                            });
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                        sendError(exchange, "响应发送失败: " + e.getMessage());
                    }
                })
                .exceptionally(ex -> {
                    ex.printStackTrace();
                    sendError(exchange, "请求失败: " + ex.getMessage());
                    return null;
                });
    }

    /**
     * 处理包含图片的非流式响应
     *
     * @param exchange 当前的 HttpExchange 对象
     * @param request  构建好的 HttpRequest 对象
     * @param model    使用的模型名称
     */
    private void handleVisionNormalResponse(HttpExchange exchange, HttpRequest request, String model) {
        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofLines())
                .thenAccept(response -> {
                    try {
                        if (response.statusCode() != 200) {
                            sendError(exchange, "API 错误: " + response.statusCode());
                            return;
                        }

                        // 收集 SSE 行
                        List<String> sseLines = new ArrayList<>();
                        response.body().forEach(sseLines::add);

                        // 拼接 content 字段，并收集图片 URL
                        StringBuilder contentBuilder = new StringBuilder();
                        List<String> imageUrls = new ArrayList<>();

                        for (String line : sseLines) {
                            if (line.startsWith("data: ")) {
                                String data = line.substring(6).trim();
                                if (data.equals("[DONE]")) {
                                    break;
                                }
                                try {
                                    JSONObject sseJson = new JSONObject(data);

                                    // 检查是否包含 choices 数组
                                    if (sseJson.has("choices")) {
                                        JSONArray choices = sseJson.getJSONArray("choices");
                                        for (int i = 0; i < choices.length(); i++) {
                                            JSONObject choice = choices.getJSONObject(i);
                                            if (choice.has("delta")) {
                                                JSONObject delta = choice.getJSONObject("delta");
                                                if (delta.has("content")) {
                                                    String content = delta.getString("content");
                                                    contentBuilder.append(content);
                                                }
                                                if (delta.has("images")) {
                                                    JSONArray imagesArray = delta.getJSONArray("images");
                                                    for (int j = 0; j < imagesArray.length(); j++) {
                                                        JSONObject imageObj = imagesArray.getJSONObject(j);
                                                        String imageData = imageObj.getString("data"); // 假设 data 是 URL
                                                        imageUrls.add(imageData);
                                                    }
                                                }
                                            }
                                        }
                                    }
                                } catch (JSONException e) {
                                    System.err.println("JSON解析错误: " + e.getMessage());
                                }
                            }
                        }

                        // 构建 OpenAI API 风格的响应 JSON
                        JSONObject openAIResponse = new JSONObject();
                        openAIResponse.put("id", "chatcmpl-" + UUID.randomUUID().toString());
                        openAIResponse.put("object", "chat.completion");
                        openAIResponse.put("created", Instant.now().getEpochSecond());
                        openAIResponse.put("model", model);

                        JSONArray choicesArray = new JSONArray();
                        JSONObject choiceObject = new JSONObject();
                        choiceObject.put("index", 0);

                        JSONObject messageObject = new JSONObject();
                        messageObject.put("role", "assistant");

                        // 构建包含图片 URL 的 assistant 内容
                        StringBuilder assistantContent = new StringBuilder();
                        assistantContent.append(contentBuilder.toString());
                        for (String imageUrl : imageUrls) {
                            assistantContent.append("\n[Image: ").append(imageUrl).append("]");
                        }
                        messageObject.put("content", assistantContent.toString());
                        System.out.println("从 API 接收到的内容: " + assistantContent.toString());

                        choiceObject.put("message", messageObject);
                        choiceObject.put("finish_reason", "stop");
                        choicesArray.put(choiceObject);

                        openAIResponse.put("choices", choicesArray);

                        exchange.getResponseHeaders().add("Content-Type", "application/json");
                        String responseBody = openAIResponse.toString();
                        exchange.sendResponseHeaders(200, responseBody.getBytes(StandardCharsets.UTF_8).length);
                        try (OutputStream os = exchange.getResponseBody()) {
                            os.write(responseBody.getBytes(StandardCharsets.UTF_8));
                        }

                    } catch (Exception e) {
                        e.printStackTrace();
                        sendError(exchange, "处理响应时发生错误: " + e.getMessage());
                    }
                })
                .exceptionally(ex -> {
                    ex.printStackTrace();
                    sendError(exchange, "请求失败: " + ex.getMessage());
                    return null;
                });
    }

    /**
     * 处理流式响应
     *
     * @param exchange 当前的 HttpExchange 对象
     * @param request  构建好的 HttpRequest 对象
     */
    private void handleStreamResponse(HttpExchange exchange, HttpRequest request) throws IOException {
        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofLines())
                .thenAccept(response -> {
                    try {
                        if (response.statusCode() != 200) {
                            sendError(exchange, "API 错误: " + response.statusCode());
                            return;
                        }

                        Headers responseHeaders = exchange.getResponseHeaders();
                        responseHeaders.add("Content-Type", "text/event-stream; charset=utf-8");
                        responseHeaders.add("Cache-Control", "no-cache");
                        responseHeaders.add("Connection", "keep-alive");
                        exchange.sendResponseHeaders(200, 0);

                        try (OutputStream os = exchange.getResponseBody()) {
                            response.body().forEach(line -> {
                                if (line.startsWith("data: ")) {
                                    String data = line.substring(6).trim();
                                    if (data.equals("[DONE]")) {
                                        try {
                                            // 转发 [DONE] 信号
                                            os.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                                            os.flush();
                                        } catch (IOException e) {
                                            e.printStackTrace();
                                        }
                                        return;
                                    }

                                    try {
                                        JSONObject sseJson = new JSONObject(data);

                                        // 检查是否包含 'choices' 数组
                                        if (sseJson.has("choices")) {
                                            JSONArray choices = sseJson.getJSONArray("choices");
                                            for (int i = 0; i < choices.length(); i++) {
                                                JSONObject choice = choices.getJSONObject(i);
                                                JSONObject delta = choice.optJSONObject("delta");
                                                if (delta != null && delta.has("content")) {
                                                    String content = delta.getString("content");

                                                    // 重新构建 SSE JSON，仅包含 'content'
                                                    JSONObject newSseJson = new JSONObject();
                                                    JSONArray newChoices = new JSONArray();
                                                    JSONObject newChoice = new JSONObject();
                                                    newChoice.put("index", choice.optInt("index", i));

                                                    // 添加 'content' 字段
                                                    JSONObject newDelta = new JSONObject();
                                                    newDelta.put("content", content);
                                                    newChoice.put("delta", newDelta);

                                                    newChoices.put(newChoice);
                                                    newSseJson.put("choices", newChoices);

                                                    if (sseJson.has("created")) {
                                                        newSseJson.put("created", sseJson.getLong("created"));
                                                    } else {
                                                        newSseJson.put("created", Instant.now().getEpochSecond());
                                                    }

                                                    if (sseJson.has("id")) {
                                                        newSseJson.put("id", sseJson.getString("id"));
                                                    } else {
                                                        newSseJson.put("id", UUID.randomUUID().toString());
                                                    }

                                                    newSseJson.put("model", sseJson.optString("model", "gpt-4o"));
                                                    newSseJson.put("system_fingerprint", "fp_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));

                                                    // 构建新的 SSE 行
                                                    String newSseLine = "data: " + newSseJson + "\n\n";
                                                    os.write(newSseLine.getBytes(StandardCharsets.UTF_8));
                                                    os.flush();
                                                }
                                            }
                                        }
                                    } catch (JSONException e) {
                                        System.err.println("JSON解析错误: " + e.getMessage());
                                    } catch (IOException e) {
                                        System.err.println("响应发送失败: " + e.getMessage());
                                        e.printStackTrace();
                                    }
                                }
                            });
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                        sendError(exchange, "响应发送失败: " + e.getMessage());
                    }
                })
                .exceptionally(ex -> {
                    ex.printStackTrace();
                    sendError(exchange, "请求失败: " + ex.getMessage());
                    return null;
                });
    }

    /**
     * 处理非流式响应
     *
     * @param exchange 当前的 HttpExchange 对象
     * @param request  构建好的 HttpRequest 对象
     * @param model    使用的模型名称
     */
    private void handleNormalResponse(HttpExchange exchange, HttpRequest request, String model) {
        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofLines())
                .thenAccept(response -> {
                    try {
                        if (response.statusCode() != 200) {
                            sendError(exchange, "API 错误: " + response.statusCode());
                            return;
                        }

                        // 收集 SSE 行
                        List<String> sseLines = new ArrayList<>();
                        response.body().forEach(sseLines::add);

                        // 拼接 content 字段，直到 data: [DONE]
                        StringBuilder contentBuilder = new StringBuilder();
                        for (String line : sseLines) {
                            if (line.startsWith("data: ")) {
                                String data = line.substring(6).trim();
                                if (data.equals("[DONE]")) {
                                    break;
                                }
                                try {
                                    JSONObject sseJson = new JSONObject(data);

                                    // 检查是否包含 choices 数组
                                    if (sseJson.has("choices")) {
                                        JSONArray choices = sseJson.getJSONArray("choices");
                                        for (int i = 0; i < choices.length(); i++) {
                                            JSONObject choice = choices.getJSONObject(i);
                                            if (choice.has("delta")) {
                                                JSONObject delta = choice.getJSONObject("delta");
                                                if (delta.has("content")) {
                                                    String content = delta.getString("content");
                                                    contentBuilder.append(content);
                                                }
                                            }
                                        }
                                    }
                                } catch (JSONException e) {
                                    System.err.println("JSON解析错误: " + e.getMessage());
                                }
                            }
                        }

                        // 构建 OpenAI API 风格的响应 JSON
                        JSONObject openAIResponse = new JSONObject();
                        openAIResponse.put("id", "chatcmpl-" + UUID.randomUUID().toString());
                        openAIResponse.put("object", "chat.completion");
                        openAIResponse.put("created", Instant.now().getEpochSecond());
                        openAIResponse.put("model", model);

                        JSONArray choicesArray = new JSONArray();
                        JSONObject choiceObject = new JSONObject();
                        choiceObject.put("index", 0);

                        JSONObject messageObject = new JSONObject();
                        messageObject.put("role", "assistant");
                        messageObject.put("content", contentBuilder.toString());
                        System.out.println("从 API 接收到的内容: " + contentBuilder.toString());

                        choiceObject.put("message", messageObject);
                        choiceObject.put("finish_reason", "stop");
                        choicesArray.put(choiceObject);

                        openAIResponse.put("choices", choicesArray);

                        exchange.getResponseHeaders().add("Content-Type", "application/json");
                        String responseBody = openAIResponse.toString();
                        exchange.sendResponseHeaders(200, responseBody.getBytes(StandardCharsets.UTF_8).length);
                        try (OutputStream os = exchange.getResponseBody()) {
                            os.write(responseBody.getBytes(StandardCharsets.UTF_8));
                        }

                    } catch (Exception e) {
                        e.printStackTrace();
                        sendError(exchange, "处理响应时发生错误: " + e.getMessage());
                    }
                })
                .exceptionally(ex -> {
                    ex.printStackTrace();
                    sendError(exchange, "请求失败: " + ex.getMessage());
                    return null;
                });
    }
}
