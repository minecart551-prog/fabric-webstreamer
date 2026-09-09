package fr.theorozier.webstreamer.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import fr.theorozier.webstreamer.WebStreamerMod;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Lightweight HTTP server that serves local files from the webstreamer
 * files directory. Clients use this to fetch media files referenced by
 * local paths in {@code sources.txt}.
 */
public class WebStreamerHttpServer {

    public static final int DEFAULT_PORT = 25600;

    private HttpServer server;
    private Path filesDir;
    private int port;
    private boolean running = false;

    /** MIME type map for common media formats. */
    private static final Map<String, String> MIME_TYPES = new HashMap<>();

    static {
        MIME_TYPES.put(".mp4", "video/mp4");
        MIME_TYPES.put(".webm", "video/webm");
        MIME_TYPES.put(".mkv", "video/x-matroska");
        MIME_TYPES.put(".avi", "video/x-msvideo");
        MIME_TYPES.put(".mov", "video/quicktime");
        MIME_TYPES.put(".gif", "image/gif");
        MIME_TYPES.put(".png", "image/png");
        MIME_TYPES.put(".jpg", "image/jpeg");
        MIME_TYPES.put(".jpeg", "image/jpeg");
        MIME_TYPES.put(".bmp", "image/bmp");
        MIME_TYPES.put(".svg", "image/svg+xml");
        MIME_TYPES.put(".m3u8", "application/vnd.apple.mpegurl");
        MIME_TYPES.put(".ts", "video/mp2t");
        MIME_TYPES.put(".mp3", "audio/mpeg");
        MIME_TYPES.put(".ogg", "audio/ogg");
        MIME_TYPES.put(".wav", "audio/wav");
        MIME_TYPES.put(".flac", "audio/flac");
    }

    public WebStreamerHttpServer(Path filesDir, int port) {
        this.filesDir = filesDir.toAbsolutePath().normalize();
        this.port = port;
    }

    /**
     * Start the HTTP server. Creates the files directory if it doesn't exist.
     */
    public void start() throws IOException {
        Files.createDirectories(this.filesDir);

        this.server = HttpServer.create(new InetSocketAddress(this.port), 0);
        this.server.createContext("/", this::handleRequest);
        this.server.setExecutor(Executors.newFixedThreadPool(2));
        this.server.start();
        this.running = true;

        WebStreamerMod.LOGGER.info("[Server] WebStreamer HTTP server started on port {}, serving from {}", this.port, this.filesDir);
    }

    /**
     * Stop the HTTP server.
     */
    public void stop() {
        if (this.server != null) {
            this.server.stop(0);
            this.server = null;
        }
        this.running = false;
        WebStreamerMod.LOGGER.info("[Server] WebStreamer HTTP server stopped");
    }

    public boolean isRunning() {
        return this.running;
    }

    public int getPort() {
        return this.port;
    }

    public Path getFilesDir() {
        return this.filesDir;
    }

    private void handleRequest(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();

            // Normalize and prevent directory traversal
            path = normalizePath(path);
            if (path == null) {
                sendError(exchange, 400, "Invalid path");
                return;
            }

            Path filePath = this.filesDir.resolve(path).normalize();

            // Ensure the resolved path is still within the files directory
            if (!filePath.startsWith(this.filesDir)) {
                sendError(exchange, 403, "Forbidden");
                return;
            }

            if (!Files.exists(filePath) || Files.isDirectory(filePath)) {
                sendError(exchange, 404, "File not found: " + path);
                return;
            }

            String mimeType = getMimeType(path);
            long fileSize = Files.size(filePath);

            exchange.getResponseHeaders().set("Content-Type", mimeType);
            exchange.getResponseHeaders().set("Content-Length", String.valueOf(fileSize));
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.sendResponseHeaders(200, fileSize);

            try (OutputStream os = exchange.getResponseBody()) {
                Files.copy(filePath, os);
            }
        } catch (Exception e) {
            WebStreamerMod.LOGGER.error("[Server] HTTP request failed: {}", e.getMessage());
            try {
                sendError(exchange, 500, "Internal server error");
            } catch (Exception ignored) { }
        }
    }

    /**
     * Normalize a URL path: strip leading slash, resolve segments, prevent traversal.
     * @return The normalized relative path, or null if invalid.
     */
    private static String normalizePath(String path) {
        if (path == null || path.isEmpty() || path.equals("/")) {
            return "";
        }
        // Remove leading slash
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        // Remove trailing slash
        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (path.isEmpty()) {
            return "";
        }
        // Reject obviously malicious patterns
        if (path.contains("..") || path.contains("\\") || path.contains("\0")) {
            return null;
        }
        return path;
    }

    private static String getMimeType(String path) {
        int dot = path.lastIndexOf('.');
        if (dot >= 0) {
            String ext = path.substring(dot).toLowerCase();
            String mime = MIME_TYPES.get(ext);
            if (mime != null) {
                return mime;
            }
        }
        return "application/octet-stream";
    }

    private static void sendError(HttpExchange exchange, int code, String message) throws IOException {
        byte[] body = message.getBytes();
        exchange.getResponseHeaders().set("Content-Type", "text/plain");
        exchange.sendResponseHeaders(code, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

}
