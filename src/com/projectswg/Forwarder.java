package com.projectswg;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;
import javafx.stage.Stage;

import com.projectswg.Connections.ConnectionCallback;
import com.projectswg.connection.ServerConnectionStatus;
import com.projectswg.utilities.Log;

public class Forwarder extends Application implements ConnectionCallback {
	
	private static final String [] DATA_NAMES = new String[]{"B", "KB", "MB", "GB", "TB"};
	
	private final HolocoreConnection connections;
	private final ExecutorService executor;
	private final TextField usernameField;
	private final TextField passwordField;
	private final TextField serverIpField;
	private final TextField serverPortField;
	private final Text serverConnectionText;
	private final Text serverStatusText;
	private final Text clientConnectionText;
	private final Text clientConnectionPort;
	private final Text serverToClientText;
	private final Text clientToServerText;
	
	public static void main(String [] args) {
		launch(args);
	}
	
	public Forwarder() {
		executor = Executors.newSingleThreadExecutor();
		usernameField = new TextField("");
		passwordField = new PasswordField();
		serverIpField = new TextField();
		serverPortField = new TextField();
		serverConnectionText = new Text(getConnectionStatus(false));
		serverStatusText = new Text(ServerConnectionStatus.DISCONNECTED.name());
		clientConnectionText = new Text(getConnectionStatus(false));
		clientConnectionPort = new Text();
		serverToClientText = new Text("0");
		clientToServerText = new Text("0");
		connections = new HolocoreConnection();
		updateConnection(serverConnectionText, false);
		updateConnection(clientConnectionText, false);
		usernameField.promptTextProperty().setValue("Username");
		passwordField.promptTextProperty().setValue("Password");
		serverIpField.setOnKeyPressed((event) -> updateServerIp());
		serverPortField.setOnKeyPressed((event) -> updateServerIp());
	}
	
	@Override
	public void onServerStatusChanged(ServerConnectionStatus oldStatus, ServerConnectionStatus status) {
		Platform.runLater(() -> {
			serverStatusText.setText(status.name().replace('_', ' '));
			updateConnection(serverConnectionText, status == ServerConnectionStatus.CONNECTED);
		});
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
	public void onDataClientToServer(byte[] data) {
		Platform.runLater(() -> clientToServerText.setText(getByteName(connections.getClientToServerCount())));
	}
	
	@Override
	public void onDataServerToClient(byte[] data) {
		Platform.runLater(() -> serverToClientText.setText(getByteName(connections.getServerToClientCount())));
	}
	
	private void updateConnection(Text t, boolean status) {
		t.setText(getConnectionStatus(status));
		t.setFill(status ? Color.GREEN : Color.RED);
	}
	
	private void updateServerIp() {
		executor.execute(() -> {
			try {
				InetAddress addr = InetAddress.getByName(serverIpField.getText());
				int port = Integer.parseInt(serverPortField.getText());
				connections.setRemote(addr, port);
			} catch (UnknownHostException e) {
				Log.err(this, "Unknown IP: " + serverIpField.getText());
			} catch (NumberFormatException e) {
				Log.err(this, "Invalid Port: " + serverPortField.getText());
			}
		});
	}
	
	@Override
	public void start(Stage primaryStage) throws Exception {
		initializeConnections();
		clientConnectionPort.setText(Integer.toString(connections.getLoginPort()));
		GridPane root = new GridPane();
		setupGridPane(root);
		Scene scene = new Scene(root, 400, 160);
		primaryStage.setTitle("Holocore Forwarder [" + Connections.VERSION + "]");
		primaryStage.setScene(scene);
		primaryStage.setMinWidth(scene.getWidth());
		primaryStage.setMinHeight(scene.getHeight());
		primaryStage.setMaxWidth(scene.getWidth());
		primaryStage.setMaxHeight(scene.getHeight());
		root.setOnMouseClicked((event) -> root.requestFocus());
		root.requestFocus();
		primaryStage.show();
		primaryStage.setOnCloseRequest(we -> {
			try {
				connections.stop();
			} catch (Exception e) {
				Log.err(this, e);
			}
			primaryStage.close();
		});
	}
	
	private void initializeConnections() {
		connections.start();
		serverIpField.setText(connections.getRemoteAddress().getHostAddress());
		serverPortField.setText(Integer.toString(connections.getRemotePort()));
		clientConnectionPort.setText(Integer.toString(connections.getLoginPort()));
		usernameField.setText(connections.getInterceptorProperties().getUsername());
		passwordField.setText(connections.getInterceptorProperties().getPassword());
		usernameField.textProperty().addListener((event, oldValue, newValue) -> connections.getInterceptorProperties().setUsername(newValue));
		passwordField.textProperty().addListener((event, oldValue, newValue) -> connections.getInterceptorProperties().setPassword(newValue));
		connections.setCallback(this);
	}
	
	private void setupGridPane(GridPane root) {
		addColumnConstraint(root, 50);
		addColumnConstraint(root, 100);
		addColumnConstraint(root, 75);
		addColumnConstraint(root, 175);
		root.add(usernameField,			0, 0, 2, 1);
		root.add(passwordField,			2, 0, 2, 1);
		root.add(serverIpField,			0, 1, 2, 1);
		root.add(serverPortField,		2, 1, 1, 1);
		root.add(new Text("Server Connection:"), 0, 2, 2, 1);
		root.add(serverConnectionText,	2, 2, 1, 1);
		root.add(serverStatusText,		3, 2, 1, 1);
		root.add(new Text("Client Connection:"), 0, 3, 2, 1);
		root.add(clientConnectionText,	2, 3, 2, 1);
		root.add(clientConnectionPort,	3, 3, 1, 1);
		root.add(new Text("Server->Client"), 0, 4, 2, 1);
		root.add(serverToClientText,	2, 4, 2, 1);
		root.add(new Text("Client->Server"), 0, 5, 2, 1);
		root.add(clientToServerText,	2, 5, 2, 1);
	}
	
	private void addColumnConstraint(GridPane root, double width) {
		ColumnConstraints cc = new ColumnConstraints();
		cc.setPrefWidth(width);
		root.getColumnConstraints().add(cc);
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
