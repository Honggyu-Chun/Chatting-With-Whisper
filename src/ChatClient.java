import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.*;
import java.net.Socket;
import java.util.Properties;
import java.util.Scanner;

// 로그인 패널 + 채팅 패널, serverinfo.dat에서 서버 정보 읽기
public class ChatClient {

    // 설정 파일 (host, port 저장)
    private static final String CONFIG_FILE = "serverinfo.dat";

    // 서버 주소 기본값
    private String serverHost = "127.0.0.1";
    private int serverPort = 8754; // ChatServer와 맞춰야 함

    // 소켓/네트워크
    private Socket sock;
    private Scanner in;
    private PrintWriter out;

    // UI
    private JFrame frame = new JFrame("ChatClient");
    private JPanel mainPanel = new JPanel(new CardLayout());
    private JPanel loginPanel;
    private JPanel chatPanel;

    private JTextField idField;
    private JPasswordField pwField;
    private JButton btnLogin;
    private JButton btnRegister;

    private JTextArea chatArea;
    private JTextField inputField;

    // 귓속말 UI
    private DefaultComboBoxModel<String> userListModel = new DefaultComboBoxModel<>();
    private JComboBox<String> userCombo;
    private JTextField whisperField;
    private JButton whisperButton;

    // 내 아이디
    private String myId = null;

    public ChatClient() {
        // serverinfo.dat 읽기
        readConfig();
        // 로그인 화면 만들기
        buildLoginPanel();
        // 채팅 화면 만들기
        buildChatPanel();

        mainPanel.add(loginPanel, "LOGIN");
        mainPanel.add(chatPanel, "CHAT");

        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setContentPane(mainPanel);
        frame.pack();
        frame.setLocationRelativeTo(null);
    }

    // serverinfo.dat 읽기
    private void readConfig() {
        File f = new File(CONFIG_FILE);
        if (!f.exists()) {
            // 없으면 기본값으로 새로 만들기
            writeConfig();
            return;
        }
        Properties props = new Properties();
        try (FileInputStream fis = new FileInputStream(f)) {
            props.load(fis);
            String host = props.getProperty("host");
            String portStr = props.getProperty("port");
            if (host != null && !host.isEmpty()) {
                serverHost = host.trim();
            }
            if (portStr != null && !portStr.isEmpty()) {
                try {
                    serverPort = Integer.parseInt(portStr.trim());
                } catch (NumberFormatException e) {
                    e.printStackTrace();
                }
            }
        } catch (IOException e) {
            System.out.println("Config read error: " + e.getMessage());
        }
    }

    // 설정 파일 쓰기 (기본값 저장)
    private void writeConfig() {
        Properties props = new Properties();
        props.setProperty("host", serverHost);
        props.setProperty("port", String.valueOf(serverPort));
        try (FileOutputStream fos = new FileOutputStream(CONFIG_FILE)) {
            props.store(fos, "chat server info");
        } catch (IOException e) {
            System.out.println("Config save error: " + e.getMessage());
        }
    }

    // 로그인 화면 만들기
    private void buildLoginPanel() {
        loginPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        JLabel lblId = new JLabel("ID:");
        JLabel lblPw = new JLabel("Password:");

        idField = new JTextField(15);
        pwField = new JPasswordField(15);
        btnLogin = new JButton("Login");
        btnRegister = new JButton("Register");

        gbc.gridx = 0; gbc.gridy = 0;
        loginPanel.add(lblId, gbc);
        gbc.gridx = 1; gbc.gridy = 0;
        loginPanel.add(idField, gbc);

        gbc.gridx = 0; gbc.gridy = 1;
        loginPanel.add(lblPw, gbc);
        gbc.gridx = 1; gbc.gridy = 1;
        loginPanel.add(pwField, gbc);

        JPanel btnPanel = new JPanel();
        btnPanel.add(btnLogin);
        btnPanel.add(btnRegister);

        gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 2;
        loginPanel.add(btnPanel, gbc);

        // 로그인 버튼 누르면 작동
        btnLogin.addActionListener(e -> startLogin());
        // 패스워드 칸에서 엔터 눌러도 작동
        pwField.addActionListener(e -> startLogin());
        // 회원가입 버튼 누르면 작동
        btnRegister.addActionListener(e -> doRegister());
    }

    // 채팅 화면 만들기
    private void buildChatPanel() {
        chatPanel = new JPanel(new BorderLayout());

        // 귓속말 패널
        JPanel whisperPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JLabel whisperLabel = new JLabel("Whisper to:");
        userCombo = new JComboBox<>(userListModel);
        userCombo.setPreferredSize(new Dimension(120, 25));
        whisperField = new JTextField(20);
        whisperButton = new JButton("Send");

        whisperButton.addActionListener(e -> sendWhisper());
        whisperField.addActionListener(e -> sendWhisper());

        whisperPanel.add(whisperLabel);
        whisperPanel.add(userCombo);
        whisperPanel.add(whisperField);
        whisperPanel.add(whisperButton);

        // 채팅창
        chatArea = new JTextArea(16, 50);
        chatArea.setEditable(false);
        JScrollPane scrollPane = new JScrollPane(chatArea);

        // 일반 메시지 입력 칸
        inputField = new JTextField(50);
        inputField.setEditable(false); // 로그인 전에는 입력 막기

        inputField.addActionListener((ActionEvent e) -> {
            sendMessage();
        });

        chatPanel.add(whisperPanel, BorderLayout.NORTH);
        chatPanel.add(scrollPane, BorderLayout.CENTER);
        chatPanel.add(inputField, BorderLayout.SOUTH);
    }

