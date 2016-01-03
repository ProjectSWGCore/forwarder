package com.projectswg;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.atomic.AtomicLong;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.event.EventHandler;
import javafx.scene.Scene;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.GridPane;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;

import com.projectswg.ClientConnection.ClientCallback;
import com.projectswg.ServerConnection.ServerCallback;

public class Forwarder extends Application {
	
	private static final String [] DATA_NAMES = new String[]{"B", "KB", "MB", "GB", "TB"};
	private static final String SERVER_CONN_STRING = "Server Connection: %s";
	private static final String CLIENT_CONN_STRING = "Client Connection: %s";
	private static final String SERVER_RX_STRING = "TCP Recv: %s";
	private static final String SERVER_TX_STRING = "TCP Sent: %s";
	
	private final ServerConnection server;
	private final ClientConnection client;
	private final AtomicLong receivedBytes;
	private final AtomicLong transmittedBytes;
	private final TextField serverIpField;
	private final TextField serverPortField;
	private final Text serverConnectionText;
	private final Text clientConnectionText;
	private final Text serverReceivedText;
	private final Text serverTransmittedText;
	
	public static void main(String [] args) {
		launch(args);
	}
	
	public Forwarder() {
		receivedBytes = new AtomicLong(0);
		transmittedBytes = new AtomicLong(0);
		serverIpField = new TextField(ServerConnection.DEFAULT_ADDR.getHostAddress());
		serverPortField = new TextField(Integer.toString(ServerConnection.DEFAULT_PORT));
		serverConnectionText = new Text(String.format(SERVER_CONN_STRING, getConnectionStatus(false)));
		clientConnectionText = new Text(String.format(CLIENT_CONN_STRING, getConnectionStatus(false)));
		serverReceivedText = new Text(String.format(SERVER_RX_STRING, getByteName(receivedBytes.get())));
		serverTransmittedText = new Text(String.format(SERVER_TX_STRING, getByteName(transmittedBytes.get())));
		EventHandler<KeyEvent> handler = new EventHandler<KeyEvent>() {
			@Override
			public void handle(KeyEvent event) {
				if (event.getCode() == KeyCode.ENTER)
					updateServerIp();
			}
		};
		serverIpField.setOnKeyPressed(handler);
		serverPortField.setOnKeyPressed(handler);
		server = new ServerConnection();
		client = new ClientConnection(44453, 44463);
		client.setCallback(new ClientCallback() {
			@Override
			public void onPacket(byte[] data) {
				transmittedBytes.addAndGet(data.length);
				server.forward(data);
				Platform.runLater(() -> serverTransmittedText.setText(String.format(SERVER_TX_STRING, getByteName(transmittedBytes.get()))));
			}
			@Override
			public void onDisconnected() {
				Platform.runLater(() -> clientConnectionText.setText(String.format(CLIENT_CONN_STRING, getConnectionStatus(false))));
				server.stop();
			}
			@Override
			public void onConnected() {
				Platform.runLater(() -> clientConnectionText.setText(String.format(CLIENT_CONN_STRING, getConnectionStatus(true))));
				server.start();
			}
		});
		server.setCallback(new ServerCallback() {
			@Override
			public void onData(byte[] data) {
				receivedBytes.addAndGet(data.length);
				client.send(data);
				Platform.runLater(() -> serverReceivedText.setText(String.format(SERVER_RX_STRING, getByteName(receivedBytes.get()))));
			}
			@Override
			public void onConnected() {
				Platform.runLater(() -> serverConnectionText.setText(String.format(SERVER_CONN_STRING, getConnectionStatus(true))));
			}
			@Override
			public void onDisconnected() {
				Platform.runLater(() -> serverConnectionText.setText(String.format(SERVER_CONN_STRING, getConnectionStatus(false))));
			}
		});
	}
	
	private void initialize() {
		client.start();
	}
	
	private void terminate() {
		server.stop();
		client.stop();
	}
	
	private void updateServerIp() {
		try {
			InetAddress addr = InetAddress.getByName(serverIpField.getText());
			int port = Integer.parseInt(serverPortField.getText());
			terminate();
			server.setRemoteAddress(addr, port);
			initialize();
		} catch (UnknownHostException e) {
			e.printStackTrace();
		}
	}
	
	@Override
	public void start(Stage primaryStage) throws Exception {
		initialize();
		GridPane root = new GridPane();
		root.add(serverIpField, 0, 0);
		root.add(serverPortField, 1, 0);
		root.add(serverConnectionText, 0, 1);
		root.add(clientConnectionText, 0, 2);
		root.add(serverReceivedText, 0, 3);
		root.add(serverTransmittedText, 0, 4);
		Scene scene = new Scene(root, 300, 160);
		scene.setRoot(root);
		primaryStage.setTitle("Holocore Forwarder");
		primaryStage.setScene(scene);
		primaryStage.setMinWidth(scene.getWidth());
		primaryStage.setMinHeight(scene.getHeight());
		primaryStage.setMaxWidth(scene.getWidth());
		primaryStage.setMaxHeight(scene.getHeight());
		primaryStage.show();
		primaryStage.setOnCloseRequest(new EventHandler<WindowEvent>() {
			public void handle(WindowEvent we) {
				terminate();
				primaryStage.close();
			}
		});
	}
	
	private static String getByteName(long bytes) {
		int index = 0;
		double reduced = bytes;
		while (reduced >= 1024 && index < DATA_NAMES.length) {
			reduced /= 1024;
			index++;
		}
		if (index == 0)
			return bytes + " B";
		return String.format("%.2f %s", reduced, DATA_NAMES[index]);
	}
	
	private static String getConnectionStatus(boolean status) {
		return status ? "ONLINE" : "OFFLINE";
	}
	
}
