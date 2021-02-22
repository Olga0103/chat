package chat.handler;

import chat.MyServer;
import chat.auth.AuthService;
import clientserver.Command;
import clientserver.CommandType;
import clientserver.commands.AuthCommandData;
import clientserver.commands.PrivateMessageCommandData;
import clientserver.commands.PublicMessageCommandData;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.Timer;
import java.util.TimerTask;

public class ClientHandler {

    private final long CONNECTION_TIMEOUT = 120000;
    private final MyServer myServer;
    private final Socket clientSocket;
    private ObjectInputStream in;
    private ObjectOutputStream out;
    private String clientUsername;

    public ClientHandler(MyServer myServer, Socket clientSocket){
        this.myServer = myServer;
        this.clientSocket = clientSocket;
    }


    public void handle() throws IOException {
        in = new ObjectInputStream(clientSocket.getInputStream());
        out = new ObjectOutputStream(clientSocket.getOutputStream());

        TimerTask timerTask = new TimerTask() {
        @Override
        public void run() {
            try {
                sendMessage(Command.authTimeoutCommand("Connection timeout"));
                closeConnection();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        };


    Timer closeConnectionOnTime = new Timer();

        closeConnectionOnTime.schedule(timerTask, CONNECTION_TIMEOUT);

        new Thread(() -> {
        try {
            authentication();
            if (clientUsername != null) {
                closeConnectionOnTime.cancel();
            }
            readMessage();
            
            
        } catch (SocketException e) {
            System.err.println("Connection has been interrupted");
            try {
                closeConnection();
            } catch (IOException ioException) {
                ioException.printStackTrace();
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                closeConnection();
            } catch (IOException e) {
                System.err.println("Failed to close connection.");
            }
        }
    }).start();
}


    private void authentication() throws IOException {

        while (true) {

            Command command = readCommand();
            if (command == null) {
                continue;
//                Set set = new TreeSet();
            }
            if (command.getType() == CommandType.AUTH) {

                boolean isSuccessAuth = processAuthCommand(command);
                if (isSuccessAuth) {
                    break;
                }

            } else {
                sendMessage(Command.authErrorCommand("Ошибка авторизации"));

            }
        }

    }

    private boolean processAuthCommand(Command command) throws IOException {
        AuthCommandData cmdData = (AuthCommandData) command.getData();
        String login = cmdData.getLogin();
        String password = cmdData.getPassword();

        AuthService authService = myServer.getAuthService();
        this.clientUsername = authService.getUsernameByLoginAndPassword(login, password);
        if (clientUsername != null) {
            if (myServer.isUsernameBusy(clientUsername)) {
                sendMessage(Command.authErrorCommand("Логин уже используется"));
                return false;
            }

            sendMessage(Command.authOkCommand(clientUsername));
            String message = String.format(">>> %s присоединился к чату", clientUsername);
            myServer.broadcastMessage(this, Command.messageInfoCommand(message, null));
            myServer.subscribe(this);
            return true;
        } else {
            sendMessage(Command.authErrorCommand("Логин или пароль не соответствуют действительности"));
            return false;
        }
    }

    private Command readCommand() throws IOException {
        try {
            return (Command) in.readObject();
        } catch (ClassNotFoundException e) {
            String errorMessage = "Получен неизвестный объект";
            System.err.println(errorMessage);
            e.printStackTrace();
            return null;
        }
    }

    private void readMessage() throws IOException {
        while (true) {
            Command command = readCommand();
            if (command == null) {
                continue;
            }

            switch (command.getType()) {
                case END:
                    return;
                case PUBLIC_MESSAGE: {
                    PublicMessageCommandData data = (PublicMessageCommandData) command.getData();
                    String message = data.getMessage();
                    String sender = data.getSender();
                    myServer.broadcastMessage(this, Command.messageInfoCommand(message, sender));
                    break;
                }
                case PRIVATE_MESSAGE:
                    PrivateMessageCommandData data = (PrivateMessageCommandData) command.getData();
                    String recipient = data.getReceiver();
                    String message = data.getMessage();
                    myServer.sendPrivateMessage(recipient, Command.messageInfoCommand(message, recipient));
                    break;
                default:
                    String errorMessage = "Неизвестный тип команды" + command.getType();
                    System.err.println(errorMessage);
                    sendMessage(Command.errorCommand(errorMessage));
            }
        }
    }

    public String getClientUsername() {
        return clientUsername;
    }

    public void sendMessage(Command command) throws IOException {
        out.writeObject(command);
    }

    private void closeConnection() throws IOException {
        myServer.unsubscribe(this);
        clientSocket.close();
    }

}
