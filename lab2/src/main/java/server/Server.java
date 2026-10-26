package main.java.server;
 
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


public class Server {
    private static final int THREAD_POOL_SIZE = 16;

    public static void recieveFile(String port) {
        try (ServerSocket serverSocket = new ServerSocket(Integer.parseInt(port))) {
        ConsoleDashboard.init();
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
        while (true) {
            Socket clientSocket = serverSocket.accept();
            executor.submit(new ClientHandler(clientSocket));
        }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
	public static void main(String[] args) {
        String port = args[0];
        recieveFile(port);
	}
}