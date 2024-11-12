import java.io.*;
import java.net.*;
import java.net.http.*;
import java.util.concurrent.*;
import com.sun.net.httpserver.*;

public class Main {
    public static final String[] models = {"gpt-4o", "gpt-4o-mini", "claude-3-5-sonnet", "claude"};
    public static int port = 80;
    private static final HttpClient httpClient = HttpClient.newHttpClient();
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    public static HttpServer createHttpServer(int initialPort) throws IOException {
        int port = initialPort;
        HttpServer server = null;
        while (server == null) {
            try {
                server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
                System.out.println("Server started on port " + port);
                Main.port = port;
            } catch (BindException e) {
                if (port < 65535) {
                    System.err.println("Port " + port + " is already in use. Trying port " + (port + 1));
                    port++;
                } else {
                    System.err.println("All ports from " + initialPort + " to 65535 are in use. Exiting.");
                    System.exit(1);
                }
            }
        }
        return server;
    }

    public static void main(String[] args) throws IOException {
        int port = 80;
        if (args.length > 0) {
            port = Integer.parseInt(args[0]);
        }
        HttpServer server = createHttpServer(port);
        server.createContext("/v1/chat/completions", new CompletionHandler());
        server.createContext("/v1/images/generations", new TextToImageHandler());
        server.setExecutor(executor);
        server.start();
    }
}
