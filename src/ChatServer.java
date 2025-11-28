import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ChatServer {

    //로그인 중인 아이디 집합
    private static Set<String> names = new HashSet<>();
    //전체 클라이언트 출력 스트림
    private static Set<PrintWriter> writers = new HashSet<>();
    //귓속말용 출력 스트림
    private static Map<String, PrintWriter> writerMap = new HashMap<>();
    //유저 파일 관련
    private static final String USER_FILE = "users.dat";
    private static final Object userFileLock = new Object();
    private static final SecureRandom random = new SecureRandom();
    public static void main(String[] args) throws Exception {
        System.out.println("The chat server is running...");
        ExecutorService pool = Executors.newFixedThreadPool(500);
        try (ServerSocket listener = new ServerSocket(8754)) {
            while (true) {
                pool.execute(new Handler(listener.accept()));
            }
        }
    }
    // 유저 정보 클래스
    private static class UserInfo {
        String id;
        String name;
        String email;
    }



    //멀티쓰레드 구현
    private static class Handler implements Runnable {
        private String name;
        private Socket socket;
        private Scanner in;
        private PrintWriter out;
        private boolean loggedIn = false;

        public Handler(Socket socket) {
            this.socket = socket;
        }



        //돌아가는 부분
        @Override
        public void run() {
            try {
                in = new Scanner(socket.getInputStream());
                out = new PrintWriter(socket.getOutputStream(), true);

                //첫 줄에서 REGISTER/LOGIN 처리
                if (!in.hasNextLine()) {
                    return;
                }
                String first = in.nextLine();

                if (first.startsWith("REGISTER ")) {
                    handleRegister(first);
                    // 회원가입 전용 연결로 사용하고 바로 종료
                    return;
                } else if (first.startsWith("LOGIN ")) {
                    if (!handleLogin(first)) {
                        // 로그인 실패 → 종료
                        return;
                    }
                } else {
                    out.println("LOGINFAIL Unknown command");
                    return;
                }

                //로그인 성공
                sendUserList();
                registerWriter();
                notifyJoin();

                //채팅 시작
                listenLoop();

            } catch (Exception e) {
                System.out.println("Handler error: " + e);
            } finally {
                // 접속 종료
                cleanup();
                try {
                    socket.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        //로그인 처리
        private boolean handleLogin(String line) {
            String[] parts = line.split(" ", 3);
            if (parts.length < 3) {
                out.println("LOGINFAIL Invalid login format");
                return false;
            }
            String userId = parts[1].trim();
            String password = parts[2];
            if (userId.isEmpty()) {
                out.println("LOGINFAIL ID is empty");
                return false;
            }
            UserInfo info = new UserInfo();
            int code = verifyUser(userId, password, info);

            if (code == 1) {
                out.println("LOGINFAIL No such user");
                return false;
            } else if (code == 2) {
                out.println("LOGINFAIL Wrong password");
                return false;
            } else if (code == 3) {
                out.println("LOGINFAIL Server user file error");
                return false;
            }
            synchronized (names) {
                if (names.contains(userId)) {
                    // 이미 로그인 중인 아이디
                    out.println("DUPLICATE");
                    return false;
                }
                names.add(userId);
            }
            this.name = userId;
            this.loggedIn = true;
            String safeName = info.name == null ? "" : info.name;
            String safeEmail = info.email == null ? "" : info.email;
            out.println("LOGINOK " + userId + " " + safeName + " " + safeEmail);
            System.out.println("User logged in: " + userId);
            return true;
        }

        //회원가입 처리
        private void handleRegister(String line) {
            String[] parts = line.split(" ", 5);
            if (parts.length < 5) {
                out.println("REGISTERFAIL Invalid register format");
                return;
            }
            String id = parts[1].trim();
            String pw = parts[2];
            String nm = parts[3].trim();
            String em = parts[4].trim();
            if (id.isEmpty() || pw.isEmpty() || nm.isEmpty() || em.isEmpty()) {
                out.println("REGISTERFAIL Empty field exists");
                return;
            }
            int result = registerUser(id, pw, nm, em);
            // 0=OK, 1=중복, 2=파일 에러
            if (result == 0) {
                out.println("REGISTERSUCCESS");
                System.out.println("User registered: " + id);
            } else if (result == 1) {
                out.println("REGISTERFAIL ID_ALREADY_EXISTS");
            } else {
                out.println("REGISTERFAIL SERVER_ERROR");
            }
        }

        //현재 로그인한 모든 유저 목록 전송
        private void sendUserList() {
            StringBuilder sb = new StringBuilder();
            synchronized (names) {
                for (String n : names) {
                    sb.append(n).append(",");
                }
            }
            out.println("USERLIST " + sb.toString());
        }

        //writers / writerMap에 자기 출력 스트림 등록
        private void registerWriter() {
            synchronized (writers) {
                writers.add(out);
            }
            synchronized (writerMap) {
                writerMap.put(name, out);
            }
        }

        //다른 사람들에게 입장 알림
        private void notifyJoin() {
            synchronized (writers) {
                for (PrintWriter w : writers) {
                    if (w != out) {
                        w.println("USERJOIN " + name);
                    }
                }
            }
        }

        //채팅 메시지 루프
        private void listenLoop() {
            while (true) {
                if (!in.hasNextLine()) {
                    return;
                }
                String msg = in.nextLine();
                if (msg == null) {
                    return;
                }
                if (msg.toLowerCase().startsWith("/quit")) {
                    return;
                }
                if (msg.startsWith("@")) {
                    handleWhisper(msg);
                } else {
                    broadcast(msg);
                }
            }
        }

        //메시지 다 보이게 하기
        private void broadcast(String msg) {
            synchronized (writers) {
                for (PrintWriter w : writers) {
                    w.println("MESSAGE " + name + ": " + msg);
                }
            }
        }

        //귓속말 처리
        private void handleWhisper(String msg) {
            int spaceIdx = msg.indexOf(' ');
            if (spaceIdx <= 1) {
                out.println("MESSAGE [system] Whisper format: @username message");
                return;
            }
            String targetId = msg.substring(1, spaceIdx);
            String body = msg.substring(spaceIdx + 1).trim();
            if (body.isEmpty()) {
                out.println("MESSAGE [system] Whisper message is empty");
                return;
            }
            PrintWriter targetWriter;
            synchronized (writerMap) {
                targetWriter = writerMap.get(targetId);
            }
            if (targetWriter == null) {
                out.println("MESSAGE [system] User '" + targetId + "' not found");
                return;
            }


            out.println("WHISPER to " + targetId + ": " + body);
            if (targetWriter != out) {
                targetWriter.println("WHISPER from " + name + ": " + body);
            }
        }

        // 접속 종료 시 정리 + 퇴장 알림
        private void cleanup() {
            if (!loggedIn || name == null) {
                return;
            }
            System.out.println(name + " is leaving");
            synchronized (names) {
                names.remove(name);
            }
            synchronized (writerMap) {
                writerMap.remove(name);
            }
            synchronized (writers) {
                writers.remove(out);
                for (PrintWriter w : writers) {
                    w.println("USERLEAVE " + name);
                }
            }
        }
    }

    //users.dat 유저 확인
    private static int verifyUser(String userId, String plainPw, UserInfo outInfo) {
        synchronized (userFileLock) {
            File f = new File(USER_FILE);
            if (!f.exists()) {
                return 3;
            }
            Scanner fileScanner = null;
            try {
                fileScanner = new Scanner(f);
                while (fileScanner.hasNextLine()) {
                    String line = fileScanner.nextLine();
                    String[] parts = line.split(",");
                    if (parts.length < 5) continue;
                    String fId = parts[0];
                    if (!fId.equals(userId)) continue;

                    String storedHash = parts[1];
                    String saltHex = parts[2];
                    String name = parts[3];
                    String email = parts[4];

                    String computed = makeHash(plainPw, saltHex);
                    if (!storedHash.equals(computed)) {
                        return 2;
                    }
                    outInfo.id = fId;
                    outInfo.name = name;
                    outInfo.email = email;
                    return 0;
                }
                return 1;
            } catch (FileNotFoundException e) {
                return 3;
            } finally {
                if (fileScanner != null) {
                    fileScanner.close();
                }
            }
        }
    }

    //users.dat 회원가입-
    private static int registerUser(String userId, String plainPw, String name, String email) {
        synchronized (userFileLock) {
            File f = new File(USER_FILE);
            try {
                if (!f.exists()) {
                    f.createNewFile();
                }
            } catch (IOException e) {
                return 2;
            }
            Scanner fileScanner = null;
            try {
                fileScanner = new Scanner(f);
                while (fileScanner.hasNextLine()) {
                    String line = fileScanner.nextLine();
                    String[] parts = line.split(",");
                    if (parts.length >= 1 && parts[0].equals(userId)) {
                        return 1;
                    }
                }
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            } finally {
                if (fileScanner != null) {
                    fileScanner.close();
                }
            }
            String saltHex = generateSalt();
            String hash = makeHash(plainPw, saltHex);
            FileWriter fw = null;
            try {
                fw = new FileWriter(f, true);
                String row = userId + "," + hash + "," + saltHex + "," + name + "," + email;
                fw.write(row + System.lineSeparator());
                return 0;
            } catch (IOException e) {
                return 2;
            } finally {
                if (fw != null) {
                    try {
                        fw.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    //해시 및 Salt 계산 함수
    private static String generateSalt() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        return bytesToHex(bytes);
    }
    private static String makeHash(String plainPw, String saltHex) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] saltBytes = hexToBytes(saltHex);
            md.update(saltBytes);
            md.update(plainPw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] digest = md.digest();
            return bytesToHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 error", e);
        }
    }
    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) sb.append('0');
            sb.append(hex);
        }
        return sb.toString();
    }
    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }
}
