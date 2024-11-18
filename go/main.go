// main.go
package main

import (
	"bufio"
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io/ioutil"
	"log"
	"net"
	"net/http"
	"net/url"
	"os"
	"os/signal"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/google/uuid"
)

// Constants and Global Variables
var (
	models       = []string{"gpt-4o", "gpt-4o-mini", "claude-3-5-sonnet", "claude"}
	baseURL      = "http://localhost"
	initialPort  = 8080 // 默认初始端口设置为8080，避免需要root权限
	baseURLMutex sync.RWMutex
	parsedURL    *url.URL
)

// Utility function to send JSON error responses
func sendError(w http.ResponseWriter, statusCode int, message string) {
	if w.Header().Get("Content-Type") == "" {
		w.Header().Set("Content-Type", "application/json")
	}
	w.WriteHeader(statusCode)
	resp := map[string]string{"error": message}
	jsonResp, _ := json.Marshal(resp)
	w.Write(jsonResp)
}

// Utility function to build HTTP requests to external API
func buildHttpRequest(modifiedRequestBody string, tmpToken []string) (*http.Request, error) {
	req, err := http.NewRequest("POST", "https://api.chaton.ai/chats/stream", strings.NewReader(modifiedRequestBody))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Date", tmpToken[1])
	req.Header.Set("Client-time-zone", "-05:00")
	req.Header.Set("Authorization", tmpToken[0])
	req.Header.Set("User-Agent", "ChatOn_Android/1.53.502")
	req.Header.Set("Accept-Language", "en-US")
	req.Header.Set("X-Cl-Options", "hb")
	req.Header.Set("Content-Type", "application/json; charset=UTF-8")
	return req, nil
}

