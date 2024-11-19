// ModelsHandler.java
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class ModelsHandler implements HttpHandler {
    public static final List<JSONObject> models = new ArrayList<>();

    static {
        models.add(new JSONObject().put("id", "gpt-4o").put("object", "model"));
        models.add(new JSONObject().put("id", "gpt-4o-mini").put("object", "model"));
        models.add(new JSONObject().put("id", "claude").put("object", "model"));
    }
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        // 仅允许GET请求
        if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
            exchange.sendResponseHeaders(405, -1); // Method Not Allowed
            return;
        }

        try {

            // 构建响应JSON
            JSONObject responseJson = new JSONObject();
            responseJson.put("object", "list");
            responseJson.put("data", models);

            byte[] responseBytes = responseJson.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, responseBytes.length);

            OutputStream os = exchange.getResponseBody();
            os.write(responseBytes);
            os.close();
        } catch (Exception e) {
            e.printStackTrace();
            exchange.sendResponseHeaders(500, -1);
        }
    }
}
