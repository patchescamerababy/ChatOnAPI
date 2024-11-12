import java.io.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import utils.*;
import static utils.utils.buildHttpRequest;
import static utils.utils.sendError;

public class CompletionHandler implements HttpHandler {
    public final String[] models = {"gpt-4o", "gpt-4o-mini", "claude-3-5-sonnet", "claude"};
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        // 设置 CORS 头
        Headers headers = exchange.getResponseHeaders();
        headers.add("Access-Control-Allow-Origin", "*");
        headers.add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        headers.add("Access-Control-Allow-Headers", "Content-Type, Authorization");

        String requestMethod = exchange.getRequestMethod().toUpperCase();

        if (requestMethod.equals("OPTIONS")) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        if ("GET".equals(requestMethod)) {
            // 返回欢迎页面
            String response = "<html><head><title>Welcome to the ChatGPT API</title></head><body><h1>Welcome to the ChatGPT API</h1><p>This API is used to interact with the ChatGPT model. You can send messages to the model and receive responses.</p></body></html>";

            exchange.getResponseHeaders().add("Content-Type", "text/html");
            exchange.sendResponseHeaders(200, response.getBytes(StandardCharsets.UTF_8).length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes(StandardCharsets.UTF_8));
            }
            return;
        }

        if (!"POST".equals(requestMethod)) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        // 异步处理请求
        CompletableFuture.runAsync(() -> {
            try {
                InputStream is = exchange.getRequestBody();
                String requestBody = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))
                        .lines()
                        .reduce("", (acc, line) -> acc + line);

                JSONObject requestJson = new JSONObject(requestBody);
                System.out.println("Received Completion JSON:" + requestJson.toString());

                StringBuilder contentBuilder = new StringBuilder();
                JSONArray messages = requestJson.optJSONArray("messages");
                double temperature = requestJson.optDouble("temperature", 0.6);
                double topP = requestJson.optDouble("top_p", 0.9);
                int maxTokens = requestJson.optInt("max_tokens", 8000);
                String model = requestJson.optString("model", "gpt-4o");
                boolean isStream = requestJson.optBoolean("stream", false);
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
                                    if (contentItem.has("text")) {
                                        contentBuilder.append(contentItem.getString("text"));
                                    }
                                    if (j < contentArray.length() - 1) {
                                        contentBuilder.append(" ");
                                    }
                                }
                                String extractedContent = contentBuilder.toString().trim();
                                if (extractedContent.isEmpty()) {
                                    iterator.remove();
                                    System.out.println("Deleted message with empty content.");
                                } else {
                                    message.put("content", extractedContent);
                                    System.out.println("Extracted: " + extractedContent);
                                }
                            } else if (contentObj instanceof String) {
                                String contentStr = ((String) contentObj).trim();
                                if (contentStr.isEmpty()) {
                                    iterator.remove();
                                    System.out.println("Deleted message with empty content.");
                                } else {
                                    message.put("content", contentStr);
                                    System.out.println("Retained content: " + contentStr);
                                }
                            } else {
                                iterator.remove();
                                System.out.println("Deleted non-expected type of content message.");
                            }
                        }
                    }

                    if (messages.length() == 0) {
                        sendError(exchange, "所有消息的内容均为空。");
                        return;
                    }
                }
                // 检查模型是否有效，如果不设置则发送用户传入的模型
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

                // 构建新的 JSON，替换有关内容
                JSONObject newRequestJson = new JSONObject();
                newRequestJson.put("function_image_gen", true);
                newRequestJson.put("function_web_search", true);
                newRequestJson.put("max_tokens", maxTokens);
                newRequestJson.put("model", model);
                newRequestJson.put("source", "chat/free");
                newRequestJson.put("temperature", temperature);
                newRequestJson.put("top_p", topP);
                newRequestJson.put("messages", messages);

                String modifiedRequestBody = newRequestJson.toString();
                System.out.println("Modified Request JSON: " + newRequestJson.toString());
                // 获取一次性 Bearer Token
                String[] tmpToken = BearerTokenGeneratorNative.GetBearer(modifiedRequestBody);
                // 使用通用的 HttpRequest 构建方法
                HttpRequest request = buildHttpRequest(modifiedRequestBody, tmpToken);

                // 发送请求
                if (isStream) {
                    // 处理流式响应
                    handleStreamResponse(exchange, request);
                } else {
                    // 处理非流式响应
                    handleNormalResponse(exchange, request);
                }

            } catch (Exception e) {
                e.printStackTrace();
                sendError(exchange, "内部服务器错误: " + e.getMessage());
            }
        }, executor);
    }

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

                                                    // 由于进行网络搜索时会出现非标准的 SSE 数据段，因此需要重新构建 SSE 数据段，仅包含 'content'
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

    private void handleNormalResponse(HttpExchange exchange, HttpRequest request){
        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofLines())
                .thenAccept(response -> {
                    try {
                        if (response.statusCode() != 200) {
                            sendError(exchange, "API 错误: " + response.statusCode());
                            return;
                        }

                        // 收集SSE行
                        List<String> sseLines = new ArrayList<>();
                        response.body().forEach(sseLines::add);

                        // 拼接content字段，直到data :[DONE]
                        StringBuilder contentBuilder = new StringBuilder();
                        for (String line : sseLines) {
                            if (line.startsWith("data: ")) {
                                String data = line.substring(6).trim();
                                if (data.equals("[DONE]")) {
                                    break;
                                }
                                try {
                                    JSONObject sseJson = new JSONObject(data);

                                    // 检查是否包含choices数组
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

                        // 构建OpenAI API风格的响应JSON
                        JSONObject openAIResponse = new JSONObject();
                        openAIResponse.put("id", "chatcmpl-" + UUID.randomUUID().toString());
                        openAIResponse.put("object", "chat.completion");
                        openAIResponse.put("created", Instant.now().getEpochSecond());
                        openAIResponse.put("model", "gpt-4o");

                        JSONArray choicesArray = new JSONArray();
                        JSONObject choiceObject = new JSONObject();
                        choiceObject.put("index", 0);

                        JSONObject messageObject = new JSONObject();
                        messageObject.put("role", "assistant");
                        messageObject.put("content", contentBuilder.toString());
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
