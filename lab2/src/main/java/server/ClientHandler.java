package main.java.server;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class ClientHandler implements Runnable {
    private final Socket socket;

    private boolean isValid(long size, String fileName, int row) {
    long MAX_FILE_SIZE = 1024L * 1024L * 1024L * 1024L; // 1 ТБ
    int MAX_FILENAME_BYTES = 4096;

    if (fileName.getBytes(StandardCharsets.UTF_8).length > MAX_FILENAME_BYTES) {
        ConsoleDashboard.printAt(row, String.format("[Ошибка %s]: Имя файла превышает 4096 байт в UTF-8", truncate(fileName, 15)));
        return false;
    }

    if (size < 0) {
        ConsoleDashboard.printAt(row, String.format("[Ошибка %s]: Отрицательный размер файла (%d байт)", truncate(fileName, 15), size));
        return false;
    }

    if (size > MAX_FILE_SIZE) {
        ConsoleDashboard.printAt(row, String.format("[Ошибка %s]: Размер превышает лимит в 1 ТБ (%d байт)", truncate(fileName, 15), size));
        return false;
    }

    return true;
    }

    private static File getUniqueFile(File dir, String fileName) {
    File file = new File(dir, fileName);
    if (!file.exists()) {
        return file;
    }

    String baseName = fileName;
    String extension = "";

    int dotIndex = fileName.lastIndexOf('.');
    if (dotIndex > 0) { // если точка есть и она не в самом начале (.gitignore)
        baseName = fileName.substring(0, dotIndex);
        extension = fileName.substring(dotIndex); // например, ".txt"
    }

    int counter = 1;
    while (file.exists()) {
        file = new File(dir, baseName + " (" + counter + ")" + extension);
        counter++;
    }

    return file;
}

    public ClientHandler(Socket socket) {
        this.socket = socket;
    }

    @Override
    public void run() {
        String clientIp = this.socket.getRemoteSocketAddress().toString().replace("/", "");
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        int row = ConsoleDashboard.allocateRow();
        try (
            DataInputStream input = new DataInputStream(this.socket.getInputStream());
            DataOutputStream output = new DataOutputStream(this.socket.getOutputStream())
        ) {
            
            String fileName = Paths.get(input.readUTF()).getFileName().toString();
            
            long size = input.readLong();

            if (!isValid(size, fileName, row)) {
            output.writeBoolean(false); // сообщаем клиенту о неудаче
            return; // выходим из try, socket.close() сработает в finally (пункт 11)
            }

            File uploadDir = new File("uploads");
            if (!uploadDir.exists()) {
                uploadDir.mkdir();
            }

            File targetFile = getUniqueFile(uploadDir, fileName);

            try (FileOutputStream outputFile = new FileOutputStream(targetFile)) {
                byte[] buffer = new byte[8192];
                long bytesRead = 0;
                boolean isSuccess = true;

                AtomicLong bytesReceived = new AtomicLong(0);
                long startTime = System.currentTimeMillis();

                scheduler.scheduleAtFixedRate(new Runnable() {
                    private long lastBytes = 0;
                    private long lastTime = startTime;

                    @Override
                    public void run() {
                        long now = System.currentTimeMillis();
                        long current = bytesReceived.get();

                        double interval = (now - lastTime) / 1000.0;
                        double instantSpeed = (current - lastBytes) / (interval > 0 ? interval : 1);

                        double totalTime = (now - startTime) / 1000.0;
                        double avgSpeed = current / (totalTime > 0 ? totalTime : 0.001);
                        double percent = (size > 0) ? (current * 100.0 / size) : 100.0;

                        // Форматируем строку в виде таблицы
                        String rowText = String.format("%-21s %-18s %6.1f%% %-15s %-15s %s",
                            truncate(clientIp, 20),
                            truncate(fileName, 17),
                            percent,
                            formatSpeed(instantSpeed),
                            formatSpeed(avgSpeed),
                            "Загрузка...");

                        ConsoleDashboard.printAt(row, rowText);

                        lastBytes = current;
                        lastTime = now;
                    }
                }, 3, 3, TimeUnit.SECONDS);

                // Чтение данных файла
                while (bytesRead < size) {
                    int read = input.read(buffer, 0, (int) Math.min(size - bytesRead, buffer.length));
                    if (read == -1) break;
                    outputFile.write(buffer, 0, read);
                    bytesRead += read;
                    bytesReceived.set(bytesRead);
                }

                scheduler.shutdown();

                long totalDuration = System.currentTimeMillis() - startTime;
                double totalSec = totalDuration / 1000.0;
                double finalSpeed = bytesRead / (totalSec > 0 ? totalSec : 0.001);

                if (bytesRead < size) {
                    isSuccess = false;
                }

                // Итоговый статус на той же строке
                String status = isSuccess ? "Успешно" : "Ошибка";
                double finalPercent = (size > 0) ? (bytesRead * 100.0 / size) : 100.0;

                String finalRowText = String.format("%-21s %-18s %6.1f%% %-15s %-15s %s",
                        truncate(clientIp, 20),
                        truncate(fileName, 17),
                        finalPercent,
                        truncate("---",3),
                        formatSpeed(finalSpeed),
                        status);

                ConsoleDashboard.printAt(row, finalRowText);

                output.writeBoolean(isSuccess);
            }
        } catch (IOException e) {
            String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            String errorRowText = String.format("%-21s %-30s",
                    truncate(clientIp, 20),
                    "Ошибка: " + truncate(errorMsg, 20));

            ConsoleDashboard.printAt(row, errorRowText);
        } finally {
            scheduler.shutdownNow();
            try {
                socket.close();
            } catch (IOException ignored) {}
        }
    }

    // Вспомогательный метод для красивого отображения байт/сек
    private String formatSpeed(double bytesPerSec) {
        if (bytesPerSec >= 1024 * 1024) {
            return String.format("%.2f MB/s", bytesPerSec / (1024 * 1024));
        } else if (bytesPerSec >= 1024) {
            return String.format("%.2f KB/s", bytesPerSec / 1024);
        } else {
            return String.format("%.2f B/s", bytesPerSec);
        }
    }

    // Обрезка слишком длинных имён файлов под колонку
    private String truncate(String text, int maxLen) {
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen - 3) + "...";
    }
}
