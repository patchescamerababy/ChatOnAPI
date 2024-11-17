using System;
using System.Net;
using System.Text;
using System.Text.Json;

namespace ChatOnServer
{
    public static class Utils
    {
        /// <summary>
        /// 构建通用的 HttpRequest
        /// </summary>
        public static HttpRequestMessage BuildHttpRequest(string modifiedRequestBody, string[] tmpToken)
        {
            var request = new HttpRequestMessage(HttpMethod.Post, "https://api.chaton.ai/chats/stream");
            request.Headers.Add("Date", tmpToken[1]);
            request.Headers.Add("Client-time-zone", "-05:00");
            request.Headers.Add("Authorization", tmpToken[0]);
            request.Headers.Add("User-Agent", "ChatOn_Android/1.53.502");
            request.Headers.Add("Accept-Language", "en-US");
            request.Headers.Add("X-Cl-Options", "hb");
            request.Content = new StringContent(modifiedRequestBody, Encoding.UTF8, "application/json"); // charset通过编码指定
            return request;
        }

        /// <summary>
        /// 发送错误响应
        /// </summary>
        public static void SendError(HttpListenerResponse response, string message)
        {
            try
            {
                var errorObj = new
                {
                    error = new
                    {
                        message = message,
                        type = "invalid_request_error",
                        param = (string)null,
                        code = (string)null
                    }
                };
                string errorJson = JsonSerializer.Serialize(errorObj);
                byte[] buffer = Encoding.UTF8.GetBytes(errorJson);
                response.ContentType = "application/json; charset=UTF-8";
                response.ContentLength64 = buffer.Length;
                response.StatusCode = 500;
                response.OutputStream.Write(buffer, 0, buffer.Length);
                response.OutputStream.Close(); // 发送响应后关闭流
            }
            catch (Exception ex)
            {
                Console.WriteLine($"SendError异常: {ex.Message}");
            }
        }
        public static async Task SendErrorAsync(HttpListenerResponse response, string message)
        {
            try
            {
                var errorObj = new
                {
                    error = new
                    {
                        message = message,
                        type = "invalid_request_error",
                        param = (string)null,
                        code = (string)null
                    }
                };
                string errorJson = JsonSerializer.Serialize(errorObj);
                byte[] buffer = Encoding.UTF8.GetBytes(errorJson);
                response.ContentType = "application/json; charset=UTF-8";
                response.ContentLength64 = buffer.Length;
                response.StatusCode = 500;
                await response.OutputStream.WriteAsync(buffer, 0, buffer.Length);
                response.OutputStream.Close(); // 发送响应后关闭流
            }
            catch (Exception ex)
            {
                Console.WriteLine($"SendErrorAsync异常: {ex.Message}");
            }
        }
    }
}
