using System;  
using System.Net;
using System.Threading.Tasks;
using System.IO;
using System.Text;

namespace ChatOnServer
{
    class Program
    {
        public static readonly string[] Models = { "gpt-4o", "gpt-4o-mini", "claude-3-5-sonnet", "claude" };
        public static int Port = 8080;
        public static string BaseURL = "http://localhost";

        [STAThread]
        static async Task Main(string[] args)
        {
            Console.WriteLine("启动服务器...");

            int initialPort = 8080; // 默认端口设置为8080
            if (args.Length > 0)
            {
                if (int.TryParse(args[0], out int parsedPort))
                {
                    initialPort = parsedPort;
                }
                else
                {
                    Console.WriteLine("无效的端口号，使用默认端口 8080。");
                }
            }

            string baseURL = "http://localhost";
            if (args.Length > 1)
            {
                baseURL = args[1];
            }

            if (args.Length == 1)
            {
                Console.WriteLine($"未提供 Base URL，使用默认值: {baseURL}");
            }

            // 确保 images 文件夹存在
            string imagesDir = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "images");
            if (!Directory.Exists(imagesDir))
            {
                try
                {
                    Directory.CreateDirectory(imagesDir);
                    Console.WriteLine($"Created Images folder: {imagesDir}");
                }
                catch (Exception ex)
                {
                    Console.WriteLine($"Failed to create Images folder: {ex.Message}");
                    Environment.Exit(1);
                }
            }

            HttpListener listener = CreateHttpServer(initialPort);

            var completionHandler = new CompletionHandler(baseURL, imagesDir);
            var textToImageHandler = new TextToImageHandler();

            Console.WriteLine($"服务器已启动，监听端口 {Port}");

            while (true)
            {
                try
                {
                    var context = await listener.GetContextAsync();
                    _ = Task.Run(async () => await HandleRequest(context, completionHandler, textToImageHandler));
                }
                catch (HttpListenerException ex)
                {
                    Console.WriteLine($"HttpListener 异常: {ex.Message}");
                    if (!listener.IsListening)
                    {
                        Console.WriteLine("HttpListener 已停止监听。退出循环。");
                        break;
                    }
                }
                catch (ObjectDisposedException)
                {
                    Console.WriteLine("HttpListener 已关闭。退出循环。");
                    break;
                }
                catch (Exception ex)
                {
                    Console.WriteLine($"未知异常: {ex.Message}");
                }
            }

            listener.Close();
            Console.WriteLine("服务器已关闭。");
        }

        public static HttpListener CreateHttpServer(int initialPort)
        {
            int port = initialPort;

            while (port <= 65535)
            {
                var listener = new HttpListener();
                try
                {
                    listener.Prefixes.Add($"http://*:{port}/");
                    listener.Start();
                    Console.WriteLine($"服务器绑定到端口 {port}");
                    Port = port;
                    return listener; // 绑定成功，返回监听器
                }
                catch (HttpListenerException ex)
                {
                    if (ex.ErrorCode == 5) // 访问被拒绝
                    {
                        Console.WriteLine("访问被拒绝。请以管理员身份运行程序。");
                        Environment.Exit(1);
                    }
                    else
                    {
                        Console.WriteLine($"端口 {port} 已被占用或不可用。尝试下一个端口...");
                        listener.Close(); // 释放监听器资源
                        port++; // 增加端口并重试
                    }
                }
                catch (Exception ex)
                {
                    Console.WriteLine($"绑定端口 {port} 时发生异常: {ex.Message}");
                    listener.Close();
                    port++; // 增加端口并重试
                }
            }

            Console.WriteLine($"无法从端口 {initialPort} 开始绑定到任何端口。程序即将退出。");
            Environment.Exit(1);
            return null;\
        }

        private static async Task HandleRequest(HttpListenerContext context, CompletionHandler completionHandler, TextToImageHandler textToImageHandler)
        {
            try
            {
                string path = context.Request.Url.AbsolutePath;
                if (path.StartsWith("/v1/chat/completions", StringComparison.OrdinalIgnoreCase))
                {
                    await completionHandler.Handle(context);
                }
                else if (path.StartsWith("/v1/images/generations", StringComparison.OrdinalIgnoreCase))
                {
                    await textToImageHandler.Handle(context);
                }
                else if (path.StartsWith("/images/", StringComparison.OrdinalIgnoreCase))
                {
                    await ServeImage(context);
                }
                else if (path.Equals("/v1/models", StringComparison.OrdinalIgnoreCase))
                {
                    await ServeModels(context);
                }
                else
                {
                    // Not found
                    context.Response.StatusCode = 404;
                    byte[] notFoundBytes = Encoding.UTF8.GetBytes("Not Found");
                    await context.Response.OutputStream.WriteAsync(notFoundBytes, 0, notFoundBytes.Length);
                    context.Response.Close();
                }
            }
            catch (Exception ex)
            {
                Console.WriteLine($"Exception occurred while processing request: {ex.Message}");
                if (context.Response.OutputStream.CanWrite)
                {
                    await Utils.SendErrorAsync(context.Response, $"Internal server error: {ex.Message}");
                }
            }
            // Do not close the response stream here; let the specific handler do that.
        }

        private static async Task ServeModels(HttpListenerContext context)
        {
            context.Response.ContentType = "application/json";
            context.Response.StatusCode = 200;
            string jsonResponse = "{\"object\":\"list\",\"data\":[{\"id\":\"gpt-4o\",\"object\":\"model\"},{\"id\":\"gpt-4o-mini\",\"object\":\"model\"},{\"id\":\"claude-3-5-sonnet\",\"object\":\"model\"},{\"id\":\"claude\",\"object\":\"model\"}]}";
            byte[] responseBytes = Encoding.UTF8.GetBytes(jsonResponse);
            await context.Response.OutputStream.WriteAsync(responseBytes, 0, responseBytes.Length);
            context.Response.Close();
        }

        private static async Task ServeImage(HttpListenerContext context)
        {
            string uriPath = context.Request.Url.AbsolutePath;
            string imageName = uriPath.Substring("/images/".Length);
            string imagePath = Path.Combine("images", imageName);

            if (File.Exists(imagePath))
            {
                string contentType = GetContentType(imagePath);
                context.Response.ContentType = contentType;
                byte[] fileBytes = await File.ReadAllBytesAsync(imagePath);
                context.Response.ContentLength64 = fileBytes.Length;
                await context.Response.OutputStream.WriteAsync(fileBytes, 0, fileBytes.Length);
                context.Response.StatusCode = 200;
                context.Response.Close();
            }
            else
            {
                context.Response.StatusCode = 404;
                byte[] notFoundBytes = Encoding.UTF8.GetBytes("Image Not Found");
                await context.Response.OutputStream.WriteAsync(notFoundBytes, 0, notFoundBytes.Length);
                context.Response.Close();
            }
        }

        private static string GetContentType(string filePath)
        {
            string extension = Path.GetExtension(filePath).ToLower();
            return extension switch
            {
                ".jpg" or ".jpeg" => "image/jpeg",
                ".png" => "image/png",
                ".gif" => "image/gif",
                _ => "application/octet-stream",
            };
        }
    }
}
