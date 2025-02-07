import javax.json.*;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class BBSLlamaBridge extends JFrame {
	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	private final JTextArea terminal;
	private final JScrollPane scrollPane;
	private ServerSocket serverSocket;
	private static final String OLLAMA_URL = "http://192.168.1.28:11434/api/chat";
	private static final String MODEL_NAME = "llama3.2-vision:11b";
	private static final int MAX_HISTORY = 20;
	private static final String WELCOME_MESSAGE = "Benvenuto nella IA di IU2EUG! Scrivi 'exit' per uscire";
	private static final String SYSTEM_PROMPT = "Sei un assistente disponibile a rispondere a domande di nome Albert";

	
	
	public BBSLlamaBridge() {
		super("BBS Llama Bridge");
		setDefaultCloseOperation(EXIT_ON_CLOSE);
		setSize(800, 600);

		// Configurazione terminale
		terminal = new JTextArea();
		terminal.setBackground(Color.BLACK);
		terminal.setForeground(Color.GREEN);
		terminal.setFont(new Font("Monospaced", Font.PLAIN, 14));
		terminal.setEditable(false);

		scrollPane = new JScrollPane(terminal);
		add(scrollPane, BorderLayout.CENTER);

		// Menu
		JMenuBar menuBar = new JMenuBar();
		JMenu fileMenu = new JMenu("File");
		JMenuItem exitItem = new JMenuItem("Exit");

		exitItem.addActionListener(e -> exitApplication());
		fileMenu.add(exitItem);
		menuBar.add(fileMenu);
		setJMenuBar(menuBar);

		// Redirect console output to terminal
		redirectSystemStreams();

		// Avvia server in un thread separato
		new Thread(this::startServer).start();
	}

	private void redirectSystemStreams() {
		OutputStream out = new OutputStream() {
			@Override
			public void write(int b) {
				updateTerminal(String.valueOf((char) b));
			}

			@Override
			public void write(byte[] b, int off, int len) {
				updateTerminal(new String(b, off, len));
			}

			private void updateTerminal(String text) {
				SwingUtilities.invokeLater(() -> {
					terminal.append(text);
					terminal.setCaretPosition(terminal.getDocument().getLength());
				});
			}
		};

		System.setOut(new PrintStream(out, true));
		System.setErr(new PrintStream(out, true));
	}

	private void exitApplication() {
		try {
			if (serverSocket != null && !serverSocket.isClosed()) {
				serverSocket.close();
			}
			System.exit(0);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	private void startServer() {
		try {
			serverSocket = new ServerSocket(8081);
			System.out.println("Server started on port 8081");

			ExecutorService threadPool = Executors.newCachedThreadPool();
			while (!serverSocket.isClosed()) {
				Socket clientSocket = serverSocket.accept();
				threadPool.execute(new ClientHandler(clientSocket));
				System.out.println("New connection from: " + clientSocket.getRemoteSocketAddress());
			}
		} catch (IOException e) {
			System.err.println("Server error: " + e.getMessage());
		}
	}

	class ClientHandler implements Runnable {
		private final Socket socket;
		private final Deque<Map<String, String>> chatHistory = new LinkedList<>();
		private boolean firstMessage = true;

		public ClientHandler(Socket socket) {
			this.socket = socket;
		}

		@Override
		public void run() {
			try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
					PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {

				System.out.println("Client connected: " + socket.getRemoteSocketAddress());
				out.println(WELCOME_MESSAGE);

				String inputLine;
				while ((inputLine = in.readLine()) != null) {
					if (isExitCommand(inputLine)) {
						out.println("Connessione chiusa. Ritorno a BPQ32");
						break;
					}

					String response = processMessage(inputLine.trim());
					out.println(response);
				}
			} catch (IOException e) {
				System.err.println("Client handling error: " + e.getMessage());
			} finally {
				try {
					socket.close();
					System.out.println("Connection closed for: " + socket.getRemoteSocketAddress());
				} catch (IOException e) {
					System.err.println("Error closing client socket: " + e.getMessage());
				}
			}
		}

		private boolean isExitCommand(String input) {
			return input.equalsIgnoreCase("exit");
		}

		private String processMessage(String userMessage) {
			if (firstMessage) {
				addToHistory("system", SYSTEM_PROMPT);
				addToHistory("user", "ciao come ti chiami?");
				firstMessage = false;
			} else {

				addToHistory("user", userMessage);
			}

			try {
				JsonObject request = buildChatRequest();
				System.out.println("Sending request: " + request);

				JsonObject response = sendOllamaRequest(request);
				String aiResponse = extractAiResponse(response);

				addToHistory("assistant", aiResponse);
				return aiResponse;
			} catch (Exception e) {
				return "Error processing request: " + e.getMessage();
			}
		}

		private JsonObject buildChatRequest() {
			JsonArrayBuilder messagesBuilder = Json.createArrayBuilder();
			for (Map<String, String> msg : chatHistory) {
				messagesBuilder.add(
						Json.createObjectBuilder().add("role", msg.get("role")).add("content", msg.get("content")));
			}

			return Json.createObjectBuilder().add("model", MODEL_NAME).add("stream", false)
					.add("messages", messagesBuilder).build();
		}

		private JsonObject sendOllamaRequest(JsonObject request) throws IOException {
			HttpURLConnection conn = (HttpURLConnection) new URL(OLLAMA_URL).openConnection();
			conn.setRequestMethod("POST");
			conn.setRequestProperty("Content-Type", "application/json");
			conn.setDoOutput(true);

			try (OutputStream os = conn.getOutputStream(); JsonWriter writer = Json.createWriter(os)) {
				writer.writeObject(request);
			}

			try (InputStream is = conn.getInputStream(); JsonReader reader = Json.createReader(is)) {
				return reader.readObject();
			}
		}

		private String extractAiResponse(JsonObject response) {
			return response.getJsonObject("message").getString("content");
		}

		private void addToHistory(String role, String content) {
			Map<String, String> message = new HashMap<>();
			message.put("role", role);
			message.put("content", content);
			chatHistory.addLast(message);

			while (chatHistory.size() > MAX_HISTORY * 2 + 1) {
				chatHistory.removeFirst();
			}
		}
	}

	public static void main(String[] args) {
		SwingUtilities.invokeLater(() -> {
			BBSLlamaBridge gui = new BBSLlamaBridge();
			gui.setVisible(true);
		});
	}
}