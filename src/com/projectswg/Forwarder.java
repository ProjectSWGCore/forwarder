package com.projectswg;

import java.net.InetAddress;
import java.net.UnknownHostException;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;

import com.projectswg.Connections.ConnectionCallback;

public class Forwarder extends Application implements ConnectionCallback {
	
	private static final String [] DATA_NAMES = new String[]{"B", "KB", "MB", "GB", "TB"};
	
	private final Connections connections;
	private final TextField serverIpField;
	private final TextField serverPortField;
	private final Button serverSetButton;
	private final Text serverConnectionText;
	private final Text clientConnectionText;
	private final Text serverConnectionPort;
	private final Text serverRxText;
	private final Text serverTxText;
	private final Text clientRxText;
	private final Text clientTxText;
	
	public static void main(String [] args) {
		launch(args);
	}
	
	public Forwarder() {
		connections = new Connections();
		serverIpField = new TextField(connections.getRemoteAddress().getHostAddress());
		serverPortField = new TextField(Integer.toString(connections.getRemotePort()));
		serverSetButton = new Button("Set");
		serverConnectionText = new Text(getConnectionStatus(false));
		clientConnectionText = new Text(getConnectionStatus(false));
		serverConnectionPort = new Text(Integer.toString(connections.getLoginPort()));
		serverRxText = new Text(getByteName(connections.getTcpRecv()));
		serverTxText = new Text(getByteName(connections.getTcpSent()));
		clientRxText = new Text(getByteName(connections.getUdpRecv()));
		clientTxText = new Text(getByteName(connections.getUdpSent()));
		updateConnection(serverConnectionText, false);
		updateConnection(clientConnectionText, false);
		updateServerButton();
		EventHandler<KeyEvent> handler = new EventHandler<KeyEvent>() {
			public void handle(KeyEvent event) {
				if (event.getCode() == KeyCode.ENTER)
					updateServerIp();
				else
					updateServerButton();
			}
		};
		serverIpField.setOnKeyPressed(handler);
		serverPortField.setOnKeyPressed(handler);
		serverIpField.setOnKeyTyped(handler);
		serverPortField.setOnKeyTyped(handler);
		serverSetButton.setOnAction(new EventHandler<ActionEvent>(){
			public void handle(ActionEvent event) {
				updateServerIp();
			}
		});
		connections.setCallback(this);
	}
	
	@Override
	public void onServerConnected() {
		Platform.runLater(() -> updateConnection(serverConnectionText, true));
	}
	
	@Override
	public void onServerDisconnected() {
		Platform.runLater(() -> updateConnection(serverConnectionText, false));
	}
	
	@Override
	public void onClientConnected() {
		Platform.runLater(() -> updateConnection(clientConnectionText, true));
	}
	
	@Override
	public void onClientDisconnected() {
		Platform.runLater(() -> updateConnection(clientConnectionText, false));
	}
	
	@Override
	public void onDataRecvTcp(byte[] data) {
		Platform.runLater(() -> serverRxText.setText(getByteName(connections.getTcpRecv())));
	}
	
	@Override
	public void onDataSentTcp(byte[] data) {
		Platform.runLater(() -> serverTxText.setText(getByteName(connections.getTcpSent())));
	}
	
	@Override
	public void onDataRecvUdp(byte[] data) {
		Platform.runLater(() -> clientRxText.setText(getByteName(connections.getUdpRecv())));
	}
	
	@Override
	public void onDataSentUdp(byte[] data) {
		Platform.runLater(() -> clientTxText.setText(getByteName(connections.getUdpSent())));
	}
	
	private void updateConnection(Text t, boolean status) {
		t.setText(getConnectionStatus(status));
		t.setFill(status ? Color.GREEN : Color.RED);
	}
	
	private void updateServerIp() {
		try {
			InetAddress addr = InetAddress.getByName(serverIpField.getText());
			int port = Integer.parseInt(serverPortField.getText());
			connections.setRemote(addr, port);
			updateServerButton();
		} catch (UnknownHostException e) {
			System.err.println("Unknown IP: " + serverIpField.getText());
		} catch (NumberFormatException e) {
			System.err.println("Invalid Port: " + serverPortField.getText());
		}
	}
	
	private void updateServerButton() {
		try {
			InetAddress addr = InetAddress.getByName(serverIpField.getText());
			int port = Integer.parseInt(serverPortField.getText());
			if (!addr.equals(connections.getRemoteAddress()) || port != connections.getRemotePort())
				serverSetButton.setDisable(false);
			else
				serverSetButton.setDisable(true);
		} catch (UnknownHostException | NumberFormatException e) {
			serverSetButton.setDisable(true);
		}
	}
	
	@Override
	public void start(Stage primaryStage) throws Exception {
		connections.initialize();
		serverConnectionPort.setText(Integer.toString(connections.getLoginPort()));
		GridPane root = new GridPane();
		for (int i = 0; i < 4; i++) {
			ColumnConstraints cc = new ColumnConstraints();
			cc.setPercentWidth(25);
			root.getColumnConstraints().add(cc);
		}
		root.add(serverIpField,			0, 0, 2, 1);
		root.add(serverPortField,		2, 0, 1, 1);
		root.add(serverSetButton,		3, 0, 1, 1);
		root.add(new Text("Server Connection:"), 0, 1, 2, 1);
		root.add(serverConnectionText,	2, 1, 1, 1);
		root.add(serverConnectionPort,	3, 1, 1, 1);
		root.add(new Text("Client Connection:"), 0, 2, 2, 1);
		root.add(clientConnectionText,	2, 2, 2, 1);
		root.add(new Text("Sent"),		1, 3, 1, 1);
		root.add(new Text("Recv"),		2, 3, 1, 1);
		root.add(new Text("TCP"),		0, 4, 1, 1);
		root.add(serverTxText,			1, 4, 1, 1);
		root.add(serverRxText,			2, 4, 1, 1);
		root.add(new Text("UDP"),		0, 5, 1, 1);
		root.add(clientTxText,			1, 5, 1, 1);
		root.add(clientRxText,			2, 5, 1, 1);
		Scene scene = new Scene(root, 300, 160);
		primaryStage.setTitle("Holocore Forwarder");
		primaryStage.setScene(scene);
		primaryStage.setMinWidth(scene.getWidth());
		primaryStage.setMinHeight(scene.getHeight());
		primaryStage.setMaxWidth(scene.getWidth());
		primaryStage.setMaxHeight(scene.getHeight());
		root.setOnMouseClicked((event) -> root.requestFocus());
		root.requestFocus();
		primaryStage.show();
		primaryStage.setOnCloseRequest(new EventHandler<WindowEvent>() {
			public void handle(WindowEvent we) {
				connections.terminate();
				primaryStage.close();
				System.exit(0);
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
