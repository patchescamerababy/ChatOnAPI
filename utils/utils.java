package utils;

import com.sun.net.httpserver.HttpExchange;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;

public class utils {

    /**
     * 构建通用的 HttpRequest
     *
     * @param modifiedRequestBody 修改后的请求体
     * @param tmpToken            包含 Bearer Token 的数组，tmpToken[0] 为 Authorization，tmpToken[1] 为 Date
     * @return 构建好的 HttpRequest 对象
     */
    public static HttpRequest buildHttpRequest(String modifiedRequestBody, String[] tmpToken) {
        return HttpRequest.newBuilder()
                .uri(URI.create("https://api.chaton.ai/chats/stream"))
                .header("Date", tmpToken[1])
                .header("Client-time-zone", "-05:00")
                .header("Authorization", tmpToken[0])
                .header("User-Agent", "ChatOn_Android/1.53.502")
                .header("Accept-Language", "en-US")
                .header("X-Cl-Options", "hb")
                .header("Content-Type", "application/json; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(modifiedRequestBody))
                .build();
    }

    /**
     * 发送错误响应
     */
    public static void sendError(HttpExchange exchange, String message) {
        try {
            JSONObject error = new JSONObject();
            error.put("error", message);
            byte[] bytes = error.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(500, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
