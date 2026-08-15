package org.gms.server.bot.environment.platform;

import lombok.extern.slf4j.Slf4j;

import java.awt.Point;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Parses movement packet CSV files and constructs Platform objects.
 *
 * CSV format:
 * - Lines with timestamps (long numbers) are ignored
 * - Lines with single digit numbers (packet counts) are ignored
 * - Movement packets: "0,x,y,..." where x and y are the 2nd and 3rd values
 *
 * Automatically detects whether a platform is FLAT or SLOPED based on Y variance.
 *
 * 移植自 SoloMapling {@code Environment.PlatformParser}。部署路径审计修正（HIGH）：
 * 读取顺序为 classpath（jar 内 src/main/resources/movementDataPackets）优先，
 * 进程 CWD 下 {@code movementDataPackets} 回退——用户把 jar 部署到任意目录
 * （如 /opt/SoloMapling）运行时不再依赖 CWD，平台系统不再静默失效。
 */
@Slf4j
public class PlatformParser {

    private static final String BASE_PATH = "movementDataPackets";

    /** Y variance threshold to determine if platform is sloped (in pixels) */
    private static final int SLOPE_THRESHOLD = 50;

    /**
     * Parses a movement CSV file and returns the raw list of (x, y) coordinates.
     *
     * @param mapId    The map ID (e.g., 910000000)
     * @param fileName The file name without extension (e.g., "m1")
     * @return List of Point objects representing all recorded coordinates
     */
    public static List<Point> parseCoordinates(int mapId, String fileName) {
        // 部署路径审计修正（HIGH）：jar 内 classpath 资源优先（部署到任意目录均可工作），
        // 开发期从项目根运行则回退 CWD 下 movementDataPackets。
        String resourcePath = "/movementDataPackets/map" + mapId + "/" + fileName + ".csv";
        try (InputStream in = PlatformParser.class.getResourceAsStream(resourcePath)) {
            if (in != null) {
                return parseCoordinatesFromStream(in, "classpath:" + resourcePath);
            }
        } catch (IOException e) {
            log.debug("Failed to read coordinates from classpath resource: {}", resourcePath, e);
        }

        Path filePath = Paths.get(BASE_PATH, "map" + mapId, fileName + ".csv");
        try (InputStream in = Files.newInputStream(filePath)) {
            return parseCoordinatesFromStream(in, filePath.toString());
        } catch (IOException e) {
            // perion/elnath 等房间无平台 CSV 属预期，debug 级别避免刷屏。
            log.debug("Failed to parse coordinates from: {}", filePath, e);
        }
        return new ArrayList<>();
    }

    /**
     * 共享解析逻辑：从 InputStream 逐行解析 (x, y) 坐标。
     * 时间戳行与包计数行跳过；IO 中途失败时返回已收集的坐标（与源行为一致）。
     */
    private static List<Point> parseCoordinatesFromStream(InputStream in, String source) {
        List<Point> coordinates = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }

                // Skip timestamp lines (just a long number) and packet count lines (single digit)
                if (isTimestampOrPacketCount(line)) {
                    continue;
                }