    // 화면 전환
    private void showCard(String name) {
        CardLayout cl = (CardLayout) mainPanel.getLayout();
        cl.show(mainPanel, name);
    }

    // 로그인
    private void startLogin() {
        String id = idField.getText().trim();
        String pw = new String(pwField.getPassword());
        if (id.isEmpty() || pw.isEmpty()) {
            JOptionPane.showMessageDialog(frame, "Please enter both ID and password.");
            return;
        }

        // UI를 위해 별도 쓰레드로 처리
        new Thread(() -> {
            try {
                sock = new Socket(serverHost, serverPort);
                in = new Scanner(sock.getInputStream());
                out = new PrintWriter(sock.getOutputStream(), true);

                // LOGIN 프로토콜 전송
                out.println("LOGIN " + id + " " + pw);

                // 서버 응답 받기
                if (!in.hasNextLine()) {
                    throw new IOException("No response from server.");
                }
                String resp = in.nextLine();

                if (resp.startsWith("LOGINOK")) {
                    String[] parts = resp.split(" ", 4);
                    myId = parts.length >= 2 ? parts[1] : id;
                    String name = parts.length >= 3 ? parts[2] : "";

                    SwingUtilities.invokeLater(() -> {
                        frame.setTitle("ChatClient - " + myId + (name.isEmpty() ? "" : " (" + name + ")"));
                        inputField.setEditable(true);
                        idField.setText("");
                        pwField.setText("");
                        showCard("CHAT");
                    });

                    // 로그인 성공 후 채팅 루프 진입
                    listenFromServer();

                //로그인 실패 프로토콜이 오면 팝업
                } else if (resp.startsWith("LOGINFAIL")) {
                    String reason = resp.length() > 9 ? resp.substring(9).trim() : "Login failed.";
                    closeSocketQuiet();
                    SwingUtilities.invokeLater(() ->
                            JOptionPane.showMessageDialog(frame, reason)
                    );
                //이미 로그인했다는 프로토콜이 오면 팝업
                } else if (resp.startsWith("DUPLICATE")) {
                    closeSocketQuiet();
                    SwingUtilities.invokeLater(() ->
                            JOptionPane.showMessageDialog(frame, "This ID is already logged in.")
                    );
                //이외 모든 안정해진 프로토콜이 오면 팝업
                } else {
                    closeSocketQuiet();
                    SwingUtilities.invokeLater(() ->
                            JOptionPane.showMessageDialog(frame, "Unknown response: " + resp)
                    );
                }

            } catch (IOException ex) {
                closeSocketQuiet();
                SwingUtilities.invokeLater(() ->
                        JOptionPane.showMessageDialog(frame, "Error while logging in: " + ex.getMessage())
                );
            }
        }).start();
    }

    // 회원가입
    private void doRegister() {
        JTextField newIdField = new JTextField();
        JPasswordField newPwField = new JPasswordField();
        JTextField nameField = new JTextField();
        JTextField emailField = new JTextField();

        Object[] msg = {
                "New ID:", newIdField,
                "New Password:", newPwField,
                "Name:", nameField,
                "Email:", emailField
        };

        int opt = JOptionPane.showConfirmDialog(
                frame,
                msg,
                "Register",
                JOptionPane.OK_CANCEL_OPTION
        );

        if (opt != JOptionPane.OK_OPTION) return;

        String newId = newIdField.getText().trim();
        String newPw = new String(newPwField.getPassword());
        String newName = nameField.getText().trim();
        String newEmail = emailField.getText().trim();

        if (newId.isEmpty() || newPw.isEmpty() || newName.isEmpty() || newEmail.isEmpty()) {
            JOptionPane.showMessageDialog(frame, "Please fill in all fields.");
            return;
        }
        //서버 연결 및 전송은 새로운 쓰레드에서 실행
        new Thread(() -> {
            try (
                    Socket regSock = new Socket(serverHost, serverPort);
                    Scanner rin = new Scanner(regSock.getInputStream());
                    PrintWriter rout = new PrintWriter(regSock.getOutputStream(), true)
            ) {
                rout.println("REGISTER " + newId + " " + newPw + " " + newName + " " + newEmail);

                if (!rin.hasNextLine()) {
                    SwingUtilities.invokeLater(() ->
                            JOptionPane.showMessageDialog(frame, "No response from server.")
                    );
                    return;
                }

                String resp = rin.nextLine();
                if (resp.startsWith("REGISTERSUCCESS")) {
                    SwingUtilities.invokeLater(() ->
                            JOptionPane.showMessageDialog(frame, "Registration complete. Please log in.")
                    );
                } else if (resp.startsWith("REGISTERFAIL")) {
                    String reason = resp.length() > "REGISTERFAIL".length()
                            ? resp.substring("REGISTERFAIL".length()).trim()
                            : "Register failed.";
                    SwingUtilities.invokeLater(() ->
                            JOptionPane.showMessageDialog(frame, "Registration failed: " + reason)
                    );
                } else {
                    SwingUtilities.invokeLater(() ->
                            JOptionPane.showMessageDialog(frame, "Unknown response: " + resp)
                    );
                }

            } catch (IOException e) {
                SwingUtilities.invokeLater(() ->
                        JOptionPane.showMessageDialog(frame, "Error while registering: " + e.getMessage())
                );
            }
        }).start();
    }