// Handler for /v1/chat/completions
func completionHandler(w http.ResponseWriter, r *http.Request) {
	client := &http.Client{}

	// Set CORS headers
	w.Header().Set("Access-Control-Allow-Origin", "*")
	w.Header().Set("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
	w.Header().Set("Access-Control-Allow-Headers", "Content-Type, Authorization")

	if r.Method == http.MethodOptions {
		// Handle preflight request
		w.WriteHeader(http.StatusNoContent)
		return
	}

	if r.Method == http.MethodGet {
		// Return welcome HTML page
		w.Header().Set("Content-Type", "text/html")
		w.WriteHeader(http.StatusOK)
		html := `<html><head><title>欢迎使用API</title></head><body><h1>欢迎使用API</h1><p>此 API 用于与 ChatGPT / Claude 模型交互。您可以发送消息给模型并接收响应。</p></body></html>`
		w.Write([]byte(html))
		return
	}

	if r.Method != http.MethodPost {
		// Method not allowed
		w.WriteHeader(http.StatusMethodNotAllowed)
		return
	}

	// Handle the POST request synchronously
	// Read request body
	bodyBytes, err := ioutil.ReadAll(r.Body)
	if err != nil {
		sendError(w, http.StatusBadRequest, "无法读取请求体")
		return
	}
	defer r.Body.Close()

	// Parse JSON
	var requestJson map[string]interface{}
	if err := json.Unmarshal(bodyBytes, &requestJson); err != nil {
		sendError(w, http.StatusBadRequest, "JSON解析错误")
		return
	}

	// Process messages
	var contentBuilder strings.Builder
	messages, ok := requestJson["messages"].([]interface{})
	temperature, _ := getFloat(requestJson, "temperature", 0.6)
	topP, _ := getFloat(requestJson, "top_p", 0.9)
	maxTokens, _ := getInt(requestJson, "max_tokens", 8000)
	model, _ := getString(requestJson, "model", "gpt-4o")
	isStream, _ := getBool(requestJson, "stream", false)
	hasImage := false
	var imageFilename, imageURL string

	if ok {
		var newMessages []interface{}
		for _, msg := range messages {
			message, ok := msg.(map[string]interface{})
			if !ok {
				continue
			}
			if content, exists := message["content"]; exists {
				switch contentTyped := content.(type) {
				case []interface{}:
					for _, contentItem := range contentTyped {
						contentMap, ok := contentItem.(map[string]interface{})
						if !ok {
							continue
						}
						if msgType, exists := contentMap["type"].(string); exists {
							if msgType == "text" {
								if text, exists := contentMap["text"].(string); exists {
									contentBuilder.WriteString(text)
									contentBuilder.WriteString(" ")
								}
							} else if msgType == "image_url" {
								if imageURLMap, exists := contentMap["image_url"].(map[string]interface{}); exists {
									if imageURLStr, exists := imageURLMap["url"].(string); exists {
										if strings.HasPrefix(imageURLStr, "data:image/") {
											// Handle base64 encoded image
											parts := strings.Split(imageURLStr, "base64,")
											if len(parts) != 2 {
												continue
											}
											imageBytes, err := base64.StdEncoding.DecodeString(parts[1])
											if err != nil {
												continue
											}
											uuidStr := uuid.New().String()
											extension := "jpg"
											if strings.HasPrefix(imageURLStr, "data:image/png") {
												extension = "png"
											} else if strings.HasPrefix(imageURLStr, "data:image/jpeg") || strings.HasPrefix(imageURLStr, "data:image/jpg") {
												extension = "jpg"
											}

											// Clean up old images
											cleanOldImages("images", 1*time.Minute)

											// Save image
											if _, err := os.Stat("images"); os.IsNotExist(err) {
												err := os.Mkdir("images", os.ModePerm)
												if err != nil {
													return
												}
											}
											imageFilename = fmt.Sprintf("%s.%s", uuidStr, extension)
											imagePath := filepath.Join("images", imageFilename)
											err = ioutil.WriteFile(imagePath, imageBytes, 0644)
											if err != nil {
												log.Println("保存图片失败:", err)
												continue
											}
											// 使用线程安全的方式获取 baseURL
											baseURLMutex.RLock()
											currentBaseURL := baseURL
											baseURLMutex.RUnlock()
											imageURL = fmt.Sprintf("%s/images/%s", currentBaseURL, imageFilename)
											hasImage = true
											log.Printf("图片已保存: %s, 可访问 URL: %s\n", imageFilename, imageURL)

											// Add images field to message
											imagesArray := []map[string]string{
												{
													"data": imageURL,
												},
											}
											message["images"] = imagesArray
										} else {
											// Handle standard image URL
											imageURL = imageURLStr
											hasImage = true
											log.Printf("接收到标准图片 URL: %s\n", imageURL)

											// Add images field to message
											imagesArray := []map[string]string{
												{
													"data": imageURL,
												},
											}
											message["images"] = imagesArray
										}
									}
								}
							}
						}
					}

					// Update content
					extractedContent := strings.TrimSpace(contentBuilder.String())
					if extractedContent == "" && !hasImage {
						// Skip message
						continue
					} else {
						message["content"] = extractedContent
						log.Printf("提取的内容: %s\n", extractedContent)
					}
				case string:
					contentStr := strings.TrimSpace(contentTyped)
					if contentStr == "" {
						// Skip message
						continue
					} else {
						message["content"] = contentStr
						log.Printf("Ss", "__________________________________________________________")
						log.Printf("保留的内容: %s\n", contentStr)
						log.Printf("Ss", "__________________________________________________________")
					}
				default:
					// Skip unexpected types
					continue
				}
				newMessages = append(newMessages, message)
			}
		}
		requestJson["messages"] = newMessages

		if len(newMessages) == 0 {
			sendError(w, http.StatusBadRequest, "所有消息的内容均为空。")
			return
		}
	}

	// Validate model
	modelValid := false
	for _, m := range models {
		if model == m {
			modelValid = true
			break
		}
	}
	if !modelValid {
		model = "claude-3-5-sonnet"
	}

	// Build new request JSON
	newRequestJson := map[string]interface{}{
		"function_image_gen":  false,
		"function_web_search": true,
		"max_tokens":          maxTokens,
		"model":               model,
		"source":              "chat/image_upload",
		"temperature":         temperature,
		"top_p":               topP,
		"messages":            requestJson["messages"],
	}
	if !hasImage {
		newRequestJson["source"] = "chat/pro"
	}

	modifiedRequestBodyBytes, err := json.Marshal(newRequestJson)
	if err != nil {
		sendError(w, http.StatusInternalServerError, "修改请求体时发生错误")
		return
	}
	modifiedRequestBody := string(modifiedRequestBodyBytes)
	//log.Printf("修改后的请求 JSON: %s\n", modifiedRequestBody)

	// Generate Bearer Token
	tmpToken, err := bearerGenerator.GetBearer(modifiedRequestBody)
	if err != nil {
		sendError(w, http.StatusInternalServerError, "生成Bearer Token时发生错误")
		return
	}

	// Build external API request
	apiReq, err := buildHttpRequest(modifiedRequestBody, tmpToken)
	if err != nil {
		sendError(w, http.StatusInternalServerError, "构建外部API请求时发生错误")
		return
	}

	// Send request to external API
	resp, err := client.Do(apiReq)
	if err != nil {
		sendError(w, http.StatusInternalServerError, "请求外部API时发生错误")
		return
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		sendError(w, resp.StatusCode, fmt.Sprintf("API 错误: %d", resp.StatusCode))
		return
	}

	// Handle response based on stream and image presence
	if hasImage && isStream {
		handleImageStreamResponse(w, resp)
	} else if hasImage && !isStream {
		handleVisionNormalResponse(w, resp, model)
	} else if !hasImage && isStream {
		handleStreamResponse(w, resp)
	} else {
		handleNormalResponse(w, resp, model)
	}
}

// Handler for /v1/images/generations
func textToImageHandler(w http.ResponseWriter, r *http.Request) {
	client := &http.Client{}

	// Set CORS headers
	w.Header().Set("Access-Control-Allow-Origin", "*")
	w.Header().Set("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
	w.Header().Set("Access-Control-Allow-Headers", "Content-Type, Authorization")

	if r.Method == http.MethodOptions {
		// Handle preflight request
		w.WriteHeader(http.StatusNoContent)
		return
	}

	if r.Method != http.MethodPost {
		// Only allow POST requests
		w.WriteHeader(http.StatusMethodNotAllowed)
		return
	}

	// Handle the POST request synchronously
	// Read request body
	bodyBytes, err := ioutil.ReadAll(r.Body)
	if err != nil {
		sendError(w, http.StatusBadRequest, "无法读取请求体")
		return
	}
	defer r.Body.Close()

	// Parse JSON
	var userInput map[string]interface{}
	if err := json.Unmarshal(bodyBytes, &userInput); err != nil {
		sendError(w, http.StatusBadRequest, "JSON解析错误")
		return
	}

	fmt.Printf("Received Image Generations JSON: %s\n", string(bodyBytes))

	// Validate required fields
	prompt, ok := getString(userInput, "prompt", "")
	if !ok || prompt == "" {
		sendError(w, http.StatusBadRequest, "缺少必需的字段: prompt")
		return
	}
	responseFormat, _ := getString(userInput, "response_format", "")
	responseFormat = strings.TrimSpace(responseFormat)

	fmt.Printf("Prompt: %s\n", prompt)

	// Build new TextToImage JSON request body
	textToImageJson := map[string]interface{}{
		"function_image_gen":  true,
		"function_web_search": true,
		"image_aspect_ratio":  "1:1",
		"image_style":         "photographic", // 固定 image_style
		"max_tokens":          8000,
		"messages": []interface{}{
			map[string]interface{}{
				"content": "You are a helpful artist, please draw a picture.Based on imagination, draw a picture with user message.",
				"role":    "system",
			},
			map[string]interface{}{
				"content": fmt.Sprintf("Draw: %s", prompt),
				"role":    "user",
			},
		},
		"model":  "gpt-4o",         // 固定 model
		"source": "chat/pro_image", // 固定 source
	}

	modifiedRequestBodyBytes, err := json.Marshal(textToImageJson)
	if err != nil {
		sendError(w, http.StatusInternalServerError, "修改请求体时发生错误")
		return
	}
	modifiedRequestBody := string(modifiedRequestBodyBytes)
	log.Printf("修改后的请求 JSON: %s\n", modifiedRequestBody)

	// Generate Bearer Token
	tmpToken, err := bearerGenerator.GetBearer(modifiedRequestBody)
	if err != nil {
		sendError(w, http.StatusInternalServerError, "生成Bearer Token时发生错误")
		return
	}

	// Build external API request
	apiReq, err := buildHttpRequest(modifiedRequestBody, tmpToken)
	if err != nil {
		sendError(w, http.StatusInternalServerError, "构建外部API请求时发生错误")
		return
	}

	// Send request to external API
	resp, err := client.Do(apiReq)
	if err != nil {
		sendError(w, http.StatusInternalServerError, "请求外部API时发生错误")
		return
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		sendError(w, resp.StatusCode, fmt.Sprintf("API 错误: %d", resp.StatusCode))
		return
	}

	// Handle SSE stream and process image
	var urlBuilder strings.Builder

	scanner := bufio.NewScanner(resp.Body)
	for scanner.Scan() {
		line := scanner.Text()
		if strings.HasPrefix(line, "data: ") {
			data := strings.TrimSpace(line[6:])
			if data == "[DONE]" {
				break // Finished reading
			}

			var sseJson map[string]interface{}
			if err := json.Unmarshal([]byte(data), &sseJson); err != nil {
				log.Println("JSON解析错误:", err)
				continue
			}

			if choices, exists := sseJson["choices"].([]interface{}); exists {
				for _, choice := range choices {
					choiceMap, ok := choice.(map[string]interface{})
					if !ok {
						continue
					}
					if delta, exists := choiceMap["delta"].(map[string]interface{}); exists {
						if content, exists := delta["content"].(string); exists {
							urlBuilder.WriteString(content)
						}
					}
				}
			}
		}
	}

	imageMarkdown := urlBuilder.String()

	// Step 1: Check if Markdown text is empty
	if imageMarkdown == "" {
		log.Println("无法从 SSE 流中构建图像 Markdown。")
		sendError(w, http.StatusInternalServerError, "无法从 SSE 流中构建图像 Markdown。")
		return
	}

	// Step 2: Extract the first image path from Markdown
	extractedPath, err := extractPathFromMarkdown(imageMarkdown)
	if err != nil || extractedPath == "" {
		log.Println("无法从 Markdown 中提取路径。")
		sendError(w, http.StatusInternalServerError, "无法从 Markdown 中提取路径。")
		return
	}

	log.Printf("提取的路径: %s\n", extractedPath)

	// Step 3: Remove the "https://spc.unk/" prefix
	extractedPath = strings.Replace(extractedPath, "https://spc.unk/", "", 1)

	// Step 4: Construct the final storage URL
	storageUrl := fmt.Sprintf("https://api.chaton.ai/storage/%s", extractedPath)
	log.Printf("存储URL: %s\n", storageUrl)

	// Step 5: Fetch the final download URL from storageUrl
	finalDownloadUrl, err := fetchGetUrlFromStorage(storageUrl)
	if err != nil || finalDownloadUrl == "" {
		sendError(w, http.StatusInternalServerError, "无法从 storage URL 获取最终下载链接。")
		return
	}

	log.Printf("Final Download URL: %s\n", finalDownloadUrl)

	// Step 6: Prepare the response based on response_format
	if strings.EqualFold(responseFormat, "b64_json") {
		// Download the image
		imageBytes, err := downloadImage(finalDownloadUrl)
		if err != nil {
			sendError(w, http.StatusInternalServerError, "无法从 URL 下载图像。")
			return
		}

		// Convert image to Base64
		imageBase64 := base64.StdEncoding.EncodeToString(imageBytes)

		responseJson := map[string]interface{}{
			"created": time.Now().Unix(),
			"data": []interface{}{
				map[string]interface{}{
					"b64_json": imageBase64,
				},
			},
		}

		responseBody, _ := json.Marshal(responseJson)
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		w.Write(responseBody)
	} else {
		// Return the URL directly
		responseJson := map[string]interface{}{
			"created": time.Now().Unix(),
			"data": []interface{}{
				map[string]interface{}{
					"url": finalDownloadUrl,
				},
			},
		}

		responseBody, _ := json.Marshal(responseJson)
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		w.Write(responseBody)
	}
}

// Handle Normal Response
func handleNormalResponse(w http.ResponseWriter, resp *http.Response, model string) {
	bodyBytes, err := ioutil.ReadAll(resp.Body)
	if err != nil {
		sendError(w, http.StatusInternalServerError, "读取API响应时发生错误")
		return
	}

	// Parse SSE lines
	lines := bytes.Split(bodyBytes, []byte("\n"))
	var contentBuilder strings.Builder

	for _, line := range lines {
		if bytes.HasPrefix(line, []byte("data: ")) {
			data := strings.TrimSpace(string(line[6:]))
			if data == "[DONE]" {
				break
			}
			var sseJson map[string]interface{}
			if err := json.Unmarshal([]byte(data), &sseJson); err != nil {
				log.Println("JSON解析错误:", err)
				continue
			}
			if choices, exists := sseJson["choices"].([]interface{}); exists {
				for _, choice := range choices {
					choiceMap, ok := choice.(map[string]interface{})
					if !ok {
						continue
					}
					if delta, exists := choiceMap["delta"].(map[string]interface{}); exists {
						if content, exists := delta["content"].(string); exists {
							contentBuilder.WriteString(content)
						}
					}
				}
			}
		}
	}

	// Build OpenAI-style response
	openAIResponse := map[string]interface{}{
		"id":      "chatcmpl-" + uuid.New().String(),
		"object":  "chat.completion",
		"created": getUnixTime(),
		"model":   model,
		"choices": []interface{}{
			map[string]interface{}{
				"index":         0,
				"message":       map[string]interface{}{"role": "assistant", "content": contentBuilder.String()},
				"finish_reason": "stop",
			},
		},
	}

	responseBody, err := json.Marshal(openAIResponse)
	if err != nil {
		sendError(w, http.StatusInternalServerError, "构建响应时发生错误")
		return
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	w.Write(responseBody)
}

// Handle Image Stream Response
func handleImageStreamResponse(w http.ResponseWriter, resp *http.Response) {
	flusher, ok := w.(http.Flusher)
	if !ok {
		sendError(w, http.StatusInternalServerError, "Streaming unsupported")
		return
	}

	w.Header().Set("Content-Type", "text/event-stream; charset=utf-8")
	w.Header().Set("Cache-Control", "no-cache")
	w.Header().Set("Connection", "keep-alive")
	w.WriteHeader(http.StatusOK)
	flusher.Flush()

	scanner := bufio.NewScanner(resp.Body)
	for scanner.Scan() {
		line := scanner.Text()
		if strings.HasPrefix(line, "data: ") {
			data := strings.TrimSpace(line[6:])
			if data == "[DONE]" {
				w.Write([]byte(line + "\n"))
				flusher.Flush()
				break
			}
			var sseJson map[string]interface{}
			if err := json.Unmarshal([]byte(data), &sseJson); err != nil {
				log.Println("JSON解析错误:", err)
				continue
			}
			if choices, exists := sseJson["choices"].([]interface{}); exists {
				for _, choice := range choices {
					choiceMap, ok := choice.(map[string]interface{})
					if !ok {
						continue
					}
					if delta, exists := choiceMap["delta"].(map[string]interface{}); exists {
						if content, exists := delta["content"].(string); exists {
							newSseJson := map[string]interface{}{
								"choices": []interface{}{
									map[string]interface{}{
										"index": 0,
										"delta": map[string]interface{}{
											"content": content,
										},
									},
								},
								"created":            getUnixTime(),
								"id":                 uuid.New().String(),
								"model":              sseJson["model"],
								"system_fingerprint": "fp_" + strings.ReplaceAll(uuid.New().String(), "-", "")[:12],
							}
							newSseLine, _ := json.Marshal(newSseJson)
							w.Write([]byte("data: " + string(newSseLine) + "\n\n"))
							flusher.Flush()
						}
						if images, exists := delta["images"].([]interface{}); exists {
							for _, img := range images {
								imgMap, ok := img.(map[string]interface{})
								if !ok {
									continue
								}
								if imageData, exists := imgMap["data"].(string); exists {
									content := fmt.Sprintf("[Image at %s]", imageData)
									newSseJson := map[string]interface{}{
										"choices": []interface{}{
											map[string]interface{}{
												"index": 0,
												"delta": map[string]interface{}{
													"content": content,
												},
											},
										},
										"created":            getUnixTime(),
										"id":                 uuid.New().String(),
										"model":              sseJson["model"],
										"system_fingerprint": "fp_" + strings.ReplaceAll(uuid.New().String(), "-", "")[:12],
									}
									newSseLine, _ := json.Marshal(newSseJson)
									w.Write([]byte("data: " + string(newSseLine) + "\n\n"))
									flusher.Flush()
								}
							}
						}
					}
				}
			}
		}
	}
}

// Handle Normal Stream Response
func handleStreamResponse(w http.ResponseWriter, resp *http.Response) {
	flusher, ok := w.(http.Flusher)
	if !ok {
		sendError(w, http.StatusInternalServerError, "Streaming unsupported")
		return
	}

	w.Header().Set("Content-Type", "text/event-stream; charset=utf-8")
	w.Header().Set("Cache-Control", "no-cache")
	w.Header().Set("Connection", "keep-alive")
	w.WriteHeader(http.StatusOK)
	flusher.Flush()

	scanner := bufio.NewScanner(resp.Body)
	for scanner.Scan() {
		line := scanner.Text()
		if strings.HasPrefix(line, "data: ") {
			data := strings.TrimSpace(line[6:])
			if data == "[DONE]" {
				w.Write([]byte(line + "\n"))
				flusher.Flush()
				break
			}
			var sseJson map[string]interface{}
			if err := json.Unmarshal([]byte(data), &sseJson); err != nil {
				log.Println("JSON解析错误:", err)
				continue
			}
			if choices, exists := sseJson["choices"].([]interface{}); exists {
				for _, choice := range choices {
					choiceMap, ok := choice.(map[string]interface{})
					if !ok {
						continue
					}
					if delta, exists := choiceMap["delta"].(map[string]interface{}); exists {
						if content, exists := delta["content"].(string); exists {
							newSseJson := map[string]interface{}{
								"choices": []interface{}{
									map[string]interface{}{
										"index": 0,
										"delta": map[string]interface{}{
											"content": content,
										},
									},
								},
								"created":            getUnixTime(),
								"id":                 uuid.New().String(),
								"model":              sseJson["model"],
								"system_fingerprint": "fp_" + strings.ReplaceAll(uuid.New().String(), "-", "")[:12],
							}
							newSseLine, _ := json.Marshal(newSseJson)
							w.Write([]byte("data: " + string(newSseLine) + "\n\n"))
							flusher.Flush()
						}
					}
				}
			}
		}
	}
}

// Handle Vision Normal Response
func handleVisionNormalResponse(w http.ResponseWriter, resp *http.Response, model string) {
	bodyBytes, err := ioutil.ReadAll(resp.Body)
	if err != nil {
		sendError(w, http.StatusInternalServerError, "读取API响应时发生错误")
		return
	}

	// Parse SSE lines
	lines := bytes.Split(bodyBytes, []byte("\n"))
	var contentBuilder strings.Builder
	var imageUrls []string

	for _, line := range lines {
		if bytes.HasPrefix(line, []byte("data: ")) {
			data := strings.TrimSpace(string(line[6:]))
			if data == "[DONE]" {
				break
			}
			var sseJson map[string]interface{}
			if err := json.Unmarshal([]byte(data), &sseJson); err != nil {
				log.Println("JSON解析错误:", err)
				continue
			}
			if choices, exists := sseJson["choices"].([]interface{}); exists {
				for _, choice := range choices {
					choiceMap, ok := choice.(map[string]interface{})
					if !ok {
						continue
					}
					if delta, exists := choiceMap["delta"].(map[string]interface{}); exists {
						if content, exists := delta["content"].(string); exists {
							contentBuilder.WriteString(content)
						}
						if images, exists := delta["images"].([]interface{}); exists {
							for _, img := range images {
								imgMap, ok := img.(map[string]interface{})
								if !ok {
									continue
								}
								if imageData, exists := imgMap["data"].(string); exists {
									imageUrls = append(imageUrls, imageData)
								}
							}
						}
					}
				}
			}
		}
	}

	// Build OpenAI-style response
	openAIResponse := map[string]interface{}{
		"id":      "chatcmpl-" + uuid.New().String(),
		"object":  "chat.completion",
		"created": getUnixTime(),
		"model":   model,
		"choices": []interface{}{
			map[string]interface{}{
				"index":         0,
				"message":       map[string]interface{}{"role": "assistant", "content": buildAssistantContent(contentBuilder.String(), imageUrls)},
				"finish_reason": "stop",
			},
		},
	}

	responseBody, err := json.Marshal(openAIResponse)
	if err != nil {
		sendError(w, http.StatusInternalServerError, "构建响应时发生错误")
		return
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	w.Write(responseBody)
}

// Helper functions to extract types from interface{}
func getFloat(m map[string]interface{}, key string, defaultVal float64) (float64, bool) {
	if val, exists := m[key]; exists {
		switch v := val.(type) {
		case float64:
			return v, true
		case int:
			return float64(v), true
		}
	}
	return defaultVal, false
}

func getInt(m map[string]interface{}, key string, defaultVal int) (int, bool) {
	if val, exists := m[key]; exists {
		switch v := val.(type) {
		case float64:
			return int(v), true
		case int:
			return v, true
		}
	}
	return defaultVal, false
}

func getString(m map[string]interface{}, key string, defaultVal string) (string, bool) {
	if val, exists := m[key]; exists {
		if s, ok := val.(string); ok {
			return s, true
		}
	}
	return defaultVal, false
}

func getBool(m map[string]interface{}, key string, defaultVal bool) (bool, bool) {
	if val, exists := m[key]; exists {
		if b, ok := val.(bool); ok {
			return b, true
		}
	}
	return defaultVal, false
}

// Function to clean images older than the specified duration
func cleanOldImages(dir string, olderThan time.Duration) {
	files, err := ioutil.ReadDir(dir)
	if err != nil {
		log.Println("读取images目录失败:", err)
		return
	}

	cutoff := time.Now().Add(-olderThan)
	for _, file := range files {
		if !file.IsDir() {
			filePath := filepath.Join(dir, file.Name())
			modTime := file.ModTime()
			if modTime.Before(cutoff) {
				err := os.Remove(filePath)
				if err != nil {
					log.Printf("删除旧图片失败: %s\n", filePath)
				} else {
					log.Printf("删除旧图片: %s\n", filePath)
				}
			}
		}
	}
}

// Function to extract image path from Markdown
func extractPathFromMarkdown(markdown string) (string, error) {
	// Regular expression to match ![Image](URL)
	re := regexp.MustCompile(`!\[.*?]\((.*?)\)`)
	matches := re.FindStringSubmatch(markdown)
	if len(matches) < 2 {
		return "", errors.New("无法匹配Markdown中的图片路径")
	}
	return matches[1], nil
}

// Function to fetch the final download URL from storage
func fetchGetUrlFromStorage(storageUrl string) (string, error) {
	client := &http.Client{
		Timeout: 10 * time.Second,
	}
	req, err := http.NewRequest("GET", storageUrl, nil)
	if err != nil {
		return "", err
	}

	resp, err := client.Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("获取 storage URL 失败，状态码: %d", resp.StatusCode)
	}

	bodyBytes, err := ioutil.ReadAll(resp.Body)
	if err != nil {
		return "", err
	}

	var jsonResponse map[string]interface{}
	if err := json.Unmarshal(bodyBytes, &jsonResponse); err != nil {
		return "", err
	}

	if getUrl, exists := jsonResponse["getUrl"].(string); exists {
		return getUrl, nil
	}

	return "", errors.New("JSON 响应中缺少 'getUrl' 字段")
}

// Function to download image from URL
func downloadImage(imageUrl string) ([]byte, error) {
	client := &http.Client{
		Timeout: 30 * time.Second,
	}
	req, err := http.NewRequest("GET", imageUrl, nil)
	if err != nil {
		return nil, err
	}

	resp, err := client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("下载图像失败，状态码: %d", resp.StatusCode)
	}

	imageBytes, err := ioutil.ReadAll(resp.Body)
	if err != nil {
		return nil, err
	}

	return imageBytes, nil
}

// Helper function to build assistant content with images
func buildAssistantContent(content string, imageUrls []string) string {
	var sb strings.Builder
	sb.WriteString(content)
	for _, url := range imageUrls {
		sb.WriteString(fmt.Sprintf("\n[Image: %s]", url))
	}
	return sb.String()
}

// Helper function to get current Unix time
func getUnixTime() int64 {
	return time.Now().Unix()
}

// Function to create HTTP server with port fallback
func createHTTPServer(initialPort int) (*http.Server, int, error) {
	var srv *http.Server
	var finalPort = initialPort

	for finalPort <= 65535 {
		addr := fmt.Sprintf("0.0.0.0:%d", finalPort)
		listener, err := net.Listen("tcp", addr)
		if err != nil {
			if strings.Contains(err.Error(), "address already in use") {
				log.Printf("端口 %d 已被占用，尝试端口 %d\n", finalPort, finalPort+1)
				finalPort++
				continue
			} else {
				return nil, 0, err
			}
		}
		mux := http.NewServeMux()
		mux.HandleFunc("/v1/chat/completions", completionHandler)
		mux.HandleFunc("/v1/images/generations", textToImageHandler)
		mux.HandleFunc("/v1/models", func(w http.ResponseWriter, r *http.Request) {
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusOK)
			w.Write([]byte(`{"object":"list","data":[{"id":"gpt-4o","object":"model"},{"id":"gpt-4o-mini","object":"model"},{"id":"claude-3-5-sonnet","object":"model"},{"id":"claude","object":"model"}]}`))
		})
		// Serve /images/ directory
		fileServer := http.FileServer(http.Dir("./images"))
		mux.Handle("/images/", http.StripPrefix("/images/", fileServer))

		srv = &http.Server{
			Handler: mux,
		}

		log.Printf("服务器已启动，监听端口 %d\n", finalPort)
		go func() {
			if err := srv.Serve(listener); err != nil && err != http.ErrServerClosed {
				log.Fatalf("服务器启动失败: %v\n", err)
			}
		}()
		return srv, finalPort, nil
	}

	return nil, 0, fmt.Errorf("所有端口从 %d 到 65535 都被占用，无法启动服务器", initialPort)
}

func main() {
	// 解析命令行参数
	if len(os.Args) > 1 {
		p, err := strconv.Atoi(os.Args[1])
		if err == nil && p > 0 && p <= 65535 {
			initialPort = p
		} else {
			log.Printf("无效的端口号: %s，使用默认端口 %d\n", os.Args[1], initialPort)
		}
	}
	if len(os.Args) > 2 {
		baseURL = os.Args[2]
	}
	if len(os.Args) == 2 {
		log.Printf("未提供 Base URL，使用默认值: %s\n", baseURL)
	}

	// 解析 baseURL 并验证其有效性
	parsed, err := url.Parse(baseURL)
	if err != nil {
		log.Fatalf("无效的 baseURL: %s, 错误: %v\n", baseURL, err)
	}

	// 确保 baseURL 使用正确的端口，如果未指定则使用默认端口
	if parsed.Port() == "" {
		defaultPort := ""
		switch strings.ToLower(parsed.Scheme) {
		case "http":
			defaultPort = "80"
		case "https":
			defaultPort = "443"
		default:
			log.Fatalf("不支持的协议: %s\n", parsed.Scheme)
		}
		parsed.Host = net.JoinHostPort(parsed.Hostname(), defaultPort)
	}

	// 更新 baseURL
	baseURL = parsed.String()
	parsedURL = parsed

	// 创建 HTTP 服务器
	var srv *http.Server
	port := initialPort
	for {
		var err error
		srv, _, err = createHTTPServer(port)
		if err == nil {
			log.Printf("服务器正在端口 %d 上运行\n", port)
			break // 端口绑定成功，退出循环
		}
		log.Printf("端口 %d 被占用，尝试下一个端口...\n", port)
		port++
		if port > 65535 {
			log.Printf("端口超过 65535，重置为 1024 继续尝试...\n")
			port = 1024
		}
	}

	// 记录最终的 baseURL
	baseURLMutex.RLock()
	log.Printf("最终的 Base URL: %s\n", baseURL)
	baseURLMutex.RUnlock()

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, os.Interrupt, syscall.SIGTERM)
	<-quit
	log.Println("正在关闭服务器...")

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := srv.Shutdown(ctx); err != nil {
		log.Fatalf("服务器关闭失败: %v\n", err)
	}

	log.Println("服务器已成功关闭")
}
