package main.java.client;

import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.io.*;

public class Client {

    public static boolean isValid(long size, String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            System.out.println("[" + fileName + "] Ошибка: Пустое имя файла");
            return false;
        } else if (size > 1024L * 1024L * 1024L * 1024L) { 
            System.out.println("[" + fileName + "] Ошибка: Размер файла превышает 1 TБ");
            return false;
        } else if (fileName.getBytes(StandardCharsets.UTF_8).length > 4096) {
            System.out.println("[" + fileName + "] Ошибка: Имя файла слишком длинное (максимум 4096 байт в UTF-8)");
            return false;

        }
        return true;
    }

    public static void sendFile(String host, String port, String path) {
        File file = new File(path);
        long size = file.length();
        String fileName = file.getName();
        boolean valid = isValid(size, fileName);
        if (!valid) {
            return;
        }

        try (
            Socket socket = new Socket(host, Integer.parseInt(port));
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            DataInputStream input = new DataInputStream(socket.getInputStream());

            DataInputStream inputFile = new DataInputStream(new FileInputStream(file));
            ) 
            {
            System.out.println("Connected to server on " + host + ":" + port);

            output.writeUTF(fileName);
            output.writeLong(size);
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = inputFile.read(buffer)) != -1) {
                output.write(buffer, 0, bytesRead);
            
            }

            boolean response = input.readBoolean();
            if (response) {
                System.out.println("File sent successfully.");
            } else {
                System.out.println("File transfer failed.");
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    public static void main(String[] args) {
        String host = args[0];
        String port = args[1];
        String path = args[2];
        sendFile(host, port, path);
    }
}