    // 일반 채팅 보내기
    private void sendMessage() {
        if (out == null) return;
        String text = inputField.getText().trim();
        if (text.isEmpty()) return;
        out.println(text);
        inputField.setText("");
    }

    // 귓속말 보내기
    private void sendWhisper() {
        if (out == null) return;

        String target = (String) userCombo.getSelectedItem();
        String msg = whisperField.getText().trim();

        if (target == null || target.isEmpty()) {
            JOptionPane.showMessageDialog(frame, "Please select a user.");
            return;
        }
        if (msg.isEmpty()) {
            JOptionPane.showMessageDialog(frame, "Whisper message is empty.");
            return;
        }

        // 서버 프로토콜: @상대아이디 내용
        out.println("@" + target + " " + msg);
        whisperField.setText("");
    }

    // 서버에서 오는 메시지 계속 읽기
    private void listenFromServer() {
        try {
            while (in.hasNextLine()) {
                String line = in.nextLine();
                handleServerLine(line);
            }
        } finally {
            closeSocketQuiet();
            SwingUtilities.invokeLater(() -> {
                inputField.setEditable(false);
                chatArea.append("[INFO] Disconnected from server.\n");
                showCard("LOGIN");
                // 유저 목록도 초기화
                userListModel.removeAllElements();
            });
        }
    }

    // USERLIST 문자열을 콤마 기준으로 잘라서 콤보박스 갱신
    private void updateUserListFromString(String users) {
        userListModel.removeAllElements();
        String[] arr = users.split(",");
        for (String u : arr) {
            String name = u.trim();
            if (name.isEmpty()) continue;
            if (myId != null && name.equals(myId)) continue; // 자기 자신은 제외
            userListModel.addElement(name);
        }
    }

    // 서버에서 한 줄 들어왔을 때 처리
    private void handleServerLine(String line) {
        if (line.startsWith("USERLIST")) {
            String users = line.length() > 9 ? line.substring(9) : "";
            SwingUtilities.invokeLater(() -> {
                updateUserListFromString(users);
            });
        } else if (line.startsWith("USERJOIN")) {
            String who = line.substring(9).trim();
            SwingUtilities.invokeLater(() -> {
                chatArea.append("[INFO] " + who + " joined.\n");
                // 새 유저를 콤보에 추가 (중복 체크)
                if (myId != null && who.equals(myId)) {
                    // 자기 자신은 추가하지 않음
                    return;
                }
                boolean exists = false;
                for (int i = 0; i < userListModel.getSize(); i++) {
                    if (who.equals(userListModel.getElementAt(i))) {
                        exists = true;
                        break;
                    }
                }
                if (!exists) {
                    userListModel.addElement(who);
                }
            });
        } else if (line.startsWith("USERLEAVE")) {
            String who = line.substring(10).trim();
            SwingUtilities.invokeLater(() -> {
                chatArea.append("[INFO] " + who + " left.\n");
                // 콤보에서 제거
                for (int i = 0; i < userListModel.getSize(); i++) {
                    if (who.equals(userListModel.getElementAt(i))) {
                        userListModel.removeElementAt(i);
                        break;
                    }
                }
            });
        } else if (line.startsWith("WHISPER")) {
            SwingUtilities.invokeLater(() ->
                    chatArea.append("[Whisper] " + line.substring(8) + "\n")
            );
        } else if (line.startsWith("MESSAGE")) {
            SwingUtilities.invokeLater(() ->
                    chatArea.append(line.substring(8) + "\n")
            );
        } else {
            SwingUtilities.invokeLater(() ->
                    chatArea.append(line + "\n")
            );
        }
    }

    // 소켓 정리
    private void closeSocketQuiet() {
        try {
            if (sock != null) sock.close();
        } catch (IOException e) {
            // ignore
        }
        sock = null;
        in = null;
        out = null;
    }

    public void start() {
        frame.setVisible(true);
        showCard("LOGIN");
    }

    public static void main(String[] args) {
        ChatClient c = new ChatClient();
        c.start();
    }
}