                // Parse movement packet: 0,x,y,...
                Point point = parseMovementPacket(line);
                if (point != null) {
                    coordinates.add(point);
                }
            }
        } catch (IOException e) {
            log.debug("Failed to parse coordinates from: {}", source, e);
        }
        return coordinates;
    }

    /**
     * Parses coordinates and organizes them into a Platform object.
     * Automatically detects if platform is FLAT or SLOPED based on Y variance.
     *
     * @param mapId    The map ID (e.g., 910000000)
     * @param fileName The file name without extension (e.g., "m1")
     * @return Platform object with bounds, type, and sorted reference points
     */
    public static Platform parsePlatform(int mapId, String fileName) {
        List<Point> rawCoordinates = parseCoordinates(mapId, fileName);
        return organizePlatform(rawCoordinates);
    }

    /**
     * Parses coordinates into a Platform with explicit type override.
     * Use this if you know the platform type ahead of time.
     *
     * @param mapId    The map ID
     * @param fileName The file name without extension
     * @param type     Explicit platform type (FLAT or SLOPED)
     * @return Platform object
     */
    public static Platform parsePlatform(int mapId, String fileName, Platform.Type type) {
        List<Point> rawCoordinates = parseCoordinates(mapId, fileName);
        return organizePlatform(rawCoordinates, type);
    }

    /**
     * Organizes a raw list of coordinates into a Platform.
     * Automatically detects if platform is FLAT or SLOPED.
     *
     * @param coordinates Raw list of recorded coordinates
     * @return Platform object
     */
    public static Platform organizePlatform(List<Point> coordinates) {
        Platform.Type detectedType = detectPlatformType(coordinates);
        return organizePlatform(coordinates, detectedType);
    }

    /**
     * Organizes a raw list of coordinates into a Platform with explicit type.
     *
     * @param coordinates Raw list of recorded coordinates
     * @param type        Platform type (FLAT or SLOPED)
     * @return Platform object
     */
    public static Platform organizePlatform(List<Point> coordinates, Platform.Type type) {
        if (coordinates == null || coordinates.isEmpty()) {
            return new Platform(0, 0, 0, new ArrayList<>(), type);
        }

        // Deduplicate and sort by X
        List<Point> sortedPoints = coordinates.stream()
                .distinct()
                .sorted(Comparator.comparingInt(p -> p.x))
                .collect(Collectors.toList());

        // Find bounds
        int minX = sortedPoints.get(0).x;
        int maxX = sortedPoints.get(sortedPoints.size() - 1).x;

        // Calculate base Y
        int baseY;
        if (type == Platform.Type.FLAT) {
            // For flat platforms, use the mode (most common Y)
            baseY = calculateModeY(coordinates);
        } else {
            // For sloped platforms, use average Y (informational only)
            baseY = (int) coordinates.stream().mapToInt(p -> p.y).average().orElse(0);
        }

        return new Platform(minX, maxX, baseY, sortedPoints, type);
    }

    /**
     * Detects whether the platform is FLAT or SLOPED based on Y variance.
     */
    private static Platform.Type detectPlatformType(List<Point> coordinates) {
        if (coordinates == null || coordinates.isEmpty()) {
            return Platform.Type.FLAT;
        }

        int minY = coordinates.stream().mapToInt(p -> p.y).min().orElse(0);
        int maxY = coordinates.stream().mapToInt(p -> p.y).max().orElse(0);
        int yRange = maxY - minY;

        return yRange > SLOPE_THRESHOLD ? Platform.Type.SLOPED : Platform.Type.FLAT;
    }

    /**
     * Checks if a line is a timestamp (long number) or packet count (single digit).
     */
    private static boolean isTimestampOrPacketCount(String line) {
        // Single digit = packet count
        if (line.length() == 1 && Character.isDigit(line.charAt(0))) {
            return true;
        }

        // Two digits can also be packet count (e.g., "10", "12")
        if (line.length() == 2 && Character.isDigit(line.charAt(0)) && Character.isDigit(line.charAt(1))) {
            return true;
        }

        // No commas and all digits = timestamp
        if (!line.contains(",")) {
            try {
                Long.parseLong(line);
                return true;
            } catch (NumberFormatException e) {
                return false;
            }
        }

        return false;
    }

    /**
     * Parses a movement packet line and extracts the (x, y) coordinate.
     * Format: "0,x,y,..."
     */
    private static Point parseMovementPacket(String line) {
        String[] parts = line.split(",");
        if (parts.length < 3) {
            return null;
        }

        try {
            int x = Integer.parseInt(parts[1].trim());
            int y = Integer.parseInt(parts[2].trim());
            return new Point(x, y);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Calculates the mode (most frequent value) of Y coordinates.
     */
    private static int calculateModeY(List<Point> coordinates) {
        Map<Integer, Integer> frequencyMap = new HashMap<>();

        for (Point p : coordinates) {
            frequencyMap.merge(p.y, 1, Integer::sum);
        }

        return frequencyMap.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(0);
    }
}
